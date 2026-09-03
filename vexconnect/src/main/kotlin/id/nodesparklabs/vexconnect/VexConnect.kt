package id.nodesparklabs.vexconnect

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object VexConnect {

    /**
     * Parses a vexconnect:// URI. The `pub=` parameter carries the dApp's ephemeral
     * X25519 public key (base64url-encoded). The wallet generates its own keypair on
     * approval and derives the shared AES-256-GCM session key via ECDH + HKDF-SHA256.
     *
     * Format: vexconnect://wc?sid=UUID&relay=wss://HOST&name=DApp&url=https://...&pub=BASE64URL&icon=...
     */
    fun parseUri(raw: String): VexConnectUri? = runCatching {
        var uri = Uri.parse(raw)
        // Unwrap App Link / Universal Link: https://wallet.app/wc?uri=vexconnect://...
        if (uri.scheme == "https" || uri.scheme == "http") {
            uri = Uri.parse(uri.getQueryParameter("uri") ?: return null)
        }
        if (uri.scheme != "vexconnect") return null
        val pubB64 = uri.getQueryParameter("pub") ?: return null
        val dappPublicKey = try {
            Base64.decode(pubB64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) { return null }
        VexConnectUri(
            sessionId     = uri.getQueryParameter("sid")   ?: return null,
            relayUrl      = uri.getQueryParameter("relay") ?: return null,
            dappName      = uri.getQueryParameter("name")  ?: "Unknown dApp",
            dappUrl       = uri.getQueryParameter("url")   ?: "",
            dappIcon      = uri.getQueryParameter("icon"),
            dappPublicKey = dappPublicKey,
        )
    }.getOrNull()

    /**
     * Creates a [VexConnectBridge] for a new session from a scanned URI.
     * Generates an ephemeral X25519 keypair, derives the AES session key via ECDH+HKDF,
     * and stores the wallet's public key for inclusion in the approve/reject response.
     */
    fun createBridge(
        uri: VexConnectUri,
        account: String,
        publicKey: String,
    ): VexConnectBridge {
        val (walletPrivKey, walletPubKey) = CryptoBox.generateX25519KeyPair()
        val sessionKey = CryptoBox.deriveSessionKey(walletPrivKey, uri.dappPublicKey)
        val walletPubKeyB64 = Base64.encodeToString(
            walletPubKey,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )

        val relay = RelayClient(relayUrl = uri.relayUrl, topic = uri.sessionId, key = sessionKey)
        relay.connect()
        val bridge = VexConnectBridge(
            pendingSession  = PendingSession(
                sessionId = uri.sessionId,
                dappName  = uri.dappName,
                dappUrl   = uri.dappUrl,
                dappIcon  = uri.dappIcon,
            ),
            relayClient     = relay,
            account         = account,
            publicKey       = publicKey,
            relayUrl        = uri.relayUrl,
            key             = sessionKey,
            walletPubKeyB64 = walletPubKeyB64,
        )
        bridge.startListening()
        return bridge
    }

    /**
     * Re-creates a [VexConnectBridge] from a persisted session. ECDH was already performed
     * during the original session — the derived AES key is passed directly.
     */
    fun createBridgeRestored(
        sessionId: String,
        relayUrl: String,
        dappName: String,
        dappUrl: String,
        dappIcon: String?,
        derivedKey: ByteArray,
        account: String,
        publicKey: String,
    ): VexConnectBridge {
        val relay = RelayClient(relayUrl = relayUrl, topic = sessionId, key = derivedKey)
        relay.connect()
        val bridge = VexConnectBridge(
            pendingSession  = PendingSession(
                sessionId = sessionId,
                dappName  = dappName,
                dappUrl   = dappUrl,
                dappIcon  = dappIcon,
            ),
            relayClient     = relay,
            account         = account,
            publicKey       = publicKey,
            relayUrl        = relayUrl,
            key             = derivedKey,
            walletPubKeyB64 = null,
        )
        bridge.startListening()
        return bridge
    }
}

/**
 * Active bridge between the wallet and a single dApp session over the relay.
 *
 * Lifecycle:
 * 1. Created via [VexConnect.createBridge] — relay is already connected.
 * 2. Show approval UI; collect [events] for incoming requests and disconnect events.
 * 3. Call [approve] or [reject].
 * 4. Call [disconnect] when done or when [VexConnectEvent.Disconnected] is received.
 */
class VexConnectBridge internal constructor(
    val pendingSession: PendingSession,
    private val relayClient: RelayClient,
    val account: String,
    val publicKey: String,
    /** Stored so the session can be persisted and restored after app restart. */
    val relayUrl: String,
    val key: ByteArray,
    /** Wallet's ephemeral X25519 public key sent back to dApp in approve/reject. Null for restored sessions. */
    private val walletPubKeyB64: String?,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _events = MutableSharedFlow<VexConnectEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow    = BufferOverflow.DROP_OLDEST,
    )

    /** Flow of session events. Collect on Main for safe UI updates. */
    val events: SharedFlow<VexConnectEvent> = _events

    private val _isReconnecting = MutableStateFlow(false)
    /** True while the relay WebSocket is trying to reconnect after an unexpected drop. */
    val isReconnecting: StateFlow<Boolean> = _isReconnecting

    suspend fun approve() = withContext(Dispatchers.IO) {
        relayClient.sendMessage(
            type = "session_approve",
            payload = mapOf(
                "approved"  to true,
                "account"   to account,
                "publicKey" to publicKey,
            ),
            pub = walletPubKeyB64,
        )
    }

    suspend fun reject(reason: String = "User rejected") = withContext(Dispatchers.IO) {
        relayClient.sendMessage(
            type = "session_reject",
            payload = mapOf("reason" to reason),
            pub = walletPubKeyB64,
        )
        relayClient.close()
    }

    suspend fun respondSuccess(requestId: String, txId: String, blockNum: Long) =
        withContext(Dispatchers.IO) {
            relayClient.sendMessage(
                type = "response",
                payload = mapOf(
                    "requestId" to requestId,
                    "txId"      to txId,
                    "blockNum"  to blockNum,
                ),
            )
        }

    suspend fun respondError(requestId: String, error: String) = withContext(Dispatchers.IO) {
        relayClient.sendMessage(
            type = "response",
            payload = mapOf(
                "requestId" to requestId,
                "error"     to error,
            ),
        )
    }

    /** Triggers an immediate reconnect attempt, bypassing the current backoff delay.
     * Safe to call at any time (network-back, app-foregrounded). No-op if already connected. */
    fun reconnectNow() = relayClient.reconnectNow()

    fun disconnect() {
        relayClient.sendMessage(type = "session_delete", payload = emptyMap())
        relayClient.close()
    }

    internal fun startListening() {
        relayClient.events
            .onEach { msg -> handleIncoming(msg) }
            .onCompletion { _events.tryEmit(VexConnectEvent.Disconnected(pendingSession.sessionId)) }
            .catch { /* network errors handled by onCompletion */ }
            .launchIn(scope)

        // Sync isReconnecting flag dari RelayClient ke StateFlow yang bisa di-observe UI
        scope.launch {
            while (true) {
                _isReconnecting.value = relayClient.isReconnecting
                delay(500)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleIncoming(msg: Map<String, Any?>) {
        when (msg["type"] as? String) {
            "request" -> {
                val payload    = msg["payload"] as? Map<String, Any?> ?: return
                val requestId  = payload["requestId"] as? String ?: return
                val actionsRaw = payload["actions"] as? List<*> ?: return
                val actions = actionsRaw.mapNotNull { item ->
                    val a       = item as? Map<String, Any?> ?: return@mapNotNull null
                    val account = a["account"] as? String ?: return@mapNotNull null
                    val name    = a["name"]    as? String ?: return@mapNotNull null
                    val authRaw = a["authorization"] as? List<*> ?: emptyList<Any>()
                    val auth = authRaw.mapNotNull { authItem ->
                        val m = authItem as? Map<String, Any?> ?: return@mapNotNull null
                        Authorization(
                            actor      = m["actor"]      as? String ?: return@mapNotNull null,
                            permission = m["permission"] as? String ?: return@mapNotNull null,
                        )
                    }
                    val data = (a["data"] as? Map<String, Any?>) ?: emptyMap()
                    AntelopeAction(account, name, auth, data)
                }
                if (actions.isEmpty()) return
                _events.tryEmit(VexConnectEvent.TransactionRequest(
                    VexConnectRequest(
                        requestId = requestId,
                        sessionId = pendingSession.sessionId,
                        actions   = actions,
                    )
                ))
            }
            "session_delete" -> relayClient.close() // onCompletion emits Disconnected
        }
    }
}
