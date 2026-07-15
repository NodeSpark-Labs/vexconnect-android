package id.pixelgenius.vexconnect

import android.net.Uri
import android.util.Base64

/**
 * Parsed VexConnect session info from a vexconnect:// deep link — or from an
 * https:// Android App Link that wraps it (`https://yourwallet.app/wc?uri=<encoded
 * vexconnect://...>`), the form used when the wallet has registered its own
 * /.well-known/assetlinks.json. Which form the OS hands to the app depends on
 * how the wallet registered its intent-filter; this handles both so wallet
 * devs can pick either without extra glue code.
 *
 * Usage:
 *   val session = VexConnectSession.fromUri(intent.data) ?: return
 */
data class VexConnectSession(
    val sessionId: String,
    val relayUrl: String,
    val dappName: String,
    val dappUrl: String,
    val dappIcon: String?,
    /** dApp's X25519 *public* key, out-of-band via the deep link/QR. Not a
     * secret — the actual AES session key is derived via ECDH once the
     * wallet generates its own ephemeral keypair (see VexConnect.connect()),
     * so nothing sensitive ever travels through the QR/URI or the relay. */
    val dappPublicKey: ByteArray,
) {
    companion object {
        private const val B64_URL_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

        fun fromUri(uri: Uri): VexConnectSession? {
            // App Link wrapper: unwrap `uri` and parse the real pairing URI inside.
            if (uri.scheme == "https" || uri.scheme == "http") {
                val wrapped = uri.getQueryParameter("uri") ?: return null
                return fromUriString(wrapped)
            }
            if (uri.scheme != "vexconnect") return null
            val sid   = uri.getQueryParameter("sid")   ?: return null
            val relay = uri.getQueryParameter("relay") ?: return null
            val name  = uri.getQueryParameter("name")  ?: return null
            val url   = uri.getQueryParameter("url")   ?: return null
            val icon  = uri.getQueryParameter("icon")
            val pubB64 = uri.getQueryParameter("pub")  ?: return null
            val pub = try { Base64.decode(pubB64, B64_URL_FLAGS) } catch (_: IllegalArgumentException) { return null }
            return VexConnectSession(sid, relay, name, url, icon, pub)
        }

        fun fromUriString(raw: String): VexConnectSession? =
            fromUri(Uri.parse(raw))
    }
}
