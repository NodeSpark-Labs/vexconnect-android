package id.pixelgenius.vexconnect

import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Wallet-side VexConnect client.
 *
 * Usage (inside your wallet Activity / ViewModel):
 *
 *   val session = VexConnectSession.fromUri(intent.data) ?: return
 *   val vc = VexConnect(session)
 *   vc.onTransactionRequest = { req -> /* show confirmation UI */ }
 *   vc.onDisconnect = { /* dApp disconnected */ }
 *   vc.connect()
 *   // after user confirms:
 *   vc.approve(account = "myaccount", publicKey = "VEX_PUB_KEY...")
 */
class VexConnect(private val session: VexConnectSession) {

    var onTransactionRequest: ((TransactionRequest) -> Unit)? = null
    var onDisconnect: (() -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null

    // ── Connection ────────────────────────────────────────────────────────────

    fun connect() {
        val request = Request.Builder().url(session.relayUrl).build()
        ws = client.newWebSocket(request, listener)
    }

    // ── Session control ───────────────────────────────────────────────────────

    fun approve(account: String, publicKey: String) {
        send(JSONObject().apply {
            put("type", "session_approve")
            put("topic", session.sessionId)
            put("payload", JSONObject().apply {
                put("account", account)
                put("publicKey", publicKey)
            })
        })
    }

    fun reject(reason: String = "User rejected") {
        send(JSONObject().apply {
            put("type", "session_reject")
            put("topic", session.sessionId)
            put("payload", JSONObject().apply { put("reason", reason) })
        })
        close()
    }

    fun disconnect() {
        send(JSONObject().apply {
            put("type", "session_delete")
            put("topic", session.sessionId)
            put("payload", JSONObject())
        })
        close()
    }

    // ── Transaction ───────────────────────────────────────────────────────────

    fun sendTransactionResult(requestId: String, txId: String, blockNum: Long) {
        send(JSONObject().apply {
            put("type", "response")
            put("topic", session.sessionId)
            put("payload", JSONObject().apply {
                put("requestId", requestId)
                put("txId", txId)
                put("blockNum", blockNum)
            })
        })
    }

    fun sendTransactionError(requestId: String, error: String) {
        send(JSONObject().apply {
            put("type", "response")
            put("topic", session.sessionId)
            put("payload", JSONObject().apply {
                put("requestId", requestId)
                put("error", error)
            })
        })
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            send(JSONObject().apply {
                put("type", "subscribe")
                put("topic", session.sessionId)
            })
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val msg = JSONObject(text)
                when (msg.optString("type")) {
                    "request" -> {
                        val payload = msg.optJSONObject("payload") ?: return
                        val requestId = payload.optString("requestId").ifEmpty { return }
                        val action    = payload.optString("action").ifEmpty { return }
                        val paramsObj = payload.optJSONObject("params")
                        val params    = buildMap<String, String> {
                            paramsObj?.keys()?.forEach { k -> put(k, paramsObj.optString(k)) }
                        }
                        onTransactionRequest?.invoke(TransactionRequest(requestId, action, params))
                    }
                    "session_delete" -> {
                        close()
                        onDisconnect?.invoke()
                    }
                }
            } catch (_: Exception) { }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onError?.invoke(t)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onDisconnect?.invoke()
        }
    }

    private fun send(json: JSONObject) {
        ws?.send(json.toString())
    }

    private fun close() {
        ws?.close(1000, null)
        ws = null
    }
}
