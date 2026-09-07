package com.moneyprinterturbo.android.core.media

/**
 * SRT subtitle building/parsing — parity with upstream edge SubMaker + subtitle.py.
 * Word boundaries from edge-tts carry offsets/durations in 100-nanosecond units.
 */

data class Cue(val startMs: Long, val endMs: Long, val text: String)

object Srt {

    fun formatTimestamp(ms: Long): String {
        val total = ms.coerceAtLeast(0)
        val h = total / 3_600_000
        val m = (total % 3_600_000) / 60_000
        val s = (total % 60_000) / 1000
        val milli = total % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, milli)
    }

    fun write(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { i, c ->
            append(i + 1).append('\n')
            append(formatTimestamp(c.startMs)).append(" --> ").append(formatTimestamp(c.endMs)).append('\n')
            append(c.text.trim()).append("\n\n")
        }
    }

    private val TS = Regex("(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})")


    /** Convert Whisper/OpenAI verbose_json segments into stable SRT cues. */
    fun fromSegments(segments: List<WhisperSegment>): List<Cue> =
        segments.mapNotNull { seg ->
            val start = (seg.start * 1000.0).toLong().coerceAtLeast(0)
            val end = (seg.end * 1000.0).toLong().coerceAtLeast(start + 1)
            val text = seg.text.trim()
            if (text.isBlank()) null else Cue(start, end, text)
        }

    fun parse(content: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        val blocks = content.replace("\r\n", "\n").split(Regex("\n\n+"))
        for (block in blocks) {
            val lines = block.trim().lines().filter { it.isNotBlank() }
            if (lines.size < 2) continue
            val arrow = lines.firstOrNull { "-->" in it } ?: continue
            val m = TS.find(arrow) ?: continue
            val all = TS.findAll(arrow).toList()
            if (all.size < 2) continue
            fun p(mm: MatchResult): Long {
                val (h, mi, s, ms) = mm.groupValues.drop(1).map { it.toLong() }
                return h * 3_600_000 + mi * 60_000 + s * 1000 + ms
            }
            val text = lines.filter { "-->" !in it && !(it.length <= 5 && it.trim().all { c -> c.isDigit() }) }
                .joinToString("\n").trim()
            if (text.isEmpty()) continue
            cues += Cue(p(all[0]), p(all[1]), text)
        }
        return cues
    }
}

/** One recognized word from edge-tts audio.metadata (100-ns units). */
data class WhisperSegment(val start: Double, val end: Double, val text: String)

data class WordBoundary(val offset100ns: Long, val duration100ns: Long, val text: String) {
    val startMs: Long get() = offset100ns / 10_000
    val endMs: Long get() = (offset100ns + duration100ns) / 10_000
}

object SubtitleBuilder {

    private val sentenceEnders = charArrayOf('。', '！', '？', '，', '.', '!', '?', ',', ';', '；', '：', ':')

    /** Group word boundaries into sentence cues — mirrors upstream voice.SubMaker sentence logic. */
    fun sentences(words: List<WordBoundary>): List<Cue> {
        val cues = mutableListOf<Cue>()
        var start = -1L
        var end = -1L
        val sb = StringBuilder()
        fun flush() {
            if (start >= 0 && sb.isNotBlank()) cues += Cue(start, end, sb.toString().trim())
            sb.clear(); start = -1; end = -1
        }
        for (w in words) {
            if (start < 0) start = w.startMs
            end = w.endMs
            sb.append(w.text)
            val last = sb.toString().trimEnd().lastOrNull()
            if (last != null && sentenceEnders.indexOf(last) >= 0) flush()
        }
        flush()
        return cues
    }

    /** One cue per word (word_by_word display mode). */
    fun wordByWord(words: List<WordBoundary>): List<Cue> =
        words.filter { it.text.isNotBlank() }.map { Cue(it.startMs, it.endMs, it.text) }

    /** Merge words into cues of at most [maxChars] characters (fallback segmentation for silent punctuation). */
    fun grouped(words: List<WordBoundary>, maxChars: Int = 28): List<Cue> {
        val out = mutableListOf<Cue>()
        var start = -1L; var end = -1L; var len = 0; val sb = StringBuilder()
        fun flush() {
            if (start >= 0 && sb.isNotBlank()) out += Cue(start, end, sb.toString().trim())
            sb.clear(); start = -1; end = -1; len = 0
        }
        for (w in words) {
            if (start < 0) start = w.startMs
            end = w.endMs
            sb.append(w.text); len += w.text.length
            if (len >= maxChars) flush()
        }
        flush()
        return out
    }
}
