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
)