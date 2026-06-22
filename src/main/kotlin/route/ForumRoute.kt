package moe.tachyon.shadowed.route

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.readBytes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import moe.tachyon.shadowed.dataClass.*
import moe.tachyon.shadowed.database.*
import moe.tachyon.shadowed.route.packets.pushForumNotification
import moe.tachyon.shadowed.utils.FileUtils

private val logger = moe.tachyon.shadowed.logger.ShadowedLogger.getLogger()

@Serializable
data class CreateBoardRequest(val name: String, val description: String = "", val icon: String = "", val zone: ForumPostZone = ForumPostZone.PUBLIC)

@Serializable
data class UpdateBoardRequest(
    val name: String? = null,
    val description: String? = null,
    val icon: String? = null,
    val sortOrder: Int? = null,
    val zone: ForumPostZone? = null,
)

@Serializable
data class CreateTopicRequest(
    val boardId: Long,
    val zone: ForumPostZone,
    val title: String,
    val content: String,
    val isAnonymous: Boolean = false,
    val anonymousName: String? = null,
)

@Serializable
data class UpdateTopicRequest(val title: String)

@Serializable
data class PinRequest(val pinned: Boolean)

@Serializable
data class LockRequest(val locked: Boolean)

@Serializable
data class ReplyRequest(
    val content: String,
    val isAnonymous: Boolean = false,
    val anonymousName: String? = null,
    val epochId: Long? = null,
    val replyToPostId: Long? = null,
)

@Serializable
data class EditPostRequest(val content: String)

@Serializable
data class ReactRequest(val emoji: String)

@Serializable
data class InviteRequest(val targetUserId: Int)

@Serializable
data class EpochEncryptedKeyEntry(val userId: Int, val encryptedKey: String)

@Serializable
data class ApproveInviteRequest(
    val protectEncryptedKey: String,
    val epochEncryptedKeys: List<EpochEncryptedKeyEntry>,
)

@Serializable
data class GrantInviteRequest(val userId: Int, val slots: Int = 10)

@Serializable
data class RevokeInviteRequest(val userId: Int)

@Serializable
data class ReadNotificationRequest(val ids: List<Long>? = null)

fun Route.forumRoute()
{
    val usersDao by getKoin().inject<Users>()
    val boardsDao by getKoin().inject<ForumBoards>()
    val topicsDao by getKoin().inject<ForumTopics>()
    val postsDao by getKoin().inject<ForumPosts>()
    val reactionsDao by getKoin().inject<ForumReactions>()
    val attachmentsDao by getKoin().inject<ForumAttachments>()
    val protectKeysDao by getKoin().inject<ForumProtectKeys>()
    val epochsDao by getKoin().inject<ForumPrivateEpochs>()
    val epochKeysDao by getKoin().inject<ForumPrivateEpochKeys>()
    val invitationsDao by getKoin().inject<ForumInvitations>()
    val notificationsDao by getKoin().inject<ForumNotifications>()

    route("/forum")
    {
        // ==================== Permissions ====================
        get("/permissions")
        {
            val user = getForumUser() ?: return@get
            val isAdmin = ForumAuth.isAdmin(user.id)
            val zone = usersDao.getUserForumZone(user.id)
            val canInvite = ForumAuth.canInvite(user.id)
            val inviteSlots = usersDao.getInviteSlots(user.id)
            call.respondApi(buildJsonObject {
                put("isForumAdmin", isAdmin)
                put("forumZone", zone.name)
                put("canInvite", canInvite)
                put("inviteSlots", inviteSlots)
            })
        }

        // ==================== Boards ====================
        get("/boards")
        {
            val list = boardsDao.getAllBoards()
            val boardsJson = Json.encodeToJsonElement(list)
            call.respondApi(buildJsonObject {
                put("boards", boardsJson)
            })
        }

        post("/boards")
        {
            val user = getForumUser() ?: return@post
            if (!requireForumAdmin(user)) return@post
            val req = call.receive<CreateBoardRequest>()
            val id = boardsDao.createBoard(req.name, req.description, req.icon, req.zone)
            call.respondApi(buildJsonObject { put("id", id) })
        }

        put("/boards/{id}")
        {
            val user = getForumUser() ?: return@put
            if (!requireForumAdmin(user)) return@put
            val id = call.parameters["id"]?.toLongOrNull() ?: return@put call.respondApiError("Invalid board id", HttpStatusCode.BadRequest)
            val req = call.receive<UpdateBoardRequest>()
            boardsDao.updateBoard(id, req.name, req.description, req.icon, req.sortOrder, req.zone)
            call.respondApi(Unit)
        }

        delete("/boards/{id}")
        {
            val user = getForumUser() ?: return@delete
            if (!requireForumAdmin(user)) return@delete
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete call.respondApiError("Invalid board id", HttpStatusCode.BadRequest)
            boardsDao.deleteBoard(id)
            call.respondApi(Unit)
        }

        // ==================== Topics ====================
        get("/topics")
        {
            val user = getForumUser() ?: return@get
            val boardId = call.request.queryParameters["boardId"]?.toLongOrNull()
            val zoneParam = call.request.queryParameters["zone"]
            val explicitZone = zoneParam?.let { ForumPostZone.entries.find { z -> z.name.equals(it, ignoreCase = true) } }

            // When boardId is specified, don't filter by zone — the board already determines it.
            // But we still need to check access based on the board's zone.
            val effectiveZone: ForumPostZone? = if (boardId != null) {
                val board = boardsDao.getBoard(boardId)
                if (board != null && board.zone != ForumPostZone.PUBLIC) {
                    if (!ForumAuth.canAccessZone(user.id, board.zone))
                        return@get call.respondApiError("No access to this zone", HttpStatusCode.Forbidden)
                }
                null
            } else {
                if (explicitZone != null && explicitZone != ForumPostZone.PUBLIC) {
                    if (!ForumAuth.canAccessZone(user.id, explicitZone))
                        return@get call.respondApiError("No access to this zone", HttpStatusCode.Forbidden)
                }
                explicitZone
            }

            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
            val (total, list) = topicsDao.getTopics(boardId, effectiveZone, page)
            val topicsJson = Json.encodeToJsonElement(list)
            call.respondApi(buildJsonObject {
                put("total", total)
                put("topics", topicsJson)
            })
        }

        get("/topics/{id}")
        {
            val user = getForumUser() ?: return@get
            val id = call.parameters["id"]?.toLongOrNull() ?: return@get call.respondApiError("Invalid topic id", HttpStatusCode.BadRequest)
            val topic = topicsDao.getTopic(id)
                ?: return@get call.respondApiError("Topic not found", HttpStatusCode.NotFound)

            if (topic.zone != ForumPostZone.PUBLIC) {
                if (!ForumAuth.canAccessZone(user.id, topic.zone))
                    return@get call.respondApiError("No access to this zone", HttpStatusCode.Forbidden)
            }

            topicsDao.incrementViewCount(id)

            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
            val (totalPosts, posts) = postsDao.getPostsByTopic(id, page)

            val postIds = posts.map { it.id }
            val reactions = if (postIds.isNotEmpty()) reactionsDao.getReactionsForPosts(postIds) else emptyMap()
            val attachments = attachmentsDao.getAttachmentsForTopic(id)

            val topicJson = Json.encodeToJsonElement(topic)
            val postsJson = Json.encodeToJsonElement(posts)
            val reactionsJson = Json.encodeToJsonElement(reactions)
            val attachmentsJson = Json.encodeToJsonElement(attachments)
            call.respondApi(buildJsonObject {
                put("topic", topicJson)
                put("posts", postsJson)
                put("totalPosts", totalPosts)
                put("reactions", reactionsJson)
                put("attachments", attachmentsJson)
            })
        }

        post("/topics")
        {
            val user = getForumUser() ?: return@post
            val req = call.receive<CreateTopicRequest>()

            if (!ForumAuth.canAccessZone(user.id, req.zone))
                return@post call.respondApiError("No access to this zone", HttpStatusCode.Forbidden)

            val topicId = topicsDao.createTopic(
                boardId = req.boardId, zone = req.zone, title = req.title,
                authorId = user.id.value, isAnonymous = req.isAnonymous, anonymousName = req.anonymousName,
            )
            postsDao.createPost(
                topicId = topicId, content = req.content,
                authorId = user.id.value, isAnonymous = req.isAnonymous, anonymousName = req.anonymousName,
            )
            call.respondApi(buildJsonObject { put("topicId", topicId) })
        }

        put("/topics/{id}")
        {
            val user = getForumUser() ?: return@put
            val id = call.parameters["id"]?.toLongOrNull() ?: return@put call.respondApiError("Invalid topic id", HttpStatusCode.BadRequest)
            val req = call.receive<UpdateTopicRequest>()
            if (!topicsDao.isAuthor(id, user.id.value) && !ForumAuth.isAdmin(user.id))
                return@put call.respondApiError("Not authorized", HttpStatusCode.Forbidden)
            topicsDao.updateTopic(id, req.title)
            call.respondApi(Unit)
        }

        delete("/topics/{id}")
        {
            val user = getForumUser() ?: return@delete
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete call.respondApiError("Invalid topic id", HttpStatusCode.BadRequest)
            if (!topicsDao.isAuthor(id, user.id.value) && !ForumAuth.isAdmin(user.id))
                return@delete call.respondApiError("Not authorized", HttpStatusCode.Forbidden)
            topicsDao.deleteTopic(id)
            call.respondApi(Unit)
        }

        post("/topics/{id}/pin")
        {
            val user = getForumUser() ?: return@post
            if (!requireForumAdmin(user)) return@post
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post call.respondApiError("Invalid topic id", HttpStatusCode.BadRequest)
            val req = call.receive<PinRequest>()
            topicsDao.setPinned(id, req.pinned)
            call.respondApi(Unit)
        }

        post("/topics/{id}/lock")
        {
            val user = getForumUser() ?: return@post
            if (!requireForumAdmin(user)) return@post
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post call.respondApiError("Invalid topic id", HttpStatusCode.BadRequest)
            val req = call.receive<LockRequest>()
            topicsDao.setLocked(id, req.locked)
            call.respondApi(Unit)
        }

        // ==================== Posts (Replies) ====================
        post("/topics/{id}/reply")
        {
            val user = getForumUser() ?: return@post
            val topicId = call.parameters["id"]?.toLongOrNull() ?: return@post call.respondApiError("Invalid topic id", HttpStatusCode.BadRequest)
            val req = call.receive<ReplyRequest>()

            try {
                val topic = topicsDao.getTopic(topicId)
                    ?: return@post call.respondApiError("Topic not found", HttpStatusCode.NotFound)

                if (!ForumAuth.canAccessZone(user.id, topic.zone))
                    return@post call.respondApiError("No access to this zone", HttpStatusCode.Forbidden)

                if (topic.locked && !ForumAuth.isAdmin(user.id))
                    return@post call.respondApiError("Topic is locked", HttpStatusCode.Forbidden)

                val postId = postsDao.createPost(
                    topicId = topicId, content = req.content,
                    authorId = user.id.value, isAnonymous = req.isAnonymous,
                    anonymousName = req.anonymousName, epochId = req.epochId,
                    replyToPostId = req.replyToPostId,
                )
                topicsDao.updateLastReply(topicId, user.id.value)

                try {
                    if (topic.authorId != user.id.value)
                    {
                        val notifId = notificationsDao.createNotification(
                            userId = topic.authorId,
                            type = "REPLY",
                            topicId = topicId,
                            postId = postId,
                            fromUserId = user.id.value,
                            message = if (req.isAnonymous) (req.anonymousName ?: "Someone") + " replied to your topic" else "Someone replied to your topic",
                        )
                        val notif = notificationsDao.getNotification(notifId)
                        if (notif != null) pushForumNotification(topic.authorId, notif)
                    }
                } catch (e: Exception) { logger.warning("Notification sending failed: ${e.message}", e) }

                try {
                    if (req.replyToPostId != null)
                    {
                        val repliedPost = postsDao.getPost(req.replyToPostId)
                        if (repliedPost != null && repliedPost.authorId != user.id.value && repliedPost.authorId != topic.authorId)
                        {
                            val replyNotifId = notificationsDao.createNotification(
                                userId = repliedPost.authorId,
                                type = "REPLY",
                                topicId = topicId,
                                postId = postId,
                                fromUserId = user.id.value,
                                message = "Someone replied to your post",
                            )
                            val replyNotif = notificationsDao.getNotification(replyNotifId)
                            if (replyNotif != null) pushForumNotification(repliedPost.authorId, replyNotif)
                        }
                    }
                } catch (e: Exception) { logger.warning("Notification sending failed: ${e.message}", e) }

                call.respondApi(buildJsonObject { put("postId", postId) })
            } catch (e: Exception) {
                logger.warning("Failed to create reply: ${e.message}", e)
                call.respondApiError("Failed to create reply", HttpStatusCode.InternalServerError)
            }
        }

        put("/posts/{id}")
        {
            val user = getForumUser() ?: return@put
            val id = call.parameters["id"]?.toLongOrNull() ?: return@put call.respondApiError("Invalid post id", HttpStatusCode.BadRequest)
            val req = call.receive<EditPostRequest>()
            if (!postsDao.isAuthor(id, user.id.value) && !ForumAuth.isAdmin(user.id))
                return@put call.respondApiError("Not authorized", HttpStatusCode.Forbidden)
            postsDao.editPost(id, req.content)
            call.respondApi(Unit)
        }

        delete("/posts/{id}")
        {
            val user = getForumUser() ?: return@delete
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete call.respondApiError("Invalid post id", HttpStatusCode.BadRequest)
            if (!postsDao.isAuthor(id, user.id.value) && !ForumAuth.isAdmin(user.id))
                return@delete call.respondApiError("Not authorized", HttpStatusCode.Forbidden)
            postsDao.deletePost(id)
            call.respondApi(Unit)
        }

        // ==================== Reactions ====================
        post("/posts/{id}/react")
        {
            val user = getForumUser() ?: return@post
            val postId = call.parameters["id"]?.toLongOrNull() ?: return@post call.respondApiError("Invalid post id", HttpStatusCode.BadRequest)
            val req = call.receive<ReactRequest>()
            val added = reactionsDao.toggleReaction(postId, user.id.value, req.emoji)
            if (added)
            {
                val post = postsDao.getPost(postId)
                if (post != null && post.authorId != user.id.value)
                {
                    val notifId = notificationsDao.createNotification(
                        userId = post.authorId,
                        type = "REACTION",
                        postId = postId,
                        fromUserId = user.id.value,
                        message = "Someone reacted to your post",
                    )
                    val notif = notificationsDao.getNotification(notifId)
                    if (notif != null) pushForumNotification(post.authorId, notif)
                }
            }
            call.respondApi(buildJsonObject { put("added", added) })
        }

        // ==================== Search (PUBLIC only) ====================
        get("/search")
        {
            val user = getForumUser() ?: return@get
            val query = call.request.queryParameters["q"] ?: return@get call.respondApiError(
                "Query parameter 'q' required", HttpStatusCode.BadRequest
            )
            val boardId = call.request.queryParameters["boardId"]?.toLongOrNull()
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0

            val (total, list) = topicsDao.searchTopics(query, boardId, page)
            val resultsJson = Json.encodeToJsonElement(list)
            call.respondApi(buildJsonObject {
                put("total", total)
                put("results", resultsJson)
            })
        }

        // ==================== User Profile ====================
        get("/user/{id}/profile")
        {
            val uid = call.parameters["id"]?.toIntOrNull() ?: return@get call.respondApiError("Invalid user id", HttpStatusCode.BadRequest)
            val u = usersDao.getUser(UserId(uid))
                ?: return@get call.respondApiError("User not found", HttpStatusCode.NotFound)
            call.respondApi(buildJsonObject {
                put("user", buildJsonObject {
                    put("id", u.id.value)
                    put("username", u.username)
                    put("signature", u.signature)
                    put("isDonor", u.isDonor)
                    put("nickname", u.nickname)
                })
            })
        }

        // ==================== Protect Keys ====================
        get("/protect/key")
        {
            val user = getForumUser() ?: return@get
            val key = protectKeysDao.getKey(user.id.value)
                ?: return@get call.respondApiError("No PROTECT key found", HttpStatusCode.NotFound)
            call.respondApi(buildJsonObject { put("encryptedKey", key) })
        }

        // ==================== Private Epochs ====================
        get("/private/epoch/current")
        {
            val user = getForumUser() ?: return@get
            if (!ForumAuth.canAccessZone(user.id, ForumPostZone.PRIVATE))
                return@get call.respondApiError("No access to PRIVATE zone", HttpStatusCode.Forbidden)

            val epoch = epochsDao.getLatestEpoch()
                ?: return@get call.respondApiError("No epoch found", HttpStatusCode.NotFound)
            val key = epochKeysDao.getKey(epoch.id, user.id.value)
                ?: return@get call.respondApiError("No key for current epoch", HttpStatusCode.Forbidden)

            call.respondApi(buildJsonObject { put("epochId", epoch.id); put("encryptedKey", key) })
        }

        get("/private/members")
        {
            val user = getForumUser() ?: return@get
            if (!ForumAuth.canAccessZone(user.id, ForumPostZone.PRIVATE))
                return@get call.respondApiError("No access", HttpStatusCode.Forbidden)

            val memberIds = protectKeysDao.getAllMemberIds()
            val usersMap = usersDao.getUsers(memberIds.map { UserId(it) })
            call.respondApi(buildJsonObject {
                put("members", buildJsonArray {
                    for (mid in memberIds)
                    {
                        usersMap[mid]?.let { u ->
                            add(buildJsonObject {
                                put("userId", u.id.value)
                                put("username", u.username)
                                put("publicKey", u.publicKey)
                            })
                        }
                    }
                })
            })
        }

        get("/private/epoch/{id}/key")
        {
            val user = getForumUser() ?: return@get
            if (!ForumAuth.canAccessZone(user.id, ForumPostZone.PRIVATE))
                return@get call.respondApiError("No access", HttpStatusCode.Forbidden)

            val epochId = call.parameters["id"]?.toLongOrNull() ?: return@get call.respondApiError("Invalid epoch id", HttpStatusCode.BadRequest)
            val key = epochKeysDao.getKey(epochId, user.id.value)
                ?: return@get call.respondApiError("No key for this epoch", HttpStatusCode.Forbidden)

            call.respondApi(buildJsonObject { put("encryptedKey", key) })
        }

        // ==================== Invite ====================
        post("/invite")
        {
            val user = getForumUser() ?: return@post
            val req = call.receive<InviteRequest>()

            if (!ForumAuth.canInvite(user.id))
                return@post call.respondApiError("No invite permission", HttpStatusCode.Forbidden)

            usersDao.getUser(UserId(req.targetUserId))
                ?: return@post call.respondApiError("Target user not found", HttpStatusCode.NotFound)

            if (invitationsDao.hasPendingForUser(req.targetUserId))
                return@post call.respondApiError("User already has pending invitation", HttpStatusCode.Conflict)

            if (usersDao.getUserForumZone(UserId(req.targetUserId)) == ForumZone.INVITED)
                return@post call.respondApiError("User already invited", HttpStatusCode.Conflict)

            if (!ForumAuth.isAdmin(user.id))
            {
                if (!usersDao.useInviteSlot(user.id))
                    return@post call.respondApiError("No invite slots remaining", HttpStatusCode.Forbidden)
            }

            val code = java.util.UUID.randomUUID().toString().replace("-", "")
            val invitationId = invitationsDao.createInvitation(user.id.value, req.targetUserId, code)

            val adminIds = usersDao.getForumAdminIds()
            for (adminId in adminIds)
            {
                if (adminId != user.id.value)
                {
                    val notifId = notificationsDao.createNotification(
                        userId = adminId,
                        type = "SYSTEM",
                        message = "New invitation from ${user.username} pending approval",
                    )
                    val notif = notificationsDao.getNotification(notifId)
                    if (notif != null) pushForumNotification(adminId, notif)
                }
            }

            call.respondApi(buildJsonObject { put("invitationId", invitationId); put("inviteCode", code) })
        }

        get("/invite/mine")
        {
            val user = getForumUser() ?: return@get
            val list = invitationsDao.getInvitationsByInviter(user.id.value)
            val invitationsJson = Json.encodeToJsonElement(list)
            call.respondApi(buildJsonObject {
                put("invitations", invitationsJson)
            })
        }

        get("/invite/pending")
        {
            val user = getForumUser() ?: return@get
            if (!requireForumAdmin(user)) return@get
            val list = invitationsDao.getPendingInvitations()
            val invitationsJson = Json.encodeToJsonElement(list)
            val inviteeIds = list.map { it.inviteeId }
            val inviteeUsers = usersDao.getUsers(inviteeIds.map { UserId(it) })
            val keysMap = buildJsonObject {
                for (inv in list)
                {
                    inviteeUsers[inv.inviteeId]?.let { u ->
                        put(inv.id.toString(), u.publicKey)
                    }
                }
            }
            call.respondApi(buildJsonObject {
                put("invitations", invitationsJson)
                put("inviteePublicKeys", keysMap)
            })
        }

        post("/invite/{id}/approve")
        {
            val user = getForumUser() ?: return@post
            if (!requireForumAdmin(user)) return@post

            val id = call.parameters["id"]?.toLongOrNull() ?: return@post call.respondApiError("Invalid invitation id", HttpStatusCode.BadRequest)
            val req = call.receive<ApproveInviteRequest>()

            try {
                val invitation = invitationsDao.getInvitation(id)
                    ?: return@post call.respondApiError("Invitation not found", HttpStatusCode.NotFound)

                if (invitation.status != "PENDING")
                    return@post call.respondApiError("Invitation not pending", HttpStatusCode.Conflict)

                val approved = invitationsDao.approveInvitation(id)
                if (!approved)
                    return@post call.respondApiError("Invitation no longer pending (concurrent approval)", HttpStatusCode.Conflict)

                protectKeysDao.setKey(invitation.inviteeId, req.protectEncryptedKey)

                val epochId = epochsDao.createEpoch("MEMBER_JOINED", "User #${invitation.inviteeId} joined via invitation #$id")

                epochKeysDao.setKeys(epochId, req.epochEncryptedKeys.map { it.userId to it.encryptedKey })

                usersDao.setForumZone(UserId(invitation.inviteeId), ForumZone.INVITED)
                usersDao.setInvitedBy(UserId(invitation.inviteeId), user.id)

                val memberIds = usersDao.getInvitedUserIds().filter { it != invitation.inviteeId }
                for (memberId in memberIds)
                {
                    try {
                        val notifId = notificationsDao.createNotification(
                            userId = memberId,
                            type = "EPOCH_ROTATED",
                            message = "New member joined — encryption epoch updated",
                        )
                        val notif = notificationsDao.getNotification(notifId)
                        if (notif != null) pushForumNotification(memberId, notif)
                    } catch (e: Exception) { logger.warning("Notification sending failed: ${e.message}", e) }
                }

                call.respondApi(buildJsonObject { put("epochId", epochId) })
            } catch (e: Exception) {
                logger.warning("Failed to approve invitation: ${e.message}", e)
                call.respondApiError("Failed to approve invitation", HttpStatusCode.InternalServerError)
            }
        }

        post("/invite/{id}/reject")
        {
            val user = getForumUser() ?: return@post
            if (!requireForumAdmin(user)) return@post
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post call.respondApiError("Invalid invitation id", HttpStatusCode.BadRequest)
            val rejected = invitationsDao.rejectInvitation(id)
            if (!rejected) return@post call.respondApiError("Invitation no longer pending", HttpStatusCode.Conflict)
            call.respondApi(Unit)
        }

        // ==================== Admin ====================
        post("/admin/grant-invite")
        {
            val user = getForumUser() ?: return@post
            if (!requireForumAdmin(user)) return@post
            val req = call.receive<GrantInviteRequest>()
            usersDao.setCanInvite(UserId(req.userId), true, req.slots)
            call.respondApi(Unit)
        }

        post("/admin/revoke-invite")
        {
            val user = getForumUser() ?: return@post
            if (!requireForumAdmin(user)) return@post
            val req = call.receive<RevokeInviteRequest>()
            usersDao.setCanInvite(UserId(req.userId), false, 0)
            call.respondApi(Unit)
        }

        get("/admin/invitations")
        {
            val user = getForumUser() ?: return@get
            if (!requireForumAdmin(user)) return@get
            val pending = invitationsDao.getPendingInvitations()
            val pendingJson = Json.encodeToJsonElement(pending)
            call.respondApi(buildJsonObject {
                put("invitations", pendingJson)
            })
        }

        post("/admin/init")
        {
            val user = getForumUser() ?: return@post
            if (!requireForumAdmin(user)) return@post
            val req = call.receive<ApproveInviteRequest>()

            protectKeysDao.setKey(user.id.value, req.protectEncryptedKey)
            val epochId = epochsDao.createEpoch("INIT", "Forum initialized by admin")
            epochKeysDao.setKeys(epochId, req.epochEncryptedKeys.map { it.userId to it.encryptedKey })

            call.respondApi(buildJsonObject { put("epochId", epochId) })
        }

        post("/admin/set-admin")
        {
            val user = getForumUser() ?: return@post
            if (!requireForumAdmin(user)) return@post
            val req = call.receive<GrantInviteRequest>()
            usersDao.setForumAdmin(UserId(req.userId), true)
            call.respondApi(Unit)
        }

        // ==================== Notifications ====================
        get("/notifications")
        {
            val user = getForumUser() ?: return@get
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
            val (total, list) = notificationsDao.getNotifications(user.id.value, page)
            val notifsJson = Json.encodeToJsonElement(list)
            call.respondApi(buildJsonObject {
                put("total", total)
                put("notifications", notifsJson)
            })
        }

        post("/notifications/read")
        {
            val user = getForumUser() ?: return@post
            val req = call.receive<ReadNotificationRequest>()
            notificationsDao.markRead(user.id.value, req.ids)
            call.respondApi(Unit)
        }

        // ==================== Attachments ====================
        post("/upload")
        {
            val user = getForumUser() ?: return@post
            val multipart = call.receiveMultipart()
            var attachmentType = "IMAGE"
            var topicId: Long? = null
            var postId: Long? = null
            var epochId: Long? = null
            var sortOrder = 0
            var fileBytes: ByteArray? = null
            var fileName: String? = null
            var mimeType: String? = null
            var fileSize: Long = 0

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> {
                        when (part.name) {
                            "attachmentType" -> attachmentType = part.value
                            "topicId" -> topicId = part.value.toLongOrNull()
                            "postId" -> postId = part.value.toLongOrNull()
                            "epochId" -> epochId = part.value.toLongOrNull()
                            "sortOrder" -> sortOrder = part.value.toIntOrNull() ?: 0
                        }
                    }
                    is PartData.FileItem -> {
                        fileName = part.originalFileName
                        mimeType = part.contentType?.toString()
                        fileBytes = part.provider().readBuffer().readBytes()
                        fileSize = fileBytes.size.toLong()
                    }
                    else -> {}
                }
                part.dispose()
            }

            if (fileBytes == null) return@post call.respondApiError("No file provided", HttpStatusCode.BadRequest)

            val allowedMimeTypes = setOf(
                "image/png", "image/jpeg", "image/gif", "image/webp", "image/avif",
                "video/mp4", "video/webm",
                "audio/mpeg", "audio/ogg", "audio/wav",
                "application/pdf",
            )
            val normalizedMime = mimeType?.lowercase()?.trim()
            if (normalizedMime == null || normalizedMime !in allowedMimeTypes)
                return@post call.respondApiError("Unsupported content type", HttpStatusCode.BadRequest)

            val maxSize = if (attachmentType == "VIDEO") 100L * 1024 * 1024 else 10L * 1024 * 1024
            if (fileSize > maxSize) return@post call.respondApiError("File too large", HttpStatusCode.PayloadTooLarge)

            val attachmentId = attachmentsDao.createAttachment(
                postId = postId, topicId = topicId,
                fileUrl = "", fileName = fileName, mimeType = mimeType,
                fileSize = fileSize, attachmentType = attachmentType,
                epochId = epochId, sortOrder = sortOrder,
            )
            FileUtils.saveForumFile(attachmentId, fileBytes.inputStream())
            val fileUrl = "/api/forum/attachments/$attachmentId"
            attachmentsDao.updateAttachmentUrl(attachmentId, fileUrl)
            call.respondApi(buildJsonObject { put("attachmentId", attachmentId); put("fileUrl", fileUrl) })
        }

        get("/attachments/{id}")
        {
            val user = getForumUser() ?: return@get
            val id = call.parameters["id"]?.toLongOrNull() ?: return@get call.respondApiError("Invalid attachment id", HttpStatusCode.BadRequest)
            val attachment = attachmentsDao.getAttachment(id)
                ?: return@get call.respondApiError("Attachment not found", HttpStatusCode.NotFound)
            if (attachment.topicId != null) {
                val topic = topicsDao.getTopic(attachment.topicId)
                if (topic != null && topic.zone != ForumPostZone.PUBLIC) {
                    if (!ForumAuth.canAccessZone(user.id, topic.zone))
                        return@get call.respondApiError("No access to this zone", HttpStatusCode.Forbidden)
                }
            }
            val file = FileUtils.getForumFile(id)
                ?: return@get call.respondApiError("File not found", HttpStatusCode.NotFound)
            call.response.header(HttpHeaders.CacheControl, "max-age=${30 * 24 * 60 * 60}")
            val contentType = attachment.mimeType?.let { ContentType.parse(it) } ?: ContentType.Application.OctetStream
            val safeFileName = (attachment.fileName ?: "file")
                .replace("\"", "").replace("\\", "").replace("\r", "").replace("\n", "")
                .trim().take(128).ifBlank { "file" }
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$safeFileName\"")
            call.response.header("X-Content-Type-Options", "nosniff")
            call.respondBytes(file.readBytes(), contentType)
        }

        delete("/attachments/{id}")
        {
            val user = getForumUser() ?: return@delete
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete call.respondApiError("Invalid attachment id", HttpStatusCode.BadRequest)
            val attachment = attachmentsDao.getAttachment(id)
                ?: return@delete call.respondApiError("Attachment not found", HttpStatusCode.NotFound)

            val isOwner = attachment.postId?.let { pid ->
                postsDao.getPost(pid)?.authorId == user.id.value
            } ?: (attachment.topicId?.let { tid ->
                topicsDao.getTopic(tid)?.authorId == user.id.value
            } ?: false)

            if (!isOwner && !ForumAuth.isAdmin(user.id))
                return@delete call.respondApiError("Not authorized to delete this attachment", HttpStatusCode.Forbidden)

            FileUtils.deleteForumFile(id)
            attachmentsDao.deleteAttachment(id)
            call.respondApi(Unit)
        }
    }
}
