@file:Suppress("unused")

package moe.tachyon.shadowed.dataClass

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: UserId,
    val username: String,
    val password: String,
    val publicKey: String,
    val privateKey: String,
    val signature: String = "",
    val isDonor: Boolean = false,
    val nickname: String? = null,
    /**
     * Per-user PBKDF2 salt (base64-encoded random bytes). `null` for legacy
     * accounts created before the C-3 hardening: those still use `username`
     * as the salt with iterations=100000. New accounts set this to a random
     * 16-byte base64 value alongside `pbkdf2Iterations >= 600000`.
     */
    val pbkdf2Salt: String? = null,
    val pbkdf2Iterations: Int = 100000,
)