package moe.tachyon.shadowed.database

import kotlinx.datetime.Clock
import moe.tachyon.shadowed.dataClass.FriendRequest
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.utils.singleOrNull
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.koin.core.component.inject

class FriendRequests : SqlDao<FriendRequests.FriendRequestTable>(FriendRequestTable)
{
    private val users by inject<Users>()

    object FriendRequestTable : IdTable<Int>("friend_requests")
    {
        override val id = integer("id").autoIncrement().entityId()
        override val primaryKey = PrimaryKey(id)

        val fromUser = reference(
            "from_user",
            Users.UserTable,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        ).index()
        val toUser = reference(
            "to_user",
            Users.UserTable,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        ).index()
        val status = varchar("status", 20).default("PENDING")
        val message = varchar("message", 200).nullable().default(null)
        val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    }

    suspend fun createRequest(
        fromUserId: UserId,
        toUserId: UserId,
        message: String? = null
    ): Int = query()
    {
        table.insertAndGetId()
        {
            it[fromUser] = fromUserId
            it[toUser] = toUserId
            it[this.message] = message
        }.value
    }

    suspend fun getPendingRequestsFull(userId: UserId): List<FriendRequest> = query()
    {
        val userTable = users.table

        val pendingRows = table.selectAll().where { (table.toUser eq userId) and (table.status eq "PENDING") }
            .orderBy(table.createdAt, SortOrder.DESC)
            .toList()

        if (pendingRows.isEmpty()) return@query emptyList()

        val allUserIds = pendingRows.flatMap { listOf(it[table.fromUser].value, it[table.toUser].value) }.distinct()
        val userInfo = userTable.select(userTable.id, userTable.username, userTable.nickname)
            .where { userTable.id inList allUserIds }
            .associate { it[userTable.id].value to (it[userTable.username] to it[userTable.nickname]) }

        pendingRows.map { row ->
            val fromId = row[table.fromUser].value
            val toId = row[table.toUser].value
            FriendRequest(
                id = row[table.id].value,
                fromUser = fromId,
                fromUsername = userInfo[fromId]?.first ?: "Unknown",
                fromNickname = userInfo[fromId]?.second,
                toUser = toId,
                toUsername = userInfo[toId]?.first ?: "Unknown",
                status = row[table.status],
                createdAt = row[table.createdAt],
                message = row[table.message],
            )
        }
    }

    suspend fun getSentPendingRequests(userId: UserId): List<FriendRequest> = query()
    {
        val userTable = users.table

        val pendingRows = table.selectAll().where { (table.fromUser eq userId) and (table.status eq "PENDING") }
            .orderBy(table.createdAt, SortOrder.DESC)
            .toList()

        if (pendingRows.isEmpty()) return@query emptyList()

        val allUserIds = pendingRows.flatMap { listOf(it[table.fromUser].value, it[table.toUser].value) }.distinct()
        val userInfo = userTable.select(userTable.id, userTable.username, userTable.nickname)
            .where { userTable.id inList allUserIds }
            .associate { it[userTable.id].value to (it[userTable.username] to it[userTable.nickname]) }

        pendingRows.map { row ->
            val fromId = row[table.fromUser].value
            val toId = row[table.toUser].value
            FriendRequest(
                id = row[table.id].value,
                fromUser = fromId,
                fromUsername = userInfo[fromId]?.first ?: "Unknown",
                fromNickname = userInfo[fromId]?.second,
                toUser = toId,
                toUsername = userInfo[toId]?.first ?: "Unknown",
                status = row[table.status],
                createdAt = row[table.createdAt],
                message = row[table.message],
            )
        }
    }

    suspend fun getRequest(requestId: Int): FriendRequest? = query()
    {
        val userTable = users.table

        val row = table.selectAll().where { table.id eq requestId }.singleOrNull() ?: return@query null

        val fromId = row[table.fromUser].value
        val toId = row[table.toUser].value
        val userInfo = userTable.select(userTable.id, userTable.username, userTable.nickname)
            .where { userTable.id inList listOf(fromId, toId) }
            .associate { it[userTable.id].value to (it[userTable.username] to it[userTable.nickname]) }

        FriendRequest(
            id = row[table.id].value,
            fromUser = fromId,
            fromUsername = userInfo[fromId]?.first ?: "Unknown",
            fromNickname = userInfo[fromId]?.second,
            toUser = toId,
            toUsername = userInfo[toId]?.first ?: "Unknown",
            status = row[table.status],
            createdAt = row[table.createdAt],
            message = row[table.message],
        )
    }

    suspend fun acceptRequest(requestId: Int): Boolean = query()
    {
        table.update({ (table.id eq requestId) and (table.status eq "PENDING") })
        {
            it[status] = "ACCEPTED"
        } > 0
    }

    suspend fun rejectRequest(requestId: Int): Boolean = query()
    {
        table.update({ (table.id eq requestId) and (table.status eq "PENDING") })
        {
            it[status] = "REJECTED"
        } > 0
    }

    suspend fun hasPendingRequest(fromUserId: UserId, toUserId: UserId): Boolean = query()
    {
        table.selectAll().where {
            (table.fromUser eq fromUserId) and (table.toUser eq toUserId) and (table.status eq "PENDING")
        }.count() > 0
    }

    suspend fun hasPendingRequestBetween(userA: UserId, userB: UserId): Boolean = query()
    {
        table.selectAll().where {
            ((table.fromUser eq userA) and (table.toUser eq userB) or
             ((table.fromUser eq userB) and (table.toUser eq userA))) and
            (table.status eq "PENDING")
        }.count() > 0
    }
}
