package moe.tachyon.shadowed.database

import kotlinx.datetime.Clock
import moe.tachyon.shadowed.dataClass.ForumReaction
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.utils.singleOrNull
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class ForumReactions : SqlDao<ForumReactions.ForumReactionTable>(ForumReactionTable)
{
    object ForumReactionTable : Table("forum_reactions")
    {
        val postId = long("post_id").references(ForumPosts.ForumPostTable.id, onDelete = ReferenceOption.CASCADE)
        val userId = reference("user_id", Users.UserTable, onDelete = ReferenceOption.CASCADE)
        val emoji = varchar("emoji", 10)
        val createdAt = long("created_at").default(Clock.System.now().toEpochMilliseconds())

        override val primaryKey = PrimaryKey(postId, userId, emoji)
    }

    private fun deserialize(row: ResultRow): ForumReaction = ForumReaction(
        postId = row[table.postId],
        userId = row[table.userId].value.value,
        emoji = row[table.emoji],
        createdAt = row[table.createdAt],
    )

    suspend fun toggleReaction(postId: Long, userId: Int, emoji: String): Boolean = query()
    {
        val uid = UserId(userId)
        val existing = table.selectAll().where {
            (table.postId eq postId) and (table.userId eq uid) and (table.emoji eq emoji)
        }.singleOrNull()

        if (existing != null)
        {
            table.deleteWhere {
                (table.postId eq postId) and (table.userId eq uid) and (table.emoji eq emoji)
            }
            false // removed
        }
        else
        {
            table.insert {
                it[table.postId] = postId
                it[table.userId] = uid
                it[table.emoji] = emoji
            }
            true // added
        }
    }

    suspend fun getReactionsForPost(postId: Long): Map<String, List<Int>> = query()
    {
        table.selectAll().where { table.postId eq postId }
            .groupBy { it[table.emoji] }
            .mapValues { (_, rows) -> rows.map { it[table.userId].value.value } }
    }

    suspend fun getReactionsForPosts(postIds: List<Long>): Map<Long, Map<String, List<Int>>> = query()
    {
        if (postIds.isEmpty()) return@query emptyMap()
        table.selectAll().where { table.postId inList postIds }
            .groupBy { it[table.postId] }
            .mapValues { (_, rows) ->
                rows.groupBy { it[table.emoji] }
                    .mapValues { (_, emojiRows) -> emojiRows.map { it[table.userId].value.value } }
            }
    }
}
