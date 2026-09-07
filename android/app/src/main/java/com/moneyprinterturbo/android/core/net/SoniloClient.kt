package com.moneyprinterturbo.android.core.net

import android.content.Context
import com.moneyprinterturbo.android.core.logging.AppLogger
import com.moneyprinterturbo.android.core.storage.PrefsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/** Real Sonilo video-to-music adapter matching the current upstream protocol. */
class SoniloClient(
    private val context: Context,
    private val prefs: PrefsStore,
) {
    class SoniloException(message: String) : Exception(message)

    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val key = prefs.soniloApiKeyNow().ifBlank { throw SoniloException("Sonilo API key is not configured") }
        val base = prefs.settingsNow().soniloBaseUrl.trim().trimEnd('/').ifBlank { "https://api.sonilo.com" }
        val req = Request.Builder()
            .url("$base/v1/account/services")
            .header("Authorization", "Bearer $key")
            .get().build()
        Http.client(prefs.settingsNow().networkTimeoutSec).newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw SoniloException("Sonilo connection failed: HTTP ${r.code}")
            if (!body.contains("video_to_music") && !body.contains("video-to-music")) {
                throw SoniloException("Sonilo key is valid but video-to-music service is unavailable")
            }
            AppLogger.network(context, "GET", "$base/v1/account/services", r.code)
            "Sonilo connection OK"
        }
    }

    suspend fun generateBgm(video: File, prompt: String, output: File): File = withContext(Dispatchers.IO) {
        require(video.exists() && video.length() > 0) { "video for Sonilo is missing" }
        val key = prefs.soniloApiKeyNow().ifBlank { throw SoniloException("Sonilo API key is not configured") }
        val settings = prefs.settingsNow()
        val base = settings.soniloBaseUrl.trim().trimEnd('/').ifBlank { "https://api.sonilo.com" }
        val videoBody = video.asRequestBody("video/mp4".toMediaType())
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("video", video.name, videoBody)
            .apply { if (prompt.isNotBlank()) addFormDataPart("prompt", prompt.take(2000)) }
            .build()
        val req = Request.Builder()
            .url("$base/v1/video-to-music")
            .header("Authorization", "Bearer $key")
            .post(multipart).build()
        output.parentFile?.mkdirs()
        val temp = File(output.parentFile, output.name + ".part")
        temp.delete()
        Http.client(settings.networkTimeoutSec).newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw SoniloException("Sonilo generation failed: HTTP ${r.code}")
            val body = r.body ?: throw SoniloException("Sonilo returned an empty audio response")
            val length = body.contentLength()
            if (length > 30L * 1024L * 1024L) throw SoniloException("Sonilo audio response exceeds 30 MB")
            var total = 0L
            body.byteStream().use { input -> temp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > 30L * 1024L * 1024L) throw SoniloException("Sonilo audio response exceeds 30 MB")
                    out.write(buffer, 0, n)
                }
            } }
            if (total == 0L) throw SoniloException("Sonilo returned empty audio")
            if (!temp.renameTo(output)) temp.copyTo(output, overwrite = true).also { temp.delete() }
            AppLogger.network(context, "POST", "$base/v1/video-to-music", r.code)
        }
        if (!output.exists() || output.length() == 0L) throw SoniloException("Sonilo returned empty audio")
        output
    }
}
