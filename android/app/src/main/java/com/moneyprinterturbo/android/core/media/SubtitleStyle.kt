package com.moneyprinterturbo.android.core.media

/**
 * Builds the libass force_style string for ffmpeg subtitles filter — parity with upstream
 * app/services/subtitle.py (font/size/position/colors → ffmpeg burn-in).
 *
 * Color model: upstream stores "#RRGGBB" and ffmpeg ASS uses &HAABBGGRR (BGR order, alpha first).
 */
object SubtitleStyle {

    data class Style(
        val fontName: String,
        val fontSize: Int,          // px at output resolution (PlayRes matched to video)
        val videoWidth: Int,
        val videoHeight: Int,
        val position: String,       // top/bottom/center/custom/two_thirds_bottom
        val customPosition: Float,  // percent from bottom for custom
        val foreColor: String,      // #RRGGBB
        val backColor: String,      // "" = none, "#RRGGBB" = box background
        val strokeColor: String,    // #RRGGBB
        val strokeWidth: Float,
    )

    /** #RRGGBB → &H00BBGGRR (alpha 0 = opaque in libass). Returns "&H00000000" for invalid input. */
    fun toAssColor(hex: String): String {
        val h = hex.removePrefix("#").trim()
        if (h.length != 6 || h.any { it !in "0123456789abcdefABCDEF" }) return "&H00000000"
        val r = h.substring(0, 2)
        val g = h.substring(2, 4)
        val b = h.substring(4, 6)
        return "&H00$b$g$r"
    }

    fun alignment(position: String): Int = when (position) {
        "top" -> 8
        "center" -> 5
        else -> 2 // bottom + custom + two_thirds_bottom all use bottom alignment with MarginV
    }

    fun marginV(style: Style): Int {
        val h = style.videoHeight
        return when (style.position) {
            "top" -> h / 12
            "center" -> 0
            "custom" -> ((style.customPosition / 100.0) * h).toInt().coerceIn(0, h - style.fontSize)
            "two_thirds_bottom" -> h / 3
            else -> h / 12
        }
    }

    /** Assembly of the force_style parameter. PlayResX/Y are set to the video size so FontSize is px-exact. */
    fun forceStyle(s: Style): String = buildString {
        append("FontName=${s.fontName}")
        append(",FontSize=${s.fontSize}")
        append(",PrimaryColour=${toAssColor(s.foreColor)}")
        append(",OutlineColour=${toAssColor(s.strokeColor)}")
        if (s.backColor.isNotBlank() && s.backColor.removePrefix("#") != "000000") {
            append(",BorderStyle=4") // 4 = opaque box behind text (libass ext)
            append(",BackColour=${toAssColor(s.backColor)}")
        } else {
            append(",BorderStyle=1")
        }
        append(",Outline=${s.strokeWidth.coerceAtLeast(0f)}")
        append(",Shadow=0")
        append(",Alignment=${alignment(s.position)}")
        append(",MarginV=${marginV(s)}")
        append(",PlayResX=${s.videoWidth}")
        append(",PlayResY=${s.videoHeight}")
        append(",Bold=0,Italic=0,Spacing=0,WrapStyle=2")
    }
}
