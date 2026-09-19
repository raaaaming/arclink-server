package kr.acda.arclink.internal

import com.google.gson.GsonBuilder
import kr.acda.arccore.api.module.ModuleLogger
import java.nio.file.Files
import java.nio.file.Path
import java.util.HexFormat

/**
 * Plain JSON config at `<module data>/config.json`. The pre-shared secret is
 * generated locally on first run (never hardcoded) — copy the printed hex
 * string into the client mod's `config/arclink.json` to pair them.
 */
data class ArcLinkConfig(
	var port: Int = 25566,
	var presharedKeyHex: String = "",
	var sessionTokenTtlSeconds: Long = 1800,
	var idleTimeoutSeconds: Long = 30,
) {
	fun presharedKeyBytes(): ByteArray = HexFormat.of().parseHex(presharedKeyHex)

	// Gson instantiates via Unsafe allocation, bypassing the constructor and these Kotlin
	// default values entirely — a hand-edited config.json missing a field would otherwise
	// silently zero it out (e.g. idleTimeoutSeconds=0) instead of falling back to the default.
	private data class Raw(
		val port: Int? = null,
		val presharedKeyHex: String? = null,
		val sessionTokenTtlSeconds: Long? = null,
		val idleTimeoutSeconds: Long? = null,
	)

	companion object {
		private val GSON = GsonBuilder().setPrettyPrinting().create()

		fun loadOrCreate(path: Path, logger: ModuleLogger): ArcLinkConfig {
			if (Files.exists(path)) {
				val raw = Files.newBufferedReader(path).use { reader -> GSON.fromJson(reader, Raw::class.java) }
				if (raw?.presharedKeyHex?.isNotBlank() == true) {
					val defaults = ArcLinkConfig()
					return ArcLinkConfig(
						port = raw.port ?: defaults.port,
						presharedKeyHex = raw.presharedKeyHex,
						sessionTokenTtlSeconds = raw.sessionTokenTtlSeconds ?: defaults.sessionTokenTtlSeconds,
						idleTimeoutSeconds = raw.idleTimeoutSeconds ?: defaults.idleTimeoutSeconds,
					)
				}
			}

			val fresh = ArcLinkConfig(presharedKeyHex = HexFormat.of().formatHex(ArcAuthCodec.randomNonce(32)))
			save(path, fresh)
			logger.warn(
				"generated new config at $path — copy 'presharedKeyHex' into each client mod's " +
					"config/arclink.json before it will authenticate.",
			)
			return fresh
		}

		private fun save(path: Path, config: ArcLinkConfig) {
			Files.createDirectories(path.parent)
			Files.newBufferedWriter(path).use { writer -> GSON.toJson(config, writer) }
		}
	}
}
