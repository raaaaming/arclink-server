package kr.acda.arclink.internal

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kr.acda.arccore.api.module.ModuleLogger
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Serves files over plain HTTP — the data-plane counterpart to [ArcLinkSocketServer]'s
 * WebSocket control-plane (see DECISIONS.md "파일 전송 기능"). Short-lived signed URLs (see
 * [ArcLinkFileToken]) let the client mod's MCEF browser `navigate()` straight to a file instead
 * of the mod reassembling it from WebSocket chunks. Runs on its own thread pool, entirely
 * independent of [ArcLinkSocketServer]'s single-threaded scheduler, so a large transfer can
 * never starve the WebSocket heartbeat.
 *
 * Also exposes a second, sibling route (`/a/<action>`) for MCEF pages that need a synchronous
 * request/response instead of a pushed WS signal — e.g. a page's own `fetch()` calls invoking
 * server-side logic and getting JSON back directly, without any JS↔native bridge inside the
 * client mod (see DECISIONS.md "브라우저 액션 엔드포인트"). It reuses the exact same signed-URL
 * scheme as file serving: [ArcLinkFileToken] doesn't care whether the string it signs is a real
 * relative file path or a bare action name, so no new crypto/verification code was needed.
 */
class ArcLinkFileServer(
	private val config: ArcLinkConfig,
	private val baseDir: Path,
	private val logger: ModuleLogger,
	private val isPlayerOnline: (UUID) -> Boolean,
) {
	private var httpServer: HttpServer? = null
	private val gson = Gson()
	private val actionHandlers = ConcurrentHashMap<String, (UUID, Map<String, String>) -> JsonObject>()

	fun start() {
		Files.createDirectories(baseDir)
		val server = HttpServer.create(InetSocketAddress(config.httpPort), 0)
		server.createContext("/f/") { exchange -> handleFile(exchange) }
		server.createContext("/a/") { exchange -> handleAction(exchange) }
		server.setExecutor(Executors.newCachedThreadPool { r -> Thread(r, "arclink-http").apply { isDaemon = true } })
		server.start()
		httpServer = server
		logger.info("ARCLink file server listening on ${config.httpPort} (base dir: $baseDir)")
	}

	fun stop() {
		httpServer?.stop(0)
		httpServer = null
	}

	/**
	 * True once [start] has completed successfully. [ArcLinkModule] catches a failed [start]
	 * (bad/conflicting `httpPort`) and keeps the module enabled with file transfer disabled —
	 * callers that hand out signed URLs (`sign`/`signAction`) must check this first, since both
	 * of those only do crypto/path-relativization and don't know the HTTP listener never came up
	 * (see ARClinkPlugin DECISIONS.md — 'MCEF 화면이 안 뜬다' 조사).
	 */
	fun isRunning(): Boolean = httpServer != null

	/**
	 * Signs [file] for [playerId], valid for [ttlSeconds]. Returns a path+query URL fragment
	 * (no scheme/host — the caller combines it with the client's known `host` and the
	 * `httpPort` advertised at handshake), or null if [file] doesn't resolve inside [baseDir].
	 */
	fun sign(file: File, playerId: UUID, ttlSeconds: Long): String? {
		val relative = relativize(file) ?: return null
		val expiresAt = System.currentTimeMillis() + ttlSeconds * 1000
		val signature = ArcLinkFileToken.sign(config.presharedKeyBytes(), relative, playerId, expiresAt)
		val encodedPath = relative.split('/').joinToString("/") { segment ->
			URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")
		}
		return "/f/$encodedPath?pid=$playerId&exp=$expiresAt&sig=$signature"
	}

	/** Registers [handler] for GET requests to `/a/<name>`. Last registration per [name] wins. */
	fun registerAction(name: String, handler: (UUID, Map<String, String>) -> JsonObject) {
		actionHandlers[name] = handler
	}

	/**
	 * Signs an action URL for [name], bound to [playerId], valid for [ttlSeconds]. Same shape
	 * and guarantees as [sign], but for [name] (an opaque action identifier, not a file path).
	 */
	fun signAction(name: String, playerId: UUID, ttlSeconds: Long): String {
		val expiresAt = System.currentTimeMillis() + ttlSeconds * 1000
		val signature = ArcLinkFileToken.sign(config.presharedKeyBytes(), actionResource(name), playerId, expiresAt)
		val encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")
		return "/a/$encodedName?pid=$playerId&exp=$expiresAt&sig=$signature"
	}

	// Namespaced so an action name can never collide with a real relative file path signed by
	// [sign] even if they happened to share literal text (defense in depth, not load-bearing —
	// the two routes/handlers are already fully separate).
	private fun actionResource(name: String): String = "action:$name"

	private fun relativize(file: File): String? {
		val base = baseDir.normalize().toAbsolutePath()
		val resolved = file.toPath().normalize().toAbsolutePath()
		if (!resolved.startsWith(base) || resolved == base) return null
		return base.relativize(resolved).toString().replace(File.separatorChar, '/')
	}

	private fun handleFile(exchange: HttpExchange) {
		try {
			if (exchange.requestMethod != "GET") {
				respondEmpty(exchange, 405)
				return
			}

			val relativePath = URLDecoder.decode(
				exchange.requestURI.path.removePrefix("/f/"),
				StandardCharsets.UTF_8,
			)
			val query = parseQuery(exchange.requestURI.rawQuery)
			val playerId = query["pid"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
			val expiresAt = query["exp"]?.toLongOrNull()
			val signature = query["sig"]

			if (playerId == null || expiresAt == null || signature == null ||
				!ArcLinkFileToken.verify(config.presharedKeyBytes(), relativePath, playerId, expiresAt, signature)
			) {
				respondEmpty(exchange, 403)
				return
			}
			if (!isPlayerOnline(playerId)) {
				respondEmpty(exchange, 403)
				return
			}

			val base = baseDir.normalize().toAbsolutePath()
			val target = base.resolve(relativePath).normalize()
			if (!target.startsWith(base) || !Files.isRegularFile(target)) {
				respondEmpty(exchange, 404)
				return
			}

			serveFile(exchange, target)
		} catch (e: Exception) {
			logger.warn("ARCLink file server error: ${e.message}")
			runCatching { respondEmpty(exchange, 500) }
		}
	}

	private fun handleAction(exchange: HttpExchange) {
		try {
			if (exchange.requestMethod != "GET") {
				respondEmpty(exchange, 405)
				return
			}

			val name = URLDecoder.decode(
				exchange.requestURI.path.removePrefix("/a/").substringBefore('/'),
				StandardCharsets.UTF_8,
			)
			val query = parseQuery(exchange.requestURI.rawQuery)
			val playerId = query["pid"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
			val expiresAt = query["exp"]?.toLongOrNull()
			val signature = query["sig"]

			if (playerId == null || expiresAt == null || signature == null ||
				!ArcLinkFileToken.verify(config.presharedKeyBytes(), actionResource(name), playerId, expiresAt, signature)
			) {
				respondJson(exchange, 403, errorJson("forbidden"))
				return
			}
			if (!isPlayerOnline(playerId)) {
				respondJson(exchange, 403, errorJson("player not online"))
				return
			}

			val handler = actionHandlers[name]
			if (handler == null) {
				respondJson(exchange, 404, errorJson("no such action"))
				return
			}

			val body = handler(playerId, query)
			respondJson(exchange, 200, body)
		} catch (e: Exception) {
			logger.warn("ARCLink action server error: ${e.message}")
			runCatching { respondJson(exchange, 500, errorJson("internal error")) }
		}
	}

	private fun errorJson(reason: String): JsonObject = JsonObject().apply {
		addProperty("ok", false)
		addProperty("reason", reason)
	}

	private fun respondJson(exchange: HttpExchange, status: Int, body: JsonObject) {
		val bytes = gson.toJson(body).toByteArray(StandardCharsets.UTF_8)
		exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
		exchange.sendResponseHeaders(status, bytes.size.toLong())
		exchange.responseBody.use { it.write(bytes) }
	}

	private fun serveFile(exchange: HttpExchange, target: Path) {
		val size = Files.size(target)
		exchange.responseHeaders.add("Content-Type", contentTypeOf(target))
		exchange.responseHeaders.add("Accept-Ranges", "bytes")

		val range = exchange.requestHeaders.getFirst("Range")?.let { parseRange(it, size) }
		if (range != null) {
			val (start, endInclusive) = range
			val length = endInclusive - start + 1
			exchange.responseHeaders.add("Content-Range", "bytes $start-$endInclusive/$size")
			exchange.sendResponseHeaders(206, length)
			Files.newInputStream(target).use { input ->
				input.skip(start)
				copyBounded(input, exchange.responseBody, length)
			}
		} else {
			exchange.sendResponseHeaders(200, size)
			Files.newInputStream(target).use { input -> input.copyTo(exchange.responseBody) }
		}
		exchange.responseBody.close()
	}

	private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, length: Long) {
		val buffer = ByteArray(8192)
		var remaining = length
		while (remaining > 0) {
			val toRead = minOf(buffer.size.toLong(), remaining).toInt()
			val read = input.read(buffer, 0, toRead)
			if (read < 0) break
			output.write(buffer, 0, read)
			remaining -= read
		}
	}

	/** Single-range "bytes=start-end" (or open-ended "bytes=start-") requests only. */
	private fun parseRange(header: String, size: Long): Pair<Long, Long>? {
		if (!header.startsWith("bytes=")) return null
		val spec = header.removePrefix("bytes=").substringBefore(',')
		val parts = spec.split('-', limit = 2)
		if (parts.size != 2) return null
		val start = parts[0].toLongOrNull() ?: return null
		val end = parts[1].toLongOrNull() ?: (size - 1)
		if (start < 0 || end < start || start >= size) return null
		return start to minOf(end, size - 1)
	}

	private fun parseQuery(raw: String?): Map<String, String> {
		if (raw.isNullOrEmpty()) return emptyMap()
		return raw.split('&').mapNotNull { part ->
			val idx = part.indexOf('=')
			if (idx < 0) return@mapNotNull null
			val key = URLDecoder.decode(part.substring(0, idx), StandardCharsets.UTF_8)
			val value = URLDecoder.decode(part.substring(idx + 1), StandardCharsets.UTF_8)
			key to value
		}.toMap()
	}

	private fun respondEmpty(exchange: HttpExchange, status: Int) {
		exchange.sendResponseHeaders(status, -1)
		exchange.responseBody.close()
	}

	private fun contentTypeOf(path: Path): String {
		val name = path.fileName.toString().lowercase()
		return when {
			name.endsWith(".html") || name.endsWith(".htm") -> "text/html; charset=utf-8"
			name.endsWith(".css") -> "text/css; charset=utf-8"
			name.endsWith(".js") -> "text/javascript; charset=utf-8"
			name.endsWith(".json") -> "application/json; charset=utf-8"
			name.endsWith(".png") -> "image/png"
			name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
			name.endsWith(".gif") -> "image/gif"
			name.endsWith(".webp") -> "image/webp"
			name.endsWith(".svg") -> "image/svg+xml"
			name.endsWith(".ico") -> "image/x-icon"
			name.endsWith(".woff2") -> "font/woff2"
			name.endsWith(".woff") -> "font/woff"
			else -> Files.probeContentType(path) ?: "application/octet-stream"
		}
	}
}
