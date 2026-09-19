package kr.acda.arclink.internal

import org.java_websocket.WebSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Per-connection state, from socket-open through mutual authentication to close. */
class ArcLinkSession(val conn: WebSocket) {
	@Volatile var pendingClientNonce: ByteArray? = null

	@Volatile var authenticated: Boolean = false
	@Volatile var sessionToken: String? = null
	@Volatile var expiresAtEpochMs: Long = 0
	@Volatile var playerId: UUID? = null
	@Volatile var playerName: String? = null

	private val lastMessageAtMillis = AtomicLong(System.currentTimeMillis())

	fun touch() {
		lastMessageAtMillis.set(System.currentTimeMillis())
	}

	fun idleForMillis(): Long = System.currentTimeMillis() - lastMessageAtMillis.get()

	fun isTokenExpired(): Boolean = System.currentTimeMillis() > expiresAtEpochMs

	fun matchesToken(candidate: String?): Boolean =
		authenticated && candidate != null && sessionToken?.let { ArcAuthCodec.tokensEqual(it, candidate) } == true
			&& !isTokenExpired()
}
