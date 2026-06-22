package moe.tachyon.shadowed.route.packets

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.*
import moe.tachyon.shadowed.contentNegotiationJson
import moe.tachyon.shadowed.database.Broadcasts
import moe.tachyon.shadowed.dataClass.User
import moe.tachyon.shadowed.route.getKoin
import moe.tachyon.shadowed.route.renewBroadcast

private val logger = moe.tachyon.shadowed.logger.ShadowedLogger.getLogger()

object SendBroadcastHandler : PacketHandler
{
    override val packetName = "send_broadcast"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val (message, anonymous) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val message = json.jsonObject["message"]!!.jsonPrimitive.content
            val anon = json.jsonObject["anonymous"]!!.jsonPrimitive.boolean
            Pair(message, anon)
        }.onFailure { logger.warning("Packet parse failed in SendBroadcastHandler: ${it.message}", it) }.getOrNull() ?: return session.sendError("Broadcast failed: Invalid packet format")
        
        val broadcasts = getKoin().get<Broadcasts>()
        val id = broadcasts.addBroadcast(
            content = message,
            senderId = if (anonymous) null else loginUser.id,
        )
        renewBroadcast(id)
    }
}

object GetBroadcastsHandler : PacketHandler
{
    override val packetName = "get_broadcasts"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val (system, before, count) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val sys = json.jsonObject["system"]?.jsonPrimitive?.booleanOrNull
            val bef = json.jsonObject["before"]!!.jsonPrimitive.long
            val cnt = json.jsonObject["count"]!!.jsonPrimitive.int
            Triple(sys, bef, cnt)
        }.onFailure { logger.warning("Packet parse failed in GetBroadcastsHandler: ${it.message}", it) }.getOrNull() ?: return session.sendError("Get broadcasts failed: Invalid packet format")

        val broadcasts = getKoin().get<Broadcasts>().getBroadcasts(system, before, count)
        val response = buildJsonObject()
        {
            put("packet", "broadcasts_list")
            put("broadcasts", contentNegotiationJson.encodeToJsonElement(broadcasts))
        }
        session.send(contentNegotiationJson.encodeToString(response))
    }
}
