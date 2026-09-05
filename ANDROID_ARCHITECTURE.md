# ANDROID ARCHITECTURE

## Overview

```
┌────────────────────────────────────────────────────────────┐
│ presentation (Jetpack Compose, Material 3, MVVM)           │
│   HomeScreen · CreateScreen · ProjectScreen · TaskScreen   │
│   HistoryScreen · ProvidersScreen · SettingsScreen         │
├────────────────────────────────────────────────────────────┤
│ data + domain (Kotlin)                                     │
│   Repository (Room + WorkManager)                          │
│   TaskPipeline — 7-stage port of app/services/task.py      │
├──────────┬──────────────┬──────────────┬──────────────────┤
│ core.llm │ core.tts     │ core.media   │ core.storage     │
│ LlmService│ TtsService  │ MediaComposer│ SecureStore      │
│ Prompts  │ EdgeTtsClient│ FfmpegExecutor PrefsStore       │
│ (OpenAI +│ (WSS + DRM)  │ Srt/Subtitle │ (Keystore)       │
│  Gemini) │ VoiceCatalog │ Style/Fonts  │ DataStore        │
├──────────┴──────────────┴──────────────┴──────────────────┤
│ execution                                                  │
│   RenderWorker (WorkManager, foreground, cancel/retry)     │
│   static ffmpeg binary (libffmpeg_exec.so, arm64-v8a)      │
│   executed from nativeLibraryDir (Android 10+ W^X-safe)    │
└────────────────────────────────────────────────────────────┘
```

## Execution modes

| Mode | Pipeline location | Render location | Notes |
|---|---|---|---|
| LOCAL (default) | on-device (Kotlin) | on-device (ffmpeg binary) | LLM/TTS via user-configured cloud APIs |
| REMOTE | unmodified upstream server (`POST /v1/videos`) | server | app = native client; poll `/v1/tasks/{id}` |
| CLOUD_API | per-stage cloud services | on-device | same as LOCAL for render |

## Pipeline (TaskPipeline.kt)

Stage parity with upstream `_run_pipeline`:

| Stage | Progress | Android implementation |
|---|---|---|
| preflight | 1 | ffmpeg availability, StatFs storage check (≥250 MB), voice config check |
| script | 5→10 | LlmService + Prompts (verbatim upstream prompt templates) |
| terms | 12→20 | LlmService → JSON array parse (code-fence tolerant) |
| materials | 20→40 | StockMediaClient (Pexels/Pixabay/Coverr), aspect filtering, OkHttp download |
| audio | 40→50 | EdgeTtsClient (WSS, Sec-MS-GEC DRM) → MP3 + WordBoundary metadata |
| subtitle | 50→60 | SubtitleBuilder: sentences/word-by-word from word boundaries; equal-segment fallback for custom audio |
| combine | 60→99 | MediaComposer: scene normalize (scale/crop cover/contain, fps, speed, fade) → concat demuxer → amix (voice + looped BGM + fades) → subtitles burn (libass force_style, PlayRes-exact px) → faststart |
| done | 100 | final.mp4 in `Android/data/<pkg>/files/tasks/<taskid>/` |

## FFmpeg on Android

- Cross-compiled from source in CI (`android/scripts/build_ffmpeg_android.sh`) with NDK r27c: static single binary ~15–25 MB.
- Libraries: libx264 (GPL), libass + freetype + fribidi + harfbuzz (subtitles, Persian/Arabic shaping via harfbuzz, bidi via fribidi).
- Packaged as `jniLibs/arm64-v8a/libffmpeg_exec.so` → PackageManager extracts to `nativeLibraryDir` where `exec()` is permitted on Android 10+ (W^X).
- Real progress: `-progress pipe:1` + `out_time_us` parsing.
- Failure isolation: stderr tail included in task error message; codec/preset failures surface verbatim.

## Persistence

- **Room**: `projects` + `tasks` tables (status codes mirror upstream const.py: −1 failed, 1 complete, 4 processing).
- **DataStore**: app settings + LLM provider registry (API keys stored empty).
- **SecureStore**: AndroidKeyStore AES/GCM blob for all API keys (LLM + stock). Never in prefs, logs, or backups (`allowBackup=false`).

## Concurrency

- Pipeline runs inside `CoroutineWorker` (WorkManager `ExistingWorkPolicy.KEEP`, one unique worker per task).
- UI observes Room; polling `TaskScreen` at 1.5 s.
- Cancellation: `WorkManager.cancelUniqueWork` → worker stops; pipeline checks `cancelled` between steps; task marked CANCELLED.
- All network/ffmpeg work on IO dispatchers; no UI-thread blocking.

## Error handling

Every stage failure is caught in `TaskPipeline.run`, written to the task row (`error` + log) with a human-readable message; raw stack traces never shown. Provider errors are mapped (401/403 → invalid key, 429 → rate limit, timeouts, no materials found…). Debug details live in the per-task log view.

## i18n

`values/` (English), `values-fa/` (Persian), `values-zh/` (Chinese) — all user-facing strings via resources. RTL-safe layouts.

## Security

- API keys: Keystore-backed only; never logged; `allowBackup=false`.
- HTTP: plain OkHttp; user-supplied base URLs (documented in PROVIDER_CONFIGURATION.md).
- No WebView usage; no JS execution; downloaded materials confined to app-private dirs.
