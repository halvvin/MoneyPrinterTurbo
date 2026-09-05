# BACKEND SETUP (REMOTE MODE)

The Android app can drive an **unmodified MoneyPrinterTurbo server** as a native client.

## 1. Run the upstream server

```bash
# on any machine reachable from the phone (PC, VPS, home server)
git clone https://github.com/harry0703/MoneyPrinterTurbo
cd MoneyPrinterTurbo
docker compose up -d          # web UI on :8501, API on :8080
```
Or manually: `uv sync && uvicorn app.asgi:app --host 0.0.0.0 --port 8080`

Configure the server's providers normally (config.toml / WebUI): LLM, TTS, Pexels/Pixabay keys, whisper if desired.

## 2. Point the app at it

Android app → Settings → Execution:
- Mode: **remote**
- Backend URL: `http://<server-ip>:8080` (same LAN) or your public HTTPS endpoint
- API token: only if you set `app.enable_api_token` upstream

## 3. What the app uses

| App action | Upstream endpoint |
|---|---|
| Generate script | `POST /v1/llm/scripts` |
| Generate keywords | `POST /v1/llm/terms` |
| Generate video / batch | `POST /v1/videos` (full VideoParams JSON) |
| Task list/progress | `GET /v1/tasks`, `GET /v1/tasks/{id}` |
| Delete | `DELETE /v1/tasks/{id}` |
| Result video | `GET /v1/download/{file_path}` / `GET /v1/stream/{file_path}` |

Task model compatibility: upstream states (−1 failed / 1 complete / 4 processing) map 1:1 to the app's task states.

## 4. Security notes
- Plain HTTP on a LAN is fine for home use; for public exposure put the server behind HTTPS (reverse proxy) — the app accepts `https://` URLs.
- Do not expose the server publicly without an API token.
