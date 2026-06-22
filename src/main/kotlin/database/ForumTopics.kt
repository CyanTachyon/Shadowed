package moe.tachyon.shadowed.database

import kotlinx.datetime.Clock
import moe.tachyon.shadowed.dataClass.ForumPostZone
import moe.tachyon.shadowed.dataClass.ForumTopic
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.utils.singleOrNull
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus

class ForumTopics : SqlDao<ForumTopics.ForumTopicTable>(ForumTopicTable)
{
    object ForumTopicTable : IdTable<Long>("forum_topics")
    {
        override val id = long("id").autoIncrement().entityId()
        override val primaryKey = PrimaryKey(id)

        val boardId = reference("board_id", ForumBoards.ForumBoardTable, onDelete = ReferenceOption.CASCADE).index()
        val zone = varchar("zone", 10)
        val title = text("title")
        val authorId = reference("author_id", Users.UserTable, onDelete = ReferenceOption.CASCADE).index()
        val isAnonymous = bool("is_anonymous").default(false)
        val anonymousName = varchar("anonymous_name", 50).nullable().default(null)
        val createdAt = long("created_at").default(Clock.System.now().toEpochMilliseconds())
        val pinned = bool("pinned").default(false)
        val locked = bool("locked").default(false)
        val viewCount = integer("view_count").default(0)
        val lastReplyAt = long("last_reply_at").nullable().default(null).index()
        val lastReplyUserId = reference("last_reply_user_id", Users.UserTable, onDelete = ReferenceOption.SET_NULL).nullable().default(null)
        val isDeleted = bool("is_deleted").default(false)
        // Per-topic atomic floor counter; incremented by createPost to assign floor numbers race-free
        val nextFloor = integer("next_floor").default(0)
    }

    // Backfill next_floor for topics that predate the column
    override fun Transaction.init()
    {
        exec("UPDATE forum_topics SET next_floor = COALESCE((SELECT MAX(floor_number) FROM forum_posts WHERE forum_posts.topic_id = forum_topics.id), 0) WHERE next_floor = 0")
    }

    private fun deserialize(row: ResultRow, replyCount: Int = 0, authorName: String? = null): ForumTopic = ForumTopic(
        id = row[table.id].value,
        boardId = row[table.boardId].value,
        zone = ForumPostZone.valueOf(row[table.zone]),
        title = row[table.title],
        authorId = row[table.authorId].value.value,
        authorName = authorName,
        isAnonymous = row[table.isAnonymous],
        anonymousName = row[table.anonymousName],
        createdAt = row[table.createdAt],
        pinned = row[table.pinned],
        locked = row[table.locked],
        viewCount = row[table.viewCount],
        lastReplyAt = row[table.lastReplyAt],
        lastReplyUserId = row[table.lastReplyUserId]?.value?.value,
        replyCount = replyCount,
        isDeleted = row[table.isDeleted],
    )

    suspend fun createTopic(
        boardId: Long,
        zone: ForumPostZone,
        title: String,
        authorId: Int,
        isAnonymous: Boolean = false,
        anonymousName: String? = null,
    ): Long = query()
    {
        table.insertAndGetId {
            it[table.boardId] = boardId
            it[table.zone] = zone.name
            it[table.title] = title
            it[table.authorId] = UserId(authorId)
            it[table.isAnonymous] = isAnonymous
            it[table.anonymousName] = anonymousName
            it[table.lastReplyAt] = Clock.System.now().toEpochMilliseconds()
        }.value
    }

    suspend fun getTopic(id: Long): ForumTopic? = query()
    {
        val postTable = ForumPosts.ForumPostTable
        val replyCount = postTable.select(postTable.id.count())
            .where { (postTable.topicId eq id) and (postTable.isDeleted eq false) and (postTable.floorNumber greater 1) }
            .singleOrNull()?.get(postTable.id.count())?.toInt() ?: 0

        val userTable = Users.UserTable
        val row = table.join(userTable, JoinType.INNER, table.authorId, userTable.id)
            .selectAll()
            .where { (table.id eq id) and (table.isDeleted eq false) }
            .singleOrNull() ?: return@query null
        deserialize(row, replyCount, row[userTable.username])
    }

    suspend fun getTopics(
        boardId: Long? = null,
        zone: ForumPostZone? = null,
        page: Int = 0,
        pageSize: Int = 20,
    ): Pair<Int, List<ForumTopic>> = query()
    {
        var condition: Op<Boolean> = table.isDeleted eq false
        boardId?.let { condition = condition and (table.boardId eq it) }
        zone?.let { condition = condition and (table.zone eq it.name) }

        val total = table.select(table.id.count()).where(condition).single()[table.id.count()].toInt()

        val userTable = Users.UserTable
        val rows = table.join(userTable, JoinType.INNER, table.authorId, userTable.id)
            .selectAll()
            .where(condition)
            .orderBy(table.pinned to SortOrder.DESC, table.lastReplyAt to SortOrder.DESC)
            .limit(pageSize).offset((page * pageSize).toLong())
            .toList()

        // Batch get reply counts
        val postTable = ForumPosts.ForumPostTable
        val topicIds = rows.map { it[table.id].value }
        val replyCounts = if (topicIds.isNotEmpty()) {
            postTable
                .select(postTable.topicId, postTable.id.count())
                .where {
                    (postTable.topicId inList topicIds) and
                    (postTable.isDeleted eq false) and
                    (postTable.floorNumber greater 1)
                }
                .groupBy(postTable.topicId)
                .associate { it[postTable.topicId].value to it[postTable.id.count()].toInt() }
        } else emptyMap()

        total to rows.map { row ->
            val authorName = row[Users.UserTable.username]
            deserialize(row, replyCounts[row[table.id].value] ?: 0, authorName)
        }
    }

    suspend fun updateTopic(id: Long, title: String? = null) = query()
    {
        table.update({ table.id eq id }) {
            title?.let { t -> it[table.title] = t }
        }
    }

    suspend fun deleteTopic(id: Long) = query()
    {
        table.update({ table.id eq id }) { it[isDeleted] = true }
    }

    suspend fun setPinned(id: Long, pinned: Boolean) = query()
    {
        table.update({ table.id eq id }) { it[table.pinned] = pinned }
    }

    suspend fun setLocked(id: Long, locked: Boolean) = query()
    {
        table.update({ table.id eq id }) { it[table.locked] = locked }
    }

    suspend fun incrementViewCount(id: Long) = query()
    {
        table.update({ table.id eq id }) {
            it[viewCount] = viewCount + 1
        }
    }

    suspend fun updateLastReply(id: Long, userId: Int) = query()
    {
        table.update({ table.id eq id }) {
            it[lastReplyAt] = Clock.System.now().toEpochMilliseconds()
            it[lastReplyUserId] = UserId(userId)
        }
    }

    suspend fun isAuthor(topicId: Long, userId: Int): Boolean = query()
    {
        table.selectAll().where { (table.id eq topicId) and (table.authorId eq UserId(userId)) }
            .singleOrNull() != null
    }

    suspend fun searchTopics(
        query: String,
        boardId: Long? = null,
        page: Int = 0,
        pageSize: Int = 20,
    ): Pair<Int, List<ForumTopic>> = query()
    {
        var condition: Op<Boolean> = (table.isDeleted eq false) and (table.zone eq ForumPostZone.PUBLIC.name)
        boardId?.let { condition = condition and (table.boardId eq it) }
        val escaped = query.lowercase()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        condition = condition and Op.build { table.title.lowerCase() like "%$escaped%" }

        val total = table.select(table.id.count()).where(condition).single()[table.id.count()].toInt()

        val userTable = Users.UserTable
        val rows = table.join(userTable, JoinType.INNER, table.authorId, userTable.id)
            .selectAll()
            .where(condition)
            .orderBy(table.lastReplyAt to SortOrder.DESC)
            .limit(pageSize).offset((page * pageSize).toLong())
            .toList()

        val postTable = ForumPosts.ForumPostTable
        val topicIds = rows.map { it[table.id].value }
        val replyCounts = if (topicIds.isNotEmpty()) {
            postTable
                .select(postTable.topicId, postTable.id.count())
                .where {
                    (postTable.topicId inList topicIds) and
                    (postTable.isDeleted eq false) and
                    (postTable.floorNumber greater 1)
                }
                .groupBy(postTable.topicId)
                .associate { it[postTable.topicId].value to it[postTable.id.count()].toInt() }
        } else emptyMap()

        total to rows.map { row ->
            val authorName = row[Users.UserTable.username]
            deserialize(row, replyCounts[row[table.id].value] ?: 0, authorName)
        }
    }
}
