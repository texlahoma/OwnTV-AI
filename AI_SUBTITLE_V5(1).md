# OwnTV AI Subtitle v5

## Pipeline

- On-device multilingual Whisper (`ggml-tiny.bin` by default; `ggml-base.bin` optional).
- One multilingual model covers Chinese/English/French/German/Spanish/Italian and other supported languages; no per-language model downloads.
- First use downloads the selected model into app-private storage.
- Per-channel language can be explicitly selected (`auto`, `zh`, `en`, `fr`, `de`, `es`, `it`).
- Chinese source + Chinese target bypasses translation entirely. Auto-detected Chinese text is also recognized by a conservative CJK heuristic and bypasses translation.
- Non-Chinese source is translated through a configurable OpenAI-compatible `/chat/completions` endpoint, DeepSeek `deepseek-flash` by default.
- If the configured LLM fails or has no key, the existing keyless Google Translate fallback is used.
- AI audio is tapped from the same ExoPlayer decoded PCM path; no second IPTV connection is opened.
- Channel/player generation IDs prevent stale subtitles from a previous channel from appearing after a channel switch.

## DeepSeek default

Base URL: `https://api.deepseek.com`

Model: `deepseek-flash`

For live subtitle latency, thinking should normally be disabled.
