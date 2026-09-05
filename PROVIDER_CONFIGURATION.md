# PROVIDER CONFIGURATION

## LLM providers (script + keywords)

Settings → LLM Providers → Add provider.

| Field | Value |
|---|---|
| Base URL | OpenAI-compatible root, e.g. `https://api.openai.com/v1`, `https://api.deepseek.com/v1`, `https://api.moonshot.cn/v1`, `http://<host>:11434/v1` (Ollama), SiliconFlow, one-api gateways… |
| API kind | `openai_compatible` (default) or `gemini` (Google AI Studio; base `https://generativelanguage.googleapis.com/v1beta`) |
| API key | stored **encrypted with Android Keystore** (SecureStore); never logged, never exported, `allowBackup=false` |
| Model | e.g. `gpt-4o-mini`, `deepseek-chat`, `llama3.1`, `gemini-1.5-flash` |
| Test | one-click connection test with latency (parity with upstream `llm.test_connection`) |

Default provider (★) is used unless a per-function override exists (script / terms).

## Stock media keys

Settings → Stock media API keys:
- **Pexels**: https://www.pexels.com/api/ (free tier)
- **Pixabay**: https://pixabay.com/api/docs/ (free)
- **Coverr**: https://coverr.co (optional)

Keys are Keystore-encrypted like LLM keys. Missing key = clear error, never a crash.

## TTS

On-device default is **edge-tts** (same as upstream default): Microsoft Edge read-aloud service over WebSocket with the Sec-MS-GEC DRM token. Voice catalog = `azure_voices.json` (331 voices) bundled from upstream, searchable by locale — including Persian (`fa-IR-DilaraNeural`), Chinese, English…

Subtitles are generated from edge-tts **word boundary metadata** (sentence or word-by-word mode) — no whisper needed for the default path.

Azure/OpenAI TTS and other REST TTS services: use REMOTE mode (upstream server does the synthesis) or custom audio upload.

## Execution modes

| Mode | LLM | TTS | Stock | Render |
|---|---|---|---|---|
| LOCAL (default) | user cloud API | edge-tts on device | user keys | on-device FFmpeg |
| REMOTE | server | server | server | server |
| CLOUD_API | user cloud API | user cloud API | user keys | on-device FFmpeg |

## Security guarantees
- Keys exist only inside the app's Keystore-encrypted blob file.
- Keys are never written to logs, task records, exported files, or crash reports.
- Provider "Test" never echoes the key.
