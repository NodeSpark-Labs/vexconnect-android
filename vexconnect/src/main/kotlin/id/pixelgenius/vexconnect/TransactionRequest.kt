package id.pixelgenius.vexconnect

/** A single Antelope/EOSIO action — account/name/authorization/data, same shape a native tx needs. */
data class AntelopeAction(
    val account: String,
    val name: String,
    val authorization: List<Authorization>,
    /** Plain JSON action data — resolve it against the live ABI before signing. */
    val data: Map<String, Any?>,
)

data class Authorization(val actor: String, val permission: String)

data class TransactionRequest(
    val requestId: String,
    val actions: List<AntelopeAction>,
)
