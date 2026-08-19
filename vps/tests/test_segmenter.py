import pytest

from app.processor import AudioRange, build_keep_ranges


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
    assert len(ranges) == 2
    assert ranges[0].start_s == 0.0
    assert ranges[0].end_s == pytest.approx(0.3)
    assert ranges[1].start_s == pytest.approx(1.1)
    assert ranges[1].end_s == 1.4
