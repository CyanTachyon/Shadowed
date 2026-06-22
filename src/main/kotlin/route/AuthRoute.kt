package moe.tachyon.shadowed.route

import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
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
            call.respondApiError("Username contains invalid characters")
            return@post
        }
        if (registerRequest.username.length !in 4..20)
        {
            call.respondApiError("Username length must be between 4 and 20 characters")
            return@post
        }
        if (registerRequest.publicKey.length > 500 || registerRequest.privateKey.length > 2500)
        {
            call.respondApiError("Key length exceeds limit")
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
                call.respondApi(id.value)
            }
            else
            {
                call.respondApiError("the username already exists")
            }
        }
        else
        {
            call.respondApiError("Username already exists")
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
            call.respondApiError("User not found")
            return@post
        }
        if (!verifyPassword(resetRequest.oldPassword, user.password))
        {
            call.respondApiError("Old password is incorrect")
            return@post
        }
        users.updatePasswordAndKey(
            userId = user.id,
            newEncryptedPassword = encryptPassword(resetRequest.newPassword),
            newEncryptedPrivateKey = resetRequest.privateKey,
        )
        call.respondApi("Password and key updated successfully")
    }
}
