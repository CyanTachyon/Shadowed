@file:Suppress("unused")

package moe.tachyon.shadowed.dataClass

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class GroupInvitation(
    val id: Int,
    val chatId: ChatId,
    val chatName: String?,
    val inviterId: UserId,
    val inviterName: String,
    val targetUserId: UserId,
    val targetUsername: String,
    val encryptedKey: String,
    val status: String, // "PENDING" | "APPROVED" | "REJECTED"
    val createdAt: Instant,
)
