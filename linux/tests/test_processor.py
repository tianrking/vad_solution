import sys
from array import array
from pathlib import Path

import pytest

from app import processor
from app.processor import (
    AudioInfo,
    AudioProcessingError,
    AudioRange,
    build_keep_ranges,
    build_manual_keep_ranges,
    invert_ranges,
    normalize_ranges,
    process_audio,
    render_ranges,
    run_webrtc_vad,
)


def test_short_silence_is_kept_inside_one_range() -> None:
    ranges = build_keep_ranges(
        [True] * 10 + [False] * 10 + [True] * 10,
        duration_s=0.6,
        frame_ms=20,
        min_silence_ms=800,
        keep_silence_ms=250,
        padding_ms=0,
        min_speech_ms=100,
    )
    assert ranges == (AudioRange(0.0, 0.6),)


def test_long_silence_creates_two_ranges_with_boundary_padding() -> None:
    ranges = build_keep_ranges(
        [True] * 10 + [False] * 50 + [True] * 10,
        duration_s=1.4,
        frame_ms=20,
        min_silence_ms=800,
        keep_silence_ms=200,
        padding_ms=0,
        min_speech_ms=100,
    )
    assert ranges == (
        AudioRange(0.0, pytest.approx(0.3)),
        AudioRange(pytest.approx(1.1), 1.4),
    )


def test_minimum_speech_counts_voiced_frames_not_padding() -> None:
    ranges = build_keep_ranges(
        [True] * 2 + [False] * 50,
        duration_s=1.04,
        frame_ms=20,
        min_silence_ms=800,
        keep_silence_ms=1000,
        padding_ms=500,
        min_speech_ms=100,
    )
    assert ranges == ()


def test_normalize_ranges_sorts_and_merges_touching_ranges() -> None:
    ranges = normalize_ranges(
        [
            AudioRange(3.0, 4.0),
            AudioRange(1.0, 2.0),
            AudioRange(1.5, 3.0),
        ],
        duration_s=5.0,
    )
    assert ranges == (AudioRange(1.0, 4.0),)


def test_manual_remove_ranges_are_inverted_on_original_timeline() -> None:
    kept = build_manual_keep_ranges(
        duration_s=10.0,
        remove_ranges=[AudioRange(2.0, 4.0), AudioRange(6.0, 8.0)],
    )
    assert kept == (
        AudioRange(0.0, 2.0),
        AudioRange(4.0, 6.0),
        AudioRange(8.0, 10.0),
    )
    assert invert_ranges(kept, duration_s=10.0) == (
        AudioRange(2.0, 4.0),
        AudioRange(6.0, 8.0),
    )


def test_manual_range_outside_audio_is_rejected() -> None:
    with pytest.raises(AudioProcessingError, match="must stay within"):
        build_manual_keep_ranges(
            duration_s=10.0,
            keep_ranges=[AudioRange(9.0, 11.0)],
        )


def test_final_partial_vad_frame_is_zero_padded(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    frame_bytes = 16000 * 20 // 1000 * 2
    pcm_path = tmp_path / "partial.pcm"
    pcm_path.write_bytes(bytes(frame_bytes + frame_bytes // 2))
    seen_lengths: list[int] = []

    class FakeVad:
        def __init__(self, aggressiveness: int) -> None:
            assert aggressiveness == 2

        def is_speech(self, frame: bytes, sample_rate: int) -> bool:
            assert sample_rate == 16000
            seen_lengths.append(len(frame))
            return True

    monkeypatch.setattr(processor.webrtcvad, "Vad", FakeVad)
    assert run_webrtc_vad(pcm_path) == [True, True]
    assert seen_lengths == [frame_bytes, frame_bytes]


def test_render_ranges_reports_actual_frames_and_fades_edit_edges(
    tmp_path: Path,
) -> None:
    source_path = tmp_path / "source.pcm"
    output_path = tmp_path / "output.pcm"
    samples = array("h", [10000] * 300)
    if sys.byteorder != "little":
        samples.byteswap()
    source_path.write_bytes(samples.tobytes())

    duration = render_ranges(
        source_path,
        output_path,
        (AudioRange(0.0, 0.1), AudioRange(0.2, 0.3)),
        sample_rate=1000,
        channels=1,
        fade_ms=10,
    )
    rendered = array("h")
    rendered.frombytes(output_path.read_bytes())
    if sys.byteorder != "little":
        rendered.byteswap()

    assert duration == pytest.approx(0.2)
    assert len(rendered) == 200
    assert rendered[0] == 10000
    assert rendered[99] == 0
    assert rendered[100] == 0
    assert rendered[-1] == 10000


def test_manual_process_bypasses_vad_and_removes_intermediate_pcm(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    input_path = tmp_path / "input.bin"
    input_path.write_bytes(b"input")
    work_dir = tmp_path / "work"

    monkeypatch.setattr(
        processor,
        "probe_audio",
        lambda path: AudioInfo(duration_s=1.0, sample_rate=1000, channels=1),
    )

    def fake_decode(
        input_file: Path,
        output_file: Path,
        *,
        sample_rate: int,
        channels: int,
    ) -> None:
        assert input_file == input_path
        assert sample_rate == 1000
        assert channels == 1
        pcm = array("h", [1000] * 1000)
        if sys.byteorder != "little":
            pcm.byteswap()
        output_file.write_bytes(pcm.tobytes())

    def fail_vad(*args, **kwargs):
        pytest.fail("manual processing must bypass WebRTC VAD")

    def fake_encode(
        pcm_path: Path,
        output_path: Path,
        *,
        output_format: str,
        sample_rate: int,
        channels: int,
    ) -> None:
        assert output_format == "wav"
        assert sample_rate == 1000
        assert channels == 1
        output_path.write_bytes(pcm_path.read_bytes())

    monkeypatch.setattr(processor, "decode_pcm", fake_decode)
    monkeypatch.setattr(processor, "run_webrtc_vad", fail_vad)
    monkeypatch.setattr(processor, "encode_output", fake_encode)

    result = process_audio(
        input_path,
        work_dir,
        output_format="wav",
        manual_remove_ranges=[AudioRange(0.25, 0.75)],
    )

    assert result.detector == "manual"
    assert result.kept_ranges == (
        AudioRange(0.0, 0.25),
        AudioRange(0.75, 1.0),
    )
    assert result.removed_ranges == (AudioRange(0.25, 0.75),)
    assert result.output_duration_s == pytest.approx(0.5)
    assert result.output_path.is_file()
    assert not (work_dir / "analysis-16k-mono.pcm").exists()
    assert not (work_dir / "source.pcm").exists()
    assert not (work_dir / "rendered.pcm").exists()
