package moe.tachyon.shadowed.dataClass

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Chat(
    val id: ChatId,
    val name: String?,
    val owner: UserId,
    val private: Boolean,
    val isMoment: Boolean = false,
    val lastChatAt: Instant,
    val burnTime: Long? = null, // 阅后即焚时间（毫秒），null表示关闭
)
