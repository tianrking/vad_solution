import io
import json
import math
import wave
import zipfile
from pathlib import Path

import httpx
import pytest
from fastapi import HTTPException

from app import main
from app.main import _parse_ranges_ms
from app.processor import AudioRange


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


def _tone_wav(
    duration_ms: int = 1000,
    sample_rate: int = 16000,
    amplitude: int = 8000,
) -> bytes:
    sample_count = duration_ms * sample_rate // 1000
    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        frames = bytearray()
        for index in range(sample_count):
            sample = round(amplitude * math.sin(2 * math.pi * 440 * index / sample_rate))
            frames.extend(sample.to_bytes(2, "little", signed=True))
        wav.writeframes(frames)
    return output.getvalue()


def test_parse_manual_ranges_accepts_pairs_and_objects() -> None:
    assert _parse_ranges_ms(
        '[[0,1000],{"start_ms":2000,"end_ms":3000}]',
        "ranges",
    ) == (AudioRange(0.0, 1.0), AudioRange(2.0, 3.0))


@pytest.mark.parametrize(
    "payload",
    [
        '{"start_ms":0}',
        "[[1000,1000]]",
        "[[true,1000]]",
        '[[0,"1000"]]',
    ],
)
def test_parse_manual_ranges_rejects_invalid_shapes(payload: str) -> None:
    with pytest.raises(HTTPException) as error:
        _parse_ranges_ms(payload, "ranges")
    assert error.value.status_code == 422


@pytest.mark.anyio
async def test_health_identifies_real_detector_and_features() -> None:
    transport = httpx.ASGITransport(app=main.app)
    async with httpx.AsyncClient(
        transport=transport,
        base_url="http://test",
    ) as client:
        response = await client.get("/healthz")

    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "service": "linux-audio-silence-service",
        "version": "0.2.0",
        "vad": "webrtc",
        "model": None,
        "manual_ranges": True,
        "archive_metadata": True,
        "max_upload_bytes": main.MAX_UPLOAD_BYTES,
        "max_duration_seconds": main.MAX_DURATION_SECONDS,
        "max_concurrent_jobs": main.MAX_CONCURRENT_JOBS,
    }


@pytest.mark.anyio
async def test_http_manual_archive_contains_audio_and_complete_metadata(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(main, "WORK_ROOT", tmp_path)
    transport = httpx.ASGITransport(app=main.app)
    async with httpx.AsyncClient(
        transport=transport,
        base_url="http://test",
    ) as client:
        response = await client.post(
            "/v1/audio/remove-long-silence",
            files={"file": ("tone.wav", _tone_wav(), "audio/wav")},
            data={
                "output_format": "wav",
                "response_mode": "archive",
                "manual_remove_ranges_ms": "[[200,800]]",
            },
        )

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("application/zip")
    assert response.headers["x-detector"] == "manual"
    assert response.headers["x-removed-duration-milliseconds"] == "600"
    with zipfile.ZipFile(io.BytesIO(response.content)) as archive:
        assert sorted(archive.namelist()) == ["metadata.json", "tone_trimmed.wav"]
        metadata = json.loads(archive.read("metadata.json"))
        assert metadata["detector"] == "manual"
        assert metadata["kept_ranges_ms"] == [[0, 200], [800, 1000]]
        assert metadata["removed_ranges_ms"] == [[200, 800]]
        assert metadata["output_duration_ms"] == 400
        assert len(archive.read("tone_trimmed.wav")) > 44


@pytest.mark.anyio
async def test_audio_response_exposes_complete_range_headers(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(main, "WORK_ROOT", tmp_path)
    transport = httpx.ASGITransport(app=main.app)
    async with httpx.AsyncClient(
        transport=transport,
        base_url="http://test",
    ) as client:
        response = await client.post(
            "/v1/audio/remove-long-silence",
            files={"file": ("tone.wav", _tone_wav(), "audio/wav")},
            data={
                "output_format": "wav",
                "manual_keep_ranges_ms": "[[100,300],[700,900]]",
            },
        )

    assert response.status_code == 200
    assert response.headers["x-range-metadata"] == "complete"
    assert response.headers["x-kept-range-count"] == "2"
    assert response.headers["x-removed-range-count"] == "3"
    assert json.loads(response.headers["x-kept-ranges-milliseconds"]) == [
        [100, 300],
        [700, 900],
    ]
    assert json.loads(response.headers["x-removed-ranges-milliseconds"]) == [
        [0, 100],
        [300, 700],
        [900, 1000],
    ]
    assert response.headers["x-output-duration-milliseconds"] == "400"
    assert response.headers["x-removed-duration-milliseconds"] == "600"


@pytest.mark.anyio
async def test_no_speech_keeps_original_and_reports_warning(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(main, "WORK_ROOT", tmp_path)
    transport = httpx.ASGITransport(app=main.app)
    async with httpx.AsyncClient(
        transport=transport,
        base_url="http://test",
    ) as client:
        response = await client.post(
            "/v1/audio/remove-long-silence",
            files={"file": ("silence.wav", _tone_wav(amplitude=0), "audio/wav")},
            data={"output_format": "wav"},
        )

    assert response.status_code == 200
    assert response.headers["x-processing-warnings"] == ("no_speech_detected_original_kept")
    assert response.headers["x-output-duration-milliseconds"] == "1000"
    assert response.headers["x-removed-duration-milliseconds"] == "0"
    assert json.loads(response.headers["x-kept-ranges-milliseconds"]) == [[0, 1000]]
    assert list(tmp_path.iterdir()) == []


@pytest.mark.anyio
async def test_no_speech_error_policy_returns_422_and_cleans_job(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(main, "WORK_ROOT", tmp_path)
    transport = httpx.ASGITransport(app=main.app)
    async with httpx.AsyncClient(
        transport=transport,
        base_url="http://test",
    ) as client:
        response = await client.post(
            "/v1/audio/remove-long-silence",
            files={"file": ("silence.wav", _tone_wav(amplitude=0), "audio/wav")},
            data={
                "output_format": "wav",
                "no_speech_policy": "error",
            },
        )

    assert response.status_code == 422
    assert response.json() == {"detail": "No speech was detected in the audio"}
    assert list(tmp_path.iterdir()) == []
