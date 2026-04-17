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

    private var serverUrl: String = "http://192.168.68.57:3000/"  // Set from Settings
    private var authToken: String = "change-me-to-a-random-secret"

    private val gson = Gson()
    private var webSocket: WebSocket? = null

    private val _summaryUpdates = MutableSharedFlow<SummaryUpdate>(extraBufferCapacity = 50)
    val summaryUpdates: SharedFlow<SummaryUpdate> = _summaryUpdates

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .addHeader("X-Auth-Token", authToken)
                    .build()
                chain.proceed(req)
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private var _api: ApiService? = null
    val api: ApiService
        get() {
            if (_api == null) {
                _api = createApiService()
            }
            return _api!!
        }

    private fun createApiService(): ApiService {
        return Retrofit.Builder()
            .baseUrl(serverUrl)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    fun configure(url: String, token: String) {
        val newUrl = if (url.endsWith("/")) url else "$url/"
        if (newUrl != serverUrl || token != authToken || _api == null) {
            serverUrl = newUrl
            authToken = token
            _api = createApiService()
            // If WebSocket was connected, it might need reconnecting with new URL/token
            if (webSocket != null) {
                disconnectWebSocket()
                connectWebSocket()
            }
        }
    }

    fun connectWebSocket() {
        val wsUrl = serverUrl.replace("http://", "ws://").replace("https://", "wss://")
            .trimEnd('/') + "?token=$authToken"

        val request = Request.Builder().url(wsUrl).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val msg = gson.fromJson(text, Map::class.java)
                    if (msg["type"] == "summary_updated") {
                        val update = gson.fromJson(text, SummaryUpdate::class.java)
                        _summaryUpdates.tryEmit(update)
                    }
                    // Respond to pings
                    if (msg["type"] == "ping") ws.send("{\"type\":\"pong\"}")
                } catch (e: Exception) {
                    Log.e(TAG, "WebSocket parse error: ${e.message}")
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $reason")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                // Auto-reconnect after 5s
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    connectWebSocket()
                }, 5000)
            }
        })
    }

    fun disconnectWebSocket() {
        webSocket?.close(1000, "App closed")
    }
}
