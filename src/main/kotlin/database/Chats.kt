package moe.tachyon.shadowed.database

import kotlinx.datetime.Clock
import moe.tachyon.shadowed.dataClass.Chat
import moe.tachyon.shadowed.dataClass.ChatId
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.Users.UserTable
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class Chats: SqlDao<Chats.ChatTable>(ChatTable)
{
    object ChatTable: IdTable<ChatId>("chats")
    {
        override val id = chatId("id").autoIncrement().entityId()
        override val primaryKey = PrimaryKey(id)
        val name = varchar("name", 255).nullable()
        val owner = reference("owner", UserTable, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE).index()
        val private = bool("private")
        val lastChatAt = timestamp("last_chat_at").clientDefault { Clock.System.now() }
    }

    suspend fun createChat(
        name: String?,
        owner: UserId,
    ): ChatId = query()
    {
        table.insertAndGetId()
        {
            it[this.name] = name
            it[this.owner] = owner
            it[this.private] = false
        }.value
    }

    suspend fun getChat(chatId: ChatId): Chat? = query()
    {
        table.selectAll().where { table.id eq chatId }.singleOrNull()?.let()
        {
            Chat(
                id = it[table.id].value,
                name = it[table.name],
                owner = it[table.owner].value,
                private = it[table.private],
                lastChatAt = it[table.lastChatAt],
            )
        }
    }

    suspend fun isChatOwner(chatId: ChatId, userId: UserId): Boolean = query()
    {
        table.selectAll().where { (table.id eq chatId) and (table.owner eq userId) }.any()
    }

    suspend fun renameChat(chatId: ChatId, newName: String) = query()
    {
        table.update({ table.id eq chatId })
        {
            it[name] = newName
        }
    }

    suspend fun updateTime(chatId: ChatId) = query()
    {
        table.update({ table.id eq chatId })
        {
            it[lastChatAt] = Clock.System.now()
        }
    }

    suspend fun deleteChat(chatId: ChatId) = query()
    {
        table.deleteWhere { table.id eq chatId }
    }
}