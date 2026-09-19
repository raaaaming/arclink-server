package kr.acda.arclink.internal

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArcAuthCodecTest {

	@Test
	fun validSignatureVerifies() {
		val secret = ArcAuthCodec.randomNonce(32)
		val nonce = "challenge-nonce".toByteArray()

		val signature = ArcAuthCodec.sign(secret, nonce)

		assertTrue(ArcAuthCodec.verify(secret, nonce, signature))
	}

	@Test
	fun tamperedSignatureIsRejected() {
		val secret = ArcAuthCodec.randomNonce(32)
		val nonce = "challenge-nonce".toByteArray()
		val signature = ArcAuthCodec.sign(secret, nonce)
		val tampered = if (signature == "AAAA") "BBBB" else "AAAA" + signature.substring(4)

		assertFalse(ArcAuthCodec.verify(secret, nonce, tampered))
	}

	@Test
	fun wrongSecretIsRejected() {
		val secret = ArcAuthCodec.randomNonce(32)
		val otherSecret = ArcAuthCodec.randomNonce(32)
		val nonce = "challenge-nonce".toByteArray()

		val signature = ArcAuthCodec.sign(secret, nonce)

		assertFalse(ArcAuthCodec.verify(otherSecret, nonce, signature))
	}

	@Test
	fun malformedBase64SignatureIsRejectedNotThrown() {
		val secret = ArcAuthCodec.randomNonce(32)
		val nonce = "n".toByteArray()

		assertFalse(ArcAuthCodec.verify(secret, nonce, "not-base64!!"))
	}

	@Test
	fun tokensEqualMatchesOnlyIdenticalStrings() {
		assertTrue(ArcAuthCodec.tokensEqual("abc", "abc"))
		assertFalse(ArcAuthCodec.tokensEqual("abc", "abd"))
		assertFalse(ArcAuthCodec.tokensEqual("abc", "ab"))
	}
}
