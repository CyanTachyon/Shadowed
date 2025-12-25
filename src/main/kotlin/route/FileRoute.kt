package moe.tachyon.shadowed.route

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.websocket.*
import kotlinx.datetime.Clock
import kotlinx.io.asSource
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import moe.tachyon.shadowed.contentNegotiationJson
import moe.tachyon.shadowed.dataClass.ChatId
import moe.tachyon.shadowed.dataClass.Message
import moe.tachyon.shadowed.dataClass.MessageType
import moe.tachyon.shadowed.database.ChatMembers
import moe.tachyon.shadowed.database.Chats
import moe.tachyon.shadowed.database.Messages
import moe.tachyon.shadowed.database.Users
import moe.tachyon.shadowed.logger.ShadowedLogger
import moe.tachyon.shadowed.utils.FileUtils

private val logger = ShadowedLogger.getLogger()

fun Route.fileRoute()
{
    post("/send_file")
    {
        val chat = call.request.header("X-Chat-Id")?.toIntOrNull()?.let(::ChatId)
        val username = call.request.header("X-Auth-User")
        val passwordHash = call.request.header("X-Auth-Token")
        val messageType = call.request.header("X-Message-Type")?.let(MessageType::fromString)
        val metadata = call.request.header("X-Message-Metadata") ?: ""
        val bodySize = call.request.header(HttpHeaders.ContentLength)?.toIntOrNull() 
            ?: return@post call.respond(HttpStatusCode.LengthRequired)
        if (bodySize > environment.config.property("maxImageSize").getString().toLong())
        {
            call.respond(HttpStatusCode.PayloadTooLarge, "File size exceeds limit")
            return@post
        }
        val fileBase64 = call.receiveStream()
        if (chat == null || username == null || passwordHash == null || messageType == null)
            return@post call.respond(HttpStatusCode.BadRequest)
        val users = getKoin().get<Users>()
        val userAuth = users.getUserByUsername(username)
        if (userAuth == null || !verifyPassword(passwordHash, userAuth.password))
            return@post call.respond(HttpStatusCode.Unauthorized)
        if (getKoin().get<ChatMembers>().getUserChats(userAuth.id).none { it.chatId == chat })
            return@post call.respond(HttpStatusCode.Forbidden)
        val messages = getKoin().get<Messages>()
        val messageId = messages.addChatMessage(
            content = metadata,
            type = messageType,
            chatId = chat,
            senderId = userAuth.id,
        )
        getKoin().get<Chats>().updateTime(chat)
        getKoin().get<ChatMembers>().incrementUnread(chat, userAuth.id)
        getKoin().get<ChatMembers>().resetUnread(chat, userAuth.id)
        FileUtils.saveChatFile(messageId, fileBase64)
        call.respond(
            buildJsonObject()
            {
                put("messageId", messageId)
            }
        )
        distributeMessage(
            Message(
                id = messageId,
                content = metadata,
                type = messageType,
                chatId = chat,
                senderId = userAuth.id,
                senderName = userAuth.username,
                time = Clock.System.now().toEpochMilliseconds(),
                isRead = false,
            )
        )
    }

    get("/file/{messageId}")
    {
        val messageId = call.pathParameters["messageId"]?.toLongOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
        val fileBytes = FileUtils.getChatFile(messageId) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.CacheControl, "max-age=${30*24*60*60}") // 30 days
        call.respondSource(fileBytes.asSource(), ContentType.Text.Plain)
    }
}

internal suspend fun distributeMessage(message: Message)
{
    val members = getKoin().get<ChatMembers>().getMemberIds(message.chatId)
    members.forEach()
    { uid ->
        SessionManager.forEachSession(uid)
        { s ->
            s.sendUnreadCount(uid, message.chatId)
            val pushData = buildJsonObject()
            {
                put("packet", "receive_message")
                put("message", contentNegotiationJson.encodeToJsonElement(message))
            }
            logger.warning("sending message to $uid")
            {
                s.send(contentNegotiationJson.encodeToString(pushData))
            }
        }
    }
}
