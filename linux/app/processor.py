from __future__ import annotations

import json
import math
import os
import subprocess
import sys
from array import array
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path

import webrtcvad


class AudioProcessingError(RuntimeError):
    """Raised when probing, decoding, VAD, planning, rendering, or encoding fails."""


class NoSpeechDetected(AudioProcessingError):
    """Raised when no usable speech range is found."""


@dataclass(frozen=True)
class AudioInfo:
    duration_s: float
    sample_rate: int
    channels: int


@dataclass(frozen=True, order=True)
class AudioRange:
    start_s: float
    end_s: float

    @property
    def duration_s(self) -> float:
        return max(0.0, self.end_s - self.start_s)


@dataclass(frozen=True)
class ProcessResult:
    output_path: Path
    input_info: AudioInfo
    kept_ranges: tuple[AudioRange, ...]
    removed_ranges: tuple[AudioRange, ...]
    output_duration_s: float
    detector: str
    warnings: tuple[str, ...] = ()

    @property
    def ranges(self) -> tuple[AudioRange, ...]:
        """Backward-compatible alias for callers of the 0.1 API."""
        return self.kept_ranges

    @property
    def removed_duration_s(self) -> float:
        return max(0.0, self.input_info.duration_s - self.output_duration_s)


def _integer_environment(
    name: str,
    default: int,
    *,
    minimum: int = 1,
) -> int:
    raw_value = os.getenv(name, str(default))
    try:
        value = int(raw_value)
    except ValueError as exc:
        raise AudioProcessingError(f"{name} must be an integer") from exc
    if value < minimum:
        raise AudioProcessingError(f"{name} must be at least {minimum}")
    return value


def _command_timeout() -> int:
    return _integer_environment("FFMPEG_TIMEOUT_SECONDS", 900, minimum=30)


def _ffmpeg_threads() -> int:
    return _integer_environment("FFMPEG_THREADS", 1)


def _run_command(command: list[str]) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            command,
            check=True,
            stdin=subprocess.DEVNULL,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=_command_timeout(),
        )
    except FileNotFoundError as exc:
        raise AudioProcessingError(f"Required binary is unavailable: {command[0]}") from exc
    except subprocess.TimeoutExpired as exc:
        raise AudioProcessingError("Audio processing timed out") from exc
    except subprocess.CalledProcessError as exc:
        detail = (exc.stderr or "").strip().splitlines()
        detail = detail[-1] if detail else "unknown FFmpeg error"
        raise AudioProcessingError(f"Audio processing failed: {detail}") from exc


def probe_audio(input_path: Path) -> AudioInfo:
    if not input_path.is_file() or input_path.stat().st_size <= 0:
        raise AudioProcessingError("Input audio is missing or empty")

    ffprobe = os.getenv("FFPROBE_BIN", "ffprobe")
    command = [
        ffprobe,
        "-v",
        "error",
        "-select_streams",
        "a:0",
        "-show_entries",
        "stream=sample_rate,channels,duration:format=duration",
        "-of",
        "json",
        str(input_path),
    ]
    result = _run_command(command)
    try:
        payload = json.loads(result.stdout)
        stream = (payload.get("streams") or [])[0]
        format_duration = (payload.get("format") or {}).get("duration")
        duration_value = stream.get("duration") or format_duration
        sample_rate = int(stream.get("sample_rate") or 16000)
        channels = int(stream.get("channels") or 1)
        duration_s = float(duration_value)
    except (KeyError, IndexError, TypeError, ValueError, json.JSONDecodeError) as exc:
        raise AudioProcessingError("Input does not contain a readable audio stream") from exc

    if not math.isfinite(duration_s) or duration_s <= 0:
        raise AudioProcessingError("Audio duration is missing or invalid")
    if sample_rate < 8000 or sample_rate > 96000:
        raise AudioProcessingError(f"Unsupported sample rate: {sample_rate}")
    if channels < 1 or channels > 8:
        raise AudioProcessingError(f"Unsupported channel count: {channels}")

    return AudioInfo(duration_s=duration_s, sample_rate=sample_rate, channels=channels)


def decode_pcm(
    input_path: Path,
    output_path: Path,
    *,
    sample_rate: int,
    channels: int,
) -> None:
    ffmpeg = os.getenv("FFMPEG_BIN", "ffmpeg")
    command = [
        ffmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-nostdin",
        "-y",
        "-threads",
        str(_ffmpeg_threads()),
        "-i",
        str(input_path),
        "-map",
        "0:a:0",
        "-vn",
        "-acodec",
        "pcm_s16le",
        "-ar",
        str(sample_rate),
        "-ac",
        str(channels),
        "-f",
        "s16le",
        str(output_path),
    ]
    _run_command(command)


def run_webrtc_vad(
    pcm_path: Path,
    *,
    sample_rate: int = 16000,
    frame_ms: int = 20,
    aggressiveness: int = 2,
) -> list[bool]:
    """Return one WebRTC decision per frame, zero-padding the final partial frame."""
    if sample_rate not in {8000, 16000, 32000, 48000}:
        raise AudioProcessingError("WebRTC VAD requires 8, 16, 32, or 48 kHz audio")
    if frame_ms not in {10, 20, 30}:
        raise AudioProcessingError("WebRTC VAD frame_ms must be 10, 20, or 30")
    if aggressiveness not in {0, 1, 2, 3}:
        raise AudioProcessingError("VAD aggressiveness must be between 0 and 3")

    frame_samples = sample_rate * frame_ms // 1000
    frame_bytes = frame_samples * 2
    vad = webrtcvad.Vad(aggressiveness)
    decisions: list[bool] = []

    with pcm_path.open("rb") as pcm_file:
        while True:
            frame = pcm_file.read(frame_bytes)
            if not frame:
                break
            is_partial = len(frame) < frame_bytes
            if is_partial:
                if len(frame) < 2:
                    break
                frame += bytes(frame_bytes - len(frame))
            decisions.append(vad.is_speech(frame, sample_rate))
            if is_partial:
                break

    return decisions


def _merge_ranges(ranges: Iterable[AudioRange]) -> tuple[AudioRange, ...]:
    ordered = sorted(ranges, key=lambda value: (value.start_s, value.end_s))
    merged: list[AudioRange] = []
    for current in ordered:
        if current.end_s <= current.start_s:
            continue
        if not merged or current.start_s > merged[-1].end_s:
            merged.append(current)
            continue
        previous = merged[-1]
        merged[-1] = AudioRange(
            start_s=previous.start_s,
            end_s=max(previous.end_s, current.end_s),
        )
    return tuple(merged)


def normalize_ranges(
    ranges: Iterable[AudioRange],
    *,
    duration_s: float,
) -> tuple[AudioRange, ...]:
    """Validate, sort, and merge ranges on the original audio timeline."""
    if not math.isfinite(duration_s) or duration_s <= 0:
        raise AudioProcessingError("Audio duration must be positive and finite")

    validated: list[AudioRange] = []
    tolerance = 1e-6
    for index, audio_range in enumerate(ranges):
        start = audio_range.start_s
        end = audio_range.end_s
        if not math.isfinite(start) or not math.isfinite(end):
            raise AudioProcessingError(f"Range {index} must contain finite values")
        if start < -tolerance or end > duration_s + tolerance:
            raise AudioProcessingError(
                f"Range {index} must stay within 0 and {duration_s:.3f} seconds"
            )
        start = min(duration_s, max(0.0, start))
        end = min(duration_s, max(0.0, end))
        if end <= start:
            raise AudioProcessingError(f"Range {index} end must be after start")
        validated.append(AudioRange(start, end))
    return _merge_ranges(validated)


def invert_ranges(
    kept_ranges: Iterable[AudioRange],
    *,
    duration_s: float,
) -> tuple[AudioRange, ...]:
    """Return the exact complement of normalized kept ranges."""
    normalized = normalize_ranges(kept_ranges, duration_s=duration_s)
    removed: list[AudioRange] = []
    cursor = 0.0
    for kept in normalized:
        if kept.start_s > cursor:
            removed.append(AudioRange(cursor, kept.start_s))
        cursor = max(cursor, kept.end_s)
    if cursor < duration_s:
        removed.append(AudioRange(cursor, duration_s))
    return tuple(removed)


def build_manual_keep_ranges(
    *,
    duration_s: float,
    keep_ranges: Iterable[AudioRange] | None = None,
    remove_ranges: Iterable[AudioRange] | None = None,
) -> tuple[AudioRange, ...]:
    """Build a keep plan from exactly one caller-supplied range representation."""
    if keep_ranges is not None and remove_ranges is not None:
        raise AudioProcessingError("Supply manual keep ranges or remove ranges, not both")
    if keep_ranges is None and remove_ranges is None:
        raise AudioProcessingError("No manual range plan was supplied")
    if keep_ranges is not None:
        return normalize_ranges(keep_ranges, duration_s=duration_s)
    assert remove_ranges is not None
    normalized_removed = normalize_ranges(remove_ranges, duration_s=duration_s)
    return invert_ranges(normalized_removed, duration_s=duration_s)


def build_keep_ranges(
    decisions: list[bool],
    *,
    duration_s: float,
    frame_ms: int = 20,
    min_silence_ms: int = 800,
    keep_silence_ms: int = 250,
    padding_ms: int = 80,
    min_speech_ms: int = 160,
) -> tuple[AudioRange, ...]:
    """Convert frame-level VAD decisions into ranges to retain.

    Short unvoiced gaps stay inside one range. Once a gap reaches
    min_silence_ms, only boundary padding is retained. Minimum speech is
    measured from voiced frames, not from padding or intervening silence.
    """
    if not decisions or duration_s <= 0:
        return ()
    if frame_ms not in {10, 20, 30}:
        raise AudioProcessingError("frame_ms must be 10, 20, or 30")
    if min_silence_ms < frame_ms:
        raise AudioProcessingError("min_silence_ms must be at least one frame")
    if keep_silence_ms < 0 or padding_ms < 0 or min_speech_ms < 0:
        raise AudioProcessingError("Silence and padding parameters must be non-negative")

    frame_s = frame_ms / 1000.0
    padding_s = padding_ms / 1000.0
    boundary_silence_s = keep_silence_ms / 2000.0
    boundary_padding_s = max(padding_s, boundary_silence_s)
    min_speech_s = min_speech_ms / 1000.0
    close_after_frames = max(1, math.ceil(min_silence_ms / frame_ms))

    ranges: list[AudioRange] = []
    current_start: float | None = None
    last_voice_end: float | None = None
    voiced_duration_s = 0.0
    silence_run = 0

    def close_current_range() -> None:
        nonlocal current_start, last_voice_end, voiced_duration_s, silence_run
        if current_start is not None and last_voice_end is not None:
            end = min(duration_s, last_voice_end + boundary_padding_s)
            if voiced_duration_s + 1e-9 >= min_speech_s:
                ranges.append(AudioRange(current_start, end))
        current_start = None
        last_voice_end = None
        voiced_duration_s = 0.0
        silence_run = 0

    for frame_index, is_speech in enumerate(decisions):
        frame_start = frame_index * frame_s
        frame_end = min(duration_s, (frame_index + 1) * frame_s)
        if frame_start >= duration_s:
            break

        if is_speech:
            if current_start is None:
                current_start = max(0.0, frame_start - boundary_padding_s)
            last_voice_end = frame_end
            voiced_duration_s += frame_end - frame_start
            silence_run = 0
            continue

        if current_start is None:
            continue

        silence_run += 1
        if silence_run >= close_after_frames:
            close_current_range()

    close_current_range()
    return _merge_ranges(ranges)


def _fade_pcm_chunk(
    data: bytes,
    *,
    channels: int,
    segment_offset_frames: int,
    segment_frames: int,
    fade_in_frames: int,
    fade_out_frames: int,
) -> bytes:
    if fade_in_frames <= 0 and fade_out_frames <= 0:
        return data

    samples = array("h")
    samples.frombytes(data)
    if sys.byteorder != "little":
        samples.byteswap()

    frame_count = len(samples) // channels
    for local_frame in range(frame_count):
        segment_frame = segment_offset_frames + local_frame
        gain = 1.0
        if fade_in_frames > 0 and segment_frame < fade_in_frames:
            gain = min(gain, segment_frame / fade_in_frames)
        frames_after = segment_frames - 1 - segment_frame
        if fade_out_frames > 0 and frames_after < fade_out_frames:
            gain = min(gain, max(0.0, frames_after / fade_out_frames))
        if gain >= 1.0:
            continue
        sample_offset = local_frame * channels
        for channel in range(channels):
            index = sample_offset + channel
            samples[index] = round(samples[index] * gain)

    if sys.byteorder != "little":
        samples.byteswap()
    return samples.tobytes()


def render_ranges(
    source_pcm_path: Path,
    output_pcm_path: Path,
    ranges: tuple[AudioRange, ...],
    *,
    sample_rate: int,
    channels: int,
    fade_ms: int = 8,
) -> float:
    """Copy selected PCM ranges and fade true edit boundaries to prevent clicks."""
    if sample_rate <= 0 or channels <= 0:
        raise AudioProcessingError("PCM sample rate and channel count must be positive")
    if fade_ms < 0 or fade_ms > 100:
        raise AudioProcessingError("fade_ms must be between 0 and 100")

    bytes_per_frame = channels * 2
    source_size = source_pcm_path.stat().st_size
    source_frames = source_size // bytes_per_frame
    if source_frames <= 0:
        raise AudioProcessingError("Decoded source PCM is empty")

    chunk_frames = max(1, sample_rate // 2)
    requested_fade_frames = round(sample_rate * fade_ms / 1000.0)
    total_frames_written = 0

    with source_pcm_path.open("rb") as source, output_pcm_path.open("wb") as output:
        for audio_range in ranges:
            start_frame = min(
                source_frames,
                max(0, round(audio_range.start_s * sample_rate)),
            )
            end_frame = min(
                source_frames,
                max(start_frame, round(audio_range.end_s * sample_rate)),
            )
            segment_frames = end_frame - start_frame
            if segment_frames <= 0:
                continue

            maximum_edge_fade = segment_frames // 2
            fade_in_frames = min(requested_fade_frames, maximum_edge_fade) if start_frame > 0 else 0
            fade_out_frames = (
                min(requested_fade_frames, maximum_edge_fade) if end_frame < source_frames else 0
            )

            source.seek(start_frame * bytes_per_frame)
            remaining_frames = segment_frames
            segment_offset_frames = 0
            while remaining_frames > 0:
                frames_to_read = min(chunk_frames, remaining_frames)
                data = source.read(frames_to_read * bytes_per_frame)
                complete_bytes = len(data) - (len(data) % bytes_per_frame)
                if complete_bytes <= 0:
                    break
                data = data[:complete_bytes]
                actual_frames = len(data) // bytes_per_frame
                data = _fade_pcm_chunk(
                    data,
                    channels=channels,
                    segment_offset_frames=segment_offset_frames,
                    segment_frames=segment_frames,
                    fade_in_frames=fade_in_frames,
                    fade_out_frames=fade_out_frames,
                )
                output.write(data)
                total_frames_written += actual_frames
                remaining_frames -= actual_frames
                segment_offset_frames += actual_frames
                if actual_frames < frames_to_read:
                    break

    if total_frames_written <= 0:
        raise AudioProcessingError("Range plan produced an empty PCM output")
    return total_frames_written / sample_rate


def encode_output(
    pcm_path: Path,
    output_path: Path,
    *,
    output_format: str,
    sample_rate: int,
    channels: int,
) -> None:
    ffmpeg = os.getenv("FFMPEG_BIN", "ffmpeg")
    if output_format == "wav":
        codec_args = ["-c:a", "pcm_s16le"]
        format_args: list[str] = ["-rf64", "auto"]
    elif output_format == "m4a":
        codec_args = ["-c:a", "aac", "-b:a", os.getenv("AAC_BITRATE", "128k")]
        format_args = ["-movflags", "+faststart"]
    else:
        raise AudioProcessingError("output_format must be m4a or wav")

    command = [
        ffmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-nostdin",
        "-y",
        "-threads",
        str(_ffmpeg_threads()),
        "-f",
        "s16le",
        "-ar",
        str(sample_rate),
        "-ac",
        str(channels),
        "-i",
        str(pcm_path),
        "-map_metadata",
        "-1",
        "-vn",
        *codec_args,
        *format_args,
        str(output_path),
    ]
    _run_command(command)


def process_audio(
    input_path: Path,
    work_dir: Path,
    *,
    max_duration_s: int = 3600,
    output_format: str = "m4a",
    frame_ms: int = 20,
    aggressiveness: int = 2,
    min_silence_ms: int = 800,
    keep_silence_ms: int = 250,
    padding_ms: int = 80,
    min_speech_ms: int = 160,
    fade_ms: int = 8,
    no_speech_policy: str = "keep_original",
    manual_keep_ranges: Iterable[AudioRange] | None = None,
    manual_remove_ranges: Iterable[AudioRange] | None = None,
) -> ProcessResult:
    if output_format not in {"m4a", "wav"}:
        raise AudioProcessingError("output_format must be m4a or wav")
    if no_speech_policy not in {"keep_original", "error"}:
        raise AudioProcessingError("no_speech_policy must be keep_original or error")
    if fade_ms < 0 or fade_ms > 100:
        raise AudioProcessingError("fade_ms must be between 0 and 100")

    work_dir.mkdir(parents=True, exist_ok=True)
    info = probe_audio(input_path)
    if info.duration_s > max_duration_s:
        raise AudioProcessingError(f"Audio duration exceeds the {max_duration_s} second limit")

    analysis_pcm = work_dir / "analysis-16k-mono.pcm"
    source_pcm = work_dir / "source.pcm"
    rendered_pcm = work_dir / "rendered.pcm"
    output_path = work_dir / f"trimmed.{output_format}"
    warnings: list[str] = []

    has_manual_plan = manual_keep_ranges is not None or manual_remove_ranges is not None
    if has_manual_plan:
        kept_ranges = build_manual_keep_ranges(
            duration_s=info.duration_s,
            keep_ranges=manual_keep_ranges,
            remove_ranges=manual_remove_ranges,
        )
        detector = "manual"
        if not kept_ranges:
            raise NoSpeechDetected("Manual range plan removes all audio")
    else:
        decode_pcm(input_path, analysis_pcm, sample_rate=16000, channels=1)
        try:
            decisions = run_webrtc_vad(
                analysis_pcm,
                sample_rate=16000,
                frame_ms=frame_ms,
                aggressiveness=aggressiveness,
            )
        finally:
            analysis_pcm.unlink(missing_ok=True)

        kept_ranges = build_keep_ranges(
            decisions,
            duration_s=info.duration_s,
            frame_ms=frame_ms,
            min_silence_ms=min_silence_ms,
            keep_silence_ms=keep_silence_ms,
            padding_ms=padding_ms,
            min_speech_ms=min_speech_ms,
        )
        detector = "webrtc_vad"
        if not kept_ranges:
            if no_speech_policy == "error":
                raise NoSpeechDetected("No speech was detected in the audio")
            kept_ranges = (AudioRange(0.0, info.duration_s),)
            warnings.append("no_speech_detected_original_kept")

    kept_ranges = normalize_ranges(kept_ranges, duration_s=info.duration_s)
    removed_ranges = invert_ranges(kept_ranges, duration_s=info.duration_s)

    decode_pcm(
        input_path,
        source_pcm,
        sample_rate=info.sample_rate,
        channels=info.channels,
    )
    try:
        output_duration_s = render_ranges(
            source_pcm,
            rendered_pcm,
            kept_ranges,
            sample_rate=info.sample_rate,
            channels=info.channels,
            fade_ms=fade_ms,
        )
    finally:
        source_pcm.unlink(missing_ok=True)

    try:
        encode_output(
            rendered_pcm,
            output_path,
            output_format=output_format,
            sample_rate=info.sample_rate,
            channels=info.channels,
        )
    finally:
        rendered_pcm.unlink(missing_ok=True)

    return ProcessResult(
        output_path=output_path,
        input_info=info,
        kept_ranges=kept_ranges,
        removed_ranges=removed_ranges,
        output_duration_s=output_duration_s,
        detector=detector,
        warnings=tuple(warnings),
    )
