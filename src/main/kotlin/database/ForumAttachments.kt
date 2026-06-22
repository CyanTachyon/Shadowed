package moe.tachyon.shadowed.database

import kotlinx.datetime.Clock
import moe.tachyon.shadowed.dataClass.ForumAttachment
import moe.tachyon.shadowed.database.utils.singleOrNull
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class ForumAttachments : SqlDao<ForumAttachments.ForumAttachmentTable>(ForumAttachmentTable)
{
    object ForumAttachmentTable : IdTable<Long>("forum_attachments")
    {
        override val id = long("id").autoIncrement().entityId()
        override val primaryKey = PrimaryKey(id)

        val postId = reference("post_id", ForumPosts.ForumPostTable, onDelete = ReferenceOption.CASCADE).nullable().default(null)
        val topicId = reference("topic_id", ForumTopics.ForumTopicTable, onDelete = ReferenceOption.CASCADE).nullable().default(null)
        val fileUrl = text("file_url")
        val fileName = varchar("file_name", 255).nullable().default(null)
        val mimeType = varchar("mime_type", 100).nullable().default(null)
        val fileSize = long("file_size")
        val attachmentType = varchar("attachment_type", 10)  // IMAGE / VIDEO / FILE
        val epochId = reference("epoch_id", ForumPrivateEpochs.ForumPrivateEpochTable, onDelete = ReferenceOption.SET_NULL).nullable().default(null)
        val sortOrder = integer("sort_order").default(0)
        val createdAt = long("created_at").default(Clock.System.now().toEpochMilliseconds())
    }

    private fun deserialize(row: ResultRow): ForumAttachment = ForumAttachment(
        id = row[table.id].value,
        postId = row[table.postId]?.value,
        topicId = row[table.topicId]?.value,
        fileUrl = row[table.fileUrl],
        fileName = row[table.fileName],
        mimeType = row[table.mimeType],
        fileSize = row[table.fileSize],
        attachmentType = row[table.attachmentType],
        epochId = row[table.epochId]?.value,
        sortOrder = row[table.sortOrder],
        createdAt = row[table.createdAt],
    )

    suspend fun createAttachment(
        postId: Long? = null,
        topicId: Long? = null,
        fileUrl: String,
        fileName: String? = null,
        mimeType: String? = null,
        fileSize: Long,
        attachmentType: String,
        epochId: Long? = null,
        sortOrder: Int = 0,
    ): Long = query()
    {
        table.insertAndGetId {
            it[table.postId] = postId
            it[table.topicId] = topicId
            it[table.fileUrl] = fileUrl
            it[table.fileName] = fileName
            it[table.mimeType] = mimeType
            it[table.fileSize] = fileSize
            it[table.attachmentType] = attachmentType
            it[table.epochId] = epochId
            it[table.sortOrder] = sortOrder
        }.value
    }

    suspend fun getAttachmentsForPost(postId: Long): List<ForumAttachment> = query()
    {
        table.selectAll().where { table.postId eq postId }
            .orderBy(table.sortOrder to SortOrder.ASC)
            .map(::deserialize)
    }

    suspend fun getAttachmentsForTopic(topicId: Long): List<ForumAttachment> = query()
    {
        table.selectAll().where { (table.topicId eq topicId) and (table.postId.isNull()) }
            .orderBy(table.sortOrder to SortOrder.ASC)
            .map(::deserialize)
    }

    suspend fun deleteAttachment(id: Long) = query()
    {
        table.deleteWhere { table.id eq id }
    }

    suspend fun getAttachment(id: Long): ForumAttachment? = query()
    {
        table.selectAll().where { table.id eq id }.singleOrNull()?.let(::deserialize)
    }

    suspend fun updateAttachmentUrl(id: Long, fileUrl: String) = query()
    {
        table.update({ table.id eq id }) { it[table.fileUrl] = fileUrl }
    }
}
