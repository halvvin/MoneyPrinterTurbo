package com.moneyprinterturbo.android.core.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Shared HTTP client factory — timeouts from Settings → Network. */
object Http {
    fun client(timeoutSec: Int = 120, forWebSocket: Boolean = false): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .pingInterval(if (forWebSocket) 15L else 0L, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
}
