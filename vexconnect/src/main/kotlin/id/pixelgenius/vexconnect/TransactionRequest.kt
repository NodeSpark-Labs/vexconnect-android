package id.pixelgenius.vexconnect

data class TransactionRequest(
    val requestId: String,
    val action: String,
    val params: Map<String, String>,
)
