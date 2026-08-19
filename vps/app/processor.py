from __future__ import annotations

import json
import math
import os
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import webrtcvad


class AudioProcessingError(RuntimeError):
    """Raised when probing, decoding, VAD, rendering, or encoding fails."""


class NoSpeechDetected(AudioProcessingError):
    """Raised when no usable speech range is found."""


@dataclass(frozen=True)
class AudioInfo:
    duration_s: float
    sample_rate: int
    channels: int


@dataclass(frozen=True)
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
    ranges: tuple[AudioRange, ...]
    output_duration_s: float


def _command_timeout() -> int:
    return max(30, int(os.getenv("FFMPEG_TIMEOUT_SECONDS", "900")))


def _run_command(command: list[str]) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            command,
            check=True,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=_command_timeout(),
        )
    except FileNotFoundError as exc:
        raise AudioProcessingError(
            f"Required binary is unavailable: {command[0]}"
        ) from exc
    except subprocess.TimeoutExpired as exc:
        raise AudioProcessingError("Audio processing timed out") from exc
    except subprocess.CalledProcessError as exc:
        detail = (exc.stderr or "").strip().splitlines()
        detail = detail[-1] if detail else "unknown ffmpeg error"
        raise AudioProcessingError(f"Audio processing failed: {detail}") from exc


def probe_audio(input_path: Path) -> AudioInfo:
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
    threads = max(1, int(os.getenv("FFMPEG_THREADS", "1")))
    command = [
        ffmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-nostdin",
        "-y",
        "-threads",
        str(threads),
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
            if len(frame) < frame_bytes:
                break
            decisions.append(vad.is_speech(frame, sample_rate))

    return decisions


def _merge_ranges(ranges: Iterable[AudioRange]) -> tuple[AudioRange, ...]:
    merged: list[AudioRange] = []
    for current in ranges:
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
    min_silence_ms, only a small amount of boundary silence is retained.
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
            silence_run = 0
            continue

        if current_start is None:
            continue

        silence_run += 1
        if silence_run < close_after_frames:
            continue

        assert last_voice_end is not None
        end = min(duration_s, last_voice_end + boundary_padding_s)
        if end - current_start >= min_speech_s:
            ranges.append(AudioRange(current_start, end))
        current_start = None
        last_voice_end = None
        silence_run = 0

    if current_start is not None and last_voice_end is not None:
        end = min(duration_s, last_voice_end + boundary_padding_s)
        if end - current_start >= min_speech_s:
            ranges.append(AudioRange(current_start, end))

    return _merge_ranges(ranges)


def render_ranges(
    source_pcm_path: Path,
    output_pcm_path: Path,
    ranges: tuple[AudioRange, ...],
    *,
    sample_rate: int,
    channels: int,
) -> float:
    bytes_per_frame = channels * 2
    chunk_frames = max(1, sample_rate // 2)
    output_duration_s = 0.0

    with source_pcm_path.open("rb") as source, output_pcm_path.open("wb") as output:
        for audio_range in ranges:
            start_frame = max(0, round(audio_range.start_s * sample_rate))
            end_frame = max(start_frame, round(audio_range.end_s * sample_rate))
            remaining_frames = end_frame - start_frame
            if remaining_frames <= 0:
                continue

            source.seek(start_frame * bytes_per_frame)
            while remaining_frames > 0:
                frames_to_read = min(chunk_frames, remaining_frames)
                data = source.read(frames_to_read * bytes_per_frame)
                if not data:
                    break
                output.write(data)
                actual_frames = len(data) // bytes_per_frame
                remaining_frames -= actual_frames
                if actual_frames <= 0:
                    break
            output_duration_s += audio_range.duration_s

    return output_duration_s


def encode_output(
    pcm_path: Path,
    output_path: Path,
    *,
    output_format: str,
    sample_rate: int,
    channels: int,
) -> None:
    ffmpeg = os.getenv("FFMPEG_BIN", "ffmpeg")
    threads = max(1, int(os.getenv("FFMPEG_THREADS", "1")))
    if output_format == "wav":
        codec_args = ["-c:a", "pcm_s16le"]
        format_args: list[str] = []
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
        str(threads),
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
) -> ProcessResult:
    info = probe_audio(input_path)
    if info.duration_s > max_duration_s:
        raise AudioProcessingError(
            f"Audio duration exceeds the {max_duration_s} second limit"
        )
    analysis_pcm = work_dir / "analysis-16k-mono.pcm"
    source_pcm = work_dir / "source.pcm"
    rendered_pcm = work_dir / "rendered.pcm"
    output_suffix = "m4a" if output_format == "m4a" else "wav"
    output_path = work_dir / f"trimmed.{output_suffix}"

    decode_pcm(input_path, analysis_pcm, sample_rate=16000, channels=1)
    decisions = run_webrtc_vad(
        analysis_pcm,
        sample_rate=16000,
        frame_ms=frame_ms,
        aggressiveness=aggressiveness,
    )
    ranges = build_keep_ranges(
        decisions,
        duration_s=info.duration_s,
        frame_ms=frame_ms,
        min_silence_ms=min_silence_ms,
        keep_silence_ms=keep_silence_ms,
        padding_ms=padding_ms,
        min_speech_ms=min_speech_ms,
    )
    if not ranges:
        raise NoSpeechDetected("No speech was detected in the audio")

    decode_pcm(
        input_path,
        source_pcm,
        sample_rate=info.sample_rate,
        channels=info.channels,
    )
    output_duration_s = render_ranges(
        source_pcm,
        rendered_pcm,
        ranges,
        sample_rate=info.sample_rate,
        channels=info.channels,
    )
    encode_output(
        rendered_pcm,
        output_path,
        output_format=output_format,
        sample_rate=info.sample_rate,
        channels=info.channels,
    )
    return ProcessResult(
        output_path=output_path,
        input_info=info,
        ranges=ranges,
        output_duration_s=output_duration_s,
    )
