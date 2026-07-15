package id.pixelgenius.vexconnect

import android.net.Uri

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
) {
    companion object {
        fun fromUri(uri: Uri): VexConnectSession? {
            if (uri.scheme != "vexconnect") return null
            val sid   = uri.getQueryParameter("sid")   ?: return null
            val relay = uri.getQueryParameter("relay") ?: return null
            val name  = uri.getQueryParameter("name")  ?: return null
            val url   = uri.getQueryParameter("url")   ?: return null
            val icon  = uri.getQueryParameter("icon")
            return VexConnectSession(sid, relay, name, url, icon)
        }

        fun fromUriString(raw: String): VexConnectSession? =
            fromUri(Uri.parse(raw))
    }
}
