package com.moneyprinterturbo.android.core.media

import android.content.Context
import java.io.File

/**
 * Font management: copies bundled fonts from assets to a private dir and makes them
 * available to libass via fontsdir. Persian/Arabic/CJK glyphs resolve through bundled
 * fonts first; system fonts can be added by the user via the UI.
 */
object FontManager {

    val bundledFonts = listOf(
        "BeVietnamPro-Medium.ttf",    // latin
        "BeVietnamPro-Bold.ttf",
        "Charm-Regular.ttf",          // latin serif-style
        "Charm-Bold.ttf",
        "UTM Kabel KT.ttf",
    )

    /** Default display name for a script — libass picks the first font that has the glyphs. */
    const val DEFAULT_FONT = "BeVietnamPro"

    fun fontsDir(context: Context): File =
        File(context.filesDir, "fonts").apply { mkdirs() }

    fun ensureBundled(context: Context): File {
        val dir = fontsDir(context)
        for (name in bundledFonts) {
            val out = File(dir, name)
            if (!out.exists() || out.length() == 0L) {
                try {
                    context.assets.open("fonts/$name").use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                } catch (e: Exception) {
                    out.delete() // asset missing — skip silently, subtitle falls back to another font
                }
            }
        }
        return dir
    }

    /** Copy a user-selected font (from SAF) into the fonts dir. */
    fun importFont(context: Context, uri: android.net.Uri, displayName: String): File {
        val safe = displayName.replace(Regex("[^A-Za-z0-9._ -]"), "").ifBlank { "font.ttf" }
        val out = File(fontsDir(context), safe)
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalStateException("cannot open the selected font file")
        return out
    }

    fun listUserFonts(context: Context): List<File> =
        fontsDir(context).listFiles()?.filter { it.extension.lowercase() in listOf("ttf", "otf", "ttc") } ?: emptyList()
}
