package com.ykolran.wam.api

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.ykolran.wam.models.SummaryUpdate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val TAG = "ApiClient"

    private var serverUrl: String = "http://192.168.1.100:3000/"
    private var authToken: String = ""

    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private var reconnectHandler: android.os.Handler? = null

    private val _summaryUpdates = MutableSharedFlow<SummaryUpdate>(extraBufferCapacity = 100)
    val summaryUpdates: SharedFlow<SummaryUpdate> = _summaryUpdates

    // Rebuild Retrofit whenever settings change
    private var _api: ApiService? = null
    val api: ApiService
        get() = _api ?: buildApi().also { _api = it }

    private val httpClient: OkHttpClient
        get() = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .addHeader("X-Auth-Token", authToken)
                        .build()
                )
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    private fun buildApi(): ApiService {
        val base = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        return Retrofit.Builder()
            .baseUrl(base)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    fun configure(url: String, token: String) {
        serverUrl = url
        authToken = token
        _api = null // force rebuild on next access
    }

    fun loadFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences("wamirror", Context.MODE_PRIVATE)
        configure(
            prefs.getString("server_url", "http://192.168.1.100:3000") ?: "http://192.168.1.100:3000",
            prefs.getString("auth_token", "") ?: ""
        )
    }

    fun connectWebSocket() {
        val base = if (serverUrl.endsWith("/")) serverUrl.dropLast(1) else serverUrl
        val wsUrl = base.replace("http://", "ws://").replace("https://", "wss://") + "?token=$authToken"

        val request = Request.Builder().url(wsUrl).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val map = gson.fromJson(text, Map::class.java)
                    when (map["type"]) {
                        "ping"               -> ws.send("{\"type\":\"pong\"}")
                        "summary_updated",
                        "conversation_swiped" -> {
                            val update = gson.fromJson(text, SummaryUpdate::class.java)
                            _summaryUpdates.tryEmit(update)
                        }
                    }
                } catch (e: Exception) { Log.e(TAG, "WS parse error: ${e.message}") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS failure: ${t.message}")
                scheduleReconnect()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WS closed: $reason")
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())
        reconnectHandler?.postDelayed({ connectWebSocket() }, 5000)
    }

    fun disconnectWebSocket() {
        reconnectHandler?.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "App closed")
        webSocket = null
    }
}
