package moe.tachyon.shadowed.dataClass

import kotlinx.serialization.Serializable

@Serializable
data class ChatMember(
    val id: UserId,
    val username: String,
    val nickname: String? = null
)

@Serializable
data class ChatFull(
    val chatId: ChatId,
    val name: String?,
    val key: String,
    val members: List<ChatMember>,
    val isPrivate: Boolean,
    val unreadCount: Int,
    val doNotDisturb: Boolean,
    val burnTime: Long?,
    val otherUserIsDonor: Boolean,
    val requireApproval: Boolean = false,
)
