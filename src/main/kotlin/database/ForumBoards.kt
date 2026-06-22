package moe.tachyon.shadowed.database

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import moe.tachyon.shadowed.dataClass.ForumBoard
import moe.tachyon.shadowed.dataClass.ForumPostZone
import moe.tachyon.shadowed.database.utils.singleOrNull
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class ForumBoards : SqlDao<ForumBoards.ForumBoardTable>(ForumBoardTable)
{
    object ForumBoardTable : IdTable<Long>("forum_boards")
    {
        override val id = long("id").autoIncrement().entityId()
        override val primaryKey = PrimaryKey(id)

        val name = varchar("name", 100)
        val description = text("description").default("")
        val icon = varchar("icon", 50).default("")
        val zone = enumerationByName("zone", 20, ForumPostZone::class).default(ForumPostZone.PUBLIC)
        val sortOrder = integer("sort_order").default(0)
        val createdAt = long("created_at").default(Clock.System.now().toEpochMilliseconds())
    }

    private fun deserialize(row: ResultRow): ForumBoard = ForumBoard(
        id = row[table.id].value,
        name = row[table.name],
        description = row[table.description].ifEmpty { null },
        icon = row[table.icon].ifEmpty { null },
        zone = row[table.zone],
        sortOrder = row[table.sortOrder],
        createdAt = row[table.createdAt],
    )

    suspend fun createBoard(name: String, description: String = "", icon: String = "", zone: ForumPostZone = ForumPostZone.PUBLIC): Long = query()
    {
        table.insertAndGetId {
            it[table.name] = name
            it[table.description] = description
            it[table.icon] = icon
            it[table.zone] = zone
        }.value
    }

    suspend fun getBoard(id: Long): ForumBoard? = query()
    {
        table.selectAll().where { table.id eq id }.singleOrNull()?.let(::deserialize)
    }

    suspend fun getAllBoards(): List<ForumBoard> = query()
    {
        table.selectAll().orderBy(table.sortOrder to SortOrder.ASC, table.id to SortOrder.ASC)
            .map(::deserialize)
    }

    suspend fun updateBoard(id: Long, name: String? = null, description: String? = null, icon: String? = null, sortOrder: Int? = null, zone: ForumPostZone? = null) = query()
    {
        table.update({ table.id eq id }) {
            name?.let { n -> it[table.name] = n }
            description?.let { d -> it[table.description] = d }
            icon?.let { i -> it[table.icon] = i }
            sortOrder?.let { s -> it[table.sortOrder] = s }
            zone?.let { z -> it[table.zone] = z }
        }
    }

    suspend fun deleteBoard(id: Long) = query()
    {
        table.deleteWhere { table.id eq id }
    }
}
