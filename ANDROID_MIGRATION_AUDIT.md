# ANDROID MIGRATION AUDIT — MoneyPrinterTurbo

Audit date: 2026-09-05 · Base commit: `9f0b28f` (main) · Target: native Android (Kotlin, arm64-v8a, Android 10+)

---

## 1. Existing architecture (as audited)

```
main.py / cli.py ──────────────┐
webui/ (Streamlit) ────────────┤
app/controllers/v1 (FastAPI) ──┤
                               ▼
                    app/services/task.py  (_run_pipeline — the functional core)
                               ▼
   ┌──────────┬──────────┬────────────┬─────────────┬──────────────┐
   │ llm.py   │ voice.py │ material.py│ subtitle.py │ video.py     │
   │ (LLM)    │ (TTS)    │ (stock)    │ (SRT/whisper│ (moviepy+    │
   │          │          │            │  + edge)    │  ffmpeg)     │
   └──────────┴──────────┴────────────┴─────────────┴──────────────┘
                               ▼
              app/services/state.py (task state manager, optional Redis)
                               ▼
                 FFmpeg binary + moviepy 2.2.1 + faster-whisper
```

Pipeline stage order (from `_run_pipeline`, `app/services/task.py:1284`):
1. **Preflight** — provider key checks, ffmpeg availability, music-prompt validation
2. **Script** (progress 5→10) — LLM, `DEFAULT_SCRIPT_SYSTEM_PROMPT` + paragraph/language/user-requirements
3. **Terms** (10→20) — LLM returns JSON array of 1–3 word search terms (ordered mode optional)
4. **Materials** (20→40) — stock search (Pexels/Pixabay/Coverr) or AI video/image providers or local materials
5. **Audio** (40→50) — TTS (edge default) or custom audio; silent-audio generation when voice is "no voice"
6. **Subtitle** (50→60) — `edge` (from TTS word boundaries) or `whisper` (faster-whisper)
7. **Combine/render** (60→90→100) — per-scene clip download, fit-to-canvas, concat, audio mix, subtitles, final encode with codec fallback

## 2. Existing modules

| Module | File(s) | Purpose |
|---|---|---|
| Task pipeline | `app/services/task.py` (~1800 ln) | Stage orchestration, progress, retries, failure states |
| Video compose | `app/services/video.py` | moviepy clips, canvas fit, concat (ffmpeg filter), BGM mix, subtitle burn-in, codec fallback chain |
| TTS | `app/services/voice.py` | edge-tts (default), Azure v2, Azure, SiliconFlow, Gemini, MiMo, MiniMax, ElevenLabs, Chatterbox, Fish, 302.ai; SubMaker → SRT from word boundaries |
| LLM | `app/services/llm.py` | OpenAI-compatible, Gemini, Cloudflare AI Gateway, Azure OpenAI, Ollama; script + terms prompts; test_connection |
| Stock media | `app/services/material.py` | Pexels, Pixabay, Coverr video search; aspect filtering; local materials |
| AI media | `volcengine_seedance.py`, `ofox.py`, `metaso_minimax.py`, `twelvelabs.py` | AI video/image generation providers |
| Subtitles | `app/services/subtitle.py` | faster-whisper integration, style → ffmpeg force_style string |
| BGM | `app/services/bgm.py` + `resource/songs/` | preset songs, volume/fade handling |
| State | `app/services/state.py` | in-memory + optional Redis task store |
| API | `app/controllers/v1/{video,llm}.py` | `POST /videos, /subtitle, /audio`, `GET /tasks`, `GET/DELETE /tasks/{id}`, `GET /musics`, `/download/{file}`, `/stream/{file}`, `GET /ping`, LLM helpers |
| WebUI | `webui/Main.py` (Streamlit) | full form UI, per-stage buttons, batch by topic lines |
| CLI | `cli.py` | argparse mirror of the same flow |
| Config | `app/config/config.py`, `config.example.toml` | TOML: app / llm_provider / whisper / proxy / azure / pexels / pixabay / siliconflow / redis / provider registries |

## 3. Existing APIs (reused in REMOTE mode)

The Android app's **remote backend mode** talks to the original FastAPI verbatim:
- `POST /v1/videos` (VideoParams) → task id · `POST /v1/audio`, `POST /v1/subtitle` (intermediate artifacts)
- `GET /v1/tasks`, `GET /v1/tasks/{id}`, `DELETE /v1/tasks/{id}`
- `GET /v1/musics`, `POST /v1/upload-bgm-file`, `GET /v1/stream/{file}` (HTTP Range supported)

## 4. Existing workflows
- Full flow: subject → script → terms → media → audio → subtitle → render
- Intermediate-only flows: audio only, subtitle only, script only, materials only
- Batch: one topic per line → N tasks (WebUI + CLI)
- Voice preview, BGM preview, materials cache, task history w/ artifacts (`task_artifacts.py`)

## 5. Existing configuration system
TOML (`config.example.toml`, 25 KB) with sections `app`, `whisper`, `proxy`, `azure_speech`, `azure_translation`, `siliconflow`, `redis`, plus UI provider registries (`llm_providers`, `tts_providers`...) stored alongside. API keys are plaintext in config.toml — **upgraded** on Android to Keystore-encrypted storage.

## 6. Existing external dependencies

| Dependency | Role | Android class |
|---|---|---|
| moviepy 2.2.1 | clip composition | REPLACED (Kotlin ffmpeg command composer) |
| edge_tts 7.2.7 | TTS + subtitle timing | ADAPTED (Kotlin WebSocket port of the protocol incl. DRM Sec-MS-GEC) |
| openai / litellm | LLM | ADAPTED (Kotlin OkHttp, OpenAI-compatible + Gemini REST) |
| faster-whisper 1.1.0 | STT subtitles | REQUIRES_REMOTE_BACKEND (CTranslate2; no on-device port) |
| azure-cognitiveservices-speech | TTS | ADAPTED (REST v2 endpoint via OkHttp) |
| dashscope / google-genai | provider SDKs | ADAPTED (REST) |
| redis 5.2 | task store | REPLACED (Room) |
| streamlit | WebUI | REPLACED (Compose) |
| FFmpeg binary | rendering | ADAPTED (static aarch64 binary built from source in CI, run from nativeLibraryDir) |
| pydub | audio utils | REPLACED (ffmpeg) |

## 7. Android-compatible components (used as-is)
- All REST-based providers (LLM, stock media, TTS-REST, AI video APIs) — pure HTTP
- Pipeline stage semantics, progress model, prompt templates (ported verbatim)
- Task state model (states + progress + stage labels)
- SRT format & subtitle styling (force_style parameters)
- Preset songs + fonts (`resource/songs`, `resource/fonts` → app assets)

## 8. Components requiring adaptation
- **FFmpeg**: cross-compiled static binary (libx264 + libass + fontconfig + freetype + fribidi + harfbuzz), executed from `nativeLibraryDir` (W^X-safe on Android 10+). moviepy logic re-implemented as deterministic ffmpeg filtergraphs: concat demuxer + scale/crop fit (cover/contain), `amix` with volume + afade, `subtitles=...:force_style=...` burn-in, codec fallback chain (libx264 → mpeg4).
- **edge-tts**: Kotlin port — WSS to `speech.platform.bing.com`, `Sec-MS-GEC` DRM hash (SHA-256 of 5-min-quantized Windows ticks + trusted token), MP3 audio + WordBoundary metadata → SRT (sentence & word-by-word modes).
- **LLM providers**: single OpenAI-compatible client (covers OpenAI, Moonshot, DeepSeek, Ollama, SiliconFlow, Cloudflare gateway, one-api…) + Gemini native REST adapter; `test_connection` parity.
- **State manager**: Room tables + WorkManager (foreground service during render).

## 9. Components requiring replacement
- Streamlit WebUI → Jetpack Compose (full information-architecture parity; see FEATURE_PARITY.md)
- Redis state → Room
- moviepy → MediaComposer (ffmpeg CLI orchestration)
- TOML config → DataStore + encrypted provider store

## 10. Components that cannot realistically run locally on Android

| Component | Verdict | Reason |
|---|---|---|
| faster-whisper | REQUIRES_REMOTE_BACKEND | CTranslate2 + model weights (39M–3G); no maintained aarch64 Android port |
| Python runtime (Streamlit/WebUI) | UNSUPPORTED on-device | Full CPython + uv env; the WebUI's *function* is fully replaced by native screens |
| Chatterbox / Fish-Speech local inference | REQUIRES_REMOTE_BACKEND | GPU/vLLM-class models; their *API endpoints* are used in CLOUD mode |
| moviepy | UNSUPPORTED on-device | CPython; functionally replaced by direct ffmpeg filtergraphs (strictly more efficient) |
| AI video (Seedance/OFox/Metaso) on-device | UNSUPPORTED | Cloud APIs by design; used via cloud mode |

## 11. Recommended architecture (implemented)

Three execution modes, user-selectable per task and globally:
- **LOCAL** — full pipeline on device: Kotlin LLM client → cloud LLM API; edge-tts (Kotlin) or native REST TTS; stock download; on-device static FFmpeg render. No Python, no server.
- **REMOTE** — the Android app is a native client of an unmodified MoneyPrinterTurbo server (same endpoints audited in §3); task polling + downloads + playback.
- **CLOUD API** — per-stage provider services (LLM/TTS/media) called directly; identical to LOCAL except all heavy AI work is hosted; still renders locally with FFmpeg.

Layering:
```
presentation (Compose, MVVM, Navigation)
        ▼
domain (TaskPipeline, models, use-cases)
        ▼
data (Room · DataStore · KeystoreSecureStore · OkHttp clients · WorkManager workers)
        ▼
media (FfmpegExecutor over packaged static binary; fonts; SRT)
```

Constraints honored: no UI-thread work, streaming downloads, WorkManager persistence, cancellation, storage preflight checks, secrets never logged (see ANDROID_ARCHITECTURE.md).
