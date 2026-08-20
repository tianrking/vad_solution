import shutil
from pathlib import Path

import pytest

from app.processor import process_audio

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
MANDARIN_FIXTURE = (
    REPOSITORY_ROOT
    / "android"
    / "vadcut"
    / "src"
    / "androidTest"
    / "assets"
    / "mandarin-silence-demo.mp3"
)


@pytest.mark.real_audio
@pytest.mark.skipif(
    shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None,
    reason="FFmpeg and FFprobe are required",
)
def test_real_mandarin_mp3_removes_inserted_long_silence(tmp_path: Path) -> None:
    assert MANDARIN_FIXTURE.is_file(), "shared Mandarin regression fixture is missing"
    result = process_audio(
        MANDARIN_FIXTURE,
        tmp_path,
        output_format="m4a",
    )

    assert result.detector == "webrtc_vad"
    assert result.output_path.is_file()
    assert result.output_path.stat().st_size > 0
    assert result.input_info.duration_s > 15.0
    assert result.output_duration_s < result.input_info.duration_s - 3.0
    assert result.removed_duration_s > 3.0
    assert len(result.kept_ranges) >= 2
    assert any(audio_range.duration_s >= 3.0 for audio_range in result.removed_ranges)
    assert not (tmp_path / "analysis-16k-mono.pcm").exists()
    assert not (tmp_path / "source.pcm").exists()
    assert not (tmp_path / "rendered.pcm").exists()
