package moe.tachyon.shadowed.database

import kotlinx.datetime.Clock
import moe.tachyon.shadowed.dataClass.ForumInvitation
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.utils.singleOrNull
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class ForumInvitations : SqlDao<ForumInvitations.ForumInvitationTable>(ForumInvitationTable)
{
    object ForumInvitationTable : IdTable<Long>("forum_invitations")
    {
        override val id = long("id").autoIncrement().entityId()
        override val primaryKey = PrimaryKey(id)

        val inviterId = reference("inviter_id", Users.UserTable, onDelete = ReferenceOption.CASCADE)
        val inviteeId = reference("invitee_id", Users.UserTable, onDelete = ReferenceOption.CASCADE)
        val inviteCode = varchar("invite_code", 64).uniqueIndex()
        val status = varchar("status", 20).default("PENDING")
        val createdAt = long("created_at").default(Clock.System.now().toEpochMilliseconds())
        val usedAt = long("used_at").nullable().default(null)
        val expiresAt = long("expires_at").nullable().default(null)
    }

    private fun deserialize(row: ResultRow): ForumInvitation = ForumInvitation(
        id = row[table.id].value,
        inviterId = row[table.inviterId].value.value,
        inviteeId = row[table.inviteeId].value.value,
        inviteCode = row[table.inviteCode],
        status = row[table.status],
        createdAt = row[table.createdAt],
        usedAt = row[table.usedAt],
        expiresAt = row[table.expiresAt],
    )

    suspend fun createInvitation(inviterId: Int, inviteeId: Int, inviteCode: String, expiresAt: Long? = null): Long = query()
    {
        table.insertAndGetId {
            it[table.inviterId] = UserId(inviterId)
            it[table.inviteeId] = UserId(inviteeId)
            it[table.inviteCode] = inviteCode
            it[table.expiresAt] = expiresAt
        }.value
    }

    suspend fun getInvitation(id: Long): ForumInvitation? = query()
    {
        table.selectAll().where { table.id eq id }
            .singleOrNull()?.let(::deserialize)
    }

    suspend fun getByCode(code: String): ForumInvitation? = query()
    {
        table.selectAll().where { table.inviteCode eq code }
            .singleOrNull()?.let(::deserialize)
    }

    suspend fun getPendingInvitations(): List<ForumInvitation> = query()
    {
        table.selectAll().where { table.status eq "PENDING" }
            .orderBy(table.createdAt to SortOrder.ASC)
            .map(::deserialize)
    }

    suspend fun getInvitationsByInviter(inviterId: Int): List<ForumInvitation> = query()
    {
        table.selectAll().where { table.inviterId eq UserId(inviterId) }
            .orderBy(table.createdAt to SortOrder.DESC)
            .map(::deserialize)
    }

    suspend fun approveInvitation(id: Long): Boolean = query()
    {
        table.update({ (table.id eq id) and (table.status eq "PENDING") }) {
            it[table.status] = "APPROVED"
            it[table.usedAt] = Clock.System.now().toEpochMilliseconds()
        } > 0
    }

    suspend fun rejectInvitation(id: Long): Boolean = query()
    {
        table.update({ (table.id eq id) and (table.status eq "PENDING") }) {
            it[table.status] = "REJECTED"
        } > 0
    }

    suspend fun hasPendingForUser(inviteeId: Int): Boolean = query()
    {
        table.selectAll().where { (table.inviteeId eq UserId(inviteeId)) and (table.status eq "PENDING") }
            .singleOrNull() != null
    }
}
