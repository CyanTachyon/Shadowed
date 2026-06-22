package moe.tachyon.shadowed.route.packets

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.*
import moe.tachyon.shadowed.contentNegotiationJson
import moe.tachyon.shadowed.database.Sessions
import moe.tachyon.shadowed.database.Users
import moe.tachyon.shadowed.dataClass.User
import moe.tachyon.shadowed.route.SessionManager
import moe.tachyon.shadowed.route.getKoin
import moe.tachyon.shadowed.route.verifyPassword
import moe.tachyon.shadowed.route.DUMMY_BCRYPT_HASH

private val logger = moe.tachyon.shadowed.logger.ShadowedLogger.getLogger()

object LoginHandler : LoginPacketHandler
{
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String
    ): User?
    {
        val json = runCatching()
        {
            contentNegotiationJson.parseToJsonElement(packetData).jsonObject
        }.onFailure { logger.warning("Packet parse failed in LoginHandler: ${it.message}", it) }.getOrNull() ?: run()
        {
            val response = buildJsonObject()
            {
                put("packet", "login_result")
                put("success", false)
                put("error", "Login failed: Invalid packet format")
            }
            session.send(contentNegotiationJson.encodeToString(response))
            return null
        }

        val username = json["username"]?.jsonPrimitive?.content
        val password = json["password"]?.jsonPrimitive?.content
        val reconnectToken = json["sessionToken"]?.jsonPrimitive?.content

        if (username == null)
        {
            val response = buildJsonObject()
            {
                put("packet", "login_result")
                put("success", false)
                put("error", "Login failed: Username required")
            }
            session.send(contentNegotiationJson.encodeToString(response))
            return null
        }

        if (reconnectToken != null)
        {
            val userId = getKoin().get<Sessions>().verify(reconnectToken)
            val user = userId?.let { getKoin().get<Users>().getUser(it) }
            if (user == null || user.username != username)
            {
                val response = buildJsonObject()
                {
                    put("packet", "login_result")
                    put("success", false)
                    put("error", "Login failed: Invalid or expired session")
                }
                session.send(contentNegotiationJson.encodeToString(response))
                return null
            }
            SessionManager.addSession(user.id, session)
            val response = buildJsonObject()
            {
                put("packet", "login_result")
                put("success", true)
                put("user", contentNegotiationJson.encodeToJsonElement(user))
                put("sessionToken", reconnectToken)
            }
            session.send(contentNegotiationJson.encodeToString(response))
            return user
        }

        val user = getKoin().get<Users>().getUserByUsername(username)
        if (user == null)
        {
            verifyPassword(password ?: "", DUMMY_BCRYPT_HASH)
            val response = buildJsonObject()
            {
                put("packet", "login_result")
                put("success", false)
                put("error", "Login failed: Invalid credentials")
            }
            session.send(contentNegotiationJson.encodeToString(response))
            return null
        }

        if (password == null || !verifyPassword(password, user.password))
        {
            val response = buildJsonObject()
            {
                put("packet", "login_result")
                put("success", false)
                put("error", "Login failed: Invalid credentials")
            }
            session.send(contentNegotiationJson.encodeToString(response))
            return null
        }

        SessionManager.addSession(user.id, session)

        val sessionToken = getKoin().get<Sessions>().create(user.id)
        val response = buildJsonObject()
        {
            put("packet", "login_result")
            put("success", true)
            put("user", contentNegotiationJson.encodeToJsonElement(user))
            put("sessionToken", sessionToken)
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
        }.onFailure { logger.warning("Packet parse failed in GetPublicKeyByUsernameHandler: ${it.message}", it) }.getOrNull() ?: return

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
        else session.sendError("Failed to get public key: User not found")
    }
}

object UpdateSignatureHandler : PacketHandler
{
    override val packetName = "update_signature"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val signature = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            json.jsonObject["signature"]!!.jsonPrimitive.content
        }.onFailure { logger.warning("Packet parse failed in UpdateSignatureHandler: ${it.message}", it) }.getOrNull() ?: return session.sendError("Invalid signature format")

        // Limit signature length
        if (signature.length > 100)
        {
            return session.sendError("Signature too long (max 100 characters)")
        }

        getKoin().get<Users>().updateSignature(loginUser.id, signature)
        
        val response = buildJsonObject()
        {
            put("packet", "signature_updated")
            put("signature", signature)
        }
        session.send(contentNegotiationJson.encodeToString(response))
        session.sendSuccess("Signature updated successfully")
    }
}
