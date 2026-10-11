package kr.acda.arclink.internal

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArcLinkFileTokenTest {

	@Test
	fun validTokenVerifies() {
		val secret = ArcAuthCodec.randomNonce(32)
		val playerId = UUID.randomUUID()
		val exp = System.currentTimeMillis() + 60_000

		val token = ArcLinkFileToken.sign(secret, "ui/menu.html", playerId, exp)

		assertTrue(ArcLinkFileToken.verify(secret, "ui/menu.html", playerId, exp, token))
	}

	@Test
	fun expiredTokenIsRejected() {
		val secret = ArcAuthCodec.randomNonce(32)
		val playerId = UUID.randomUUID()
		val exp = System.currentTimeMillis() - 1_000

		val token = ArcLinkFileToken.sign(secret, "ui/menu.html", playerId, exp)

		assertFalse(ArcLinkFileToken.verify(secret, "ui/menu.html", playerId, exp, token))
	}

	@Test
	fun wrongSecretIsRejected() {
		val secret = ArcAuthCodec.randomNonce(32)
		val otherSecret = ArcAuthCodec.randomNonce(32)
		val playerId = UUID.randomUUID()
		val exp = System.currentTimeMillis() + 60_000

		val token = ArcLinkFileToken.sign(secret, "ui/menu.html", playerId, exp)

		assertFalse(ArcLinkFileToken.verify(otherSecret, "ui/menu.html", playerId, exp, token))
	}

	@Test
	fun differentPathIsRejected() {
		val secret = ArcAuthCodec.randomNonce(32)
		val playerId = UUID.randomUUID()
		val exp = System.currentTimeMillis() + 60_000

		val token = ArcLinkFileToken.sign(secret, "ui/menu.html", playerId, exp)

		assertFalse(ArcLinkFileToken.verify(secret, "ui/other.html", playerId, exp, token))
	}

	@Test
	fun differentPlayerIsRejected() {
		val secret = ArcAuthCodec.randomNonce(32)
		val exp = System.currentTimeMillis() + 60_000

		val token = ArcLinkFileToken.sign(secret, "ui/menu.html", UUID.randomUUID(), exp)

		assertFalse(ArcLinkFileToken.verify(secret, "ui/menu.html", UUID.randomUUID(), exp, token))
	}

	@Test
	fun differentExpiryIsRejected() {
		val secret = ArcAuthCodec.randomNonce(32)
		val playerId = UUID.randomUUID()
		val exp = System.currentTimeMillis() + 60_000

		val token = ArcLinkFileToken.sign(secret, "ui/menu.html", playerId, exp)

		assertFalse(ArcLinkFileToken.verify(secret, "ui/menu.html", playerId, exp + 1, token))
	}

	@Test
	fun malformedSignatureIsRejectedNotThrown() {
		val secret = ArcAuthCodec.randomNonce(32)
		val playerId = UUID.randomUUID()
		val exp = System.currentTimeMillis() + 60_000

		assertFalse(ArcLinkFileToken.verify(secret, "ui/menu.html", playerId, exp, "not valid base64!!"))
	}
}
