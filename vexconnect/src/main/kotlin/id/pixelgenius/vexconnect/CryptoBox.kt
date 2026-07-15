package id.pixelgenius.vexconnect

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM envelope encryption via the platform's javax.crypto — no crypto
 * library dependency. Mirrors the JS SDK's crypto.ts exactly (same algorithm,
 * 12-byte IV, base64 iv+ct fields) so payloads encrypted by one side decrypt
 * cleanly on the other. The relay only ever sees `type`/`topic` for routing;
 * `payload` travels as ciphertext it cannot read.
 */
internal object CryptoBox {
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    data class Envelope(val iv: String, val ct: String)

    fun encrypt(key: ByteArray, plaintext: String): Envelope {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Envelope(b64(iv), b64(ct))
    }

    fun decrypt(key: ByteArray, env: Envelope): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, unb64(env.iv)))
        return String(cipher.doFinal(unb64(env.ct)), Charsets.UTF_8)
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
