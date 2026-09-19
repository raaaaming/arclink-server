package kr.acda.arclink.internal

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WireMessageTest {
	private val gson = Gson()

	@Test
	fun roundTripsThroughJson() {
		val payload = JsonObject().apply { addProperty("text", "hello") }
		val original = WireMessage.create("server_message", "1.0.0", payload).withSessionToken("tok-123")

		val parsed = WireMessage.parse(gson, original.toJson(gson))

		assertEquals(original.type, parsed.type)
		assertEquals(original.version, parsed.version)
		assertEquals(original.id, parsed.id)
		assertEquals(original.sessionToken, parsed.sessionToken)
		assertEquals("hello", parsed.payload.get("text").asString)
	}

	@Test
	fun missingPayloadNormalizesToEmptyObjectInsteadOfNull() {
		val parsed = WireMessage.parse(gson, """{"type":"ping","version":"1.0.0"}""")

		assertEquals(0, parsed.payload.size())
	}

	@Test
	fun rejectsMessageMissingType() {
		assertFailsWith<JsonParseException> { WireMessage.parse(gson, """{"version":"1.0.0"}""") }
	}

	@Test
	fun rejectsGarbageJson() {
		assertFailsWith<JsonParseException> { WireMessage.parse(gson, "{not valid json") }
	}
}
