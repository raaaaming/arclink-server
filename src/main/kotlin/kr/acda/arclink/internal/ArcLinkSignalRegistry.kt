package kr.acda.arclink.internal

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A single executable client-originated signal (mirrors the client mod's
 * command-pattern `Command`/`CommandRegistry`; named `Signal` here, not
 * `Command`, so it doesn't collide with ARCCore's own `ARCCommand` chat-command
 * framework used for `/arclink`).
 */
fun interface ArcLinkSignal {
	fun handle(playerId: UUID, message: WireMessage)
}

/** Maps a message's `type` tag to the [ArcLinkSignal] that handles it. */
class ArcLinkSignalRegistry {
	private val handlers = ConcurrentHashMap<String, ArcLinkSignal>()

	fun register(type: String, signal: ArcLinkSignal) {
		handlers[type] = signal
	}

	/** @return true if a handler was found and run for `message.type`. */
	fun dispatch(playerId: UUID, message: WireMessage): Boolean {
		val handler = handlers[message.type] ?: return false
		handler.handle(playerId, message)
		return true
	}
}
