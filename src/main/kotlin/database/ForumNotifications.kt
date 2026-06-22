package moe.tachyon.shadowed.database

import kotlinx.datetime.Clock
import moe.tachyon.shadowed.dataClass.ForumNotification
import moe.tachyon.shadowed.dataClass.UserId
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class ForumNotifications : SqlDao<ForumNotifications.ForumNotificationTable>(ForumNotificationTable)
{
    object ForumNotificationTable : IdTable<Long>("forum_notifications")
    {
        override val id = long("id").autoIncrement().entityId()
        override val primaryKey = PrimaryKey(id)

        val userId = reference("user_id", Users.UserTable, onDelete = ReferenceOption.CASCADE)
        val type = varchar("type", 20)  // REPLY / MENTION / REACTION / SYSTEM
        val topicId = reference("topic_id", ForumTopics.ForumTopicTable, onDelete = ReferenceOption.CASCADE).nullable().default(null)
        val postId = reference("post_id", ForumPosts.ForumPostTable, onDelete = ReferenceOption.CASCADE).nullable().default(null)
        val fromUserId = reference("from_user_id", Users.UserTable, onDelete = ReferenceOption.CASCADE).nullable().default(null)
        val message = text("message").nullable().default(null)
        val isRead = bool("is_read").default(false)
        val createdAt = long("created_at").default(Clock.System.now().toEpochMilliseconds())

        init
        {
            index(false, userId, isRead)
        }
    }

    private fun deserialize(row: ResultRow): ForumNotification = ForumNotification(
        id = row[table.id].value,
        userId = row[table.userId].value.value,
        type = row[table.type],
        topicId = row[table.topicId]?.value,
        postId = row[table.postId]?.value,
        fromUserId = row[table.fromUserId]?.value?.value,
        message = row[table.message],
        isRead = row[table.isRead],
        createdAt = row[table.createdAt],
    )

    suspend fun createNotification(
        userId: Int,
        type: String,
        topicId: Long? = null,
        postId: Long? = null,
        fromUserId: Int? = null,
        message: String? = null,
    ): Long = query()
    {
        table.insertAndGetId {
            it[table.userId] = UserId(userId)
            it[table.type] = type
            it[table.topicId] = topicId
            it[table.postId] = postId
            it[table.fromUserId] = fromUserId?.let { uid -> UserId(uid) }
            it[table.message] = message
        }.value
    }

    suspend fun getNotification(id: Long): ForumNotification? = query()
    {
        table.selectAll().where { table.id eq id }.singleOrNull()?.let(::deserialize)
    }

    suspend fun getNotifications(userId: Int, page: Int = 0, pageSize: Int = 20): Pair<Int, List<ForumNotification>> = query()
    {
        val condition = table.userId eq UserId(userId)
        val total = table.select(table.id.count()).where(condition).single()[table.id.count()].toInt()

        val rows = table.selectAll().where(condition)
            .orderBy(table.createdAt to SortOrder.DESC)
            .limit(pageSize).offset((page * pageSize).toLong())
            .map(::deserialize)

        total to rows
    }

    suspend fun markRead(userId: Int, notificationIds: List<Long>? = null) = query()
    {
        val uid = UserId(userId)
        if (notificationIds != null && notificationIds.isNotEmpty())
        {
            table.update({ (table.userId eq uid) and (table.id inList notificationIds) }) {
                it[isRead] = true
            }
        }
        else
        {
            table.update({ table.userId eq uid }) { it[isRead] = true }
        }
    }

    suspend fun getUnreadCount(userId: Int): Int = query()
    {
        table.select(table.id.count())
            .where { (table.userId eq UserId(userId)) and (table.isRead eq false) }
            .single()[table.id.count()].toInt()
    }
}
