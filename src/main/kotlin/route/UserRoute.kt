package moe.tachyon.shadowed.route

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.readBytes
import moe.tachyon.shadowed.database.Users
import moe.tachyon.shadowed.database.ChatMembers
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.dataClass.ChatId
import moe.tachyon.shadowed.logger.ShadowedLogger
import moe.tachyon.shadowed.utils.FileUtils
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.tachyon.shadowed.dataClass.UserId.Companion.toUserIdOrNull
import moe.tachyon.shadowed.database.Chats
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

private val logger = ShadowedLogger.getLogger()

fun Route.userRoute()
{
    route("/user")
    {
        post("/avatar")
        {
            // Session-based auth (was: plaintext-password replay via X-Auth-Token, C-2)
            val userAuth = call.authenticateSession()
                ?: run {
                    call.respondApiError("Authentication required", HttpStatusCode.Unauthorized)
                    return@post
                }

            // Process multipart
            val multipart = call.receiveMultipart()

            while (true)
            {
                val part = multipart.readPart() ?: break
                if (part is PartData.FileItem)
                {
                    logger.warning("Failed to process image")
                    {
                        val fileBytes = part.provider().readBuffer().readBytes()
                        val image = ImageIO.read(ByteArrayInputStream(fileBytes))
                        if (image != null) FileUtils.setAvatar(userAuth.id, image)
                        else call.respondApiError("Invalid image format")
                    }.getOrThrow()
                }
                part.dispose()
            }

            call.respondApi(Unit)
        }

        get("/{id}/avatar")
        {
            val id = call.parameters["id"]?.toUserIdOrNull() ?: return@get call.respondApiError("Invalid user id", HttpStatusCode.BadRequest)

            val avatarImage = FileUtils.getAvatar(id)

            call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
            if (avatarImage != null)
            {
                val bytes = ByteArrayOutputStream().also { ImageIO.write(avatarImage, "png", it) }.toByteArray()
                val hash = bytes.contentHashCode().toString()
                if (call.request.headers[HttpHeaders.IfNoneMatch] == hash) return@get call.respond(HttpStatusCode.NotModified)
                call.response.header(HttpHeaders.ETag, hash)
                call.respondBytes(bytes, contentType = ContentType.Image.PNG)
            }
            else
            {
                if (call.request.headers[HttpHeaders.IfNoneMatch] == "default_avatar") return@get call.respond(HttpStatusCode.NotModified)
                call.response.header(HttpHeaders.ETag, "default_avatar")
                call.respondText("""
                    <svg width="100" height="100" viewBox="0 0 64 64" xmlns="http://www.w3.org/2000/svg">
                      <rect x="0" y="0" width="64" height="64" fill="#E3F2FD"/>
                      <g fill="#42A5F5">
                        <circle cx="32" cy="24" r="10"/>
                        <path d="M32 38C22 38 14 44 12 52C12 52 12 54 14 54H50C52 54 52 52 52 52C50 44 42 38 32 38Z"/>
                      </g>
                    </svg>
                """.trimIndent(), contentType = ContentType.Image.SVG)
            }
        }

        get("/publicKey")
        {
            val username = call.parameters["username"] ?: return@get call.respondApiError("Invalid username", HttpStatusCode.BadRequest)
            val user = getKoin().get<Users>().getUserByUsername(username) ?: return@get call.respondApiError("User not found", HttpStatusCode.NotFound)
            val response = buildJsonObject()
            {
                put("publicKey", user.publicKey)
            }
            call.respondApi(response)
        }

        get("/info")
        {
            val idStr = call.parameters["id"]
            val username = call.parameters["username"]

            if (idStr != null)
            {
                val id = idStr.toIntOrNull()

                if (id == null)
                {
                    call.respondApiError("Invalid user id")
                    return@get
                }

                val user = getKoin().get<Users>().getUser(UserId(id))

                if (user == null)
                {
                    call.respondApiError("User not found", HttpStatusCode.NotFound)
                    return@get
                }

                val response = buildJsonObject()
                {
                    put("id", user.id.value)
                    put("username", user.username)
                    put("signature", user.signature)
                    put("isDonor", user.isDonor)
                    put("nickname", user.nickname)
                    put("pbkdf2Salt", user.pbkdf2Salt)
                    put("pbkdf2Iterations", user.pbkdf2Iterations)
                }
                call.respondApi(response)
            }
            else if (username != null)
            {
                val user = getKoin().get<Users>().getUserByUsername(username)

                if (user == null)
                {
                    call.respondApiError("User not found", HttpStatusCode.NotFound)
                    return@get
                }

                val response = buildJsonObject()
                {
                    put("id", user.id.value)
                    put("username", user.username)
                    put("signature", user.signature)
                    put("isDonor", user.isDonor)
                    put("nickname", user.nickname)
                    put("pbkdf2Salt", user.pbkdf2Salt)
                    put("pbkdf2Iterations", user.pbkdf2Iterations)
                }
                call.respondApi(response)
            }
            else
            {
                call.respondApiError("Invalid request")
            }
        }
    }

    // Group avatar routes
    route("/group")
    {
        post("/{id}/avatar")
        {
            val idStr = call.parameters["id"]
            val chatId = idStr?.toIntOrNull()?.let(::ChatId)

            if (chatId == null)
            {
                call.respondApiError("Invalid group id")
                return@post
            }

            // Session-based auth (was: plaintext-password replay via X-Auth-Token, C-2)
            val userAuth = call.authenticateSession()
                ?: run {
                    call.respondApiError("Authentication required", HttpStatusCode.Unauthorized)
                    return@post
                }

            // Check if user is a member of this chat
            val chatMembers = getKoin().get<ChatMembers>()
            if (!chatMembers.isMember(chatId, userAuth.id))
                return@post call.respondApiError("Not a chat member", HttpStatusCode.Forbidden)

            // Check if user is owner of group
            val chats = getKoin().get<Chats>()
            val chat = chats.getChat(chatId)
            if (chat == null || chat.owner != userAuth.id)
            {
                call.respondApiError("Only group owner can upload avatar", HttpStatusCode.Forbidden)
                return@post
            }

            // Process multipart
            val multipart = call.receiveMultipart()

            while (true)
            {
                val part = multipart.readPart() ?: break
                if (part is PartData.FileItem)
                {
                    logger.warning("Failed to process group avatar image")
                    {
                        val fileBytes = part.provider().readBuffer().readBytes()
                        val image = ImageIO.read(ByteArrayInputStream(fileBytes))
                        if (image != null) FileUtils.setGroupAvatar(chatId, image)
                        else call.respondApiError("Invalid image format")
                    }.getOrThrow()
                }
                part.dispose()
            }

            call.respondApi(Unit)
        }

        get("/{id}/avatar")
        {
            val idStr = call.parameters["id"]
            val chatId = idStr?.toIntOrNull()?.let(::ChatId)

            if (chatId == null)
            {
                call.respondApiError("Invalid group id")
                return@get
            }

            val avatarImage = FileUtils.getGroupAvatar(chatId)

            call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
            if (avatarImage != null)
            {
                val bytes = ByteArrayOutputStream().also { ImageIO.write(avatarImage, "png", it) }.toByteArray()
                val hash = bytes.contentHashCode().toString()
                if (call.request.headers[HttpHeaders.IfNoneMatch] == hash)
                    return@get call.respond(HttpStatusCode.NotModified)
                call.response.header(HttpHeaders.ETag, hash)
                call.respondBytes(bytes, contentType = ContentType.Image.PNG)
            }
            else
            {
                if (call.request.headers[HttpHeaders.IfNoneMatch] == "default_group_avatar")
                    return@get call.respond(HttpStatusCode.NotModified)
                call.response.header(HttpHeaders.ETag, "default_group_avatar")
                call.respondText("""
                    <svg viewBox="-3 -3 30 30" xmlns="http://www.w3.org/2000/svg">
                        <path d="M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z" fill="#42A5F5" />
                    </svg>
                """.trimIndent(), contentType = ContentType.Image.SVG)
            }
        }
    }
}
