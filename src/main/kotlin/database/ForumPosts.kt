package moe.tachyon.shadowed.database

import kotlinx.datetime.Clock
import moe.tachyon.shadowed.dataClass.ForumPost
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.utils.singleOrNull
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus

class ForumPosts : SqlDao<ForumPosts.ForumPostTable>(ForumPostTable)
{
    object ForumPostTable : IdTable<Long>("forum_posts")
    {
        override val id = long("id").autoIncrement().entityId()
        override val primaryKey = PrimaryKey(id)

        val topicId = reference("topic_id", ForumTopics.ForumTopicTable, onDelete = ReferenceOption.CASCADE).index()
        val content = text("content")
        val authorId = reference("author_id", Users.UserTable, onDelete = ReferenceOption.CASCADE).index()
        val isAnonymous = bool("is_anonymous").default(false)
        val anonymousName = varchar("anonymous_name", 50).nullable().default(null)
        val floorNumber = integer("floor_number")
        val createdAt = long("created_at").default(Clock.System.now().toEpochMilliseconds())
        val editedAt = long("edited_at").nullable().default(null)
        val isDeleted = bool("is_deleted").default(false)
        val epochId = reference("epoch_id", ForumPrivateEpochs.ForumPrivateEpochTable, onDelete = ReferenceOption.SET_NULL).nullable().default(null)
        val replyToPostId = reference("reply_to_post_id", ForumPostTable, onDelete = ReferenceOption.SET_NULL).nullable().default(null).index()
    }

    private fun deserialize(row: ResultRow, authorName: String? = null, replyCount: Int = 0): ForumPost = ForumPost(
        id = row[table.id].value,
        topicId = row[table.topicId].value,
        content = row[table.content],
        authorId = row[table.authorId].value.value,
        authorName = authorName,
        isAnonymous = row[table.isAnonymous],
        anonymousName = row[table.anonymousName],
        floorNumber = row[table.floorNumber],
        createdAt = row[table.createdAt],
        editedAt = row[table.editedAt],
        isDeleted = row[table.isDeleted],
        epochId = row[table.epochId]?.value,
        replyToPostId = row[table.replyToPostId]?.value,
        replyCount = replyCount,
    )

    suspend fun createPost(
        topicId: Long,
        content: String,
        authorId: Int,
        isAnonymous: Boolean = false,
        anonymousName: String? = null,
        epochId: Long? = null,
        replyToPostId: Long? = null,
    ): Long = query()
    {
        // Atomically reserve a floor number: UPDATE acquires a row lock so concurrent
        // createPost calls for the same topic serialize; SELECT reads our own write.
        val topicTable = ForumTopics.ForumTopicTable
        topicTable.update({ topicTable.id eq topicId }) {
            it[topicTable.nextFloor] = topicTable.nextFloor + 1
        }
        val floorNumber = topicTable.select(topicTable.nextFloor)
            .where { topicTable.id eq topicId }
            .single()[topicTable.nextFloor]

        table.insertAndGetId {
            it[table.topicId] = topicId
            it[table.content] = content
            it[table.authorId] = UserId(authorId)
            it[table.isAnonymous] = isAnonymous
            it[table.anonymousName] = anonymousName
            it[table.floorNumber] = floorNumber
            it[table.epochId] = epochId
            it[table.replyToPostId] = replyToPostId
        }.value
    }

    suspend fun getPost(id: Long): ForumPost? = query()
    {
        val userTable = Users.UserTable
        val row = table.join(userTable, JoinType.INNER, table.authorId, userTable.id)
            .selectAll()
            .where { (table.id eq id) and (table.isDeleted eq false) }
            .singleOrNull() ?: return@query null
        val replyCount = table.select(table.id.count())
            .where { (table.replyToPostId eq id) and (table.isDeleted eq false) }
            .single()[table.id.count()].toInt()
        deserialize(row, row[userTable.username], replyCount)
    }

    suspend fun getPostsByTopic(
        topicId: Long,
        page: Int = 0,
        pageSize: Int = 50,
    ): Pair<Int, List<ForumPost>> = query()
    {
        val condition = (table.topicId eq topicId) and (table.isDeleted eq false)
        val total = table.select(table.id.count()).where(condition).single()[table.id.count()].toInt()

        val userTable = Users.UserTable
        val rows = table.join(userTable, JoinType.INNER, table.authorId, userTable.id)
            .selectAll()
            .where(condition)
            .orderBy(table.floorNumber to SortOrder.ASC)
            .limit(pageSize).offset((page * pageSize).toLong())
            .toList()

        // Batch reply count: one query for all post IDs on this page
        val postIds = rows.map { it[table.id].value }
        val replyCountMap = if (postIds.isNotEmpty()) {
            table.select(table.replyToPostId, table.id.count())
                .where { (table.replyToPostId inList postIds) and (table.isDeleted eq false) }
                .groupBy(table.replyToPostId)
                .associate { it[table.replyToPostId]!!.value to it[table.id.count()].toInt() }
        } else emptyMap()

        val posts = rows.map { row ->
            val postId = row[table.id].value
            deserialize(row, row[userTable.username], replyCountMap[postId] ?: 0)
        }

        total to posts
    }

    suspend fun editPost(id: Long, content: String) = query()
    {
        table.update({ table.id eq id }) {
            it[table.content] = content
            it[table.editedAt] = Clock.System.now().toEpochMilliseconds()
        }
    }

    suspend fun deletePost(id: Long) = query()
    {
        table.update({ table.id eq id }) { it[isDeleted] = true }
    }

    suspend fun isAuthor(postId: Long, userId: Int): Boolean = query()
    {
        table.selectAll().where { (table.id eq postId) and (table.authorId eq UserId(userId)) }
            .singleOrNull() != null
    }
}
