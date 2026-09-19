package kr.acda.arclink.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtocolVersionTest {

	@Test
	fun sameMajorDegradesToLowerMinor() {
		val local = ProtocolVersion(1, 4, 0)
		val remote = ProtocolVersion(1, 2, 7)

		val result = local.negotiate(remote)

		assertTrue(result.compatible)
		assertEquals(2, result.negotiatedMinor)
	}

	@Test
	fun differingMajorIsRejectedWithUserFacingReason() {
		val local = ProtocolVersion(1, 0, 0)
		val remote = ProtocolVersion(2, 0, 0)

		val result = local.negotiate(remote)

		assertFalse(result.compatible)
		assertTrue(result.reason!!.contains("MAJOR"))
	}

	@Test
	fun parseRoundTrip() {
		assertEquals(ProtocolVersion(1, 2, 3), ProtocolVersion.parse("1.2.3"))
		assertEquals("1.2.3", ProtocolVersion(1, 2, 3).toString())
	}

	@Test
	fun parseRejectsMalformedInput() {
		kotlin.test.assertFailsWith<IllegalArgumentException> { ProtocolVersion.parse("1.2") }
	}
}
