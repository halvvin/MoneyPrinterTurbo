package com.moneyprinterturbo.android.core.media

import com.moneyprinterturbo.android.core.logging.AppLogger

import android.content.Context
import java.io.File

/**
 * FFmpeg execution over the statically-compiled binary packaged as jniLibs/arm64-v8a/libffmpeg_exec.so.
 * Files under nativeLibraryDir are the only app-writable-executable location on Android 10+ (W^X),
 * so PackageManager extracts the binary there with the exec bit preserved.
 */
class FfmpegExecutor(private val context: Context) {

    val binary: File
        get() = if (File(context.applicationInfo.nativeLibraryDir, "libffmpeg_exec.so").exists())
            File(context.applicationInfo.nativeLibraryDir, "libffmpeg_exec.so")
        else File(context.filesDir, "ffmpeg_exec")   // recovered copy (tryRecoverFromApk)

    fun isAvailable(): Boolean =
        binary.exists() && binary.canExecute() && binary.length() > 1_000_000

    /** Human-readable state for logs/UI — never throws. */
    fun diagnose(): String = buildString {
        val b = File(context.applicationInfo.nativeLibraryDir, "libffmpeg_exec.so")
        append("nativeDir=").append(context.applicationInfo.nativeLibraryDir)
        append(" exists=").append(b.exists())
        append(" size=").append(if (b.exists()) b.length() else 0)
        append(" exec=").append(b.canExecute())
        append(" recovered=").append(File(context.filesDir, "ffmpeg_exec").exists())
    }

    /**
     * Recovery path: if PackageManager did not extract the binary (uncompressed-ABI
     * installs, OEM quirks), copy it out of the APK ourselves into a private dir and
     * chmod +x. Works on API 29+ because app-private files are exec-allowed there
     * only for targetSdk < 29... NOT true: W^X forbids exec of app data files on API
     * 29+. So this only works as a diagnostic + when the device allows it (some OEMs).
     * Primary path remains nativeLibraryDir via useLegacyPackaging=true.
     */
    fun tryRecoverFromApk(): Boolean {
        if (isAvailable()) return true
        return try {
            val apk = context.applicationInfo.sourceDir
            val zf = java.util.zip.ZipFile(apk)
            val entry = zf.getEntry("lib/arm64-v8a/libffmpeg_exec.so") ?: return false
            val out = File(context.filesDir, "ffmpeg_exec")
            zf.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            zf.close()
            out.setExecutable(true, false)
            out.length() > 1_000_000
        } catch (e: Exception) {
            false
        }
    }

    class FfmpegException(message: String) : Exception(message)

    @Volatile private var activeProcess: Process? = null

    /** Immediately terminate the currently running FFmpeg process, if any. */
    fun cancelActiveProcess() {
        val p = activeProcess ?: return
        AppLogger.log(context, "FFMPEG", "cancellation requested")
        try { p.destroy() } catch (_: Exception) {}
        try { if (p.isAlive) p.destroyForcibly() } catch (_: Exception) {}
    }

    data class Run(val exitCode: Int, val stdout: String, val stderr: String) {
        val success get() = exitCode == 0
    }

    /** Run ffmpeg. [progress] receives parsed out_time_us when -progress is requested (0..1 given duration).
     *  [throwOnFailure]=false returns the Run instead of throwing on non-zero exit — used by metadata
     *  probes, where `ffmpeg -i file` (no output) intentionally exits 1. */
    @Synchronized
    fun run(
        args: List<String>,
        durationSec: Double? = null,
        progress: ((Float) -> Unit)? = null,
        throwOnFailure: Boolean = true,
    ): Run {
        if (!isAvailable()) throw FfmpegException(
            "ffmpeg binary is missing or not executable at ${binary.absolutePath} — build pipeline broken"
        )
        val tmp = File(context.cacheDir, "ffmpeg-progress-${System.nanoTime()}.txt")
        val workDir = File(context.cacheDir, "ffmpeg-cwd").apply { mkdirs() }
        val proc: Process
        try {
            AppLogger.log(context, "FFMPEG", "start args=${args.take(8).joinToString(" ")}${if (args.size > 8) " …" else ""}")
            proc = ProcessBuilder(listOf(binary.absolutePath) + args)
                .directory(workDir)
                .redirectErrorStream(false)
                .start()
            activeProcess = proc
        } catch (e: Exception) {
            throw FfmpegException("failed to launch ffmpeg: ${e.message}")
        }
        val outSb = StringBuilder()
        val errSb = StringBuilder()
        val tOut = Thread {
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    synchronized(outSb) { outSb.append(line).append('\n') }
                    if (progress != null && durationSec != null && line.startsWith("out_time_us=")) {
                        val us = line.substringAfter('=').trim().toLongOrNull()
                        val total = durationSec
                        if (us != null && total != null && total > 0) {
                            val p = (us / 1_000_000.0 / total).toFloat().coerceIn(0f, 1f)
                            progress.invoke(p)
                        }
                    }
                }
            }
        }
        val tErr = Thread {
            proc.errorStream.bufferedReader().useLines { lines ->
                for (line in lines) synchronized(errSb) { errSb.append(line).append('\n') }
            }
        }
        tOut.start(); tErr.start()
        try {
            val exit = proc.waitFor()
            tOut.join(5_000); tErr.join(5_000)
            AppLogger.log(context, "FFMPEG", "exit=$exit")
            if (exit != 0 && throwOnFailure) {
                val tail = synchronized(errSb) { errSb.toString() }.trim().lines().takeLast(8).joinToString("\n")
                throw FfmpegException("ffmpeg failed (exit $exit):\n$tail")
            }
            return Run(exit, synchronized(outSb) { outSb.toString() }, synchronized(errSb) { errSb.toString() })
        } finally {
            if (activeProcess === proc) activeProcess = null
            proc.destroy()
            tmp.delete()
        }
    }

    /** Expose app-level logging for media components that hold no Context. */
    fun log(tag: String, message: String) = AppLogger.log(context, tag, message)

    /** App-PRIVATE internal dir guaranteed fopen-able by the bundled ffmpeg/libass
     *  (no FUSE involvement, unlike /storage paths). */
    val internalTmp: File
        get() = File(context.cacheDir, "media-tmp").apply { mkdirs() }

    companion object {
        /** Extract output video duration (seconds) from `ffmpeg -i` stderr. */
        fun parseDuration(stderr: String): Double? {
            val m = Regex("Duration: (\\d+):(\\d+):(\\d+)\\.(\\d+)").find(stderr) ?: return null
            val (h, min, s, cs) = m.destructured
            return h.toInt() * 3600 + min.toInt() * 60 + s.toInt() + (cs.toInt() / 100.0)
        }

        /** Build scale/crop filter for a canvas — cover or contain, parity with upstream video.py _fit_clip_to_canvas. */
        fun fitFilter(mode: String, width: Int, height: Int): String = when (mode) {
            "cover" -> "scale=$width:$height:force_original_aspect_ratio=increase,crop=$width:$height,setsar=1"
            else -> "scale=$width:$height:force_original_aspect_ratio=decrease,pad=$width:$height:(ow-iw)/2:(oh-ih)/2:black,setsar=1"
        }

        /** Escape for the ffmpeg concat demuxer file list (single-quote doubling per concat spec). */
        fun concatPath(file: File): String = file.absolutePath.replace("'", "'\\''").let { "file '$it'" }

        /** Subtitles filter arg with fontsdir + force_style. */
        fun subtitlesFilter(srt: File, fontsDir: File, forceStyle: String): String =
            "subtitles=${escapeFilterPath(srt.absolutePath)}:fontsdir=${escapeFilterPath(fontsDir.absolutePath)}:force_style='$forceStyle'"

        fun escapeFilterPath(path: String): String =
            path.replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'")
    }
}
