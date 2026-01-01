package moe.tachyon.shadowed.database

import kotlinx.datetime.Clock
import moe.tachyon.shadowed.dataClass.Broadcast
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.utils.singleOrNull
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone
import org.jetbrains.exposed.sql.selectAll

class Broadcasts: SqlDao<Broadcasts.BroadcastTable>(BroadcastTable)
{
    object BroadcastTable: LongIdTable("broadcasts")
    {
        val sender = reference(
            "sender",
            Users.UserTable,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        ).nullable().index()
        val content = text("content")
        val system = bool("system").index().default(false)
        val time = timestamp("time").index()
    }

    suspend fun addBroadcast(senderId: UserId?, content: String): Long = query()
    {
        table.insertAndGetId()
        {
            it[this.sender] = senderId
            it[this.content] = content
            it[this.system] = false
            it[this.time] = Clock.System.now()
        }.value
    }

    suspend fun addSystemBroadcast(content: String): Long = query()
    {
        table.insertAndGetId()
        {
            it[this.sender] = null
            it[this.content] = content
            it[this.system] = true
            it[this.time] = Clock.System.now()
        }.value
    }

    suspend fun getBroadcast(id: Long): Broadcast? = query()
    {
        table
            .join(
                Users.UserTable,
                JoinType.LEFT,
                additionalConstraint = { table.sender eq Users.UserTable.id }
            )
            .selectAll()
            .where { table.id eq id }
            .singleOrNull()
            ?.let {
                Broadcast(
                    id = it[table.id].value,
                    senderId = it[table.sender]?.value,
                    senderName = it.getOrNull(Users.UserTable.username),
                    content = it[table.content],
                    system = it[table.system],
                    time = it[table.time].toEpochMilliseconds(),
                )
            }
    }

    suspend fun getBroadcasts(system: Boolean?, before: Long, count: Int): List<Broadcast> = query()
    {
        table.join(
            Users.UserTable,
            JoinType.LEFT,
            additionalConstraint = { table.sender eq Users.UserTable.id }
        )
            .selectAll()
            .where {
                if (system == null) Op.TRUE
                else table.system eq system
            }
            .andWhere { table.id less before }
            .orderBy(table.time to SortOrder.DESC)
            .limit(count)
            .map {
                Broadcast(
                    id = it[table.id].value,
                    senderId = it[table.sender]?.value,
                    senderName = it.getOrNull(Users.UserTable.username),
                    content = it[table.content],
                    system = it[table.system],
                    time = it[table.time].toEpochMilliseconds(),
                )
            }
    }
}