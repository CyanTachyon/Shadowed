package moe.tachyon.shadowed.database

import kotlinx.datetime.Clock
import moe.tachyon.shadowed.dataClass.ForumPrivateEpoch
import moe.tachyon.shadowed.database.utils.singleOrNull
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class ForumPrivateEpochs : SqlDao<ForumPrivateEpochs.ForumPrivateEpochTable>(ForumPrivateEpochTable)
{
    object ForumPrivateEpochTable : IdTable<Long>("forum_private_epochs")
    {
        override val id = long("id").autoIncrement().entityId()
        override val primaryKey = PrimaryKey(id)

        val createdAt = long("created_at").default(Clock.System.now().toEpochMilliseconds())
        val changeType = varchar("change_type", 20)
        val changeDetail = text("change_detail").nullable().default(null)
    }

    private fun deserialize(row: ResultRow): ForumPrivateEpoch = ForumPrivateEpoch(
        id = row[table.id].value,
        createdAt = row[table.createdAt],
        changeType = row[table.changeType],
        changeDetail = row[table.changeDetail],
    )

    suspend fun createEpoch(changeType: String, changeDetail: String? = null): Long = query()
    {
        table.insertAndGetId {
            it[table.changeType] = changeType
            it[table.changeDetail] = changeDetail
        }.value
    }

    suspend fun getLatestEpoch(): ForumPrivateEpoch? = query()
    {
        table.selectAll().orderBy(table.id to SortOrder.DESC).limit(1)
            .singleOrNull()?.let(::deserialize)
    }

    suspend fun getEpoch(id: Long): ForumPrivateEpoch? = query()
    {
        table.selectAll().where { table.id eq id }
            .singleOrNull()?.let(::deserialize)
    }
}
