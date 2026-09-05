# TROUBLESHOOTING (ANDROID)

## Install
- **"App not installed"** — remove any older build with a different signature; release builds are signed with the CI debug keystore.
- Requires Android 10+ and arm64-v8a.

## Rendering
- **"ffmpeg binary is missing or not executable"** — the APK was built without the ffmpeg job. Rebuild via CI (workflow passes the binary between jobs) or run `android/scripts/build_ffmpeg_android.sh` locally first.
- **"insufficient storage"** — pipeline preflight needs ≥250 MB free; free space or move output.
- **Render fails at scene N** — open the task → Execution log: the ffmpeg stderr tail is captured per task. Most common: exotic codec in source clip (transcode it), or a corrupt download.
- **Subtitles show boxes/???** — the selected font lacks glyphs for your script. Import a suitable TTF (e.g. Vazirmatn for Persian, Noto Sans SC for Chinese) — Settings ships with latin fonts; system fonts can be added.

## edge-tts
- **HTTP 401/403** — Microsoft DRM token rejected: device clock is wrong (Sec-MS-GEC is time-derived; enable automatic time) or the service is blocked in your region/VPN.
- **HTTP 429** — rate limited; wait and retry.
- **Timeout** — network blocks `speech.platform.bing.com`; use another network or REMOTE mode.

## LLM / stock providers
- **401/403** — wrong key or model not permitted for the key. Use the Test button.
- **"returned no parseable search terms"** — the model ignored the JSON-array instruction (reasoning models often do); pick an instruct model or lower temperature.
- **Pexels/Pixabay empty results** — terms too niche or orientation filter too strict; switch source or aspect.

## Remote mode
- **"connection refused"** — server not reachable from the phone (different LAN/VPN); check `http://<ip>:8080/v1/ping`.
- **Task stuck PROCESSING** — check the server's own task panel/log; the app only mirrors upstream state.

## General
- **Task CANCELLED unexpectedly** — WorkManager cancels workers if storage is low (constraint `requiresStorageNotLow`) or the user force-stops the app; queue again from the task screen (Retry).
- **Everything fails offline** — LOCAL mode needs cloud APIs (LLM/TTS) but renders locally; projects/settings/history are fully offline.
