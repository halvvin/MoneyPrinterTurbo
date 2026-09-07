package com.moneyprinterturbo.android.core.logging

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Central diagnostic logger.
 *
 * When enabled it records application-level events, network metadata, task stages,
 * exceptions and important lifecycle events. Secrets are deliberately redacted.
 * Logs are kept in app-private storage and can be exported by the user.
 */
object AppLogger {
    private val enabled = AtomicBoolean(false)
    private const val MAX_BYTES = 4L * 1024L * 1024L
    private const val FILE_NAME = "mpt-app.log"
    private val lock = Any()
    @Volatile private var appContext: Context? = null

    fun init(context: Context, enabledByUser: Boolean) {
        appContext = context.applicationContext
        enabled.set(enabledByUser)
        if (enabledByUser) {
            log(context, "LOGGER", "initialized; device=${Build.MANUFACTURER} ${Build.MODEL}; sdk=${Build.VERSION.SDK_INT}")
        }
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try { log(context, "CRASH", "thread=${thread.name}; ${stack(throwable)}") } catch (_: Throwable) {}
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun setEnabled(value: Boolean) { enabled.set(value) }
    fun isEnabled(): Boolean = enabled.get()

    fun logCtx(tag: String, message: String) { appContext?.let { log(it, tag, message) } }
    fun exceptionCtx(tag: String, message: String, throwable: Throwable) { appContext?.let { exception(it, tag, message, throwable) } }
    fun networkCtx(method: String, url: String, code: Int? = null, elapsedMs: Long? = null) { appContext?.let { network(it, method, url, code, elapsedMs) } }

    fun log(context: Context, tag: String, message: String) {
        if (!enabled.get()) return
        val clean = redact(message)
        synchronized(lock) {
            try {
                val file = File(context.filesDir, FILE_NAME)
                rotateIfNeeded(file)
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                file.appendText("$ts [$tag] $clean\n")
            } catch (_: Throwable) {}
        }
    }

    fun exception(context: Context, tag: String, message: String, throwable: Throwable) {
        log(context, tag, "$message; ${stack(throwable)}")
    }

    fun network(context: Context, method: String, url: String, code: Int? = null, elapsedMs: Long? = null) {
        val suffix = buildString {
            if (code != null) append(" code=$code")
            if (elapsedMs != null) append(" elapsedMs=$elapsedMs")
        }
        log(context, "HTTP", "$method ${redactUrl(url)}$suffix")
    }

    suspend fun export(context: Context): File = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val source = File(context.filesDir, FILE_NAME)
            val rotated = File(context.filesDir, "$FILE_NAME.1")
            val out = File(context.cacheDir, "MoneyPrinterTurbo-logs-${System.currentTimeMillis()}.txt")
            out.bufferedWriter().use { w ->
                w.appendLine("MoneyPrinterTurbo Android diagnostic bundle")
                w.appendLine("timestamp=${System.currentTimeMillis()}")
                w.appendLine("package=${context.packageName}")
                w.appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                w.appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
                w.appendLine("logging_enabled=${enabled.get()}")
                w.appendLine("memory_native_heap_bytes=${Debug.getNativeHeapAllocatedSize()}")
                w.appendLine("memory_heap_bytes=${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()}")
                w.appendLine("--- CURRENT LOG ---")
                if (source.exists()) source.forEachLine { w.appendLine(it) } else w.appendLine("<empty>")
                w.appendLine("--- ROTATED LOG ---")
                if (rotated.exists()) rotated.forEachLine { w.appendLine(it) } else w.appendLine("<none>")
            }
            out
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            try { File(context.filesDir, FILE_NAME).delete() } catch (_: Throwable) {}
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (file.exists() && file.length() > MAX_BYTES) {
            val old = File(file.parentFile, "$FILE_NAME.1")
            old.delete()
            file.renameTo(old)
        }
    }

    private fun redact(value: String): String {
        var s = value
        s = Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;]+").replace(s, "$1<REDACTED>")
        s = Regex("(?i)(api[-_ ]?key\\s*[:=]\\s*)[^\\s,;]+").replace(s, "$1<REDACTED>")
        s = Regex("(?i)(password|token|secret)\\s*[:=]\\s*[^\\s,;]+").replace(s) { "${it.value.substringBefore("=")}=<REDACTED>" }
        return s
    }

    private fun redactUrl(url: String): String =
        url.replace(Regex("([?&](?:key|api_key|token|access_token)=)[^&]+", RegexOption.IGNORE_CASE), "$1<REDACTED>")

    private fun stack(t: Throwable): String =
        buildString {
            append(t::class.java.simpleName).append(": ").append(t.message ?: "")
            t.stackTrace.take(12).forEach { append("\n at ").append(it) }
        }
}
