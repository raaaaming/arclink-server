package kr.acda.arclink

import com.google.gson.JsonObject
import kr.acda.arclink.internal.ArcLinkSignal
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
}
