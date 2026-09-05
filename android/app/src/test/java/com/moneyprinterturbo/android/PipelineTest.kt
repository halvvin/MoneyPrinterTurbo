package com.moneyprinterturbo.android

import com.moneyprinterturbo.android.core.llm.Prompts
import com.moneyprinterturbo.android.core.media.SubtitleBuilder
import com.moneyprinterturbo.android.core.media.SubtitleStyle
import com.moneyprinterturbo.android.core.media.Srt
import com.moneyprinterturbo.android.core.media.WordBoundary
import com.moneyprinterturbo.android.core.media.FfmpegExecutor
import com.moneyprinterturbo.android.core.model.TaskConfig
import com.moneyprinterturbo.android.core.model.VideoAspect
import com.moneyprinterturbo.android.core.model.VideoSource
import com.moneyprinterturbo.android.core.db.DbJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineTest {

    // ---------- SRT ----------

    @Test
    fun srt_timestamp_format() {
        assertEquals("00:00:01,500", Srt.formatTimestamp(1500))
        assertEquals("01:02:03,004", Srt.formatTimestamp(3_723_004))
    }

    @Test
    fun srt_write_parse_roundtrip() {
        val cues = listOf(Srt.Cue(0, 1500, "Hello world"), Srt.Cue(1500, 3000, "Second line"))
        val srt = Srt.write(cues)
        val parsed = Srt.parse(srt)
        assertEquals(2, parsed.size)
        assertEquals("Hello world", parsed[0].text)
        assertEquals(1500, parsed[1].startMs)
    }

    // ---------- Subtitle builder (edge word boundaries) ----------

    private fun wb(startS: Double, dur: Double, text: String) =
        WordBoundary((startS * 10_000_000).toLong(), (dur * 10_000_000).toLong(), text)

    @Test
    fun sentence_grouping_by_punctuation() {
        val words = listOf(
            wb(0.0, 0.5, "This"), wb(0.5, 0.5, "is"), wb(1.0, 0.5, "one."),
            wb(1.5, 0.5, "And"), wb(2.0, 0.5, "two."),
        )
        val cues = SubtitleBuilder.sentences(words)
        assertEquals(2, cues.size)
        assertEquals("This is one.", cues[0].text)
        assertEquals("And two.", cues[1].text)
    }

    @Test
    fun word_by_word_mode() {
        val words = listOf(wb(0.0, 0.4, "A"), wb(0.4, 0.4, "B"))
        val cues = SubtitleBuilder.wordByWord(words)
        assertEquals(2, cues.size)
        assertEquals("A", cues[0].text)
    }

    // ---------- SubtitleStyle (ASS colors / force_style) ----------

    @Test
    fun color_converts_rgb_to_bgr() {
        // #FF0000 (red) → &H000000FF (BGR)
        assertEquals("&H000000FF", SubtitleStyle.toAssColor("#FF0000"))
        // #00FF00 (green) → &H0000FF00
        assertEquals("&H0000FF00", SubtitleStyle.toAssColor("#00FF00"))
    }

    @Test
    fun force_style_px_exact_with_playres() {
        val style = SubtitleStyle.Style(
            fontName = "BeVietnamPro", fontSize = 60, videoWidth = 1080, videoHeight = 1920,
            position = "bottom", customPosition = 70f, foreColor = "#FFFFFF",
            backColor = "", strokeColor = "#000000", strokeWidth = 1.5f,
        )
        val s = SubtitleStyle.forceStyle(style)
        assertTrue(s.contains("PlayResX=1080"))
        assertTrue(s.contains("PlayResY=1920"))
        assertTrue(s.contains("FontSize=60"))
        assertTrue(s.contains("Alignment=2"))
        assertTrue(s.contains("Outline=1.5"))
    }

    @Test
    fun position_alignment_mapping() {
        assertEquals(8, SubtitleStyle.alignment("top"))
        assertEquals(5, SubtitleStyle.alignment("center"))
        assertEquals(2, SubtitleStyle.alignment("bottom"))
    }

    // ---------- Prompts (upstream parity) ----------

    @Test
    fun script_prompt_contains_upstream_markers() {
        val p = Prompts.buildScriptPrompt("Money", "en", 2, "keep it short", "")
        assertTrue(p.contains("# Role: Video Script Generator"))
        assertTrue(p.contains("video subject: Money"))
        assertTrue(p.contains("number of paragraphs: 2"))
        assertTrue(p.contains("language: en"))
        assertTrue(p.contains("keep it short"))
    }

    @Test
    fun terms_prompt_json_array() {
        val p = Prompts.buildTermsPrompt(5, false, "Money", "script")
        assertTrue(p.contains("json-array of strings"))
        assertTrue(p.contains("search term 5"))
    }

    @Test
    fun terms_parser_strips_code_fence() {
        val raw = "```json\n[\"money\", \"coins\", \"bank\"]\n```"
        assertEquals(listOf("money", "coins", "bank"), Prompts.parseTerms(raw))
    }

    @Test
    fun normalize_strips_thinking_block() {
        val raw = "<think>internal reasoning</think>Final answer"
        assertEquals("Final answer", Prompts.normalizeTextResponse(raw))
    }

    // ---------- TaskConfig ----------

    @Test
    fun aspect_resolution_parity() {
        assertEquals(1080 to 1920, VideoAspect.PORTRAIT.width to VideoAspect.PORTRAIT.height)
        assertEquals(1920 to 1080, VideoAspect.LANDSCAPE.width to VideoAspect.LANDSCAPE.height)
        assertEquals(1080 to 1080, VideoAspect.SQUARE.width to VideoAspect.SQUARE.height)
    }

    @Test
    fun config_json_roundtrip() {
        val c = TaskConfig(
            videoSubject = "Test", videoScript = "s", videoTerms = listOf("a", "b"),
            videoSource = VideoSource.PIXABAY, videoCount = 2,
        )
        val parsed = DbJson.configFromString(DbJson.configToString(c))
        assertEquals(c, parsed)
    }

    // ---------- FFmpeg helpers ----------

    @Test
    fun fit_filter_cover_and_contain() {
        val cover = FfmpegExecutor.fitFilter("cover", 1080, 1920)
        assertTrue(cover.contains("force_original_aspect_ratio=increase"))
        assertTrue(cover.contains("crop=1080:1920"))
        val contain = FfmpegExecutor.fitFilter("contain", 1080, 1920)
        assertTrue(contain.contains("force_original_aspect_ratio=decrease"))
        assertTrue(contain.contains("pad=1080:1920"))
    }

    @Test
    fun concat_list_quotes_single_quotes() {
        val path = java.io.File("/tmp/it's a test.mp4")
        val line = FfmpegExecutor.concatPath(path)
        assertTrue(line.startsWith("file '"))
        assertTrue(line.contains("'\\''"))
    }

    @Test
    fun subtitles_filter_escapes_paths() {
        val f = FfmpegExecutor.subtitlesFilter(
            java.io.File("/data/sub srt:colon.srt"), java.io.File("/fonts"), "FontSize=60",
        )
        assertTrue(f.startsWith("subtitles="))
        assertTrue(f.contains("fontsdir="))
        assertTrue(f.contains("force_style='FontSize=60'"))
    }

    @Test
    fun duration_parsing() {
        val stderr = "  Duration: 00:01:30.50, start: 0.000000"
        val d = FfmpegExecutor.parseDuration(stderr)
        assertEquals(90.5, d!!, 0.001)
    }
}
