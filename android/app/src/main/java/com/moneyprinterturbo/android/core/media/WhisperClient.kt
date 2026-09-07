package com.moneyprinterturbo.android.core.media

import com.moneyprinterturbo.android.core.logging.AppLogger
import com.moneyprinterturbo.android.core.storage.PrefsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * Whisper-compatible transcription adapter. This is deliberately remote/API based.
 * A local faster-whisper model is not bundled into the APK, so the app never pretends
 * that a multi-gigabyte desktop Python model is magically running on Android.
 */
class WhisperClient(private val http: OkHttpClient, private val prefs: PrefsStore) {
    class WhisperException(message: String): Exception(message)

    suspend fun transcribe(audio: File): List<WhisperSegment> = withContext(Dispatchers.IO) {
        val s = prefs.settingsNow()
        val key = prefs.whisperApiKeyNow()
        if (s.whisperBaseUrl.isBlank()) throw WhisperException("Whisper endpoint is not configured")
        if (key.isBlank()) throw WhisperException("Whisper API key is not configured")
        if (!audio.exists() || audio.length() == 0L) throw WhisperException("audio file is missing or empty")
        val endpoint = normalize(s.whisperBaseUrl)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", s.whisperModel.ifBlank { "whisper-1" })
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("file", audio.name, audio.asRequestBody(contentType(audio)))
            .build()
        val request = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $key")
            .post(body).build()
        val started = System.currentTimeMillis()
        AppLogger.logCtx("WHISPER", "request endpoint=${endpoint.take(180)} bytes=${audio.length()}")
        http.newCall(request).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            AppLogger.logCtx("WHISPER", "response code=${r.code} elapsedMs=${System.currentTimeMillis()-started} chars=${raw.length}")
            if (!r.isSuccessful) throw WhisperException("Whisper HTTP ${r.code}: ${raw.take(240)}")
            val root = try { Json.parseToJsonElement(raw).jsonObject } catch (e: Exception) { throw WhisperException("invalid Whisper JSON response") }
            val arr = root["segments"]?.jsonArray ?: throw WhisperException("Whisper response has no segments")
            arr.mapNotNull { el ->
                val o = el.jsonObject
                val start = o["start"]?.jsonPrimitive?.double ?: return@mapNotNull null
                val end = o["end"]?.jsonPrimitive?.double ?: return@mapNotNull null
                val text = o["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                WhisperSegment(start, end, text)
            }
        }
    }

    private fun normalize(value: String): String {
        val u=value.trim().trimEnd('/')
        return when {
            u.endsWith("/audio/transcriptions") -> u
            u.endsWith("/v1") -> "$u/audio/transcriptions"
            else -> "$u/v1/audio/transcriptions"
        }
    }
    private fun contentType(file: File) = when(file.extension.lowercase()) {
        "wav" -> "audio/wav"; "m4a" -> "audio/mp4"; "ogg" -> "audio/ogg"; "flac" -> "audio/flac"; else -> "audio/mpeg"
    }.toMediaType()
}
