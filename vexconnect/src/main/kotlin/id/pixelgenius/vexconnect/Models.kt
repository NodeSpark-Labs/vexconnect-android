package id.pixelgenius.vexconnect

/**
 * Parsed representation of a vexconnect:// URI scanned from a QR code.
 */
data class VexConnectUri(
    val sessionId: String,
    val relayUrl: String,
    val dappName: String,
    val dappUrl: String,
    val dappIcon: String?,
    /** dApp's ephemeral X25519 public key (32 bytes). Session AES key is derived via ECDH+HKDF. */
    val dappPublicKey: ByteArray,
)

/**
 * Pending session awaiting user approval. Shown in the wallet approval sheet.
 */
data class PendingSession(
    val sessionId: String,
    val dappName: String,
    val dappUrl: String,
    val dappIcon: String?,
)

/** Single Antelope/EOSIO action — same shape the chain requires. */
data class AntelopeAction(
    val account: String,
    val name: String,
    val authorization: List<Authorization>,
    /** Plain JSON action data as a map. */
    val data: Map<String, Any?>,
)

data class Authorization(val actor: String, val permission: String)

/**
 * A transaction request forwarded from the dApp — carries one or more
 * Antelope actions to be signed and pushed to chain.
 */
data class VexConnectRequest(
    val requestId: String,
    val sessionId: String,
    val actions: List<AntelopeAction>,
)

/** Events emitted by [VexConnectBridge] and delivered to the wallet UI. */
sealed class VexConnectEvent {
    data class TransactionRequest(val request: VexConnectRequest) : VexConnectEvent()
    data class Disconnected(val sessionId: String) : VexConnectEvent()
}
