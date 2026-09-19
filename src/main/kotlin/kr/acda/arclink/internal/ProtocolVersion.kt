package kr.acda.arclink.internal

/**
 * Semantic version for the ARCLink wire protocol (mirrors the client mod's
 * `com.arclink.protocol.ProtocolVersion` — both sides must agree on this rule).
 * Golden rule: MAJOR mismatch => reject, otherwise degrade to the lower MINOR.
 */
data class ProtocolVersion(val major: Int, val minor: Int, val patch: Int) {

	data class Negotiation(val compatible: Boolean, val negotiatedMinor: Int, val reason: String?)

	fun negotiate(other: ProtocolVersion): Negotiation {
		if (major != other.major) {
			return Negotiation(
				false, 0,
				"Incompatible ARCLink protocol: local v$this vs remote v$other — MAJOR version differs, " +
					"update the mod or the server plugin.",
			)
		}
		return Negotiation(true, minOf(minor, other.minor), null)
	}

	override fun toString(): String = "$major.$minor.$patch"

	companion object {
		fun parse(text: String): ProtocolVersion {
			val parts = text.trim().split(".")
			require(parts.size == 3) { "Not a MAJOR.MINOR.PATCH version: $text" }
			val (maj, min, pat) = try {
				Triple(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
			} catch (e: NumberFormatException) {
				throw IllegalArgumentException("Not a MAJOR.MINOR.PATCH version: $text", e)
			}
			return ProtocolVersion(maj, min, pat)
		}
	}
}
