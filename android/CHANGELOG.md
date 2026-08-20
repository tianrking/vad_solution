# Changelog

## 0.2.0 - 2026-08-20

- Added Java/Kotlin manual trimming with caller-supplied original-timeline ranges.
- Added both remove-ranges and keep-ranges modes with strict duration-bound validation.
- Manual ranges are defensively copied, sorted, and merged when overlapping or adjacent.
- Manual trimming decodes duration without loading or running the Silero model.
- `TrimResult` reports the normalized ranges actually kept and removed.
- Added `INVALID_TIME_RANGES` for range-specific processing failures.
- Preserved automatic speech/non-silence detection as the default, backward-compatible path.
- Fixed cancellation classification during the final output copy.
- Samples now remove their newly created SAF output document after cancellation or failure.
- Added unit tests, connected-device end-to-end tests, and compiled Kotlin/Java manual examples.

## 0.1.0 - 2026-08-19

- Initial Android AAR and local Maven distribution.
- On-device Silero speech VAD and energy-based non-silence mode.
- Streaming MediaCodec analysis for long recordings.
- Media3 AAC/M4A export with edit-boundary fades.
- Kotlin coroutine and Java asynchronous APIs with progress and cancellation.
- `arm64-v8a`, `armeabi-v7a`, and `x86_64` packaging verified.
- 16 KB ELF segment and APK zip alignment verified.
- Kotlin and Java sample applications.
