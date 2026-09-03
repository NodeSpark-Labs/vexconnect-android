package id.nodesparklabs.vexconnect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Low-level OkHttp WebSocket wrapper for the VexConnect relay.
 *
 * All payloads are AES-256-GCM encrypted — the relay routes messages by
 * type/topic only and never reads the payload plaintext.
 *
 * Reconnects automatically with exponential backoff on unexpected drops.
 * OkHttp sends WebSocket ping frames every 30 s to keep the connection alive.
 */
internal class RelayClient(
    private val relayUrl: String,
    private val topic: String,
    private val key: ByteArray,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val _channel = Channel<Map<String, Any?>>(capacity = 64)

    val events: Flow<Map<String, Any?>> = _channel.receiveAsFlow()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var userClosed = false
    @Volatile var isReconnecting = false
        private set
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Conflated: a trySend while nobody is waiting is still picked up on the next receive()
    private val reconnectTrigger = Channel<Unit>(Channel.CONFLATED)

    /** Signal an immediate reconnect attempt, skipping the current backoff delay. */
    fun reconnectNow() {
        if (!userClosed) reconnectTrigger.trySend(Unit)
    }

    fun connect() {
        val request = Request.Builder().url(relayUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isReconnecting = false
                ws.send(JSONObject().apply {
                    put("type", "subscribe")
                    put("topic", topic)
                }.toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "subscribe" -> return
                        "ping" -> {
                            ws.send(JSONObject().apply {
                                put("type", "pong")
                                put("topic", topic)
                            }.toString())
                            return
                        }
                    }

                    var map = json.toMap()

                    val payloadObj = json.optJSONObject("payload")
                    if (payloadObj != null && payloadObj.has("iv") && payloadObj.has("ct")) {
                        val decrypted = CryptoBox.decrypt(
                            key,
                            CryptoBox.Envelope(payloadObj.getString("iv"), payloadObj.getString("ct")),
                        )
                        map = map.toMutableMap().also { it["payload"] = JSONObject(decrypted).toMap() }
                    }

                    _channel.trySend(map)
                } catch (_: Exception) { }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
                handleDrop()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                handleDrop()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                handleDrop()
            }
        })
    }

    // Reconnect dengan exponential backoff: 3s → 6s → 12s → 24s → 48s → 60s
    // Guard isReconnecting mencegah loop ganda kalau WebSocket baru langsung drop lagi.
    // reconnectNow() bisa mempersingkat delay aktif lewat reconnectTrigger (conflated channel).
    private fun handleDrop() {
        if (userClosed) { _channel.close(); return }
        if (isReconnecting) return
        isReconnecting = true
        scope.launch {
            var delayMs = 3_000L
            repeat(6) {
                // Wait for either the backoff delay OR an external "reconnect now" signal
                withTimeoutOrNull(delayMs) { reconnectTrigger.receive() }
                if (userClosed) { isReconnecting = false; return@launch }
                try {
                    connect()
                    // isReconnecting akan di-reset ke false oleh onOpen kalau berhasil
                    return@launch
                } catch (_: Exception) { }
                delayMs = (delayMs * 2).coerceAtMost(60_000L)
            }
            isReconnecting = false
            _channel.close() // semua retry gagal → emit Disconnected ke bridge
        }
    }

    fun sendMessage(type: String, payload: Map<String, Any?>, pub: String? = null) {
        val env = CryptoBox.encrypt(key, JSONObject(payload).toString())
        webSocket?.send(JSONObject().apply {
            put("type", type)
            put("topic", topic)
            put("payload", JSONObject().apply {
                put("iv", env.iv)
                put("ct", env.ct)
            })
            if (pub != null) put("pub", pub)
        }.toString())
    }

    fun close() {
        userClosed = true
        scope.cancel()
        webSocket?.close(1000, "session ended")
        webSocket = null
        _channel.close()
    }
}

internal fun JSONObject.toMap(): Map<String, Any?> {
    val result = mutableMapOf<String, Any?>()
    for (k in keys()) {
        result[k] = when (val v = get(k)) {
            is JSONObject -> v.toMap()
            is JSONArray  -> v.toList()
            JSONObject.NULL -> null
            else -> v
        }
    }
    return result
}

internal fun JSONArray.toList(): List<Any?> = (0 until length()).map { i ->
    when (val v = get(i)) {
        is JSONObject -> v.toMap()
        is JSONArray  -> v.toList()
        JSONObject.NULL -> null
        else -> v
    }
}
