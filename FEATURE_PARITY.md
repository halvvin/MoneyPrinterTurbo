# FEATURE PARITY CHECKLIST

Original Feature | Android Equivalent | Status
---|---|---
WebUI (Streamlit) | Native Compose UI (Home/Create/Project/Task/History/Settings/Providers) | ✅ (information-architecture parity, not pixel parity)
API (FastAPI client) | RemoteBackend mode (Settings → Mode=remote) | ✅ endpoints reused verbatim (`/v1/videos`, `/v1/tasks`, `/v1/llm/scripts`, `/v1/llm/terms`)
CLI | Android task actions (queue/retry/cancel from UI) | ✅
Script generation | CreateScreen → Generate Script (upstream prompts verbatim) | ✅
Custom script | Script editor field + persisted per project | ✅
Media search (stock) | CreateScreen → source selector + StockMediaClient (Pexels/Pixabay/Coverr) | ✅
Local media | TaskConfig.videoMaterials localPath entries | ⚠️ partial (file picker integration pending — NOT IMPLEMENTED UI; config/API supports it)
AI media | OpenAI-compatible image endpoint (openai_image) | ⚠️ client included; Seedance/OFox/Metaso REMOTE-mode only (cloud APIs, on-device = UNSUPPORTED by design)
TTS (edge-tts default) | EdgeTtsClient Kotlin port (WSS + Sec-MS-GEC DRM + word boundaries) | ✅
TTS (Azure/others) | REMOTE mode / custom audio | ⚠️ (REST providers via remote backend; on-device edge-tts is default)
Custom audio | TaskConfig.customAudioFile | ✅
No voiceover | silent audio via ffmpeg anullsrc | ✅
Subtitles (edge) | SubtitleBuilder from TTS word boundaries (sentence + word_by_word) | ✅
Subtitles (whisper) | NOT IMPLEMENTED locally — faster-whisper/CTranslate2 has no aarch64 Android port; use REMOTE mode (documented) | ❌→REMOTE
Subtitle styling | SubtitleStyle force_style (font/size/position/colors/outline/box, PlayRes px-exact) | ✅
Subtitle import (external SRT) | Srt.parse supported in engine | ⚠️ UI picker NOT IMPLEMENTED
Music | preset songs bundled (10) + random + none + volume/fades | ✅ custom file via bgmFile
Rendering | static ffmpeg (x264 + libass) scenes→concat→mix→burn | ✅
Transitions | fade (per-scene fade in/out) | ⚠️ none/fade implemented; slide/zoom/shuffle NOT IMPLEMENTED locally (upstream moviepy-only) — REMOTE mode covers them
Video count (N outputs) | TaskConfig.videoCount → N queued tasks | ✅
Batch generation | Batch mode: one topic per line → N tasks via WorkManager | ✅
History | Room tasks + History screen + playback (FileProvider intent) | ✅
Projects | Room projects: open/rename/duplicate/delete/re-run | ✅ export ⚠️ (JSON export NOT IMPLEMENTED)
Task manager | WorkManager + Room, real progress (ffmpeg -progress), cancel/retry | ✅
Settings | Mode/network/storage/advanced + provider manager | ✅
Provider management | LLM provider CRUD + test connection + default + per-function model | ✅
Secure key storage | AndroidKeyStore AES/GCM (SecureStore) | ✅
Downloads/export | app-private storage + share/play via FileProvider; MediaStore export to gallery | ⚠️ gallery export NOT IMPLEMENTED (FileProvider play/share works)
i18n | en / fa / zh resources | ✅
Dark/light mode | Material 3 dynamic + DayNight | ✅
Background rendering | WorkManager foreground worker | ✅

Legend: ✅ complete · ⚠️ partial (documented) · ❌ not implemented (documented above)
