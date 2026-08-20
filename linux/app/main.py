from __future__ import annotations

import asyncio
import hmac
import json
import logging
import math
import os
import re
import shutil
import tempfile
import zipfile
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse, JSONResponse
from starlette.background import BackgroundTask
from starlette.concurrency import run_in_threadpool

from . import __version__
from .processor import (
    AudioProcessingError,
    AudioRange,
    NoSpeechDetected,
    ProcessResult,
    process_audio,
)

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
logger = logging.getLogger("linux-audio-silence-service")

MAX_UPLOAD_BYTES = max(1, int(os.getenv("MAX_UPLOAD_BYTES", str(200 * 1024 * 1024))))
MAX_DURATION_SECONDS = max(1, int(os.getenv("MAX_DURATION_SECONDS", "3600")))
MAX_CONCURRENT_JOBS = max(1, int(os.getenv("MAX_CONCURRENT_JOBS", "2")))
MAX_MANUAL_RANGES = max(1, int(os.getenv("MAX_MANUAL_RANGES", "512")))
MAX_RANGE_HEADER_BYTES = max(256, int(os.getenv("MAX_RANGE_HEADER_BYTES", "4096")))
API_KEY = os.getenv("API_KEY", "").strip()
WORK_ROOT = Path(os.getenv("WORK_ROOT", "/tmp/audio-silence-service"))
WORK_ROOT.mkdir(parents=True, exist_ok=True)

job_semaphore = asyncio.Semaphore(MAX_CONCURRENT_JOBS)

app = FastAPI(
    title="Linux Audio Silence Service",
    version=__version__,
    description=(
        "Remove long silence from uploaded audio with CPU WebRTC VAD, or apply "
        "caller-supplied keep/remove ranges on the original timeline."
    ),
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


def _parse_ranges_ms(
    raw_value: str | None,
    field_name: str,
) -> tuple[AudioRange, ...] | None:
    """Parse JSON pairs or objects while preserving an explicitly empty list."""
    if raw_value is None or not raw_value.strip():
        return None
    try:
        payload = json.loads(raw_value)
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=422, detail=f"{field_name} must be valid JSON") from exc
    if not isinstance(payload, list):
        raise HTTPException(status_code=422, detail=f"{field_name} must be a JSON array")
    if len(payload) > MAX_MANUAL_RANGES:
        raise HTTPException(
            status_code=422,
            detail=f"{field_name} exceeds the {MAX_MANUAL_RANGES} range limit",
        )

    parsed: list[AudioRange] = []
    for index, item in enumerate(payload):
        if isinstance(item, list) and len(item) == 2:
            start_ms, end_ms = item
        elif isinstance(item, dict):
            start_ms = item.get("start_ms")
            end_ms = item.get("end_ms")
        else:
            raise HTTPException(
                status_code=422,
                detail=f"{field_name}[{index}] must be [start_ms, end_ms] or an object",
            )
        if (
            isinstance(start_ms, bool)
            or isinstance(end_ms, bool)
            or not isinstance(start_ms, (int, float))
            or not isinstance(end_ms, (int, float))
        ):
            raise HTTPException(
                status_code=422,
                detail=f"{field_name}[{index}] values must be numbers",
            )
        start_value = float(start_ms)
        end_value = float(end_ms)
        if (
            not math.isfinite(start_value)
            or not math.isfinite(end_value)
            or start_value < 0
            or end_value <= start_value
        ):
            raise HTTPException(
                status_code=422,
                detail=f"{field_name}[{index}] must satisfy 0 <= start_ms < end_ms",
            )
        parsed.append(AudioRange(start_value / 1000.0, end_value / 1000.0))
    return tuple(parsed)


def _validate_parameters(
    *,
    output_format: str,
    response_mode: str,
    frame_ms: int,
    aggressiveness: int,
    min_silence_ms: int,
    keep_silence_ms: int,
    padding_ms: int,
    min_speech_ms: int,
    fade_ms: int,
    no_speech_policy: str,
    manual_keep_ranges: tuple[AudioRange, ...] | None,
    manual_remove_ranges: tuple[AudioRange, ...] | None,
) -> None:
    if output_format not in {"m4a", "wav"}:
        raise HTTPException(status_code=422, detail="output_format must be m4a or wav")
    if response_mode not in {"audio", "archive"}:
        raise HTTPException(status_code=422, detail="response_mode must be audio or archive")
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
    if not 0 <= fade_ms <= 100:
        raise HTTPException(status_code=422, detail="fade_ms must be between 0 and 100")
    if no_speech_policy not in {"keep_original", "error"}:
        raise HTTPException(
            status_code=422,
            detail="no_speech_policy must be keep_original or error",
        )
    if manual_keep_ranges is not None and manual_remove_ranges is not None:
        raise HTTPException(
            status_code=422,
            detail="Supply manual_keep_ranges_ms or manual_remove_ranges_ms, not both",
        )


def _ranges_in_milliseconds(ranges: tuple[AudioRange, ...]) -> list[list[int]]:
    return [
        [round(audio_range.start_s * 1000), round(audio_range.end_s * 1000)]
        for audio_range in ranges
    ]


def _metadata_for_result(
    result: ProcessResult,
    *,
    input_bytes: int,
    output_format: str,
    parameters: dict[str, object],
) -> dict[str, object]:
    return {
        "schema_version": 1,
        "detector": result.detector,
        "input_duration_ms": round(result.input_info.duration_s * 1000),
        "output_duration_ms": round(result.output_duration_s * 1000),
        "removed_duration_ms": round(result.removed_duration_s * 1000),
        "input_bytes": input_bytes,
        "output_bytes": result.output_path.stat().st_size,
        "output_format": output_format,
        "sample_rate": result.input_info.sample_rate,
        "channels": result.input_info.channels,
        "kept_ranges_ms": _ranges_in_milliseconds(result.kept_ranges),
        "removed_ranges_ms": _ranges_in_milliseconds(result.removed_ranges),
        "warnings": list(result.warnings),
        "parameters": parameters,
    }


def _response_for_result(
    work_dir: Path,
    result: ProcessResult,
    *,
    original_name: str | None,
    output_format: str,
    response_mode: str,
    input_bytes: int,
    parameters: dict[str, object],
) -> FileResponse:
    extension = "m4a" if output_format == "m4a" else "wav"
    audio_media_type = "audio/mp4" if output_format == "m4a" else "audio/wav"
    safe_stem = _safe_stem(original_name)
    audio_name = f"{safe_stem}_trimmed.{extension}"
    metadata = _metadata_for_result(
        result,
        input_bytes=input_bytes,
        output_format=output_format,
        parameters=parameters,
    )
    headers = {
        "X-Original-Duration-Seconds": f"{result.input_info.duration_s:.3f}",
        "X-Output-Duration-Seconds": f"{result.output_duration_s:.3f}",
        "X-Removed-Duration-Seconds": f"{result.removed_duration_s:.3f}",
        "X-Original-Duration-Milliseconds": str(metadata["input_duration_ms"]),
        "X-Output-Duration-Milliseconds": str(metadata["output_duration_ms"]),
        "X-Removed-Duration-Milliseconds": str(metadata["removed_duration_ms"]),
        "X-Detected-Speech-Ranges": str(len(result.kept_ranges)),
        "X-Kept-Range-Count": str(len(result.kept_ranges)),
        "X-Removed-Range-Count": str(len(result.removed_ranges)),
        "X-Detector": result.detector,
        "X-Input-Bytes": str(input_bytes),
        "X-Output-Bytes": str(metadata["output_bytes"]),
    }
    if result.warnings:
        headers["X-Processing-Warnings"] = ",".join(result.warnings)

    if response_mode == "archive":
        headers["X-Range-Metadata"] = "complete-in-metadata-json"
        archive_path = work_dir / f"{safe_stem}_trimmed.zip"
        with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_STORED) as archive:
            archive.write(result.output_path, arcname=audio_name)
            archive.writestr(
                "metadata.json",
                json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
            )
        return FileResponse(
            path=archive_path,
            media_type="application/zip",
            filename=archive_path.name,
            headers=headers,
            background=BackgroundTask(_cleanup, work_dir),
        )

    kept_json = json.dumps(metadata["kept_ranges_ms"], separators=(",", ":"))
    removed_json = json.dumps(metadata["removed_ranges_ms"], separators=(",", ":"))
    range_header_size = len(kept_json.encode("ascii")) + len(removed_json.encode("ascii"))
    if range_header_size <= MAX_RANGE_HEADER_BYTES:
        headers["X-Kept-Ranges-Milliseconds"] = kept_json
        headers["X-Removed-Ranges-Milliseconds"] = removed_json
        headers["X-Range-Metadata"] = "complete"
    else:
        headers["X-Range-Metadata"] = "omitted-use-response-mode-archive"

    return FileResponse(
        path=result.output_path,
        media_type=audio_media_type,
        filename=audio_name,
        headers=headers,
        background=BackgroundTask(_cleanup, work_dir),
    )


@app.get("/healthz")
async def healthz() -> JSONResponse:
    return JSONResponse(
        {
            "status": "ok",
            "service": "linux-audio-silence-service",
            "version": app.version,
            "vad": "webrtc",
            "model": None,
            "manual_ranges": True,
            "archive_metadata": True,
            "max_upload_bytes": MAX_UPLOAD_BYTES,
            "max_duration_seconds": MAX_DURATION_SECONDS,
            "max_concurrent_jobs": MAX_CONCURRENT_JOBS,
        }
    )


@app.post("/v1/audio/remove-long-silence")
async def remove_long_silence(
    file: UploadFile = File(...),
    output_format: str = Form("m4a"),
    response_mode: str = Form("audio"),
    frame_ms: int = Form(20),
    aggressiveness: int = Form(2),
    min_silence_ms: int = Form(800),
    keep_silence_ms: int = Form(250),
    padding_ms: int = Form(80),
    min_speech_ms: int = Form(160),
    fade_ms: int = Form(8),
    no_speech_policy: str = Form("keep_original"),
    manual_keep_ranges_ms: str | None = Form(None),
    manual_remove_ranges_ms: str | None = Form(None),
) -> FileResponse:
    manual_keep_ranges = _parse_ranges_ms(
        manual_keep_ranges_ms,
        "manual_keep_ranges_ms",
    )
    manual_remove_ranges = _parse_ranges_ms(
        manual_remove_ranges_ms,
        "manual_remove_ranges_ms",
    )
    _validate_parameters(
        output_format=output_format,
        response_mode=response_mode,
        frame_ms=frame_ms,
        aggressiveness=aggressiveness,
        min_silence_ms=min_silence_ms,
        keep_silence_ms=keep_silence_ms,
        padding_ms=padding_ms,
        min_speech_ms=min_speech_ms,
        fade_ms=fade_ms,
        no_speech_policy=no_speech_policy,
        manual_keep_ranges=manual_keep_ranges,
        manual_remove_ranges=manual_remove_ranges,
    )

    original_name = file.filename
    work_dir: Path | None = None
    try:
        async with job_semaphore:
            work_dir = Path(tempfile.mkdtemp(prefix="job-", dir=WORK_ROOT))
            input_path = work_dir / "input.bin"
            input_bytes = await _save_upload(file, input_path)
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
                fade_ms=fade_ms,
                no_speech_policy=no_speech_policy,
                manual_keep_ranges=manual_keep_ranges,
                manual_remove_ranges=manual_remove_ranges,
            )
            parameters: dict[str, object] = {
                "frame_ms": frame_ms,
                "aggressiveness": aggressiveness,
                "min_silence_ms": min_silence_ms,
                "keep_silence_ms": keep_silence_ms,
                "padding_ms": padding_ms,
                "min_speech_ms": min_speech_ms,
                "fade_ms": fade_ms,
                "no_speech_policy": no_speech_policy,
                "manual_plan": result.detector == "manual",
            }
            return _response_for_result(
                work_dir,
                result,
                original_name=original_name,
                output_format=output_format,
                response_mode=response_mode,
                input_bytes=input_bytes,
                parameters=parameters,
            )
    except HTTPException:
        if work_dir is not None:
            _cleanup(work_dir)
        raise
    except NoSpeechDetected as exc:
        if work_dir is not None:
            _cleanup(work_dir)
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except AudioProcessingError as exc:
        logger.warning("Audio processing rejected: %s", exc)
        if work_dir is not None:
            _cleanup(work_dir)
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except Exception as exc:  # pragma: no cover - defensive API boundary
        logger.exception("Unexpected audio processing error")
        if work_dir is not None:
            _cleanup(work_dir)
        raise HTTPException(status_code=500, detail="Audio processing failed") from exc
    finally:
        await file.close()
