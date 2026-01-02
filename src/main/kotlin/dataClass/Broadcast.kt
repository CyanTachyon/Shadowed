package moe.tachyon.shadowed.dataClass

import kotlinx.serialization.Serializable

@Serializable
data class Broadcast(
    val id: Long,
    val content: String,
    val time: Long,
    val senderId: UserId?,
    val senderName: String?,
    val system: Boolean,
    val senderIsDonor: Boolean = false
)
