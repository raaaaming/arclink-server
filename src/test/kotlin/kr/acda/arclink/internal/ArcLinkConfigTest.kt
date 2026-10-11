package kr.acda.arclink.internal

import kr.acda.arccore.api.module.ModuleLogger
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class NoopLogger : ModuleLogger {
	override fun info(message: String) {}
	override fun warn(message: String) {}
	override fun error(message: String, throwable: Throwable?) {}
	override fun debug(message: String) {}
	override fun trace(message: String) {}
	override fun withPrefix(prefix: String): ModuleLogger = this
}

class ArcLinkConfigTest {

	@Test
	fun firstRunGeneratesAndPersistsAKey() {
		val dir = Files.createTempDirectory("arclink-config-test")
		val path = dir.resolve("config.json")

		val created = ArcLinkConfig.loadOrCreate(path, NoopLogger())
		assertTrue(created.presharedKeyHex.isNotBlank())
		assertTrue(Files.exists(path))

		val reloaded = ArcLinkConfig.loadOrCreate(path, NoopLogger())
		assertEquals(created.presharedKeyHex, reloaded.presharedKeyHex)
		assertEquals(created.port, reloaded.port)
	}

	@Test
	fun missingOptionalFieldsFallBackToDefaultsNotZero() {
		val dir = Files.createTempDirectory("arclink-config-test")
		val path = dir.resolve("config.json")
		Files.writeString(path, """{"presharedKeyHex":"aabbcc"}""")

		val loaded = ArcLinkConfig.loadOrCreate(path, NoopLogger())

		assertEquals(ArcLinkConfig().port, loaded.port)
		assertEquals(ArcLinkConfig().idleTimeoutSeconds, loaded.idleTimeoutSeconds)
		assertEquals(ArcLinkConfig().sessionTokenTtlSeconds, loaded.sessionTokenTtlSeconds)
		assertEquals(ArcLinkConfig().httpPort, loaded.httpPort)
		assertEquals(ArcLinkConfig().fileBaseDir, loaded.fileBaseDir)
		assertEquals(ArcLinkConfig().fileTokenTtlSeconds, loaded.fileTokenTtlSeconds)
	}

	@Test
	fun explicitFileServerFieldsAreRespected() {
		val dir = Files.createTempDirectory("arclink-config-test")
		val path = dir.resolve("config.json")
		Files.writeString(
			path,
			"""{"presharedKeyHex":"aabbcc","httpPort":9000,"fileBaseDir":"assets","fileTokenTtlSeconds":60}""",
		)

		val loaded = ArcLinkConfig.loadOrCreate(path, NoopLogger())

		assertEquals(9000, loaded.httpPort)
		assertEquals("assets", loaded.fileBaseDir)
		assertEquals(60L, loaded.fileTokenTtlSeconds)
	}
}
