package com.moneyprinterturbo.android.core.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Stock video search — parity with upstream app/services/material.py (Pexels / Pixabay / Coverr). */
class StockMediaClient(
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val cacheDir: File? = null,
) {

    @Serializable
    data class StockVideo(
        val provider: String,
        val id: String,
        val url: String,
        val width: Int,
        val height: Int,
        val durationSec: Int,
        val creator: String? = null,
        val creatorUrl: String? = null,
        val thumbnail: String? = null,
    )

    class StockException(message: String) : Exception(message)

    /**
     * Persistent search cache and downloaded-material cache. The upstream project
     * caches search results and reuses downloaded files; Android does the same here
     * so a retry does not hammer the provider or redownload identical media.
     */
    private val searchCacheDir: File? = cacheDir?.resolve("material-search")?.apply { mkdirs() }
    private val downloadCacheDir: File? = cacheDir?.resolve("material-downloads")?.apply { mkdirs() }

    private fun cacheKey(provider: String, term: String, aspect: String): String =
        sha256("$provider|$aspect|${term.trim().lowercase()}")

    private fun sha256(value: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    suspend fun searchCached(
        provider: String, apiKey: String, term: String, aspect: String, count: Int,
        maxAgeMs: Long = 24L * 60L * 60L * 1000L,
    ): List<StockVideo> {
        val file = searchCacheDir?.resolve("${cacheKey(provider, term, aspect)}.json")
        if (file != null && file.exists() && System.currentTimeMillis() - file.lastModified() < maxAgeMs) {
            try {
                return json.decodeFromString(file.readText())
            } catch (_: Exception) {
                file.delete()
            }
        }
        val result = search(provider, apiKey, term, aspect, count)
        if (result.isNotEmpty() && file != null) {
            try { file.writeText(json.encodeToString(result)) } catch (_: Exception) {}
        }
        return result
    }

    suspend fun downloadCached(video: StockVideo): File = withContext(Dispatchers.IO) {
        val dir = downloadCacheDir ?: throw StockException("material cache directory is unavailable")
        val ext = if (video.url.substringBefore('?').lowercase().endsWith(".jpg") ||
            video.url.substringBefore('?').lowercase().endsWith(".jpeg") ||
            video.url.substringBefore('?').lowercase().endsWith(".png") ||
            video.url.substringBefore('?').lowercase().endsWith(".webp")) "jpg" else "mp4"
        val file = dir.resolve("${sha256(video.url)}.$ext")
        if (file.exists() && file.length() > 0) return@withContext file
        val tmp = dir.resolve(".${file.name}.part")
        val req = Request.Builder().url(video.url).header("User-Agent", "MoneyPrinterTurbo-Android").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw StockException("material download HTTP ${resp.code}")
            val body = resp.body ?: throw StockException("empty material response")
            body.byteStream().use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
        }
        if (tmp.length() <= 0) { tmp.delete(); throw StockException("downloaded material is empty") }
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        file
    }

    suspend fun search(provider: String, apiKey: String, term: String, aspect: String, count: Int): List<StockVideo> =
        when (provider.lowercase()) {
            "pexels" -> pexels(apiKey, term, aspect, count)
            "pixabay" -> pixabay(apiKey, term, aspect, count)
            "coverr" -> coverr(apiKey, term, aspect, count)
            else -> throw StockException("unknown stock provider: $provider")
        }

    suspend fun pexels(apiKey: String, term: String, aspect: String, count: Int): List<StockVideo> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw StockException("Pexels API key is not configured (Settings → Providers)")
            val orientation = when (aspect) { "9:16" -> "portrait"; "1:1" -> "square"; else -> "landscape" }
            val url = "https://api.pexels.com/videos/search?query=${enc(term)}&per_page=$count&orientation=$orientation"
            val req = Request.Builder().url(url).header("Authorization", apiKey).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw StockException("Pexels HTTP ${resp.code}: ${err(body)}")
                val root = json.parseToJsonElement(body).jsonObject
                val videos = root["videos"]?.jsonArray ?: return@use emptyList()
                videos.mapNotNull { v ->
                    val o = v.jsonObject
                    val files = o["video_files"]?.jsonArray ?: return@mapNotNull null
                    // pick the mp4 closest to the aspect target resolution
                    val best = files.mapNotNull { f ->
                        val fo = f.jsonObject
                        val w = fo["width"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                        val h = fo["height"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                        val link = fo["link"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        Triple(w * h, Pair(w, h), link)
                    }.filter { (_, wh, _) -> aspectMatches(wh.first, wh.second, aspect) }
                        .minByOrNull { it.first } ?: return@mapNotNull null
                    StockVideo(
                        provider = "pexels",
                        id = o["id"]?.jsonPrimitive?.content ?: "",
                        url = best.third,
                        width = best.second.first,
                        height = best.second.second,
                        durationSec = o["duration"]?.jsonPrimitive?.intOrNull ?: 0,
                        creator = o["user"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
                        creatorUrl = o["user"]?.jsonObject?.get("url")?.jsonPrimitive?.content,
                        thumbnail = o["image"]?.jsonPrimitive?.content,
                    )
                }
            }
        }

    suspend fun pixabay(apiKey: String, term: String, aspect: String, count: Int): List<StockVideo> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw StockException("Pixabay API key is not configured (Settings → Providers)")
            val url = "https://pixabay.com/api/videos/?key=${enc(apiKey)}&q=${enc(term)}&per_page=$count"
            val req = Request.Builder().url(url).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw StockException("Pixabay HTTP ${resp.code}: ${err(body)}")
                val root = json.parseToJsonElement(body).jsonObject
                val hits = root["hits"]?.jsonArray ?: return@use emptyList()
                hits.mapNotNull { v ->
                    val o = v.jsonObject
                    val vids = o["videos"]?.jsonObject ?: return@mapNotNull null
                    val sizeKeys = listOf("large", "medium", "small", "tiny")
                    val pick = sizeKeys.firstNotNullOfOrNull { k ->
                        (vids[k] as? kotlinx.serialization.json.JsonObject)?.let { so ->
                            val w = so["width"]?.jsonPrimitive?.intOrNull ?: 0
                            val h = so["height"]?.jsonPrimitive?.intOrNull ?: 0
                            val u = so["url"]?.jsonPrimitive?.content ?: return@let null
                            Triple(w, h, u)
                        }
                    } ?: return@mapNotNull null
                    if (!aspectMatches(pick.first, pick.second, aspect, loose = true)) return@mapNotNull null
                    StockVideo(
                        provider = "pixabay",
                        id = o["id"]?.jsonPrimitive?.content ?: "",
                        url = pick.third,
                        width = pick.first,
                        height = pick.second,
                        durationSec = o["duration"]?.jsonPrimitive?.intOrNull ?: 0,
                        creator = o["user"]?.jsonPrimitive?.content,
                        thumbnail = o["userImageURL"]?.jsonPrimitive?.content,
                    )
                }
            }
        }

    suspend fun coverr(apiKey: String, term: String, aspect: String, count: Int): List<StockVideo> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw StockException("Coverr API key is not configured (Settings → Providers)")
            val url = "https://api.coverr.co/v2/search?query=${enc(term)}&page_size=$count"
            val req = Request.Builder().url(url).header("Authorization", "Bearer $apiKey").build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw StockException("Coverr HTTP ${resp.code}: ${err(body)}")
                val root = json.parseToJsonElement(body).jsonObject
                val hits = (root["results"] ?: root["data"])?.jsonArray ?: return@use emptyList()
                hits.mapNotNull { v ->
                    val o = v.jsonObject
                    val dl = o["downloads"]?.jsonArray?.firstOrNull()?.jsonObject
                    val u = dl?.get("link")?.jsonPrimitive?.content
                        ?: o["urls"]?.jsonObject?.get("mp4")?.jsonPrimitive?.content
                        ?: return@mapNotNull null
                    val w = o["width"]?.jsonPrimitive?.intOrNull ?: 0
                    val h = o["height"]?.jsonPrimitive?.intOrNull ?: 0
                    if (!aspectMatches(w, h, aspect, loose = true)) return@mapNotNull null
                    StockVideo(
                        provider = "coverr",
                        id = o["id"]?.jsonPrimitive?.content ?: "",
                        url = u,
                        width = w, height = h,
                        durationSec = o["duration"]?.jsonPrimitive?.intOrNull ?: 0,
                        creator = o["uploaded_by"]?.jsonPrimitive?.content,
                        thumbnail = o["thumbnail"]?.jsonPrimitive?.content
                            ?: o["urls"]?.jsonObject?.get("poster")?.jsonPrimitive?.content,
                    )
                }
            }
        }

    /** Aspect check with tolerance — mirrors upstream _matches_video_aspect (w/h based, Coverr loose). */
    fun aspectMatches(w: Int, h: Int, aspect: String, loose: Boolean = false): Boolean {
        if (w <= 0 || h <= 0) return loose
        val ratio = w.toDouble() / h.toDouble()
        val target = when (aspect) { "9:16" -> 9.0 / 16; "1:1" -> 1.0; else -> 16.0 / 9 }
        val tol = if (loose) 0.25 else 0.12
        return Math.abs(ratio - target) / target <= tol
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    private fun err(body: String) = body.take(140)
}
