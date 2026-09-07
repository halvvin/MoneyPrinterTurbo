package com.moneyprinterturbo.android.core.tts

import com.moneyprinterturbo.android.core.logging.AppLogger
import com.moneyprinterturbo.android.core.media.WordBoundary
import com.moneyprinterturbo.android.core.model.TtsProvider
import com.moneyprinterturbo.android.core.storage.PrefsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/** Voice entry from the bundled azure_voices.json (parity with upstream voice list). */
data class Voice(val name: String, val gender: String) {
    val baseName: String get() = name.removeSuffix("-Female").removeSuffix("-Male")
    val locale: String get() = baseName.split('-').take(2).joinToString("-")
}

object VoiceCatalog {
    private val voices = mutableListOf<Voice>()
    fun load(jsonText: String): List<Voice> {
        if (voices.isNotEmpty()) return voices
        Json.parseToJsonElement(jsonText).jsonArray.forEach { el ->
            val o = el.jsonObject
            val n = o["name"]?.jsonPrimitive?.content ?: return@forEach
            val g = o["gender"]?.jsonPrimitive?.content ?: ""
            voices += Voice(n, g)
        }
        return voices
    }
    fun isKnown(name: String): Boolean = voices.any { it.name == name || it.baseName == name }
}

/**
 * TTS facade. Edge TTS is the zero-key default and provides word timestamps.
 * OPENAI_COMPATIBLE supports providers exposing the common /audio/speech contract.
 * Providers are explicit: unsupported providers are never silently treated as Edge.
 */
class TtsService(
    private val http: OkHttpClient,
    private val prefs: PrefsStore,
    private val edge: EdgeTtsClient = EdgeTtsClient(http),
) {
    class TtsResult(val file: File, val words: List<WordBoundary>)
    class TtsException(message: String) : Exception(message)

    suspend fun synthesize(
        voiceName: String,
        text: String,
        rate: Float,
        volume: Float,
        outFile: File,
    ): TtsResult {
        if (text.isBlank()) throw TtsException("script is empty — nothing to synthesize")
        val settings = prefs.settingsNow()
        val voice = voiceName.ifBlank { settings.defaultVoice.ifBlank { "en-US-AnaNeural-Female" } }
        return when (settings.ttsProvider) {
            TtsProvider.EDGE -> synthesizeEdge(voice, text, rate, volume, outFile)
            TtsProvider.OPENAI_COMPATIBLE -> synthesizeOpenAiCompatible(settings.ttsBaseUrl, settings.ttsModel, prefs.ttsApiKeyNow(), voice, text, rate, volume, outFile)
        }
    }

    private suspend fun synthesizeEdge(voiceName: String, text: String, rate: Float, volume: Float, outFile: File): TtsResult {
        val base = Voice(voiceName, "").baseName
        val started = System.currentTimeMillis()
        val result = edge.synthesize(base, text, rate, volume)
        outFile.parentFile?.mkdirs()
        outFile.writeBytes(result.mp3)
        AppLogger.logCtx("TTS", "provider=edge voice=${base.take(80)} bytes=${result.mp3.size} words=${result.words.size} elapsedMs=${System.currentTimeMillis()-started}")
        return TtsResult(outFile, result.words)
    }

    private suspend fun synthesizeOpenAiCompatible(
        configuredUrl: String,
        model: String,
        apiKey: String,
        voice: String,
        text: String,
        rate: Float,
        volume: Float,
        outFile: File,
    ): TtsResult = withContext(Dispatchers.IO) {
        if (configuredUrl.isBlank()) throw TtsException("TTS endpoint is empty")
        if (apiKey.isBlank()) throw TtsException("TTS API key is required for the OpenAI-compatible provider")
        val endpoint = normalizeSpeechEndpoint(configuredUrl)
        val body = buildJsonObject {
            put("model", model.ifBlank { "tts-1" })
            put("input", text)
            put("voice", voice)
            put("response_format", "mp3")
            put("speed", rate.coerceIn(0.25f, 4f).toDouble())
            put("volume", volume.coerceIn(0f, 2f).toDouble())
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "audio/mpeg")
            .post(body).build()
        val started = System.currentTimeMillis()
        AppLogger.logCtx("TTS", "http_request endpoint=${endpoint.take(180)}")
        http.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            AppLogger.logCtx("TTS", "provider=openai_compatible code=${response.code} bytes=${bytes.size} elapsedMs=${System.currentTimeMillis()-started}")
            if (!response.isSuccessful) {
                val detail = bytes.toString(Charsets.UTF_8).take(240).replace(Regex("(?i)(api[_-]?key|authorization|token)\\s*[:=]\\s*[^,} ]+"), "$1=[redacted]")
                throw TtsException("TTS HTTP ${response.code}: ${detail.ifBlank { response.message }}")
            }
            if (bytes.isEmpty()) throw TtsException("TTS provider returned an empty audio response")
            outFile.parentFile?.mkdirs()
            outFile.writeBytes(bytes)
            TtsResult(outFile, emptyList())
        }
    }

    private fun normalizeSpeechEndpoint(value: String): String {
        val clean = value.trim().trimEnd('/')
        return when {
            clean.endsWith("/audio/speech") -> clean
            clean.endsWith("/v1") -> "$clean/audio/speech"
            clean.endsWith("/v1/tts") -> clean
            else -> "$clean/v1/audio/speech"
        }
    }
}
