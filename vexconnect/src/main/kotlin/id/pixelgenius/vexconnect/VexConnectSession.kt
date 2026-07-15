package id.pixelgenius.vexconnect

import android.net.Uri
import android.util.Base64

/**
 * Parsed VexConnect session info from a vexconnect:// deep link.
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
    /** AES-256-GCM key, out-of-band via the deep link/QR — relay never sees it. */
    val key: ByteArray,
) {
    companion object {
        private const val B64_URL_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

        fun fromUri(uri: Uri): VexConnectSession? {
            if (uri.scheme != "vexconnect") return null
            val sid   = uri.getQueryParameter("sid")   ?: return null
            val relay = uri.getQueryParameter("relay") ?: return null
            val name  = uri.getQueryParameter("name")  ?: return null
            val url   = uri.getQueryParameter("url")   ?: return null
            val icon  = uri.getQueryParameter("icon")
            val keyB64 = uri.getQueryParameter("key")  ?: return null
            val key = try { Base64.decode(keyB64, B64_URL_FLAGS) } catch (_: IllegalArgumentException) { return null }
            return VexConnectSession(sid, relay, name, url, icon, key)
        }

        fun fromUriString(raw: String): VexConnectSession? =
            fromUri(Uri.parse(raw))
    }
}
