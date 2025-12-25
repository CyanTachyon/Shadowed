package moe.tachyon.shadowed.console.command

import moe.tachyon.shadowed.route.packets.NotifyPacket
import moe.tachyon.shadowed.route.sendNotifyToAll
import org.jline.reader.Candidate

object Notify: Command
{
    override val name: String = "notify"
    override val description: String = "Send a notification to all users."
    override val log: Boolean = true
    override val args: String = "<type> <message> ..."

    override suspend fun execute(sender: CommandSender, args: List<String>): Boolean
    {
        if (args.size < 2) return false
        val type = args[0].let(NotifyPacket.Type::valueOf)
        val message = args.subList(1, args.size).joinToString(" ")
        sendNotifyToAll(type, message)
        sender.out("Notification sent to all users.")
        return true
    }

    override suspend fun tabComplete(args: List<String>): List<Candidate>
    {
        if (args.size == 1) return NotifyPacket.Type.entries.map { Candidate(it.name) }
        return emptyList()
    }
}