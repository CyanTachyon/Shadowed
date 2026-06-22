package moe.tachyon.shadowed.dataClass

import kotlinx.serialization.Serializable

@Serializable
data class ForumBoard(
    val id: Long,
    val name: String,
    val description: String?,
    val icon: String?,
    val zone: ForumPostZone,
    val sortOrder: Int,
    val createdAt: Long,
)

@Serializable
data class ForumTopic(
    val id: Long,
    val boardId: Long,
    val zone: ForumPostZone,
    val title: String,
    val authorId: Int,
    val authorName: String? = null,
    val isAnonymous: Boolean,
    val anonymousName: String?,
    val createdAt: Long,
    val pinned: Boolean,
    val locked: Boolean,
    val viewCount: Int,
    val lastReplyAt: Long?,
    val lastReplyUserId: Int?,
    val replyCount: Int,
    val isDeleted: Boolean,
)

@Serializable
data class ForumPost(
    val id: Long,
    val topicId: Long,
    val content: String,
    val authorId: Int,
    val authorName: String? = null,
    val isAnonymous: Boolean,
    val anonymousName: String?,
    val floorNumber: Int,
    val createdAt: Long,
    val editedAt: Long?,
    val isDeleted: Boolean,
    val epochId: Long?,
    val replyToPostId: Long? = null,
    val replyCount: Int = 0,
)

@Serializable
data class ForumReaction(
    val postId: Long,
    val userId: Int,
    val emoji: String,
    val createdAt: Long,
)

@Serializable
data class ForumAttachment(
    val id: Long,
    val postId: Long?,
    val topicId: Long?,
    val fileUrl: String,
    val fileName: String?,
    val mimeType: String?,
    val fileSize: Long,
    val attachmentType: String,
    val epochId: Long?,
    val sortOrder: Int,
    val createdAt: Long,
)

@Serializable
data class ForumProtectKey(
    val userId: Int,
    val encryptedKey: String,
    val updatedAt: Long,
)

@Serializable
data class ForumPrivateEpoch(
    val id: Long,
    val createdAt: Long,
    val changeType: String,
    val changeDetail: String?,
)

@Serializable
data class ForumPrivateEpochKey(
    val epochId: Long,
    val userId: Int,
    val encryptedKey: String,
)

@Serializable
data class ForumInvitation(
    val id: Long,
    val inviterId: Int,
    val inviteeId: Int,
    val inviteCode: String,
    val status: String,
    val createdAt: Long,
    val usedAt: Long?,
    val expiresAt: Long?,
)

@Serializable
data class ForumNotification(
    val id: Long,
    val userId: Int,
    val type: String,
    val topicId: Long?,
    val postId: Long?,
    val fromUserId: Int?,
    val message: String?,
    val isRead: Boolean,
    val createdAt: Long,
)
