package moe.tachyon.shadowed.database

import moe.tachyon.shadowed.dataClass.ForumPrivateEpochKey
import moe.tachyon.shadowed.dataClass.UserId
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class ForumPrivateEpochKeys : SqlDao<ForumPrivateEpochKeys.ForumPrivateEpochKeyTable>(ForumPrivateEpochKeyTable)
{
    object ForumPrivateEpochKeyTable : Table("forum_private_epoch_keys")
    {
        val epochId = reference("epoch_id", ForumPrivateEpochs.ForumPrivateEpochTable, onDelete = ReferenceOption.CASCADE)
        val userId = reference("user_id", Users.UserTable, onDelete = ReferenceOption.CASCADE)
        val encryptedKey = text("encrypted_key")

        override val primaryKey = PrimaryKey(epochId, userId)
    }

    private fun deserialize(row: ResultRow): ForumPrivateEpochKey = ForumPrivateEpochKey(
        epochId = row[table.epochId].value,
        userId = row[table.userId].value.value,
        encryptedKey = row[table.encryptedKey],
    )

    suspend fun setKey(epochId: Long, userId: Int, encryptedKey: String) = query()
    {
        table.insertIgnore {
            it[table.epochId] = epochId
            it[table.userId] = UserId(userId)
            it[table.encryptedKey] = encryptedKey
        }
    }

    suspend fun setKeys(epochId: Long, keys: List<Pair<Int, String>>) = query()
    {
        for ((uid, encKey) in keys)
        {
            table.insertIgnore {
                it[table.epochId] = epochId
                it[table.userId] = UserId(uid)
                it[table.encryptedKey] = encKey
            }
        }
    }

    suspend fun getKey(epochId: Long, userId: Int): String? = query()
    {
        table.selectAll().where { (table.epochId eq epochId) and (table.userId eq UserId(userId)) }
            .singleOrNull()?.get(table.encryptedKey)
    }

    suspend fun hasKey(epochId: Long, userId: Int): Boolean = query()
    {
        table.selectAll().where { (table.epochId eq epochId) and (table.userId eq UserId(userId)) }
            .singleOrNull() != null
    }

    suspend fun getAllMemberIdsForEpoch(epochId: Long): List<Int> = query()
    {
        table.selectAll().where { table.epochId eq epochId }
            .map { it[table.userId].value.value }
    }
}
