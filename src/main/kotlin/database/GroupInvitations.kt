package moe.tachyon.shadowed.database

import kotlinx.datetime.Clock
import moe.tachyon.shadowed.dataClass.ChatId
import moe.tachyon.shadowed.dataClass.GroupInvitation
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.utils.singleOrNull
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.koin.core.component.inject

class GroupInvitations : SqlDao<GroupInvitations.GroupInvitationTable>(GroupInvitationTable)
{
    private val users by inject<Users>()
    private val chats by inject<Chats>()

    object GroupInvitationTable : IdTable<Int>("group_invitations")
    {
        override val id = integer("id").autoIncrement().entityId()
        override val primaryKey = PrimaryKey(id)

        val chat = reference(
            "chat",
            Chats.ChatTable,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        ).index()
        val inviter = reference(
            "inviter",
            Users.UserTable,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        )
        val targetUser = reference(
            "target_user",
            Users.UserTable,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        )
        val encryptedKey = text("encrypted_key")
        val status = varchar("status", 20).default("PENDING")
        val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    }

    suspend fun createInvitation(
        chatId: ChatId,
        inviterId: UserId,
        targetUserId: UserId,
        encryptedKey: String
    ): Int = query()
    {
        table.insertAndGetId()
        {
            it[chat] = chatId
            it[inviter] = inviterId
            it[targetUser] = targetUserId
            it[this.encryptedKey] = encryptedKey
        }.value
    }

    suspend fun getPendingInvitations(chatId: ChatId): List<GroupInvitation> = query()
    {
        buildInvitationList((table.chat eq chatId) and (table.status eq "PENDING"))
    }

    suspend fun getPendingInvitationsForOwner(ownerId: UserId): List<GroupInvitation> = query()
    {
        val chatTable = chats.table
        val pendingChatIds = chatTable.select(chatTable.id)
            .where { (chatTable.owner eq ownerId) and (chatTable.private eq false) }
            .map { it[chatTable.id].value }

        if (pendingChatIds.isEmpty()) return@query emptyList()

        buildInvitationList((table.chat inList pendingChatIds) and (table.status eq "PENDING"))
    }

    suspend fun getInvitation(invitationId: Int): GroupInvitation? = query()
    {
        val userTable = users.table
        val chatTable = chats.table
        val t = table

        val row = (t
            .innerJoin(userTable, { t.inviter }, { userTable.id })
            .innerJoin(chatTable, { t.chat }, { chatTable.id }))
            .selectAll()
            .where { t.id eq invitationId }
            .singleOrNull() ?: return@query null

        val targetId = row[t.targetUser].value
        val targetUsername = userTable.select(userTable.username)
            .where { userTable.id eq targetId }
            .singleOrNull()?.get(userTable.username) ?: "Unknown"

        rowToInvitation(row, targetUsername)
    }

    suspend fun approveInvitation(invitationId: Int): Boolean = query()
    {
        table.update({ (table.id eq invitationId) and (table.status eq "PENDING") })
        {
            it[status] = "APPROVED"
        } > 0
    }

    suspend fun rejectInvitation(invitationId: Int): Boolean = query()
    {
        table.update({ (table.id eq invitationId) and (table.status eq "PENDING") })
        {
            it[status] = "REJECTED"
        } > 0
    }

    suspend fun hasPendingInvitation(chatId: ChatId, targetUserId: UserId): Boolean = query()
    {
        table.selectAll().where {
            (table.chat eq chatId) and (table.targetUser eq targetUserId) and (table.status eq "PENDING")
        }.count() > 0
    }

    private fun buildInvitationList(where: Op<Boolean>): List<GroupInvitation>
    {
        val userTable = users.table
        val chatTable = chats.table
        val t = table

        val rows = (t
            .innerJoin(userTable, { t.inviter }, { userTable.id })
            .innerJoin(chatTable, { t.chat }, { chatTable.id }))
            .selectAll()
            .where { where }
            .orderBy(t.createdAt, SortOrder.DESC)
            .toList()

        if (rows.isEmpty()) return emptyList()

        val targetUserIds = rows.map { it[t.targetUser].value }.distinct()
        val targetUsernames = userTable.select(userTable.id, userTable.username)
            .where { userTable.id inList targetUserIds }
            .associate { it[userTable.id].value to it[userTable.username] }

        return rows.map { row -> rowToInvitation(row, targetUsernames[row[t.targetUser].value] ?: "Unknown") }
    }

    private fun rowToInvitation(row: ResultRow, targetUsername: String): GroupInvitation
    {
        val userTable = users.table
        val chatTable = chats.table
        val t = table

        return GroupInvitation(
            id = row[t.id].value,
            chatId = row[t.chat].value,
            chatName = row[chatTable.name],
            inviterId = row[t.inviter].value,
            inviterName = row[userTable.username],
            targetUserId = row[t.targetUser].value,
            targetUsername = targetUsername,
            encryptedKey = row[t.encryptedKey],
            status = row[t.status],
            createdAt = row[t.createdAt],
        )
    }
}
