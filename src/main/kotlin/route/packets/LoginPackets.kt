package moe.tachyon.shadowed.route.packets

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.*
import moe.tachyon.shadowed.contentNegotiationJson
import moe.tachyon.shadowed.database.Users
import moe.tachyon.shadowed.dataClass.User
import moe.tachyon.shadowed.route.SessionManager
import moe.tachyon.shadowed.route.getKoin
import moe.tachyon.shadowed.route.verifyPassword

object LoginHandler : LoginPacketHandler
{
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String
    ): User?
    {
        val (username, password) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val user = json.jsonObject["username"]!!.jsonPrimitive.content
            val pass = json.jsonObject["password"]!!.jsonPrimitive.content
            Pair(user, pass)
        }.getOrNull() ?: run()
        {
            session.sendError("Login failed: Invalid packet format")
            return null
        }
        
        val user = getKoin().get<Users>().getUserByUsername(username) ?: run {
            session.sendError("Login failed: User not found")
            return null
        }
        
        if (!verifyPassword(password, user.password))
        {
            session.sendError("Login failed: Incorrect password")
            return null
        }
        
        SessionManager.addSession(user.id, session)

        val response = buildJsonObject()
        {
            put("packet", "login_success")
            put("user", contentNegotiationJson.encodeToJsonElement(user))
        }
        session.send(contentNegotiationJson.encodeToString(response))
        return user
    }
}

object GetPublicKeyByUsernameHandler : PacketHandler
{
    override val packetName = "get_public_key_by_username"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val username = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            json.jsonObject["username"]!!.jsonPrimitive.content
        }.getOrNull() ?: return

        val user = getKoin().get<Users>().getUserByUsername(username)

        if (user != null)
        {
            val response = buildJsonObject()
            {
                put("packet", "public_key_by_username")
                put("username", username) // Echo back for correlation
                put("publicKey", user.publicKey)
            }
            session.send(contentNegotiationJson.encodeToString(response))
        }
        else
        {
            session.sendError("Failed to get public key: User not found")
        }
    }
}
