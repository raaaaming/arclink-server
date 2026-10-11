package kr.acda.arclink.internal

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import kr.acda.arccore.api.module.ModuleLogger
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TYPE_AUTH_CHALLENGE = "auth_challenge"
private const val TYPE_AUTH_RESPONSE = "auth_response"
private const val TYPE_AUTH_RESULT = "auth_result"
private const val TYPE_PING = "ping"
private const val TYPE_PONG = "pong"
private const val TYPE_RESYNC_REQUEST = "resync_request"
private const val TYPE_SERVER_MESSAGE = "server_message"

/**
 * The ARCLink socket server: a dedicated WebSocket port (not the Minecraft port,
 * not a plugin-message channel — see CLAUDE.md §1) that runs the connection
 * lifecycle from CLAUDE.md §3 against every connecting client mod instance:
 * mutual challenge-response handshake, version negotiation, heartbeat/idle
 * reaping, and per-player signal dispatch.
 *
 * One session == one authenticated player. Binding happens during the
 * handshake: the client sends its Minecraft username in `auth_response`,
 * and [findPlayer] resolves that to the connected [org.bukkit.entity.Player]'s
 * UUID (matched by name so this works on both online- and offline-mode
 * servers, where UUIDs aren't computed the same way).
 */
class ArcLinkSocketServer(
	private val config: ArcLinkConfig,
	private val ownVersion: ProtocolVersion,
	private val logger: ModuleLogger,
	private val mainThread: (Runnable) -> Unit,
	private val findPlayer: (String) -> UUID?,
	private val signals: ArcLinkSignalRegistry,
) : WebSocketServer(InetSocketAddress(config.port)) {

	private val gson = Gson()
	private val sessionsByConn = ConcurrentHashMap<WebSocket, ArcLinkSession>()
	private val sessionsByPlayer = ConcurrentHashMap<UUID, ArcLinkSession>()

	fun isConnected(playerId: UUID): Boolean = sessionsByPlayer[playerId]?.authenticated == true

	fun authenticatedCount(): Int = sessionsByPlayer.size

	fun send(playerId: UUID, type: String, payload: JsonObject = JsonObject()) {
		val session = sessionsByPlayer[playerId] ?: return
		if (!session.authenticated) return
		val message = WireMessage.create(type, ownVersion.toString(), payload)
		session.conn.send(message.toJson(gson))
	}

	/** Closes any session idle longer than [ArcLinkConfig.idleTimeoutSeconds]; call periodically. */
	fun reapIdleSessions() {
		val limitMs = config.idleTimeoutSeconds * 1000
		for (session in sessionsByConn.values.toList()) {
			if (session.idleForMillis() > limitMs) {
				logger.warn("closing idle ARCLink session (player=${session.playerName})")
				session.conn.close(1000, "idle timeout")
			}
		}
	}

	override fun onStart() {
		logger.info("ARCLink socket listening on port ${address.port}")
	}

	override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
		val session = ArcLinkSession(conn)
		sessionsByConn[conn] = session

		val nonce = ArcAuthCodec.randomNonce(24)
		session.pendingClientNonce = nonce
		val payload = JsonObject().apply { addProperty("nonce", Base64.getEncoder().encodeToString(nonce)) }
		conn.send(WireMessage.create(TYPE_AUTH_CHALLENGE, ownVersion.toString(), payload).toJson(gson))
	}

	override fun onMessage(conn: WebSocket, raw: String) {
		val session = sessionsByConn[conn] ?: return
		session.touch()

		val message = try {
			WireMessage.parse(gson, raw)
		} catch (e: JsonParseException) {
			logger.warn("dropping malformed ARCLink message: ${e.message}")
			return
		}

		when (message.type) {
			TYPE_AUTH_RESPONSE -> handleAuthResponse(session, message)
			TYPE_PING -> if (requireValidSession(session, message)) {
				conn.send(WireMessage.create(TYPE_PONG, ownVersion.toString(), JsonObject()).toJson(gson))
			}
			TYPE_RESYNC_REQUEST -> if (requireValidSession(session, message)) handleResync(session)
			else -> if (requireValidSession(session, message)) {
				val playerId = session.playerId ?: return
				mainThread(Runnable {
					if (!signals.dispatch(playerId, message)) {
						logger.warn("no handler registered for inbound type '${message.type}'")
					}
				})
			}
		}
	}

	override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
		val session = sessionsByConn.remove(conn) ?: return
		session.playerId?.let { sessionsByPlayer.remove(it, session) }
		logger.info("ARCLink session closed (player=${session.playerName}, code=$code, reason=$reason)")
	}

	override fun onError(conn: WebSocket?, ex: Exception) {
		logger.error("ARCLink socket error", ex)
	}

	private fun handleAuthResponse(session: ArcLinkSession, message: WireMessage) {
		val remoteVersion = try {
			ProtocolVersion.parse(message.version)
		} catch (e: IllegalArgumentException) {
			rejectAuth(session, "unparsable protocol version '${message.version}'")
			return
		}
		val negotiation = ownVersion.negotiate(remoteVersion)
		if (!negotiation.compatible) {
			rejectAuth(session, negotiation.reason ?: "incompatible protocol version")
			return
		}

		val nonce = session.pendingClientNonce
		val signature = message.payload.get("signature")?.asString
		val clientNonceB64 = message.payload.get("clientNonce")?.asString
		val playerName = message.payload.get("playerName")?.asString
		if (nonce == null || signature == null || clientNonceB64 == null || playerName == null) {
			rejectAuth(session, "auth_response missing required field(s)")
			return
		}
		if (!ArcAuthCodec.verify(config.presharedKeyBytes(), nonce, signature)) {
			rejectAuth(session, "signature verification failed")
			return
		}
		val playerId = findPlayer(playerName)
		if (playerId == null) {
			rejectAuth(session, "no online player named '$playerName'")
			return
		}

		val clientNonce = Base64.getDecoder().decode(clientNonceB64)
		val serverProof = ArcAuthCodec.sign(config.presharedKeyBytes(), clientNonce)
		val token = Base64.getEncoder().encodeToString(ArcAuthCodec.randomNonce(32))
		val expiresAt = System.currentTimeMillis() + config.sessionTokenTtlSeconds * 1000

		session.pendingClientNonce = null
		session.authenticated = true
		session.sessionToken = token
		session.expiresAtEpochMs = expiresAt
		session.playerId = playerId
		session.playerName = playerName
		sessionsByPlayer[playerId] = session

		val payload = JsonObject().apply {
			addProperty("ok", true)
			addProperty("sessionToken", token)
			addProperty("serverProof", serverProof)
			addProperty("expiresAtEpochMs", expiresAt)
			// Optional field (CLAUDE.md 4.1: additive-only) — lets a future client build file URLs
			// from its already-known `host` without a separate config entry. Older clients ignore it.
			addProperty("httpPort", config.httpPort)
		}
		session.conn.send(WireMessage.create(TYPE_AUTH_RESULT, ownVersion.toString(), payload).toJson(gson))
		logger.info("ARCLink authenticated player '$playerName'")
	}

	private fun rejectAuth(session: ArcLinkSession, reason: String) {
		val payload = JsonObject().apply {
			addProperty("ok", false)
			addProperty("reason", reason)
		}
		session.conn.send(WireMessage.create(TYPE_AUTH_RESULT, ownVersion.toString(), payload).toJson(gson))
		logger.warn("ARCLink auth rejected: $reason")
		session.conn.close(4001, reason)
	}

	private fun requireValidSession(session: ArcLinkSession, message: WireMessage): Boolean {
		if (session.matchesToken(message.sessionToken)) return true
		logger.warn("dropping '${message.type}' — missing/expired/forged session token")
		return false
	}

	private fun handleResync(session: ArcLinkSession) {
		val payload = JsonObject().apply { addProperty("text", "ARCLink connected.") }
		session.conn.send(WireMessage.create(TYPE_SERVER_MESSAGE, ownVersion.toString(), payload).toJson(gson))
	}
}
