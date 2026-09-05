package com.moneyprinterturbo.android.core.tts

import com.moneyprinterturbo.android.core.media.WordBoundary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/** Voice entry from the bundled azure_voices.json (parity with upstream voice list). */
data class Voice(val name: String, val gender: String) {
    /** "fa-IR-DilaraNeural-Female" → base "fa-IR-DilaraNeural". */
    val baseName: String get() = name.removeSuffix("-Female").removeSuffix("-Male")
    val locale: String get() = baseName.split('-').take(2).joinToString("-")
}

/** Voice catalog from assets/azure_voices.json (same data as upstream app/services/data). */
object VoiceCatalog {
    private val voices = mutableListOf<Voice>()

    fun load(jsonText: String): List<Voice> {
        if (voices.isNotEmpty()) return voices
        val arr = Json.parseToJsonElement(jsonText).jsonArray
        arr.forEach { el ->
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
 * Unified TTS: edge-tts (default provider; includes word boundaries for subtitles).
 * Voice names may carry the upstream -Female/-Male suffix — stripped for the wire protocol.
 */
class TtsService(
    private val http: OkHttpClient,
    private val edge: EdgeTtsClient = EdgeTtsClient(http),
) {
    class TtsResult(val file: java.io.File, val words: List<WordBoundary>)

    class TtsException(message: String) : Exception(message)

    suspend fun synthesize(
        voiceName: String,
        text: String,
        rate: Float,
        volume: Float,
        outFile: java.io.File,
    ): TtsResult {
        if (text.isBlank()) throw TtsException("script is empty — nothing to synthesize")
        val base = Voice(voiceName.ifBlank { "en-US-AnaNeural-Female" }, "").baseName
        val result = edge.synthesize(base, text, rate, volume)
        outFile.parentFile?.mkdirs()
        outFile.writeBytes(result.mp3)
        return TtsResult(outFile, result.words)
    }
}
