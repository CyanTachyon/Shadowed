package moe.tachyon.shadowed.dataClass

import kotlinx.serialization.Serializable

@Serializable
data class ChatMember(
    val chatId: ChatId,
    val name: String?,
    val key: String,
    val parsedOtherNames: List<String>,
    val parsedOtherIds: List<Int>,
    val isPrivate: Boolean,
    val unreadCount: Int,
    val doNotDisturb: Boolean,
    val burnTime: Long? = null, // 阅后即焚时间（毫秒），null表示关闭
    val otherUserIsDonor: Boolean = false // 私聊对方是否是捐赠者（仅私聊有效）
)
