package kr.acda.arclink.internal

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * Short-lived, signed URLs for [ArcLinkFileServer]: HMAC-SHA256 over
 * `relativePath|playerId|expiresAtEpochMs`, reusing the same pre-shared secret and primitive
 * as the WebSocket handshake (`ArcAuthCodec.hmac`) — no new algorithm, no new dependency.
 * Signatures are URL-safe (unpadded) Base64 so they drop straight into a query string with no
 * extra escaping, unlike `ArcAuthCodec.sign`'s standard Base64 (used only on the WS channel,
 * inside a JSON string, where URL-safety doesn't matter).
 */
object ArcLinkFileToken {
	private val URL_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
	private val URL_DECODER: Base64.Decoder = Base64.getUrlDecoder()

	fun sign(secret: ByteArray, relativePath: String, playerId: UUID, expiresAtEpochMs: Long): String =
		URL_ENCODER.encodeToString(ArcAuthCodec.hmac(secret, message(relativePath, playerId, expiresAtEpochMs)))

	/** Constant-time signature check; also rejects an already-expired [expiresAtEpochMs]. */
	fun verify(
		secret: ByteArray,
		relativePath: String,
		playerId: UUID,
		expiresAtEpochMs: Long,
		signature: String,
	): Boolean {
		if (System.currentTimeMillis() > expiresAtEpochMs) return false
		val expected = ArcAuthCodec.hmac(secret, message(relativePath, playerId, expiresAtEpochMs))
		val actual = try {
			URL_DECODER.decode(signature)
		} catch (e: IllegalArgumentException) {
			return false
		}
		return MessageDigest.isEqual(expected, actual)
	}

	private fun message(relativePath: String, playerId: UUID, expiresAtEpochMs: Long): ByteArray =
		"$relativePath|$playerId|$expiresAtEpochMs".toByteArray(StandardCharsets.UTF_8)
}
