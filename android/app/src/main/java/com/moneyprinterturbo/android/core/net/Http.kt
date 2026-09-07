package com.moneyprinterturbo.android.core.net

import android.content.Context
import com.moneyprinterturbo.android.core.logging.AppLogger
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Shared network clients with connection lifecycle diagnostics. Bodies and secrets are never logged. */
object Http {
    @Volatile private var appContext: Context? = null

    fun init(context: Context) { appContext = context.applicationContext }

    private val dispatcher = Dispatcher().apply {
        maxRequests = 16
        maxRequestsPerHost = 8
    }
    private val pool = ConnectionPool(8, 5, TimeUnit.MINUTES)

    private val eventFactory = object : EventListener.Factory {
        override fun create(call: Call): EventListener = object : EventListener() {
            private val started = System.nanoTime()
            private var dnsStart = 0L
            private var connectStart = 0L

            override fun callStart(call: Call) {
                AppLogger.logCtx("HTTP", "start ${call.request().method} ${call.request().url}")
            }
            override fun dnsStart(call: Call, domainName: String) { dnsStart = System.nanoTime() }
            override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
                AppLogger.logCtx("HTTP", "dns domain=$domainName elapsedMs=${elapsed(dnsStart)} addresses=${inetAddressList.size}")
            }
            override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
                connectStart = System.nanoTime()
            }
            override fun connectEnd(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?) {
                AppLogger.logCtx("HTTP", "connect host=${inetSocketAddress.hostString} elapsedMs=${elapsed(connectStart)} protocol=${protocol ?: "unknown"}")
            }
            override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) {
                AppLogger.logCtx("HTTP", "tls elapsedMs=${elapsed(connectStart)} cipher=${handshake?.cipherSuite?.javaName ?: "unknown"}")
            }
            override fun responseHeadersEnd(call: Call, response: okhttp3.Response) {
                AppLogger.networkCtx(call.request().method, call.request().url.toString(), response.code, elapsed(started))
            }
            override fun callFailed(call: Call, ioe: java.io.IOException) {
                AppLogger.exceptionCtx("HTTP_ERROR", "${call.request().method} ${call.request().url}", ioe)
            }
            override fun callEnd(call: Call) {
                AppLogger.logCtx("HTTP", "end ${call.request().method} elapsedMs=${elapsed(started)}")
            }
            private fun elapsed(start: Long) = if (start == 0L) 0 else (System.nanoTime() - start) / 1_000_000
        }
    }

    private val normal = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(pool)
        .eventListenerFactory(eventFactory)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val websocket = normal.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    fun client(timeoutSec: Int = 120, forWebSocket: Boolean = false): OkHttpClient {
        val base = if (forWebSocket) websocket else normal
        if (timeoutSec == 120 || forWebSocket) return base
        return base.newBuilder()
            .readTimeout(timeoutSec.toLong().coerceIn(10, 600), TimeUnit.SECONDS)
            .writeTimeout(timeoutSec.toLong().coerceIn(10, 600), TimeUnit.SECONDS)
            .callTimeout((timeoutSec + 60L).coerceIn(30, 660), TimeUnit.SECONDS)
            .build()
    }

    private fun AppLogger.logCtx(tag: String, message: String) {
        appContext?.let { log(it, tag, message) }
    }
    private fun AppLogger.networkCtx(method: String, url: String, code: Int, elapsedMs: Long) {
        appContext?.let { network(it, method, url, code, elapsedMs) }
    }
    private fun AppLogger.exceptionCtx(tag: String, message: String, t: Throwable) {
        appContext?.let { exception(it, tag, message, t) }
    }
}
