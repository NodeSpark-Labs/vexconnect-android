package id.nodesparklabs.vexconnect

import android.util.Base64
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object CryptoBox {

    data class Envelope(val iv: String, val ct: String)

    private const val ALG     = "AES/GCM/NoPadding"
    private const val TAG_LEN = 128
    private const val IV_LEN  = 12
    private val rng = SecureRandom()

    fun encrypt(key: ByteArray, plaintext: String): Envelope {
        val iv = ByteArray(IV_LEN).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance(ALG)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Envelope(
            iv = Base64.encodeToString(iv, Base64.NO_WRAP),
            ct = Base64.encodeToString(ct, Base64.NO_WRAP),
        )
    }

    fun decrypt(key: ByteArray, env: Envelope): String {
        val iv = Base64.decode(env.iv, Base64.NO_WRAP)
        val ct = Base64.decode(env.ct, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(ALG)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    // ── X25519 ECDH + HKDF-SHA256 key derivation ────────────────────────────

    /** Returns (privateKeyBytes, publicKeyBytes), each 32 bytes. */
    fun generateX25519KeyPair(): Pair<ByteArray, ByteArray> {
        val priv = X25519PrivateKeyParameters(rng)
        val pub  = priv.generatePublicKey()
        return priv.encoded to pub.encoded
    }

    /**
     * ECDH + HKDF-SHA256.
     * - salt: 32 zero bytes (RFC 5869 defined default)
     * - info: "vexconnect-session-key-v1"
     * Output: 32-byte AES-256-GCM key.
     */
    fun deriveSessionKey(walletPrivateKey: ByteArray, dappPublicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(walletPrivateKey, 0))
        val sharedSecret = ByteArray(32)
        agreement.calculateAgreement(X25519PublicKeyParameters(dappPublicKey, 0), sharedSecret, 0)

        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(
            sharedSecret,
            ByteArray(32),  // 32 zero-byte salt
            "vexconnect-session-key-v1".toByteArray(Charsets.UTF_8),
        ))
        val derived = ByteArray(32)
        hkdf.generateBytes(derived, 0, 32)
        return derived
    }
}
