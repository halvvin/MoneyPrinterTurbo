package com.moneyprinterturbo.android.core.tts

import com.moneyprinterturbo.android.core.media.WordBoundary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.MessageDigest
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Kotlin port of edge-tts 7.x — the upstream default TTS provider.
 *
 * - Endpoint: wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1
 * - DRM: Sec-MS-GEC = SHA-256(5-min-quantized Windows ticks + TRUSTED_CLIENT_TOKEN), uppercase hex
 * - Output: MP3 audio + WordBoundary metadata (100-ns units) used for subtitles
 */
class EdgeTtsClient(private val http: OkHttpClient) {
    private val logTag = "EDGE_TTS"

    companion object {
        const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        // Microsoft blocks stale Sec-MS-GEC-Version values with HTTP 403. Keep in sync
        // with a current Edge release (reference: edge-tts 7.2.8 uses 143.0.3650.75).
        const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
        const val BASE_WSS =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"

        fun secMsGec(unixSeconds: Long = System.currentTimeMillis() / 1000): String {
            var ticks = unixSeconds + 11_644_473_600L // Windows epoch (1601) in seconds
            ticks -= ticks % 300                      // 5-minute quantization
            val input = "$ticks$TRUSTED_CLIENT_TOKEN"
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))
            return digest.joinToString("") { "%02X".format(it) }
        }

        fun wssUrl(): String =
            "$BASE_WSS?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
                "&Sec-MS-GEC=${secMsGec()}&Sec-MS-GEC-Version=1-$CHROMIUM_FULL_VERSION"

        /** Convert upstream voice_rate float (1.0 = normal) to prosody string, e.g. "+10%". */
        fun ratePercent(rate: Float): String {
            val pct = Math.round((rate - 1.0f) * 100)
            return (if (pct >= 0) "+" else "") + pct + "%"
        }

        fun ssml(voice: String, text: String, rate: Float, volume: Float, pitch: String = "+0Hz"): String {
            val volumePct = let {
                val v = Math.round((volume - 1.0f) * 100)
                (if (v >= 0) "+" else "") + v + "%"
            }
            val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("'", "&apos;").replace("\"", "&quot;")
            return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                "<voice name='$voice'><prosody pitch='$pitch' rate='${ratePercent(rate)}' volume='$volumePct'>$escaped</prosody></voice></speak>"
        }

        private fun dateStamp(): String {
            val fmt = java.text.SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'0000 (Coordinated Universal Time)", Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return fmt.format(Date())
        }
    }

    class Result(val mp3: ByteArray, val words: List<WordBoundary>)

    /** Synthesize one text chunk. Throws EdgeTtsException on failure with a human-readable message. */
    suspend fun synthesize(voice: String, text: String, rate: Float = 1.0f, volume: Float = 1.0f): Result =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            com.moneyprinterturbo.android.core.logging.AppLogger.logCtx(logTag, "start voice=$voice chars=${text.length} rate=$rate volume=$volume")
            val audio = mutableListOf<ByteString>()
            val words = mutableListOf<WordBoundary>()
            val error = AtomicReference<String?>(null)
            val done = CountDownLatch(1)

            val req = Request.Builder().url(wssUrl())
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/$CHROMIUM_FULL_VERSION")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val ws = http.newWebSocket(req, object : WebSocketListener() {
                private var sentConfig = false
                private var sentSsml = false

                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    com.moneyprinterturbo.android.core.logging.AppLogger.logCtx(logTag, "socket_open code=${response.code}")
                    val requestId = java.util.UUID.randomUUID().toString().replace("-", "")
                    val config =
                        "X-Timestamp:${dateStamp()}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                    webSocket.send(config)
                    sentConfig = true
                    val ssmlMsg =
                        "X-RequestId:$requestId\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${dateStamp()}Z\r\nPath:ssml\r\n\r\n" +
                            ssml(voice, text, rate, volume)
                    webSocket.send(ssmlMsg)
                    sentSsml = true
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // Binary frame: [u16 headerLen][headers][payload]
                    if (bytes.size < 2) return
                    val headerLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                    if (bytes.size < headerLen + 2) return
                    val headers = bytes.substring(2, headerLen + 2).utf8()
                    val payload = bytes.substring(headerLen + 2)
                    if ("Path:audio" in headers && payload.size > 0) audio += payload
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    when {
                        "Path:turn.end" in text -> { webSocket.close(1000, "done"); done.countDown() }
                        "Path:audio.metadata" in text -> {
                            val idx = text.lastIndexOf('{')
                            if (idx >= 0) try {
                                val meta = kotlinx.serialization.json.Json.parseToJsonElement(text.substring(idx))
                                    .let { el -> el as kotlinx.serialization.json.JsonObject }
                                val type = (meta["Type"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                                if (type == "WordBoundary") {
                                    val offset = (meta["Offset"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                                    val duration = (meta["Duration"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                                    val w = (meta["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                                    if (w.isNotBlank()) words += WordBoundary(offset, duration, w)
                                }
                            } catch (_: Exception) { /* metadata frame ignored on parse error */ }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    com.moneyprinterturbo.android.core.logging.AppLogger.exceptionCtx(logTag, "socket_failure code=${response?.code}", t)
                    val code = response?.code
                    error.set(
                        when (code) {
                            401, 403 -> "edge-tts rejected the request (HTTP $code). The DRM token may be stale or the service is unavailable in your region."
                            429 -> "edge-tts rate limited (HTTP 429). Retry later."
                            else -> t.message?.let { "edge-tts connection failed: $it" } ?: "edge-tts connection failed"
                        }
                    )
                    done.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (code != 1000 && error.get() == null) error.set("edge-tts closed unexpectedly ($code)")
                    done.countDown()
                }
            })

            try {
                if (!done.await(90, TimeUnit.SECONDS)) {
                    ws.cancel()
                    throw EdgeTtsException("edge-tts timed out after 90s")
                }
            } finally {
                ws.cancel()
            }
            error.get()?.let { throw EdgeTtsException(it) }
            if (audio.isEmpty()) throw EdgeTtsException("edge-tts returned no audio for the given text")
            com.moneyprinterturbo.android.core.logging.AppLogger.logCtx(logTag, "complete bytes=${audio.sumOf { it.size }} words=${words.size} elapsedMs=${System.currentTimeMillis() - started}")
            val buf = java.io.ByteArrayOutputStream()
            audio.forEach { buf.write(it.toByteArray()) }
            Result(buf.toByteArray(), words)
        }
}

class EdgeTtsException(message: String) : Exception(message)
