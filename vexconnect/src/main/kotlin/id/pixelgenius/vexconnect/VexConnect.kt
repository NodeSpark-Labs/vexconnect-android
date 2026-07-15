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
        sendEncrypted("session_approve", JSONObject().apply {
            put("account", account)
            put("publicKey", publicKey)
        })
    }

    fun reject(reason: String = "User rejected") {
        sendEncrypted("session_reject", JSONObject().apply { put("reason", reason) })
        close()
    }

    fun disconnect() {
        sendEncrypted("session_delete", JSONObject())
        close()
    }

    // ── Transaction ───────────────────────────────────────────────────────────

    fun sendTransactionResult(requestId: String, txId: String, blockNum: Long) {
        sendEncrypted("response", JSONObject().apply {
            put("requestId", requestId)
            put("txId", txId)
            put("blockNum", blockNum)
        })
    }

    fun sendTransactionError(requestId: String, error: String) {
        sendEncrypted("response", JSONObject().apply {
            put("requestId", requestId)
            put("error", error)
        })
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sendWire(JSONObject().apply {
                put("type", "subscribe")
                put("topic", session.sessionId)
            })
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val wire = JSONObject(text)
                val payload = wire.optJSONObject("payload")?.let { env ->
                    JSONObject(CryptoBox.decrypt(
                        session.key,
                        CryptoBox.Envelope(env.getString("iv"), env.getString("ct")),
                    ))
                }
                when (wire.optString("type")) {
                    "request" -> {
                        payload ?: return
                        val requestId = payload.optString("requestId").ifEmpty { return }
                        val actionsArr = payload.optJSONArray("actions") ?: return
                        val actions = (0 until actionsArr.length()).mapNotNull { i ->
                            val a = actionsArr.optJSONObject(i) ?: return@mapNotNull null
                            val account = a.optString("account").ifEmpty { return@mapNotNull null }
                            val name    = a.optString("name").ifEmpty { return@mapNotNull null }
                            val authArr = a.optJSONArray("authorization")
                            val authorization = (0 until (authArr?.length() ?: 0)).mapNotNull { j ->
                                val auth = authArr?.optJSONObject(j) ?: return@mapNotNull null
                                Authorization(auth.optString("actor"), auth.optString("permission"))
                            }
                            @Suppress("UNCHECKED_CAST")
                            val data = (a.optJSONObject("data")?.toMap() ?: emptyMap<String, Any?>()) as Map<String, Any?>
                            AntelopeAction(account, name, authorization, data)
                        }
                        onTransactionRequest?.invoke(TransactionRequest(requestId, actions))
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

    /** type/topic stay plain for relay routing; payload is AES-256-GCM ciphertext it can't read. */
    private fun sendEncrypted(type: String, payload: JSONObject) {
        val env = CryptoBox.encrypt(session.key, payload.toString())
        sendWire(JSONObject().apply {
            put("type", type)
            put("topic", session.sessionId)
            put("payload", JSONObject().apply {
                put("iv", env.iv)
                put("ct", env.ct)
            })
        })
    }

    private fun sendWire(json: JSONObject) {
        ws?.send(json.toString())
    }

    private fun close() {
        ws?.close(1000, null)
        ws = null
    }
}
