package moe.tachyon.shadowed.route

import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.tachyon.shadowed.database.Users

fun Route.authRoute()
{
    val users by getKoin().inject<Users>()

    post("/register")
    {
        @Serializable
        data class RegisterRequest(
            val username: String,
            val password: String,
            val publicKey: String,
            val privateKey: String,
        )
        val registerRequest = call.receive<RegisterRequest>()
        if (registerRequest.username.any { it !in ('a'..'z') + ('A'..'Z') + ('0'..'9') + '_' })
        {
            call.respond(
                buildJsonObject {
                    put("success", false)
                    put("message", "Username contains invalid characters")
                }
            )
            return@post
        }
        if (registerRequest.username.length !in 4..20)
        {
            call.respond(
                buildJsonObject {
                    put("success", false)
                    put("message", "Username length must be between 4 and 20 characters")
                }
            )
            return@post
        }
        if (users.getUserByUsername(registerRequest.username) == null)
        {
            val id = users.createUser(
                username = registerRequest.username,
                encryptedPassword = encryptPassword(registerRequest.password),
                publicKey = registerRequest.publicKey,
                encryptedPrivateKey = registerRequest.privateKey,
            )
            if (id != null)
            {
                call.respond(
                    buildJsonObject {
                        put("success", true)
                        put("userId", id.value)
                    }
                )
            }
            else
            {
                call.respond(
                    buildJsonObject {
                        put("success", false)
                        put("message", "the username already exists")
                    }
                )
            }
        }
        else
        {
            call.respond(
                buildJsonObject {
                    put("success", false)
                    put("message", "Username already exists")
                }
            )
        }
    }

    post("/resetPassword")
    {
        @Serializable
        data class ResetPasswordRequest(
            val username: String,
            val oldPassword: String,
            val newPassword: String,
            val privateKey: String,
        )

        val resetRequest = call.receive<ResetPasswordRequest>()
        val user = users.getUserByUsername(resetRequest.username)
        if (user == null)
        {
            call.respond(
                buildJsonObject {
                    put("success", false)
                    put("message", "User not found")
                }
            )
            return@post
        }
        if (!verifyPassword(resetRequest.oldPassword, user.password))
        {
            call.respond(
                buildJsonObject {
                    put("success", false)
                    put("message", "Old password is incorrect")
                }
            )
            return@post
        }
        users.updatePasswordAndKey(
            userId = user.id,
            newEncryptedPassword = encryptPassword(resetRequest.newPassword),
            newEncryptedPrivateKey = resetRequest.privateKey,
        )
        return@post call.respond(
            buildJsonObject {
                put("success", true)
                put("message", "Password and key updated successfully")
            }
        )
    }
}
