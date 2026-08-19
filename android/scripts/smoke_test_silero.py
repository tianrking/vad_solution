#!/usr/bin/env python3
"""Development smoke test for the bundled Silero model (requires numpy + onnxruntime)."""

from __future__ import annotations

import argparse
import sys
import wave

import numpy as np
import onnxruntime as ort


SAMPLE_RATE = 16_000
FRAME_SIZE = 512
CONTEXT_SIZE = 64


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("model")
    parser.add_argument("wav", help="16 kHz, mono, signed 16-bit PCM WAV")
    args = parser.parse_args()

    with wave.open(args.wav, "rb") as audio:
        if (audio.getframerate(), audio.getnchannels(), audio.getsampwidth()) != (SAMPLE_RATE, 1, 2):
            raise ValueError("WAV must be 16 kHz mono PCM16")
        samples = np.frombuffer(audio.readframes(audio.getnframes()), dtype="<i2").astype(np.float32) / 32768.0

    session = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])
    input_names = [item.name for item in session.get_inputs()]
    output_names = [item.name for item in session.get_outputs()]
    if input_names != ["input", "state", "sr"] or output_names != ["output", "stateN"]:
        raise AssertionError(f"unexpected model signature: inputs={input_names}, outputs={output_names}")

    state = np.zeros((2, 1, 128), dtype=np.float32)
    context = np.zeros((1, CONTEXT_SIZE), dtype=np.float32)
    probabilities: list[float] = []
    for start in range(0, len(samples), FRAME_SIZE):
        frame = np.zeros((1, FRAME_SIZE), dtype=np.float32)
        available = samples[start : start + FRAME_SIZE]
        frame[0, : len(available)] = available
        model_input = np.concatenate((context, frame), axis=1)
        probability, state = session.run(
            ["output", "stateN"],
            {"input": model_input, "state": state, "sr": np.array([SAMPLE_RATE], dtype=np.int64)},
        )
        probabilities.append(float(probability.reshape(-1)[0]))
        context = model_input[:, -CONTEXT_SIZE:]

    frames_per_second = SAMPLE_RATE / FRAME_SIZE
    first_silence = probabilities[: int(1.5 * frames_per_second)]
    middle = probabilities[int(2.0 * frames_per_second) : int((len(samples) / SAMPLE_RATE - 2.0) * frames_per_second)]
    final_silence = probabilities[-int(1.5 * frames_per_second) :]
    print(
        "PASS model signature and recurrent inference; "
        f"leading_silence_max={max(first_silence):.4f}, "
        f"speech_max={max(middle):.4f}, trailing_silence_max={max(final_silence):.4f}"
    )
    if max(middle) < 0.55 or max(first_silence) >= 0.55 or max(final_silence) >= 0.55:
        print("FAIL: expected speech/silence separation was not observed", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
