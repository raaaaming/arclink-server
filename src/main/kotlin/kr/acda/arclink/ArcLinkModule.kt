package kr.acda.arclink

import com.google.gson.JsonObject
import kr.acda.arccore.api.module.ModuleSpec
import kr.acda.arclink.internal.ArcLinkConfig
import kr.acda.arclink.internal.ArcLinkSignal
import kr.acda.arclink.internal.ArcLinkSignalRegistry
import kr.acda.arclink.internal.ArcLinkSocketServer
import kr.acda.arclink.internal.ArcLinkStatusCommand
import kr.acda.arclink.internal.ProtocolVersion
import kr.acda.arccore.runtime.context.BaseRuntimeModule
import org.bukkit.Bukkit
import java.util.UUID

/**
 * ARCLink server plugin (module) — see CLAUDE.md for the full protocol spec
 * this implements the server half of. Opens a dedicated WebSocket port
 * (independent of the Minecraft port and of plugin-message channels) and
 * runs the connection lifecycle against every client mod instance that
 * connects: mutual challenge-response handshake, version negotiation,
 * heartbeat/idle reaping, reconnect-friendly per-connection state.
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

		// 10s idle check, comfortably below the default 30s idleTimeoutSeconds.
		scheduler.runAsyncRepeating(200L, 200L) { srv.reapIdleSessions() }

		commands.register(ArcLinkStatusCommand(config.port) { server })

		services.register(ArcLinkService::class, object : ArcLinkService {
			override fun isConnected(playerId: UUID) = srv.isConnected(playerId)
			override fun send(playerId: UUID, type: String, payload: JsonObject) = srv.send(playerId, type, payload)
			override fun onSignal(type: String, handler: ArcLinkSignal) = signals.register(type, handler)
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
