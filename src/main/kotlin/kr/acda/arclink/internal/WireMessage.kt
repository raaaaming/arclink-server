package kr.acda.arclink.internal

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import java.util.UUID

/**
 * Wire envelope for every ARCLink signal — must byte-for-byte match the field
 * names the client mod's `com.arclink.protocol.ArcLinkMessage` sends/expects.
 *
 * Gson builds instances via unsafe allocation (bypasses the constructor and any
 * Kotlin default-parameter values), so [parse] goes through a nullable-field
 * [Raw] shape first and normalizes explicitly rather than trusting defaults.
 */
data class WireMessage(
	val type: String,
	val version: String,
	val id: String?,
	val sessionToken: String?,
	val payload: JsonObject,
) {
	fun toJson(gson: Gson): String = gson.toJson(this)

	fun withSessionToken(token: String?): WireMessage = copy(sessionToken = token)

	companion object {
		private data class Raw(
			val type: String? = null,
			val version: String? = null,
			val id: String? = null,
			val sessionToken: String? = null,
			val payload: JsonObject? = null,
		)

		fun create(type: String, version: String, payload: JsonObject = JsonObject()): WireMessage =
			WireMessage(type, version, UUID.randomUUID().toString(), null, payload)

		fun parse(gson: Gson, json: String): WireMessage {
			val raw = try {
				gson.fromJson(json, Raw::class.java)
			} catch (e: RuntimeException) {
				throw JsonParseException("Malformed ARCLink message", e)
			}
			if (raw == null || raw.type == null || raw.version == null) {
				throw JsonParseException("ARCLink message missing required 'type'/'version': $json")
			}
			return WireMessage(raw.type, raw.version, raw.id, raw.sessionToken, raw.payload ?: JsonObject())
		}
	}
}
