package kr.acda.arclink.internal

import kr.acda.arccore.api.module.ModuleLogger
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class _NoopLogger : ModuleLogger {
	override fun info(message: String) {}
	override fun warn(message: String) {}
	override fun error(message: String, throwable: Throwable?) {}
	override fun debug(message: String) {}
	override fun trace(message: String) {}
	override fun withPrefix(prefix: String): ModuleLogger = this
}

/**
 * [ArcLinkFileServer.signAction] reuses [ArcLinkFileToken] (already exhaustively tested in
 * [ArcLinkFileTokenTest]) with the `"action:<name>"` resource convention documented on
 * [ArcLinkFileServer] — these tests lock in that convention and the URL shape against the real
 * production class, not a reimplementation. A live HTTP round trip (request → 200/403/404) is
 * deliberately not covered here, matching [ArcLinkFileServer]'s existing GET file route, which
 * also has no HTTP-level integration test yet (DECISIONS.md "남은 일").
 */
class ArcLinkFileServerActionTest {

	private fun server(secretHex: String): ArcLinkFileServer {
		val dir = Files.createTempDirectory("arclink-action-test")
		val config = ArcLinkConfig(presharedKeyHex = secretHex)
		return ArcLinkFileServer(config = config, baseDir = dir, logger = _NoopLogger(), isPlayerOnline = { true })
	}

	private fun queryOf(url: String): Map<String, String> =
		url.substringAfter('?').split('&').associate {
			val (k, v) = it.split('=', limit = 2)
			k to v
		}

	@Test
	fun signedActionUrlHasExpectedShapeAndVerifies() {
		val secretHex = "bb".repeat(32)
		val fileServer = server(secretHex)
		val config = ArcLinkConfig(presharedKeyHex = secretHex)
		val playerId = UUID.randomUUID()

		val url = fileServer.signAction("arcshop", playerId, 60)

		assertTrue(url.startsWith("/a/arcshop?"))
		val query = queryOf(url)
		assertEquals(playerId.toString(), query["pid"])
		val exp = query["exp"]!!.toLong()
		val sig = query["sig"]!!

		assertTrue(ArcLinkFileToken.verify(config.presharedKeyBytes(), "action:arcshop", playerId, exp, sig))
	}

	@Test
	fun differentActionNameFailsVerification() {
		val secretHex = "cc".repeat(32)
		val fileServer = server(secretHex)
		val config = ArcLinkConfig(presharedKeyHex = secretHex)
		val playerId = UUID.randomUUID()

		val url = fileServer.signAction("arcshop", playerId, 60)
		val query = queryOf(url)
		val exp = query["exp"]!!.toLong()
		val sig = query["sig"]!!

		assertFalse(ArcLinkFileToken.verify(config.presharedKeyBytes(), "action:some_other_feature", playerId, exp, sig))
	}

	@Test
	fun actionNameIsUrlEncodedInThePath() {
		val fileServer = server("dd".repeat(32))
		val url = fileServer.signAction("needs encoding", UUID.randomUUID(), 60)

		assertTrue(url.startsWith("/a/needs%20encoding?"))
	}

	@Test
	fun expiredActionTokenFailsVerification() {
		val secretHex = "ee".repeat(32)
		val fileServer = server(secretHex)
		val config = ArcLinkConfig(presharedKeyHex = secretHex)
		val playerId = UUID.randomUUID()

		// ttlSeconds negative -> already-expired token, still well-formed.
		val url = fileServer.signAction("arcshop", playerId, -60)
		val query = queryOf(url)
		val exp = query["exp"]!!.toLong()
		val sig = query["sig"]!!

		assertFalse(ArcLinkFileToken.verify(config.presharedKeyBytes(), "action:arcshop", playerId, exp, sig))
	}
}
