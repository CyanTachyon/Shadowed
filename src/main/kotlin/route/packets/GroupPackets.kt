package moe.tachyon.shadowed.route.packets

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.*
import moe.tachyon.shadowed.contentNegotiationJson
import moe.tachyon.shadowed.dataClass.ChatId
import moe.tachyon.shadowed.dataClass.ChatId.Companion.toChatId
import moe.tachyon.shadowed.dataClass.User
import moe.tachyon.shadowed.database.ChatMembers
import moe.tachyon.shadowed.database.Chats
import moe.tachyon.shadowed.database.GroupInvitations
import moe.tachyon.shadowed.database.Users
import moe.tachyon.shadowed.database.Messages
import moe.tachyon.shadowed.logger.ShadowedLogger
import moe.tachyon.shadowed.route.SessionManager
import moe.tachyon.shadowed.route.getKoin
import moe.tachyon.shadowed.route.sendChatDetails
import moe.tachyon.shadowed.route.sendChatList
import moe.tachyon.shadowed.route.distributeMessage
import moe.tachyon.shadowed.utils.FileUtils

private val logger = ShadowedLogger.getLogger()

object CreateGroupHandler : PacketHandler
{
    override val packetName = "create_group"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val (groupName, memberUsernames, encryptedKeys) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val name = json.jsonObject["name"]?.jsonPrimitive?.takeUnless { it is JsonNull }?.content ?: "New Group"
            val members = json.jsonObject["memberUsernames"]!!.jsonArray.map { it.jsonPrimitive.content }
            val keys = json.jsonObject["encryptedKeys"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
            Triple(name, members, keys)
        }.getOrNull() ?: return session.sendError("Create group failed: Invalid packet format")

        // Validate all members exist and get their user objects
        val users = getKoin().get<Users>()
        val memberUsers = memberUsernames.mapNotNull()
        { username ->
            users.getUserByUsername(username)
        }

        if (memberUsers.size != memberUsernames.size)
        {
            return session.sendError("Create group failed: One or more users not found")
        }

        // Check all members have keys
        val missingKeys = memberUsernames.filter { !encryptedKeys.containsKey(it) }
        if (missingKeys.isNotEmpty())
        {
            return session.sendError("Create group failed: Missing keys for: ${missingKeys.joinToString()}")
        }

        val chats = getKoin().get<Chats>()
        val chatMembers = getKoin().get<ChatMembers>()

        val chatId = chats.createChat(name = groupName, owner = loginUser.id)

        val creatorKey = encryptedKeys[loginUser.username]
        if (creatorKey != null)
            chatMembers.addMember(chatId, loginUser.id, creatorKey)
        memberUsers.forEach()
        { user ->
            val key = encryptedKeys[user.username]
            if (key != null && user.id != loginUser.id)
                chatMembers.addMember(chatId, user.id, key)
        }
        
        session.sendSuccess("Group created successfully")

        for (user in (memberUsers + loginUser).distinct())
            SessionManager.forEachSession(user.id) { s -> s.sendChatList(user.id) }
    }
}

object AddMemberToChatHandler : PacketHandler
{
    override val packetName = "add_member_to_chat"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val (chatIdVal, username, encryptedKey) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val id = json.jsonObject["chatId"]!!.jsonPrimitive.int
            val user = json.jsonObject["username"]!!.jsonPrimitive.content
            val key = json.jsonObject["encryptedKey"]!!.jsonPrimitive.content
            Triple(id, user, key)
        }.getOrNull() ?: return session.sendError("Invalid packet format")

        val chatId = ChatId(chatIdVal)
        val chats = getKoin().get<Chats>()
        val chatMembers = getKoin().get<ChatMembers>()
        
        // Check if this is a moment chat - only owner can invite
        val chat = chats.getChat(chatId) ?: return session.sendError("Chat not found")
        if (chat.isMoment && chat.owner != loginUser.id)
        {
            return session.sendError("Only the owner can invite viewers to their moments")
        }
        
        // Verify current user is a member of this chat
        if (!chatMembers.isMember(chatId, loginUser.id))
            return session.sendError("You are not a member of this chat")

        // Get target user
        val targetUser = getKoin().get<Users>().getUserByUsername(username) ?: return session.sendError("User not found: $username")

        // Check if user is already a member
        if (chatMembers.isMember(chatId, targetUser.id))
            return session.sendError("$username is already a member")

        // Check if the group requires approval for new members
        if (chat.requireApproval && chat.owner != loginUser.id)
        {
            // Need owner approval - create an invitation
            val groupInvitations = getKoin().get<GroupInvitations>()
            
            if (groupInvitations.hasPendingInvitation(chatId, targetUser.id))
                return session.sendError("An invitation for $username is already pending approval")

            groupInvitations.createInvitation(chatId, loginUser.id, targetUser.id, encryptedKey)

            session.sendSuccess("Invitation sent. Waiting for group owner's approval.")

            // Notify the group owner
            val notifyResponse = buildJsonObject()
            {
                put("packet", "group_invitation_received")
                put("chatId", chatId.value)
                put("chatName", chat.name)
                put("inviterName", loginUser.username)
                put("targetUsername", targetUser.username)
            }
            SessionManager.forEachSession(chat.owner) { s ->
                s.send(contentNegotiationJson.encodeToString(notifyResponse))
            }
            return
        }

        // No approval needed or inviter is the owner - add directly
        chatMembers.addMember(chatId, targetUser.id, encryptedKey)

        session.sendSuccess("Member added successfully")

        val members = chatMembers.getChatMembersDetailed(chatId)
        
        for (user in members)
            SessionManager.forEachSession(user.id) { s -> s.sendChatDetails(chat, members) }
        SessionManager.forEachSession(targetUser.id) { s -> s.sendChatList(targetUser.id) }

        val systemMessageId = getKoin().get<Messages>().addSystemMessage(
            content = "${loginUser.username} invited ${targetUser.username} to the chat",
            chatId = chatId
        )

        val systemMessage = getKoin().get<Messages>().getMessage(systemMessageId) ?: return
        distributeMessage(systemMessage, silent = true)
    }
}

/**
 * Group owner approves or rejects a group invitation.
 */
object HandleGroupInvitationHandler : PacketHandler
{
    override val packetName = "handle_group_invitation"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val (invitationId, approve) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val id = json.jsonObject["invitationId"]!!.jsonPrimitive.int
            val a = json.jsonObject["approve"]!!.jsonPrimitive.boolean
            Pair(id, a)
        }.getOrNull() ?: return session.sendError("Invalid packet format")

        val groupInvitations = getKoin().get<GroupInvitations>()
        val chats = getKoin().get<Chats>()
        val chatMembers = getKoin().get<ChatMembers>()

        val invitation = groupInvitations.getInvitation(invitationId)
            ?: return session.sendError("Invitation not found")

        val chat = chats.getChat(invitation.chatId)
            ?: return session.sendError("Chat not found")

        // Verify the current user is the group owner
        if (chat.owner != loginUser.id)
            return session.sendError("Only the group owner can approve invitations")

        if (invitation.status != "PENDING")
            return session.sendError("Invitation is no longer pending")

        if (approve)
        {
            groupInvitations.approveInvitation(invitationId)

            // Check if the user is already a member (may have been added through another invitation)
            if (!chatMembers.isMember(invitation.chatId, invitation.targetUserId))
            {
                chatMembers.addMember(invitation.chatId, invitation.targetUserId, invitation.encryptedKey)

                val members = chatMembers.getChatMembersDetailed(invitation.chatId)
                for (user in members)
                    SessionManager.forEachSession(user.id) { s -> s.sendChatDetails(chat, members) }
                SessionManager.forEachSession(invitation.targetUserId) { s -> s.sendChatList(invitation.targetUserId) }

                val systemMessageId = getKoin().get<Messages>().addSystemMessage(
                    content = "${invitation.inviterName} invited ${invitation.targetUsername} to the chat",
                    chatId = invitation.chatId
                )
                val systemMessage = getKoin().get<Messages>().getMessage(systemMessageId)
                if (systemMessage != null) distributeMessage(systemMessage, silent = true)
            }

            session.sendSuccess("Invitation approved")

            // Notify the inviter
            val notifyInviter = buildJsonObject()
            {
                put("packet", "group_invitation_approved")
                put("invitationId", invitationId)
                put("chatId", invitation.chatId.value)
            }
            SessionManager.forEachSession(invitation.inviterId) { s ->
                s.send(contentNegotiationJson.encodeToString(notifyInviter))
            }

            // Notify the target user
            val notifyTarget = buildJsonObject()
            {
                put("packet", "group_invitation_approved")
                put("invitationId", invitationId)
                put("chatId", invitation.chatId.value)
            }
            SessionManager.forEachSession(invitation.targetUserId) { s ->
                s.send(contentNegotiationJson.encodeToString(notifyTarget))
            }
        }
        else
        {
            groupInvitations.rejectInvitation(invitationId)
            session.sendSuccess("Invitation rejected")

            // Notify the inviter
            val notifyInviter = buildJsonObject()
            {
                put("packet", "group_invitation_rejected")
                put("invitationId", invitationId)
                put("chatId", invitation.chatId.value)
            }
            SessionManager.forEachSession(invitation.inviterId) { s ->
                s.send(contentNegotiationJson.encodeToString(notifyInviter))
            }
        }
    }
}

/**
 * Get all pending group invitations for groups owned by the current user.
 */
object GetGroupInvitationsHandler : PacketHandler
{
    override val packetName = "get_group_invitations"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val groupInvitations = getKoin().get<GroupInvitations>()
        val invitations = groupInvitations.getPendingInvitationsForOwner(loginUser.id)

        val response = buildJsonObject()
        {
            put("packet", "group_invitations_list")
            put("invitations", contentNegotiationJson.encodeToJsonElement(invitations))
        }
        session.send(contentNegotiationJson.encodeToString(response))
    }
}

/**
 * Group owner toggles the require approval setting.
 */
object SetRequireApprovalHandler : PacketHandler
{
    override val packetName = "set_require_approval"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val (chatIdVal, requireApproval) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val id = json.jsonObject["chatId"]!!.jsonPrimitive.int
            val a = json.jsonObject["requireApproval"]!!.jsonPrimitive.boolean
            Pair(id, a)
        }.getOrNull() ?: return session.sendError("Invalid packet format")

        val chatId = ChatId(chatIdVal)
        val chats = getKoin().get<Chats>()

        val chat = chats.getChat(chatId) ?: return session.sendError("Chat not found")
        if (chat.owner != loginUser.id)
            return session.sendError("Only the group owner can change this setting")

        chats.setRequireApproval(chatId, requireApproval)
        session.sendSuccess(if (requireApproval) "Invitations now require approval" else "Invitations no longer require approval")

        // Refresh chat details for all members
        val chatMembers = getKoin().get<ChatMembers>()
        val members = chatMembers.getChatMembersDetailed(chatId)
        val updatedChat = chats.getChat(chatId) ?: return
        for (user in members)
            SessionManager.forEachSession(user.id) { s -> s.sendChatDetails(updatedChat, members) }
    }
}

object KickMemberFromChatHandler : PacketHandler
{
    override val packetName = "kick_member_from_chat"
    
    override suspend fun handle(
        session: DefaultWebSocketServerSession,
        packetData: String,
        loginUser: User
    )
    {
        val (chatId, username) = runCatching()
        {
            val json = contentNegotiationJson.parseToJsonElement(packetData)
            val id = json.jsonObject["chatId"]!!.jsonPrimitive.int.toChatId()
            val user = json.jsonObject["username"]!!.jsonPrimitive.content
            Pair(id, user)
        }.getOrNull() ?: return session.sendError("Invalid packet format")
        
        val chats = getKoin().get<Chats>()
        val chatMembers = getKoin().get<ChatMembers>()
        val chat = chats.getChat(chatId) ?: return session.sendError("Chat not found")

        // Check if this is a moment chat - only owner can kick
        if (chat.isMoment && chat.owner != loginUser.id)
        {
            return session.sendError("Only the owner can remove viewers from their moments")
        }

        val isOwner = chats.isChatOwner(chatId, loginUser.id)

        if (chat.private || (isOwner && loginUser.username == username))
        {
            val members = chatMembers.getChatMembersDetailed(chatId)
            if (members.none { it.id == loginUser.id })
                return session.sendError("You are not a member of this chat")
            chats.deleteChat(chatId)

            // Get all message IDs that have files in this chat
            val fileMessageIds = getKoin().get<Messages>().getFileMessageIds(chatId)

            // Delete message files for this chat
            fileMessageIds.forEach()
            { msgId ->
                logger.warning("Failed to delete file for message $msgId")
                {
                    FileUtils.deleteChatFile(msgId)
                }
            }

            // Delete group avatar for this chat
            logger.warning("Failed to delete avatar for group $chatId")
            {
                FileUtils.deleteGroupAvatar(chatId)
            }
            getKoin().get<Messages>().deleteChatMessages(chatId)
            for (user in members)
                SessionManager.forEachSession(user.id) { s -> s.sendChatList(user.id) }
            return session.sendSuccess("Chat deleted successfully")
        }

        if (!isOwner && loginUser.username != username)
            return session.sendError("Only owner can kick members")
        
        val targetUser = getKoin().get<Users>().getUserByUsername(username) ?: return session.sendError("User not found: $username")
        
        val members = chatMembers.getChatMembersDetailed(chatId).filterNot { it.id == targetUser.id }
        if (members.size <= 2)
            return session.sendError("Cannot kick member: Chat must have at least 3 members")
        
        chatMembers.removeMember(chatId, targetUser.id)
        session.sendSuccess("Member kicked successfully")
        
        for (user in members)
            SessionManager.forEachSession(user.id) { s -> s.sendChatDetails(chat, members) }
        SessionManager.forEachSession(targetUser.id) { s -> s.sendChatList(targetUser.id) }

        val messageId = getKoin().get<Messages>().addSystemMessage(
            content = "${loginUser.username} removed ${targetUser.username} from the chat",
            chatId = chatId
        )
        val message = getKoin().get<Messages>().getMessage(messageId) ?: return
        distributeMessage(message, silent = true)
    }
}
