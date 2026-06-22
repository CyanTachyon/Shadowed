package moe.tachyon.shadowed.route

import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import moe.tachyon.shadowed.database.Sessions
import moe.tachyon.shadowed.database.Users

fun Route.authRoute()
{
    val users by getKoin().inject<Users>()
    val sessions by getKoin().inject<Sessions>()

    post("/register")
    {
        @Serializable
        data class RegisterRequest(
            val username: String,
            val password: String,
            val publicKey: String,
            val privateKey: String,
            val pbkdf2Salt: String? = null,
            val pbkdf2Iterations: Int? = null,
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
        // Enforce minimum PBKDF2 strength when the client claims new-style params.
        val iterations = registerRequest.pbkdf2Iterations
        if (iterations != null && (iterations < 100000 || iterations > 10_000_000))
        {
            call.respondApiError("PBKDF2 iterations out of allowed range")
            return@post
        }
        if (registerRequest.pbkdf2Salt != null && registerRequest.pbkdf2Salt.length > 64)
        {
            call.respondApiError("PBKDF2 salt too long")
            return@post
        }
        if (users.getUserByUsername(registerRequest.username) == null)
        {
            val id = users.createUser(
                username = registerRequest.username,
                encryptedPassword = encryptPassword(registerRequest.password),
                publicKey = registerRequest.publicKey,
                encryptedPrivateKey = registerRequest.privateKey,
                pbkdf2Salt = registerRequest.pbkdf2Salt,
                pbkdf2Iterations = iterations ?: 100000,
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
            val pbkdf2Salt: String? = null,
            val pbkdf2Iterations: Int? = null,
        )

        val resetRequest = call.receive<ResetPasswordRequest>()
        val user = users.getUserByUsername(resetRequest.username)
        if (user == null)
        {
            verifyPassword(resetRequest.oldPassword, DUMMY_BCRYPT_HASH)
            call.respondApiError("Invalid credentials")
            return@post
        }
        if (!verifyPassword(resetRequest.oldPassword, user.password))
        {
            call.respondApiError("Invalid credentials")
            return@post
        }
        // Progressive hardening on password reset: when the client supplies new
        // params, validate them the same way /register does. Clients that omit
        // them keep their previous parameters (which may still be legacy).
        val requestedIter = resetRequest.pbkdf2Iterations
        if (requestedIter != null && (requestedIter < 100000 || requestedIter > 10_000_000))
        {
            call.respondApiError("PBKDF2 iterations out of allowed range")
            return@post
        }
        val newSalt = resetRequest.pbkdf2Salt ?: user.pbkdf2Salt
        val newIter = requestedIter ?: user.pbkdf2Iterations
        users.updatePasswordAndKey(
            userId = user.id,
            newEncryptedPassword = encryptPassword(resetRequest.newPassword),
            newEncryptedPrivateKey = resetRequest.privateKey,
            newPbkdf2Salt = newSalt,
            newPbkdf2Iterations = newIter,
        )
        sessions.deleteByUserId(user.id)
        call.respondApi("Password and key updated successfully")
    }
}
