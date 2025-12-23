package moe.tachyon.shadowed.database

import kotlinx.datetime.Clock
import moe.tachyon.shadowed.dataClass.ChatId
import moe.tachyon.shadowed.dataClass.Message
import moe.tachyon.shadowed.dataClass.MessageType
import moe.tachyon.shadowed.dataClass.UserId
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.selectAll

class Messages: SqlDao<Messages.MessageTable>(MessageTable)
{
    object MessageTable: LongIdTable("messages")
    {
        val content = text("content")
        val type = enumerationByName<MessageType>("type", 20).default(MessageType.TEXT)
        val time = timestamp("time")
        val chat = reference("chat", Chats.ChatTable, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE).index()
        val sender = reference("sender", Users.UserTable, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE).index()
        val isRead = bool("is_read").default(false)
    }

    suspend fun addChatMessage(
        content: String,
        type: MessageType,
        chatId: ChatId,
        senderId: UserId
    ): Long = query()
    {
        table.insertAndGetId()
        {
            it[table.content] = content
            it[table.type] = type
            it[table.chat] = chatId
            it[table.sender] = senderId
            it[table.isRead] = false
            it[table.time] = Clock.System.now()
        }.value
    }
    
    suspend fun getChatMessages(
        chatId: ChatId,
        begin: Long,
        count: Int
    ): List<Message> = query()
    {
        val usersTable = getKoin().get<Users>().table
        (table innerJoin usersTable)
            .selectAll()
            .where { table.chat eq chatId }
            .orderBy(table.time to SortOrder.DESC)
            .limit(count)
            .offset(start = begin)
            .map {
                Message(
                    id = it[table.id].value,
                    content = it[table.content],
                    type = it[table.type],
                    chatId = it[table.chat].value,
                    senderId = it[table.sender].value,
                    senderName = it[usersTable.username],
                    time = it[table.time].toEpochMilliseconds(),
                    isRead = it[table.isRead],
                )
            }
            .reversed()
    }
}