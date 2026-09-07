package com.moneyprinterturbo.android.core.media

import android.content.Context
import java.io.File

/**
 * Video composer — Kotlin re-implementation of upstream app/services/video.py
 * (scene normalization, concat, audio mix, subtitle burn-in, final encode).
 * Uses the bundled static ffmpeg binary; real progress via `-progress pipe:1`.
 */
class MediaComposer(
    private val ffmpeg: FfmpegExecutor,
    private val workDir: File,
) {
    class ComposerException(message: String) : Exception(message)

    private val tmpDir: File = File(workDir, "tmp").apply { mkdirs() }

    /** Create a normalized scene clip from a source video/image. */
    fun makeScene(
        source: File,
        isImage: Boolean,
        width: Int,
        height: Int,
        durationSec: Double,
        fps: Int,
        fitMode: String,
        clipSpeed: Float,
        transition: String,
        progress: ((Float) -> Unit)? = null,
    ): File {
        val out = File(tmpDir, "scene_${System.nanoTime()}.mp4")
        val fit = FfmpegExecutor.fitFilter(fitMode, width, height)
        val speed = if (clipSpeed != 1.0f) ",setpts=PTS/${clipSpeed}" else ""
        val transitionFilter = when (transition) {
            "FADE" -> {
                val d = durationSec / clipSpeed
                ",fade=t=in:st=0:d=0.5,fade=t=out:st=${(d - 0.5).coerceAtLeast(0.0)}:d=0.5"
            }
            "ZOOM" -> ",zoompan=z='min(zoom+0.0015,1.08)':d=1:s=${width}x${height}:fps=${fps}"
            "SLIDE" -> ",crop=${width}:${height}:x='if(eq(mod(n,2),0),0,iw-${width})':y='(ih-${height})/2'"
            else -> ""
        }
        val vf = "$fit$speed,fps=$fps$transitionFilter,format=yuv420p"
        val args = if (isImage) {
            listOf("-loop", "1", "-t", fmt(durationSec), "-i", source.absolutePath,
                "-vf", vf, "-r", fps.toString(),
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-an", out.absolutePath)
        } else {
            listOf("-i", source.absolutePath,
                "-t", fmt(durationSec),
                "-vf", vf,
                "-r", fps.toString(),
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
                "-an", "-movflags", "+faststart",
                out.absolutePath)
        }
        ffmpeg.run(listOf("-y", "-hide_banner") + args, durationSec, progress)
        return out
    }

    /** Concatenate same-codec scene clips via the concat demuxer. */
    fun concat(scenes: List<File>): File {
        if (scenes.isEmpty()) throw ComposerException("no scenes to concatenate")
        val list = File(tmpDir, "concat_${System.nanoTime()}.txt")
        list.writeText(scenes.joinToString("\n") { FfmpegExecutor.concatPath(it) })
        val out = File(tmpDir, "concat_${System.nanoTime()}.mp4")
        ffmpeg.run(listOf("-y", "-hide_banner", "-f", "concat", "-safe", "0", "-i", list.absolutePath,
            "-c", "copy", "-movflags", "+faststart", out.absolutePath))
        list.delete()
        return out
    }

    /** Mix voiceover + optional looped BGM with fade — parity with upstream combine_videos audio stage. */
    fun muxAudio(video: File, voice: File?, bgm: File?, bgmVolume: Float): File {
        if (voice == null && bgm == null) return video
        val out = File(tmpDir, "mux_${System.nanoTime()}.mp4")
        val duration = probeDuration(video) ?: 0.0
        val args = mutableListOf("-y", "-hide_banner", "-i", video.absolutePath)
        if (voice != null) args += listOf("-i", voice.absolutePath)
        if (bgm != null) args += listOf("-stream_loop", "-1", "-i", bgm.absolutePath)

        val filter = when {
            voice != null && bgm != null -> {
                val fadeStart = (duration - 2.0).coerceAtLeast(0.0)
                "[2:a]volume=${bgmVolume},afade=t=in:st=0:d=2,afade=t=out:st=${fmt(fadeStart)}:d=2[bg];" +
                    "[1:a]volume=1.0[voc];[voc][bg]amix=inputs=2:duration=first:dropout_transition=0[aout]"
            }
            voice != null -> "[1:a]volume=1.0[aout]"
            else -> "[1:a]volume=${bgmVolume},afade=t=in:st=0:d=2,afade=t=out:st=${fmt((duration - 2.0).coerceAtLeast(0.0))}:d=2[aout]"
        }
        args += listOf(
            "-filter_complex", filter,
            "-map", "0:v", "-map", "[aout]",
            "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
            "-shortest", "-movflags", "+faststart",
            out.absolutePath,
        )
        ffmpeg.run(args, duration)
        return out
    }

    /** Create a lightweight Sonilo analysis proxy: max 1280px, no audio, fast H.264. */
    fun makeSoniloProxy(video: File): File {
        val out = File(tmpDir, "sonilo_proxy_${System.nanoTime()}.mp4")
        val duration = probeDuration(video)
        ffmpeg.run(
            listOf("-y", "-hide_banner", "-i", video.absolutePath,
                "-vf", "scale=w=1280:h=1280:force_original_aspect_ratio=decrease",
                "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "30",
                "-pix_fmt", "yuv420p", "-movflags", "+faststart", out.absolutePath),
            duration,
        )
        return out
    }

    /** Burn subtitles into the video — parity with upstream subtitle stage. */
    fun burnSubtitles(video: File, srt: File, fontsDir: File, forceStyle: String): File {
        val out = File(tmpDir, "sub_${System.nanoTime()}.mp4")
        val duration = probeDuration(video)
        ffmpeg.run(
            listOf("-y", "-hide_banner", "-i", video.absolutePath,
                "-vf", FfmpegExecutor.subtitlesFilter(srt, fontsDir, forceStyle),
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
                "-c:a", "copy", "-movflags", "+faststart",
                out.absolutePath),
            duration,
        )
        return out
    }

    /** Generate silent audio track (upstream: no-voice mode) — AAC in m4a (native encoder). */
    fun silentAudio(durationSec: Double): File {
        val out = File(tmpDir, "silent_${System.nanoTime()}.m4a")
        ffmpeg.run(listOf(
            "-y", "-hide_banner", "-f", "lavfi", "-i", "anullsrc=r=24000:cl=mono",
            "-t", fmt(durationSec), "-c:a", "aac", "-b:a", "48k", out.absolutePath))
        return out
    }

    fun probeDuration(file: File): Double? {
        // `ffmpeg -i file` without an output file ALWAYS exits 1 — it is a metadata
        // probe. Read the duration from stderr instead of treating exit 1 as failure.
        val run = ffmpeg.run(listOf("-hide_banner", "-i", file.absolutePath), throwOnFailure = false)
        return FfmpegExecutor.parseDuration(run.stderr)
    }

    private fun fmt(d: Double): String = "%.3f".format(d)
}
