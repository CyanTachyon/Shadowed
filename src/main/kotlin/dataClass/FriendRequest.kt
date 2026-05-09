@file:Suppress("unused")

package moe.tachyon.shadowed.dataClass

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class FriendRequest(
    val id: Int,
    val fromUser: UserId,
    val fromUsername: String,
    val fromNickname: String? = null,
    val toUser: UserId,
    val toUsername: String,
    val status: String, // "PENDING" | "ACCEPTED" | "REJECTED"
    val createdAt: Instant,
    val message: String? = null,
)
