package moe.tachyon.shadowed.route.packets

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.*
import moe.tachyon.shadowed.contentNegotiationJson
import moe.tachyon.shadowed.dataClass.ChatId
import moe.tachyon.shadowed.dataClass.User
import moe.tachyon.shadowed.database.*
import moe.tachyon.shadowed.route.*

private val logger = moe.tachyon.shadowed.logger.ShadowedLogger.getLogger()

object GetFriendsHandler : PacketHandler
{
    override val packetName = "get_friends"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val friends = getKoin().get<Friends>()
        val chats = getKoin().get<Chats>()
        val chatMembers = getKoin().get<ChatMembers>()
        
        val friendsList = friends.getFriends(loginUser.id)

        // Get the user's moment chat to check viewer permissions
        val myMomentChat = chats.getMomentChatByOwner(loginUser.id)
        val momentViewerIds = if (myMomentChat != null)
        {
            chatMembers.getMemberIds(myMomentChat.id)
        }
        else
        {
            emptyList()
        }

        val response = buildJsonObject()
        {
            put("packet", "friends_list")
            put("friends", buildJsonArray()
            {
                friendsList.forEach()
                { friend ->
                    addJsonObject()
                    {
                        put("id", friend.id.value)
                        put("username", friend.username)
                        put("nickname", friend.nickname)
                        put("remark", friend.remark)
                        put("canViewMoments", momentViewerIds.contains(friend.id))
                    }
                }
            })
        }
        session.send(contentNegotiationJson.encodeToString(response))
    }
}

/**
 * Send a friend request. The request is persisted in the database.
 * The target user will be notified via WebSocket.
 */
object SendFriendRequestHandler : PacketHandler
{
    override val packetName = "send_friend_request"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val (targetUsername, message) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val u = json.jsonObject["targetUsername"]!!.jsonPrimitive.content
            val m = json.jsonObject["message"]?.jsonPrimitive?.content
            Pair(u, m)
        }.onFailure { logger.warning("Packet parse failed in SendFriendRequestHandler: ${it.message}", it) }.getOrNull() ?: return session.sendError("Send friend request failed: Invalid packet format")

        if (targetUsername.equals(loginUser.username, ignoreCase = true))
            return session.sendError("Cannot send friend request to yourself")

        val targetUser = getKoin().get<Users>().getUserByUsername(targetUsername) 
            ?: return session.sendError("User not found")

        val friends = getKoin().get<Friends>()
        val friendRequests = getKoin().get<FriendRequests>()

        // Check if already friends
        if (friends.areFriends(loginUser.id, targetUser.id))
            return session.sendError("Already friends with $targetUsername")

        // Check if there's already a pending request in either direction
        if (friendRequests.hasPendingRequestBetween(loginUser.id, targetUser.id))
            return session.sendError("A friend request is already pending between you and $targetUsername")

        val requestId = friendRequests.createRequest(loginUser.id, targetUser.id, message)

        session.sendSuccess("Friend request sent to $targetUsername")

        // Notify the target user if they're online
        val request = friendRequests.getRequest(requestId)
        if (request != null)
        {
            val notifyResponse = buildJsonObject()
            {
                put("packet", "friend_request_received")
                put("request", contentNegotiationJson.encodeToJsonElement(request))
            }
            SessionManager.forEachSession(targetUser.id) { s ->
                s.send(contentNegotiationJson.encodeToString(notifyResponse))
            }
        }
    }
}

/**
 * Accept a friend request. The accepting user generates the chat key and establishes the friendship.
 */
object AcceptFriendRequestHandler : PacketHandler
{
    override val packetName = "accept_friend_request"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val (requestId, keyForRequester, keyForSelf) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val id = json.jsonObject["requestId"]!!.jsonPrimitive.int
            val kfr = json.jsonObject["keyForRequester"]!!.jsonPrimitive.content
            val kfs = json.jsonObject["keyForSelf"]!!.jsonPrimitive.content
            Triple(id, kfr, kfs)
        }.onFailure { logger.warning("Packet parse failed in AcceptFriendRequestHandler: ${it.message}", it) }.getOrNull() ?: return session.sendError("Accept friend request failed: Invalid packet format")

        val friendRequests = getKoin().get<FriendRequests>()
        val friends = getKoin().get<Friends>()
        val chatMembers = getKoin().get<ChatMembers>()

        val request = friendRequests.getRequest(requestId)
            ?: return session.sendError("Friend request not found")

        // Verify the accepting user is the target of the request
        if (request.toUser != loginUser.id)
            return session.sendError("You cannot accept this friend request")

        if (request.status != "PENDING")
            return session.sendError("Friend request is no longer pending")

        // Check if already friends (may have been added through another request)
        if (friends.areFriends(request.fromUser, loginUser.id))
        {
            friendRequests.acceptRequest(requestId)
            return session.sendError("Already friends with this user")
        }

        val accepted = friendRequests.acceptRequest(requestId)
        if (!accepted) return session.sendError("Friend request is no longer pending")

        // Create the friendship (accepting user is loginUser)
        val chatId = friends.addFriend(request.fromUser, loginUser.id)
            ?: return session.sendError("Failed to create chat")

        // Add both users as chat members with their encrypted keys
        chatMembers.addMember(chatId, loginUser.id, keyForSelf)
        chatMembers.addMember(chatId, request.fromUser, keyForRequester)

        // Notify both users
        val response = buildJsonObject()
        {
            put("packet", "friend_added")
            put("chatId", chatId.value)
            put("isExisting", false)
            put("message", "Friend added & Chat created")
        }
        session.send(contentNegotiationJson.encodeToString(response))

        // Notify the requester
        val requesterNotify = buildJsonObject()
        {
            put("packet", "friend_request_accepted")
            put("requestId", requestId)
            put("chatId", chatId.value)
            put("acceptedByUsername", loginUser.username)
        }
        SessionManager.forEachSession(request.fromUser) { s ->
            s.send(contentNegotiationJson.encodeToString(requesterNotify))
            s.sendChatList(request.fromUser)
        }

        SessionManager.forEachSession(loginUser.id) { s -> s.sendChatList(loginUser.id) }
    }
}

/**
 * Reject a friend request.
 */
object RejectFriendRequestHandler : PacketHandler
{
    override val packetName = "reject_friend_request"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val requestId = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            json.jsonObject["requestId"]!!.jsonPrimitive.int
        }.onFailure { logger.warning("Packet parse failed in RejectFriendRequestHandler: ${it.message}", it) }.getOrNull() ?: return session.sendError("Invalid packet format")

        val friendRequests = getKoin().get<FriendRequests>()

        val request = friendRequests.getRequest(requestId)
            ?: return session.sendError("Friend request not found")

        // Verify the rejecting user is the target of the request
        if (request.toUser != loginUser.id)
            return session.sendError("You cannot reject this friend request")

        if (request.status != "PENDING")
            return session.sendError("Friend request is no longer pending")

        val rejected = friendRequests.rejectRequest(requestId)
        if (!rejected) return session.sendError("Friend request is no longer pending")

        session.sendSuccess("Friend request rejected")

        // Notify the requester
        val requesterNotify = buildJsonObject()
        {
            put("packet", "friend_request_rejected")
            put("requestId", requestId)
            put("rejectedByUsername", loginUser.username)
        }
        SessionManager.forEachSession(request.fromUser) { s ->
            s.send(contentNegotiationJson.encodeToString(requesterNotify))
        }
    }
}

/**
 * Get all pending friend requests for the current user.
 */
object GetFriendRequestsHandler : PacketHandler
{
    override val packetName = "get_friend_requests"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val friendRequests = getKoin().get<FriendRequests>()
        val requests = friendRequests.getPendingRequestsFull(loginUser.id)

        val response = buildJsonObject()
        {
            put("packet", "friend_requests_list")
            put("requests", contentNegotiationJson.encodeToJsonElement(requests))
        }
        session.send(contentNegotiationJson.encodeToString(response))
    }
}

/**
 * Update nickname for the current user.
 */
object UpdateNicknameHandler : PacketHandler
{
    override val packetName = "update_nickname"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val nickname = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            json.jsonObject["nickname"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        }.onFailure { logger.warning("Packet parse failed in UpdateNicknameHandler: ${it.message}", it) }.getOrNull()

        getKoin().get<Users>().updateNickname(loginUser.id, nickname)
        session.sendSuccess("Nickname updated")
    }
}

/**
 * Update remark for a friend.
 */
object UpdateFriendRemarkHandler : PacketHandler
{
    override val packetName = "update_friend_remark"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val (friendId, remark) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val id = json.jsonObject["friendId"]!!.jsonPrimitive.int
            val r = json.jsonObject["remark"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            Pair(id, r)
        }.onFailure { logger.warning("Packet parse failed in UpdateFriendRemarkHandler: ${it.message}", it) }.getOrNull() ?: return session.sendError("Invalid packet format")

        val friends = getKoin().get<Friends>()
        
        if (!friends.areFriends(loginUser.id, moe.tachyon.shadowed.dataClass.UserId(friendId)))
            return session.sendError("Not friends with this user")

        friends.updateRemark(loginUser.id, moe.tachyon.shadowed.dataClass.UserId(friendId), remark)
        session.sendSuccess("Remark updated")
    }
}

/**
 * Legacy handler kept for backward compatibility.
 * Now simply sends a friend request.
 */
object AddFriendHandler : PacketHandler
{
    override val packetName = "add_friend"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        // Redirect to send_friend_request behavior but without message
        val targetUsername = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            json.jsonObject["targetUsername"]!!.jsonPrimitive.content
        }.onFailure { logger.warning("Packet parse failed in AddFriendHandler: ${it.message}", it) }.getOrNull() ?: return session.sendError("Add friend failed: Invalid packet format")

        if (targetUsername.equals(loginUser.username, ignoreCase = true))
            return session.sendError("Cannot add yourself")

        val targetUser = getKoin().get<Users>().getUserByUsername(targetUsername) 
            ?: return session.sendError("User not found")

        val friends = getKoin().get<Friends>()
        val friendRequests = getKoin().get<FriendRequests>()

        // Check if already friends
        if (friends.areFriends(loginUser.id, targetUser.id))
        {
            // If already friends, just open the existing chat
            val existingChatId = friends.getFriendChat(loginUser.id, targetUser.id)
            if (existingChatId != null)
            {
                val response = buildJsonObject()
                {
                    put("packet", "friend_added")
                    put("chatId", existingChatId.value)
                    put("isExisting", true)
                    put("message", "Opening existing chat")
                }
                session.send(contentNegotiationJson.encodeToString(response))
            }
            return
        }

        // Check if there's already a pending request
        if (friendRequests.hasPendingRequestBetween(loginUser.id, targetUser.id))
            return session.sendError("A friend request is already pending")

        val requestId = friendRequests.createRequest(loginUser.id, targetUser.id, null)

        session.sendSuccess("Friend request sent to $targetUsername")

        // Notify the target user
        val request = friendRequests.getRequest(requestId)
        if (request != null)
        {
            val notifyResponse = buildJsonObject()
            {
                put("packet", "friend_request_received")
                put("request", contentNegotiationJson.encodeToJsonElement(request))
            }
            SessionManager.forEachSession(targetUser.id) { s ->
                s.send(contentNegotiationJson.encodeToString(notifyResponse))
            }
        }
    }
}
