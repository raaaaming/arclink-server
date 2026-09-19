package kr.acda.arclink.internal

import com.google.gson.JsonObject
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArcLinkSignalRegistryTest {

	@Test
	fun dispatchesToRegisteredHandlerByType() {
		val registry = ArcLinkSignalRegistry()
		var received: String? = null
		registry.register("greet") { _, message -> received = message.payload.get("who").asString }

		val payload = JsonObject().apply { addProperty("who", "world") }
		val playerId = UUID.randomUUID()
		val handled = registry.dispatch(playerId, WireMessage.create("greet", "1.0.0", payload))

		assertTrue(handled)
		assertEquals("world", received)
	}

	@Test
	fun unknownTypeIsReportedUnhandled() {
		val registry = ArcLinkSignalRegistry()

		val handled = registry.dispatch(UUID.randomUUID(), WireMessage.create("nope", "1.0.0", JsonObject()))

		assertFalse(handled)
	}
}
