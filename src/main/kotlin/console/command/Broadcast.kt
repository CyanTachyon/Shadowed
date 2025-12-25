package moe.tachyon.shadowed.console.command

import moe.tachyon.shadowed.database.Broadcasts
import moe.tachyon.shadowed.route.renewBroadcast
import org.jline.reader.Candidate
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

object Broadcast: Command
{
    private class BroadcastHandler(private val handler: CommandHandler): CommandHandler, KoinComponent
    {
        private val sb = StringBuilder()
        override suspend fun handleTabComplete(sender: CommandSender, line: ParsedLine): List<Candidate>
        {
            if (line.wordIndex() == 0)
            {
                return listOf(
                    Candidate("/check"),
                    Candidate("/send"),
                    Candidate("/quit"),
                )
            }
            return emptyList()
        }

        override suspend fun handleCommandInvoke(sender: CommandSender, line: ParsedLine): Boolean
        {
            if (line.rawLine.trim() == "/send")
            {
                val message = sb.toString().trim()
                val id = get<Broadcasts>().addSystemBroadcast(message)
                renewBroadcast(id)
                sender.out("Broadcast message sent.")
                sender.handler = handler
                return true
            }
            if (line.rawLine.trim() == "/check")
            {
                val message = sb.toString().trim()
                sender.out("Current broadcast message:")
                message.split("\n").forEach { sender.out(it) }
                return true
            }
            if (line.rawLine.trim() == "/quit")
            {
                sender.out("Broadcast message input cancelled.")
                sender.handler = handler
                return true
            }
            sb.appendLine(line.rawLine)
            return true
        }
    }

    override val name: String = "broadcast"
    override val description: String = "Send a broadcast message to all users."
    override suspend fun execute(sender: CommandSender, args: List<String>): Boolean
    {
        sender.out("Enter broadcast message. Type /send to send, /check to check current message, /quit to cancel.")
        sender.handler = BroadcastHandler(sender.handler)
        return true
    }
}