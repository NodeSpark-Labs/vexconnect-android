package id.pixelgenius.vexconnect

import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
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
 *
 * The session's AES key is never carried in the URI/QR — it's derived here
 * via X25519 ECDH against the dApp's public key from the URI (own ephemeral
 * keypair generated per pairing), matching WalletConnect v2's session key
 * derivation. This can be computed immediately, no round trip needed - the
 * dApp's public key is already known from the parsed URI.
 */
class VexConnect(private val session: VexConnectSession) {

    var onTransactionRequest: ((TransactionRequest) -> Unit)? = null
    var onDisconnect: (() -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    private val keyPair = CryptoBox.generateX25519KeyPair()
    private val sessionKey = CryptoBox.deriveSessionKey(keyPair.secretKey, session.dappPublicKey)

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var closedIntentionally = false

    // Exponential backoff reconnect (1s, 2s, 4s, 8s, 16s, then give up and
    // surface onDisconnect) - mirrors the JS SDK's core.ts so a backgrounded
    // app or a brief network drop doesn't kill the session outright.
    private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor()
    private var reconnectAttempt = 0
    private var reconnectTask: ScheduledFuture<*>? = null

    // ── Connection ────────────────────────────────────────────────────────────

    fun connect() {
        val request = Request.Builder().url(session.relayUrl).build()
        ws = client.newWebSocket(request, listener)
    }

    /** Call when the app returns to the foreground or network connectivity is
     * restored - resets the backoff series and retries immediately instead of
     * waiting out whatever delay was already scheduled. */
    fun reconnectIfNeeded() {
        if (ws != null || closedIntentionally) return
        reconnectTask?.cancel(false)
        reconnectAttempt = 0
        connect()
    }

    private fun scheduleReconnect() {
        if (closedIntentionally || reconnectTask != null) return
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempt = 0
            onDisconnect?.invoke()
            return
        }
        val delayMs = (BASE_RECONNECT_DELAY_MS shl reconnectAttempt).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        reconnectAttempt++
        reconnectTask = reconnectExecutor.schedule({
            reconnectTask = null
            connect()
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    // ── Session control ───────────────────────────────────────────────────────

    fun approve(account: String, publicKey: String) {
        sendEncrypted("session_approve", JSONObject().apply {
            put("account", account)
            put("publicKey", publicKey)
        }, includePub = true)
    }

    fun reject(reason: String = "User rejected") {
        sendEncrypted("session_reject", JSONObject().apply { put("reason", reason) }, includePub = true)
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
            reconnectAttempt = 0
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
                        sessionKey,
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
                    // Liveness check the dApp sends both to confirm a resumed
                    // session is still alive, and periodically during an
                    // active one (catches a silently-dropped connection).
                    // No payload, so no decrypt/encrypt needed either way.
                    "ping" -> sendWire(JSONObject().apply {
                        put("type", "pong")
                        put("topic", session.sessionId)
                    })
                }
            } catch (_: Exception) { }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            ws = null
            onError?.invoke(t)
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            ws = null
            if (closedIntentionally) {
                onDisconnect?.invoke()
            } else {
                scheduleReconnect()
            }
        }
    }

    /** type/topic stay plain for relay routing; payload is AES-256-GCM ciphertext
     * it can't read. `includePub` attaches this wallet's ephemeral X25519 public
     * key in the clear - only needed on the first message the dApp will ever
     * receive from us (approve or reject), so it can derive the same session
     * key via ECDH; every later message is skipped since the dApp only reads
     * `pub` once, before it has a session key at all. */
    private fun sendEncrypted(type: String, payload: JSONObject, includePub: Boolean = false) {
        val env = CryptoBox.encrypt(sessionKey, payload.toString())
        sendWire(JSONObject().apply {
            put("type", type)
            put("topic", session.sessionId)
            put("payload", JSONObject().apply {
                put("iv", env.iv)
                put("ct", env.ct)
            })
            if (includePub) put("pub", CryptoBox.b64Url(keyPair.publicKey))
        })
    }

    private fun sendWire(json: JSONObject) {
        ws?.send(json.toString())
    }

    private fun close() {
        closedIntentionally = true
        reconnectTask?.cancel(false)
        // newSingleThreadScheduledExecutor() threads are non-daemon by
        // default - leaving this running would leak a thread (and could even
        // keep the process alive) for the rest of the app's lifetime.
        reconnectExecutor.shutdownNow()
        ws?.close(1000, null)
        ws = null
    }

    private companion object {
        const val BASE_RECONNECT_DELAY_MS = 1_000L
        const val MAX_RECONNECT_DELAY_MS  = 16_000L
        const val MAX_RECONNECT_ATTEMPTS  = 5
    }
}
