package kr.acda.arclink.internal

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 challenge/response primitives, mirroring the client mod's
 * `com.arclink.protocol.AuthCodec` byte-for-byte so both sides compute the
 * same signatures. Comparison is constant-time; the shared secret itself
 * never goes on the wire, only nonces and signatures do.
 */
object ArcAuthCodec {
	private const val HMAC_ALGO = "HmacSHA256"
	private val secureRandom = SecureRandom()

	fun randomNonce(length: Int): ByteArray = ByteArray(length).also { secureRandom.nextBytes(it) }

	fun hmac(secret: ByteArray, message: ByteArray): ByteArray {
		val mac = Mac.getInstance(HMAC_ALGO)
		mac.init(SecretKeySpec(secret, HMAC_ALGO))
		return mac.doFinal(message)
	}

	fun sign(secret: ByteArray, message: ByteArray): String =
		Base64.getEncoder().encodeToString(hmac(secret, message))

	/** Constant-time verification: never short-circuits on the first differing byte. */
	fun verify(secret: ByteArray, message: ByteArray, signatureBase64: String): Boolean {
		val expected = hmac(secret, message)
		val actual = try {
			Base64.getDecoder().decode(signatureBase64)
		} catch (e: IllegalArgumentException) {
			return false
		}
		return MessageDigest.isEqual(expected, actual)
	}

	/** Constant-time equality for opaque tokens (session tokens), not just HMAC signatures. */
	fun tokensEqual(a: String, b: String): Boolean =
		MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
