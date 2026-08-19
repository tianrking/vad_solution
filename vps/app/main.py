from __future__ import annotations

import asyncio
import hmac
import logging
import os
import re
import shutil
import tempfile
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse, JSONResponse
from starlette.background import BackgroundTask
from starlette.concurrency import run_in_threadpool

from .processor import AudioProcessingError, NoSpeechDetected, ProcessResult, process_audio


logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
logger = logging.getLogger("audio-silence-service")

MAX_UPLOAD_BYTES = max(1, int(os.getenv("MAX_UPLOAD_BYTES", str(200 * 1024 * 1024))))
MAX_DURATION_SECONDS = max(1, int(os.getenv("MAX_DURATION_SECONDS", "3600")))
MAX_CONCURRENT_JOBS = max(1, int(os.getenv("MAX_CONCURRENT_JOBS", "2")))
API_KEY = os.getenv("API_KEY", "").strip()
WORK_ROOT = Path(os.getenv("WORK_ROOT", "/tmp/audio-silence-service"))
WORK_ROOT.mkdir(parents=True, exist_ok=True)

job_semaphore = asyncio.Semaphore(MAX_CONCURRENT_JOBS)

app = FastAPI(
    title="Audio Silence Service",
    version="0.1.0",
    description="Remove long silence ranges from uploaded audio using CPU VAD.",
)


@app.middleware("http")
async def api_key_middleware(request, call_next):
    """Require X-API-Key when API_KEY is configured; keep healthz public."""
    if API_KEY and request.url.path != "/healthz":
        supplied = request.headers.get("x-api-key", "")
        authorization = request.headers.get("authorization", "")
        if not supplied and authorization.lower().startswith("bearer "):
            supplied = authorization[7:].strip()
        if not supplied or not hmac.compare_digest(supplied, API_KEY):
            return JSONResponse(status_code=401, content={"detail": "Invalid API key"})
    return await call_next(request)


def _cleanup(path: Path) -> None:
    shutil.rmtree(path, ignore_errors=True)


def _safe_stem(filename: str | None) -> str:
    stem = Path(filename or "audio").stem
    stem = re.sub(r"[^A-Za-z0-9._-]+", "_", stem).strip("._-")
    return stem[:80] or "audio"


async def _save_upload(upload: UploadFile, destination: Path) -> int:
    total = 0
    with destination.open("wb") as output:
        while True:
            chunk = await upload.read(1024 * 1024)
            if not chunk:
                break
            total += len(chunk)
            if total > MAX_UPLOAD_BYTES:
                raise HTTPException(status_code=413, detail="Uploaded file is too large")
            output.write(chunk)
    if total == 0:
        raise HTTPException(status_code=400, detail="Uploaded file is empty")
    return total


def _validate_parameters(
    *,
    output_format: str,
    frame_ms: int,
    aggressiveness: int,
    min_silence_ms: int,
    keep_silence_ms: int,
    padding_ms: int,
    min_speech_ms: int,
) -> None:
    if output_format not in {"m4a", "wav"}:
        raise HTTPException(status_code=422, detail="output_format must be m4a or wav")
    if frame_ms not in {10, 20, 30}:
        raise HTTPException(status_code=422, detail="frame_ms must be 10, 20, or 30")
    if aggressiveness not in {0, 1, 2, 3}:
        raise HTTPException(status_code=422, detail="aggressiveness must be between 0 and 3")
    if min_silence_ms < frame_ms or min_silence_ms > 60_000:
        raise HTTPException(status_code=422, detail="min_silence_ms is out of range")
    if not 0 <= keep_silence_ms <= 10_000:
        raise HTTPException(status_code=422, detail="keep_silence_ms is out of range")
    if not 0 <= padding_ms <= 5_000:
        raise HTTPException(status_code=422, detail="padding_ms is out of range")
    if not 0 <= min_speech_ms <= 60_000:
        raise HTTPException(status_code=422, detail="min_speech_ms is out of range")


def _response_for_result(
    work_dir: Path,
    result: ProcessResult,
    *,
    original_name: str | None,
    output_format: str,
) -> FileResponse:
    extension = "m4a" if output_format == "m4a" else "wav"
    media_type = "audio/mp4" if output_format == "m4a" else "audio/wav"
    download_name = f"{_safe_stem(original_name)}_trimmed.{extension}"
    output_seconds = sum(audio_range.duration_s for audio_range in result.ranges)
    headers = {
        "X-Original-Duration-Seconds": f"{result.input_info.duration_s:.3f}",
        "X-Output-Duration-Seconds": f"{output_seconds:.3f}",
        "X-Detected-Speech-Ranges": str(len(result.ranges)),
    }
    return FileResponse(
        path=result.output_path,
        media_type=media_type,
        filename=download_name,
        headers=headers,
        background=BackgroundTask(_cleanup, work_dir),
    )


@app.get("/healthz")
async def healthz() -> JSONResponse:
    return JSONResponse(
        {
            "status": "ok",
            "service": "audio-silence-service",
            "vad": "webrtc",
            "max_upload_bytes": MAX_UPLOAD_BYTES,
            "max_duration_seconds": MAX_DURATION_SECONDS,
            "max_concurrent_jobs": MAX_CONCURRENT_JOBS,
        }
    )


@app.post("/v1/audio/remove-long-silence")
async def remove_long_silence(
    file: UploadFile = File(...),
    output_format: str = Form("m4a"),
    frame_ms: int = Form(20),
    aggressiveness: int = Form(2),
    min_silence_ms: int = Form(800),
    keep_silence_ms: int = Form(250),
    padding_ms: int = Form(80),
    min_speech_ms: int = Form(160),
) -> FileResponse:
    _validate_parameters(
        output_format=output_format,
        frame_ms=frame_ms,
        aggressiveness=aggressiveness,
        min_silence_ms=min_silence_ms,
        keep_silence_ms=keep_silence_ms,
        padding_ms=padding_ms,
        min_speech_ms=min_speech_ms,
    )

    work_dir = Path(tempfile.mkdtemp(prefix="job-", dir=WORK_ROOT))
    input_path = work_dir / "input.bin"
    try:
        await _save_upload(file, input_path)
        await file.close()

        async with job_semaphore:
            result = await run_in_threadpool(
                process_audio,
                input_path,
                work_dir,
                max_duration_s=MAX_DURATION_SECONDS,
                output_format=output_format,
                frame_ms=frame_ms,
                aggressiveness=aggressiveness,
                min_silence_ms=min_silence_ms,
                keep_silence_ms=keep_silence_ms,
                padding_ms=padding_ms,
                min_speech_ms=min_speech_ms,
            )

        return _response_for_result(
            work_dir,
            result,
            original_name=file.filename,
            output_format=output_format,
        )
    except HTTPException:
        _cleanup(work_dir)
        raise
    except NoSpeechDetected as exc:
        _cleanup(work_dir)
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except AudioProcessingError as exc:
        logger.warning("Audio processing rejected: %s", exc)
        _cleanup(work_dir)
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except Exception as exc:  # pragma: no cover - defensive API boundary
        logger.exception("Unexpected audio processing error")
        _cleanup(work_dir)
        raise HTTPException(status_code=500, detail="Audio processing failed") from exc
