package kr.acda.arclink

import com.google.gson.JsonObject
import kr.acda.arccore.api.module.ModuleSpec
import kr.acda.arclink.internal.ArcLinkConfig
import kr.acda.arclink.internal.ArcLinkFileServer
import kr.acda.arclink.internal.ArcLinkSignal
import kr.acda.arclink.internal.ArcLinkSignalRegistry
import kr.acda.arclink.internal.ArcLinkSocketServer
import kr.acda.arclink.internal.ArcLinkStatusCommand
import kr.acda.arclink.internal.ProtocolVersion
import kr.acda.arccore.runtime.context.BaseRuntimeModule
import org.bukkit.Bukkit
import java.io.File
import java.util.UUID

/**
 * ARCLink server plugin (module) — see CLAUDE.md for the full protocol spec
 * this implements the server half of. Opens a dedicated WebSocket port
 * (independent of the Minecraft port and of plugin-message channels) and
 * runs the connection lifecycle against every client mod instance that
 * connects: mutual challenge-response handshake, version negotiation,
 * heartbeat/idle reaping, reconnect-friendly per-connection state.
 *
 * Also opens a second, separate HTTP port for file transfer (see DECISIONS.md
 * "파일 전송 기능") — the WebSocket stays the control-plane for small signals,
 * the HTTP server is the data-plane for HTML/images the client mod's MCEF
 * browser can navigate to directly, and (see DECISIONS.md "브라우저 액션
 * 엔드포인트") for signed action endpoints an MCEF page's own `fetch()` calls
 * can hit directly for request/response-style server logic.
 */
@ModuleSpec(
	id = "arclink",
	name = "ARCLink",
	version = "1.0.0",
	description = "Dedicated socket bridge to the ARCLink client mod for server-pushed client UI/features",
	authors = ["raaaaming"],
	// Gson is on the server's JVM classpath (Paper/arc-core use it internally) but ARCCore's
	// module classloader isolation hides it from modules unless declared here explicitly —
	// omitting it crashes onEnable with NoClassDefFoundError: com/google/gson/GsonBuilder.
	// com.sun.net.httpserver (ArcLinkFileServer) is a JDK platform package, not an application
	// classpath artifact, so it needs no `libraries` entry here. It DID still need a fix on the
	// ARCCore side, though: SharedClassFilter.default() trusted "sun." but not "com.sun.", so
	// this class was rejected at runtime with NoClassDefFoundError ("hidden by module
	// visibility policy") until that was added (see ARCCore DECISIONS.md #2).
	libraries = ["org.java-websocket:Java-WebSocket:1.6.0", "com.google.code.gson:gson:2.11.0"],
	exports = ["kr.acda.arclink"],
)
class ArcLinkModule : BaseRuntimeModule() {

	private val ownVersion = ProtocolVersion(1, 0, 0)
	private val signals = ArcLinkSignalRegistry()
	private var server: ArcLinkSocketServer? = null

	override fun onEnable() {
		super.onEnable()

		val config = ArcLinkConfig.loadOrCreate(dataFolder.resolve("config.json"), logger)
		if (config.httpPort == config.port) {
			logger.warn(
				"ARCLink httpPort (${config.httpPort}) equals the WebSocket port — the file " +
					"server will fail to bind. Set a different httpPort in config.json.",
			)
		}

		val srv = ArcLinkSocketServer(
			config = config,
			ownVersion = ownVersion,
			logger = logger,
			mainThread = { task -> scheduler.runSync(task) },
			findPlayer = { name -> Bukkit.getPlayerExact(name)?.uniqueId },
			signals = signals,
		)
		server = srv
		context.cleanupScope.onClose { srv.stop() }
		srv.start()

		val fileBaseDirPath = dataFolder.resolve(config.fileBaseDir)
		val fileServer = ArcLinkFileServer(
			config = config,
			baseDir = fileBaseDirPath,
			logger = logger,
			isPlayerOnline = { id -> Bukkit.getPlayer(id) != null },
		)
		try {
			fileServer.start()
			context.cleanupScope.onClose { fileServer.stop() }
		} catch (e: Exception) {
			// Optional subsystem — a bad httpPort shouldn't take the WebSocket side down with it.
			logger.error("ARCLink file server failed to start — file transfer disabled", e)
		}

		// 10s idle check, comfortably below the default 30s idleTimeoutSeconds.
		scheduler.runAsyncRepeating(200L, 200L) { srv.reapIdleSessions() }

		commands.register(ArcLinkStatusCommand(config.port) { server })

		services.register(ArcLinkService::class, object : ArcLinkService {
			override fun isConnected(playerId: UUID) = srv.isConnected(playerId)
			override fun send(playerId: UUID, type: String, payload: JsonObject) = srv.send(playerId, type, payload)
			override fun onSignal(type: String, handler: ArcLinkSignal) = signals.register(type, handler)
			override fun serveFile(playerId: UUID, file: File, ttlSeconds: Long): String? {
				// fileServer.isRunning()도 함께 확인해야 한다 — start()가 실패해도(포트 충돌 등)
				// 모듈 자체는 계속 활성화되고(89번째 줄 근처 catch 참고), sign()은 그 사실을 몰라서
				// 실제로는 아무도 듣고 있지 않은 URL을 계속 서명해줄 수 있다 — 그러면 호출자(예:
				// arcshop)는 성공한 줄 알고 MCEF에 open_browser를 보내지만 클라이언트는 연결 거부만
				// 겪는다("MCEF 화면이 안 뜬다" 조사 중 발견, DECISIONS.md 참고).
				if (!srv.isConnected(playerId) || !fileServer.isRunning()) return null
				return fileServer.sign(file, playerId, ttlSeconds)
			}
			override fun fileBaseDir(): File = fileBaseDirPath.toFile()
			override fun onHttpAction(action: String, handler: (UUID, Map<String, String>) -> JsonObject) =
				fileServer.registerAction(action, handler)
			override fun signAction(playerId: UUID, action: String, ttlSeconds: Long): String? {
				if (!srv.isConnected(playerId) || !fileServer.isRunning()) return null
				return fileServer.signAction(action, playerId, ttlSeconds)
			}
		})

		logger.info("ARCLink enabled")
	}

	override fun onDisable() {
		services.unregister(ArcLinkService::class)
		server = null
		logger.info("ARCLink disabled")
		super.onDisable()
	}
}
