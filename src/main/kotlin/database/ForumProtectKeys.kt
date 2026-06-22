package moe.tachyon.shadowed.database

import kotlinx.datetime.Clock
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.utils.singleOrNull
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class ForumProtectKeys : SqlDao<ForumProtectKeys.ForumProtectKeyTable>(ForumProtectKeyTable)
{
    object ForumProtectKeyTable : Table("forum_protect_keys")
    {
        val userId = reference("user_id", Users.UserTable, onDelete = ReferenceOption.CASCADE).uniqueIndex()
        val encryptedKey = text("encrypted_key")
        val updatedAt = long("updated_at").default(Clock.System.now().toEpochMilliseconds())

        override val primaryKey = PrimaryKey(userId)
    }

    suspend fun setKey(userId: Int, encryptedKey: String) = query()
    {
        val uid = UserId(userId)
        val exists = table.selectAll().where { table.userId eq uid }.singleOrNull()
        if (exists != null)
        {
            table.update({ table.userId eq uid }) {
                it[table.encryptedKey] = encryptedKey
                it[table.updatedAt] = Clock.System.now().toEpochMilliseconds()
            }
        }
        else
        {
            table.insert {
                it[table.userId] = uid
                it[table.encryptedKey] = encryptedKey
                it[table.updatedAt] = Clock.System.now().toEpochMilliseconds()
            }
        }
    }

    suspend fun getKey(userId: Int): String? = query()
    {
        table.selectAll().where { table.userId eq UserId(userId) }
            .singleOrNull()?.get(table.encryptedKey)
    }

    suspend fun hasKey(userId: Int): Boolean = query()
    {
        table.selectAll().where { table.userId eq UserId(userId) }
            .singleOrNull() != null
    }

    suspend fun getAllMemberIds(): List<Int> = query()
    {
        table.selectAll().map { it[table.userId].value.value }
    }
}
