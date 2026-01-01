package moe.tachyon.shadowed.dataClass

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: Long,
    val content: String,
    val type: MessageType,
    val chatId: ChatId,
    val senderId: UserId,
    val senderName: String,
    val time: Long,
    val readAt: Long? = null, // 已读时间戳，null表示未读
    val replyTo: ReplyInfo? = null
)