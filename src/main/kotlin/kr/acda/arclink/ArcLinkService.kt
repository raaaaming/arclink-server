package kr.acda.arclink

import com.google.gson.JsonObject
import kr.acda.arclink.internal.ArcLinkSignal
import java.io.File
import java.util.UUID

/**
 * Public service other ARCCore modules use to push signals to a specific
 * player's ARCLink client mod, or react to signals the client sends back.
 * Register a dependency on the `arclink` module and consume via:
 *
 * ```kotlin
 * val arclink = context.api.serviceRegistry.require(ArcLinkService::class)
 * arclink.send(playerId, "my_feature", JsonObject().apply { addProperty("key", "value") })
 * ```
 */
interface ArcLinkService {
	fun isConnected(playerId: UUID): Boolean

	fun send(playerId: UUID, type: String, payload: JsonObject = JsonObject())

	/** Registers a handler for a client-originated signal `type`. Last registration wins. */
	fun onSignal(type: String, handler: ArcLinkSignal)

	/**
	 * Signs a short-lived URL (path + query only, no scheme/host) for [file], which must
	 * resolve inside the configured file base directory ([fileBaseDir]). The caller embeds
	 * this in whatever signal it sends via [send] — this service knows nothing about MCEF or
	 * how the client uses the URL, only how to sign and serve it (see DECISIONS.md
	 * "파일 전송 기능"). The client combines the returned path with its already-configured
	 * `host` and the `httpPort` advertised in the handshake's `auth_result` to form the full URL.
	 *
	 * Returns null if [file] falls outside the file base directory, or if [playerId] isn't
	 * currently authenticated.
	 */
	fun serveFile(playerId: UUID, file: File, ttlSeconds: Long = 300): String?

	/**
	 * The directory [serveFile] resolves [File] arguments against (`<this module's data
	 * folder>/<ArcLinkConfig.fileBaseDir>`, default `files/`). Callers write their own files
	 * somewhere under here — in their own subdirectory, to avoid colliding with another
	 * module's files — before signing them with [serveFile].
	 */
	fun fileBaseDir(): File

	/**
	 * Registers [handler] for GET requests to the action endpoint named [action]
	 * (`/a/<action>?pid=...&exp=...&sig=...`, signed per-player via [signAction]). The
	 * HTTP-plane request/response counterpart to [onSignal]/[send] — for an MCEF page that
	 * needs a synchronous result (e.g. its own `fetch()` call) instead of a pushed signal, with
	 * no JS↔native bridge required inside the client mod at all: the page just calls the signed
	 * URL like any other web request.
	 *
	 * [handler] receives the requesting (already-authenticated) player and the full query
	 * string as a map (including `pid`/`exp`/`sig`) and must return a JSON object to serialize
	 * as the response body. It runs on an HTTP worker thread — never the server main thread —
	 * so hop yourself (e.g. via your module's scheduler) for any Bukkit API access. Last
	 * registration per [action] wins.
	 */
	fun onHttpAction(action: String, handler: (playerId: UUID, query: Map<String, String>) -> JsonObject)

	/**
	 * Signs a short-lived action URL (path + query only) for [action], bound to [playerId],
	 * valid for [ttlSeconds]. Combine with the client's `host`/`httpPort` exactly like
	 * [serveFile]'s return value. Returns null if [playerId] isn't currently authenticated.
	 */
	fun signAction(playerId: UUID, action: String, ttlSeconds: Long = 300): String?
}
