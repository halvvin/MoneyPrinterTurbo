package com.moneyprinterturbo.android.core.net

import android.content.Context
import com.moneyprinterturbo.android.core.logging.AppLogger
import com.moneyprinterturbo.android.core.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlinx.serialization.json.intOrNull
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * Client for the real MoneyPrinterTurbo FastAPI service.
 *
 * Upstream exposes POST /videos, GET /tasks/{id}, DELETE /tasks/{id}, and
 * /download/{file_path}. The Android app uses this path only in REMOTE mode.
 */
class RemoteMptClient(private val context: Context) {

    suspend fun generate(
        config: TaskConfig,
        outputDir: File,
        onProgress: (Int, String) -> Unit,
        onCreated: (String) -> Unit = {},
    ): File =
        withContext(Dispatchers.IO) {
            val settings = com.moneyprinterturbo.android.core.storage.PrefsStore(
                context,
                com.moneyprinterturbo.android.core.storage.SecureStore(context)
            ).settingsNow()
            val base = settings.remoteBackendUrl.trim().trimEnd('/')
            require(base.isNotBlank()) { "Remote backend URL is empty" }
            val secure = com.moneyprinterturbo.android.core.storage.SecureStore(context)
            val remoteToken = secure.get("remote_api_token") ?: ""

            val http = Http.client(settings.networkTimeoutSec)
            val apiBase = if (base.endsWith("/api/v1")) base else "$base/api/v1"
            val prepared = prepareRemoteAssets(http, apiBase, remoteToken, config)
            val payload = taskVideoPayload(prepared)
            val request = Request.Builder()
                .url("$apiBase/videos")
                .header("Accept", "application/json")
                .apply { if (remoteToken.isNotBlank()) header("Authorization", "Bearer $remoteToken") }
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            var taskId: String
            executeWithRetry(http, request).use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("remote POST /videos failed: HTTP ${response.code} ${safe(body)}")
                val root = Json.parseToJsonElement(body).jsonObject
                taskId = findString(root, "task_id")
                    ?: findString(root["data"]?.jsonObject ?: buildJsonObject {}, "task_id")
                    ?: error("remote response did not contain task_id")
                AppLogger.network(context, "POST", "$apiBase/videos", response.code)
                AppLogger.log(context, "REMOTE", "created remote task=$taskId")
                onCreated(taskId)
            }

            var lastProgress = -1
            var finalUrls: List<String> = emptyList()
            repeat(720) { // ~60 minutes at 5 sec polling
                delay(5000)
                val poll = Request.Builder().url("$apiBase/tasks/$taskId")
                    .header("Accept", "application/json")
                    .apply { if (remoteToken.isNotBlank()) header("Authorization", "Bearer $remoteToken") }
                    .get().build()
                executeWithRetry(http, poll).use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("remote task polling failed: HTTP ${response.code} ${safe(body)}")
                    val root = Json.parseToJsonElement(body).jsonObject
                    val data = root["data"]?.jsonObject ?: root
                    val progress = data["progress"]?.jsonPrimitive?.intOrNull ?: 0
                    val stateValue = data["state"] ?: data["status"]
                    val state = stateValue?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
                    val stateCode = stateValue?.jsonPrimitive?.intOrNull
                    val errorText = data["error"]?.jsonPrimitive?.contentOrNull
                    if (progress != lastProgress) {
                        lastProgress = progress
                        onProgress(progress.coerceIn(0, 100), state.ifBlank { "remote" })
                    }
                    if (state.contains("fail") || state == "error" || stateCode == -1) error(errorText ?: "remote task failed")
                    if (state.contains("success") || state.contains("complete") || stateCode == 1 || progress >= 100) {
                        finalUrls = findVideoUrls(data)
                        if (finalUrls.isNotEmpty()) return@use
                    }
                }
                if (finalUrls.isNotEmpty()) return@repeat
            }

            val urls = finalUrls.ifEmpty { error("remote task timed out without a video URL") }
            var first: File? = null
            urls.take(config.videoCount.coerceIn(1, 10)).forEachIndexed { index, url ->
                val absolute = resolveRemoteUrl(base, apiBase, url)
                val name = if (urls.size == 1) "final-remote.mp4" else "final-${index + 1}.mp4"
                val out = File(outputDir, name)
                val dl = Request.Builder().url(absolute).get()
                    .apply { if (remoteToken.isNotBlank()) header("Authorization", "Bearer $remoteToken") }
                    .build()
                executeWithRetry(http, dl).use { response ->
                    if (!response.isSuccessful) error("video download failed: HTTP ${response.code}")
                    val body = response.body ?: error("remote video response was empty")
                    body.byteStream().use { input -> out.outputStream().use { output -> input.copyTo(output) } }
                    if (!out.exists() || out.length() == 0L) error("remote video download was empty")
                    AppLogger.network(context, "GET", absolute, response.code)
                }
                if (first == null) first = out
            }
            first!!
        }

    private fun taskVideoPayload(c: TaskConfig) = buildJsonObject {
        put("video_subject", c.videoSubject)
        put("video_script", c.videoScript)
        put("video_terms", buildJsonArray { c.videoTerms.forEach { add(it) } })
        put("video_language", c.videoLanguage)
        put("video_aspect", c.videoAspect.value)
        put("video_concat_mode", c.videoConcatMode.name.lowercase())
        put("video_transition_mode", c.videoTransition.name.lowercase())
        put("video_clip_duration", c.videoClipDuration)
        put("video_clip_speed", c.videoClipSpeed)
        put("match_materials_to_script", c.matchMaterialsToScript)
        put("video_count", c.videoCount)
        put("video_source", c.videoSource.vValue)
        put("video_materials", buildJsonArray { c.videoMaterials.forEach { m ->
            add(buildJsonObject {
                put("provider", m.provider)
                put("url", m.url)
                put("duration", m.duration)
                m.creator?.let { put("creator", it) }
                m.creatorUrl?.let { put("creator_url", it) }
            })
        } })
        put("voice_name", c.voiceName)
        put("voice_volume", c.voiceVolume)
        put("voice_rate", c.voiceRate)
        put("bgm_type", c.bgmType.vValue)
        put("video_music_prompt", c.soniloBgmPrompt)
        put("bgm_file", c.bgmFile)
        put("bgm_volume", c.bgmVolume)
        put("subtitle_enabled", c.subtitleEnabled)
        put("subtitle_position", c.subtitlePosition.vValue)
        put("custom_position", c.customPosition)
        put("font_name", c.fontName)
        put("font_size", c.fontSize)
        put("text_fore_color", c.textForeColor)
        put("text_background_color", c.textBackgroundColor)
        put("stroke_color", c.strokeColor)
        put("stroke_width", c.strokeWidth)
        put("rounded_subtitle_background", c.roundedSubtitleBackground)
        put("paragraph_number", c.paragraphNumber)
        put("n_threads", c.nThreads)
        put("sonilo_bgm_prompt", c.soniloBgmPrompt)
        put("video_script_prompt", c.videoScriptPrompt)
        put("custom_system_prompt", c.customSystemPrompt)
    }

    /**
     * The upstream API has real multipart upload endpoints for local video materials
     * and background music. Android must upload content instead of leaking a
     * phone-local /storage/... path to the server.
     */
    private suspend fun prepareRemoteAssets(
        http: okhttp3.OkHttpClient,
        apiBase: String,
        token: String,
        config: TaskConfig,
    ): TaskConfig = withContext(Dispatchers.IO) {
        var prepared = config
        if (config.videoSource == VideoSource.LOCAL) {
            val uploaded = config.videoMaterials.map { material ->
                val source = material.localPath?.let(::File)
                    ?: material.url.takeIf { it.startsWith("/") }?.let(::File)
                if (source == null || !source.exists() || source.length() <= 0L) {
                    throw IllegalArgumentException("remote local material is missing: ${material.localPath ?: material.url}")
                }
                val filename = uploadMultipart(http, "$apiBase/video_materials", token, source)
                material.copy(provider = "local", url = filename, localPath = null)
            }
            prepared = prepared.copy(videoMaterials = uploaded)
        }
        if (config.bgmType == BgmType.CUSTOM && config.bgmFile.isNotBlank()) {
            val bgm = File(config.bgmFile)
            if (!bgm.exists() || bgm.length() <= 0L) {
                throw IllegalArgumentException("remote custom BGM is missing: ${config.bgmFile}")
            }
            val filename = uploadMultipart(http, "$apiBase/musics", token, bgm)
            prepared = prepared.copy(bgmFile = filename)
        }
        if (config.customAudioFile != null) {
            throw IllegalArgumentException(
                "Remote custom narration upload is not supported by the upstream API. " +
                    "Use Edge/TTS or an API-backed TTS provider, or add a server-side custom-audio upload endpoint."
            )
        }
        prepared
    }

    private suspend fun uploadMultipart(
        http: okhttp3.OkHttpClient,
        endpoint: String,
        token: String,
        file: File,
    ): String {
        val media = guessMediaType(file.name)
        val body = file.asRequestBody(media.toMediaType())
        val multipart = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", file.name, body)
            .build()
        val request = Request.Builder().url(endpoint)
            .header("Accept", "application/json")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .post(multipart).build()
        executeWithRetry(http, request).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("remote upload failed: HTTP ${response.code} ${safe(text)}")
            }
            val root = Json.parseToJsonElement(text).jsonObject
            val data = root["data"]?.jsonObject ?: root
            val uploaded = data["file"]?.jsonPrimitive?.contentOrNull
                ?: data["filename"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalStateException("remote upload response did not contain a file name")
            AppLogger.network(context, "POST", endpoint, response.code)
            return uploaded
        }
    }

    private fun guessMediaType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "mkv" -> "video/x-matroska"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wma" -> "audio/x-ms-wma"
        else -> "application/octet-stream"
    }

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        val settings = com.moneyprinterturbo.android.core.storage.PrefsStore(
            context, com.moneyprinterturbo.android.core.storage.SecureStore(context)
        ).settingsNow()
        val base = settings.remoteBackendUrl.trim().trimEnd('/')
        if (base.isBlank()) return@withContext false
        val token = com.moneyprinterturbo.android.core.storage.SecureStore(context).get("remote_api_token") ?: ""
        val url = if (base.endsWith("/api/v1")) "$base/tasks?page=1&page_size=1" else "$base/api/v1/tasks?page=1&page_size=1"
        runCatching {
            Http.client(settings.networkTimeoutSec).newCall(
                Request.Builder().url(url).apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }.get().build()
            ).execute().use { response ->
                AppLogger.network(context, "GET", url, response.code)
                response.isSuccessful
            }
        }.getOrDefault(false)
    }

    private fun resolveRemoteUrl(rootBase: String, apiBase: String, value: String): String {
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        val path = value.trimStart('/')
        return when {
            path.startsWith("api/v1/") -> "$rootBase/$path"
            path.startsWith("tasks/") || path.startsWith("download/") || path.startsWith("stream/") -> "$rootBase/$path"
            else -> "$apiBase/$path"
        }
    }

    private fun findVideoUrls(data: JsonObject): List<String> {
        val result = mutableListOf<String>()
        for (key in listOf("videos", "combined_videos", "video_paths", "outputs")) {
            val arr = data[key]?.jsonArray ?: continue
            arr.forEach { element ->
                element.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }?.let(result::add)
            }
        }
        data["video"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let(result::add)
        return result.distinct()
    }

    suspend fun cancelTask(taskId: String): Boolean = taskAction(taskId, "DELETE")

    suspend fun deleteTask(taskId: String): Boolean = taskAction(taskId, "DELETE")

    private suspend fun taskAction(taskId: String, method: String): Boolean = withContext(Dispatchers.IO) {
        val settings = com.moneyprinterturbo.android.core.storage.PrefsStore(
            context, com.moneyprinterturbo.android.core.storage.SecureStore(context)
        ).settingsNow()
        val base = settings.remoteBackendUrl.trim().trimEnd('/')
        if (base.isBlank()) return@withContext false
        val apiBase = if (base.endsWith("/api/v1")) base else "$base/api/v1"
        val token = com.moneyprinterturbo.android.core.storage.SecureStore(context).get("remote_api_token") ?: ""
        val request = Request.Builder().url("$apiBase/tasks/$taskId")
            .header("Accept", "application/json")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .method(method, null).build()
        runCatching {
            Http.client(settings.networkTimeoutSec).newCall(request).execute().use { r ->
                AppLogger.network(context, method, "$apiBase/tasks/$taskId", r.code)
                r.isSuccessful || r.code == 404
            }
        }.getOrDefault(false)
    }

    private fun findString(obj: JsonObject, key: String): String? = obj[key]?.jsonPrimitive?.contentOrNull

    private suspend fun executeWithRetry(client: okhttp3.OkHttpClient, request: Request): okhttp3.Response {
        var last: Exception? = null
        for (attempt in 0..3) {
            try {
                val response = client.newCall(request).execute()
                if (response.code !in listOf(408, 425, 429, 500, 502, 503, 504)) return response
                val copyCode = response.code
                response.close()
                last = IllegalStateException("HTTP $copyCode")
            } catch (e: Exception) {
                last = e
            }
            delay(750L * (1L shl attempt))
        }
        throw last ?: IllegalStateException("network request failed")
    }

    private fun safe(body: String) = body.replace(Regex("(?i)(api[-_ ]?key|authorization|token|password|secret)\\s*[:=]\\s*[^,}\\s]+"), "<REDACTED>").take(240)
}
