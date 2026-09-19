package kr.acda.arclink.internal

import kr.acda.arccore.api.command.ARCCommand
import kr.acda.arccore.api.command.CommandContext
import kr.acda.arccore.api.command.CommandMetadata
import kr.acda.arccore.api.command.CommandResult

/** `/arclink status` diagnostic — is the socket up, how many clients are authenticated. */
class ArcLinkStatusCommand(private val port: Int, private val server: () -> ArcLinkSocketServer?) : ARCCommand {
	override val metadata = CommandMetadata(
		name = "arclink",
		permission = "arclink.admin",
		description = "ARCLink socket diagnostics.",
		usage = "/arclink status",
	)

	override fun execute(context: CommandContext): CommandResult {
		if (context.subCommand != "status" && context.subCommand != null) {
			return CommandResult.InvalidUsage(metadata.usage)
		}
		val running = server()
		if (running == null) {
			context.sender.sendMessage("§cARCLink: socket not running")
		} else {
			context.sender.sendMessage("§aARCLink: listening on §f$port §7— §f${running.authenticatedCount()}§7 client(s) authenticated")
		}
		return CommandResult.Success
	}

	override fun onTabComplete(context: CommandContext): List<String> =
		if (context.args.size <= 1) listOf("status").filter { it.startsWith(context.firstArg ?: "", true) } else emptyList()
}
