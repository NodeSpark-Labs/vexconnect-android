package id.pixelgenius.vexconnect

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * AES-256-GCM envelope encryption via the platform's javax.crypto — no crypto
 * library dependency for the symmetric part. Mirrors the JS SDK's crypto.ts
 * exactly (same algorithm, 12-byte IV, base64 iv+ct fields) so payloads
 * encrypted by one side decrypt cleanly on the other. The relay only ever
 * sees `type`/`topic` for routing; `payload` travels as ciphertext it cannot
 * read.
 *
 * The AES key itself is never carried in the pairing URI/QR — it's derived
 * per-session via X25519 ECDH (Bouncy Castle; Android's own XDH provider
 * support is inconsistent below recent API levels, this works on any minSdk),
 * same as WalletConnect v2's session key derivation and the JS SDK's
 * crypto.ts. Only a public key ever travels in the URI or over the relay in
 * the clear.
 */
internal object CryptoBox {
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    /** Must match crypto.ts's HKDF_INFO byte-for-byte or the two sides derive
     * different AES keys. */
    private val HKDF_INFO = "vexconnect-session-key-v1".toByteArray(Charsets.UTF_8)
    private const val HKDF_KEY_LENGTH = 32

    data class Envelope(val iv: String, val ct: String)
    data class X25519KeyPair(val secretKey: ByteArray, val publicKey: ByteArray)

    fun generateX25519KeyPair(): X25519KeyPair {
        val priv = X25519PrivateKeyParameters(SecureRandom())
        return X25519KeyPair(priv.encoded, priv.generatePublicKey().encoded)
    }

    /** ECDH(secretKey, peerPublicKey) -> HKDF-SHA256 -> 32-byte AES-256 key.
     * Salt is 32 zero bytes (RFC 5869's defined default for "no salt"),
     * spelled out explicitly to guarantee identical output to the JS side's
     * `hkdf(sha256, shared, undefined, HKDF_INFO, 32)`, which resolves an
     * absent salt to the same zero-filled array internally. */
    fun deriveSessionKey(secretKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(secretKey, 0))
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKey, 0), shared, 0)

        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(shared, ByteArray(32), HKDF_INFO))
        val okm = ByteArray(HKDF_KEY_LENGTH)
        hkdf.generateBytes(okm, 0, HKDF_KEY_LENGTH)
        return okm
    }

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

    fun b64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun unb64Url(s: String): ByteArray =
        Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
