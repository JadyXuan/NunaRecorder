package com.example.wearable.internal

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Deepgram listen-flux WebSocket 客户端：连接 [listen-flux](https://developers.deepgram.com/reference/speech-to-text/listen-flux)，
 * 发送 linear16 PCM，解析 TurnInfo 并回调 (transcript, audio_window_start_sec, audio_window_end_sec)。
 * 由 [NunaWearableServiceImpl] 在解码路径 tee 出的连续 PCM 上使用，并将秒数转换为墙钟毫秒后交给 [TranscriptionProvider.TranscriptionListener]。
 */
class DeepgramFluxClient(
    private val apiKey: String,
    private val sampleRate: Int = WearableBleConfig.SAMPLE_RATE,
    private val onTurnInfo: (transcript: String, audioWindowStartSec: Double, audioWindowEndSec: Double) -> Unit,
    private val onError: (message: String) -> Unit,
    private val onClosed: () -> Unit
) {
    companion object {
        private const val TAG = "DeepgramFlux"
        private const val WS_URL = "wss://api.deepgram.com/v2/listen"
        private const val MODEL = "flux-general-en"
        private const val ENCODING = "linear16"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    private val closed = AtomicBoolean(false)

    /**
     * 建立 WebSocket 连接。连接成功后可调用 [sendPcm]。
     */
    fun connect() {
        if (closed.get()) return
        val url = "$WS_URL?model=$MODEL&encoding=$ENCODING&sample_rate=$sampleRate"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $apiKey")
            .build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Deepgram listen-flux WebSocket opened")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (closed.get()) return
                parseServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed.get()) {
                    Log.w(TAG, "Deepgram WebSocket failure", t)
                    onError("Deepgram: ${t.message ?: "connection failed"}")
                }
                onClosed()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Deepgram WebSocket closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Deepgram WebSocket closed: $code $reason")
                onClosed()
            }
        })
    }

    private fun parseServerMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")
            when (type) {
                "Connected" -> {
                    Log.d(TAG, "Deepgram Connected request_id=${json.optString("request_id")}")
                }
                "TurnInfo" -> {
                    val event = json.optString("event", "")
                    val transcript = json.optString("transcript", "").trim()
                    if (event != "EndOfTurn" || transcript.isEmpty()) {
                        // 只关心一句完整结束时的结果，其它 Update/StartOfTurn 等忽略
                        Log.d(TAG, "TurnInfo ignored: event=$event transcript='$transcript'")
                        return
                    }
                    val startSec = json.optDouble("audio_window_start", 0.0)
                    val endSec = json.optDouble("audio_window_end", 0.0)
                    Log.d(
                        TAG,
                        "TurnInfo EndOfTurn: window=${startSec}s..${endSec}s transcript='$transcript'"
                    )
                    onTurnInfo(transcript, startSec, endSec)
                }
                "ConfigureSuccess", "ConfigureFailure" -> {
                    // 可选：记录配置结果
                }
                "Error" -> {
                    val code = json.optString("code", "")
                    val desc = json.optString("description", "")
                    Log.w(TAG, "Deepgram Error: $code $desc")
                    onError("Deepgram: $code $desc")
                }
                else -> {
                    Log.d(TAG, "Deepgram message type=$type")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseServerMessage failed: $text", e)
        }
    }

    /**
     * 发送一段 linear16 PCM。可在任意线程调用；OkHttp WebSocket 发送线程安全。
     */
    fun sendPcm(pcmBytes: ByteArray) {
        if (pcmBytes.isEmpty() || closed.get()) return
        webSocket?.send(ByteString.of(*pcmBytes))
    }

    /**
     * 发送 CloseStream 并关闭连接。返回后不再回调。
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            val ws = webSocket
            if (ws != null) {
                val closeStream = """{"type":"CloseStream"}"""
                ws.send(closeStream)
                ws.close(1000, "CloseStream sent")
            }
        } catch (e: Exception) {
            Log.w(TAG, "close send failed", e)
        }
        webSocket = null
        onClosed()
    }
}
