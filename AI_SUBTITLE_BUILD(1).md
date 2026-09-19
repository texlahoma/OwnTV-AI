# OwnTV-AI

OwnTV v5.0.0 + OwnTV_Core 1.0.47 with optional local Whisper live AI subtitles.

## AI subtitle pipeline

- Decoded PCM is tapped from the same ExoPlayer live session.
- Whisper runs locally on arm64-v8a.
- Tiny multilingual model is the default; Base is optional.
- DeepSeek is the default translation provider when an API key is configured.
- A free Google translation endpoint is used as fallback when DeepSeek is unavailable.
- Chinese source + Chinese target bypasses translation.
- Channel/player generations invalidate stale ASR/translation results after channel changes.
- AI diagnostics are stored locally without API keys.

## Build

The GitHub Actions workflow builds:

`./gradlew :app:assembleStandardDebug -Powntv.corePath="$GITHUB_WORKSPACE/OwnTV_Core"`

The Whisper Android AAR is published on Maven Central as
`dev.ffmpegkit-maintained:whisper-android:1.0.0`.
