package moe.tachyon.shadowed.database

import moe.tachyon.shadowed.dataClass.ChatId
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.utils.singleOrNull
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.sql.*
import org.koin.core.component.get

class Friends: SqlDao<Friends.FriendTable>(FriendTable)
{
    object FriendTable: CompositeIdTable("friends")
    {
        val userA = reference(
            "user_a",
            Users.UserTable,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        ).index()
        val userB = reference(
            "user_b",
            Users.UserTable,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        ).index()
        val chat = reference(
            "chat",
            Chats.ChatTable,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        ).index()
        val remarkForA = varchar("remark_for_a", 100).nullable().default(null)
        val remarkForB = varchar("remark_for_b", 100).nullable().default(null)
        // reference to chat is enough; moment viewer membership is stored in ChatMembers
        override val primaryKey: PrimaryKey = PrimaryKey(userA, userB)

        init
        {
            addIdColumn(userA)
            addIdColumn(userB)
        }
    }

    suspend fun addFriend(
        userAId: UserId,
        userBId: UserId,
    ): ChatId? = query()
    {
        val chatTable = get<Chats>().table

        val userA = minOf(userAId, userBId)
        val userB = maxOf(userAId, userBId)

        // Check if friendship already exists
        val existingFriend = selectAll().where { (table.userA eq userA) and (table.userB eq userB) }.singleOrNull()
        if (existingFriend != null) return@query existingFriend[table.chat].value

        val userTable = get<Users>().table
        val usernames = userTable.select(userTable.id, userTable.username)
            .where { (userTable.id eq userA) or (userTable.id eq userB) }
            .associate { it[userTable.id].value to it[userTable.username] }

        val chatName = "Friend Chat (${usernames[userA]}, ${usernames[userB]})"
        val chat = chatTable.insertIgnoreAndGetId()
        {
            it[this.name] = chatName
            it[this.owner] = userA
            it[this.private] = true
        }?.value ?: return@query null
        insertIgnoreAndGetId()
        {
            it[this.userA] = userA
            it[this.userB] = userB
            it[this.chat] = chat
        }
        chat
    }

    /**
     * Friend info including nickname and remark
     */
    data class FriendInfo(
        val id: UserId,
        val username: String,
        val nickname: String? = null,
        val remark: String? = null,
    )

    suspend fun getFriends(userId: UserId): List<FriendInfo> = query()
    {
        val userTable = get<Users>().table
        val chatTable = get<Chats>().table
        val queryA = table
            .join(userTable, JoinType.INNER, table.userB, userTable.id)
            .join(chatTable, JoinType.INNER, table.chat, chatTable.id)
            .selectAll()
            .where { table.userA eq userId }
            .orderBy(chatTable.lastChatAt, SortOrder.DESC)
            .map {
                FriendInfo(
                    id = it[userTable.id].value,
                    username = it[userTable.username],
                    nickname = it[userTable.nickname],
                    remark = it[table.remarkForA],
                )
            }
        val queryB = table
            .join(userTable, JoinType.INNER, table.userA, userTable.id)
            .join(chatTable, JoinType.INNER, table.chat, chatTable.id)
            .selectAll()
            .where { table.userB eq userId }
            .orderBy(chatTable.lastChatAt, SortOrder.DESC)
            .map {
                FriendInfo(
                    id = it[userTable.id].value,
                    username = it[userTable.username],
                    nickname = it[userTable.nickname],
                    remark = it[table.remarkForB],
                )
            }

        (queryA + queryB).distinctBy { it.id }
    }

    /**
     * Check if the friendship exists between two users
     */
    suspend fun areFriends(userAId: UserId, userBId: UserId): Boolean = query()
    {
        val a = minOf(userAId, userBId)
        val b = maxOf(userAId, userBId)
        table.selectAll().where { (table.userA eq a) and (table.userB eq b) }.count() > 0
    }

    suspend fun getFriendChat(userAId: UserId, userBId: UserId): ChatId? = query()
    {
        val a = minOf(userAId, userBId)
        val b = maxOf(userAId, userBId)
        table.selectAll().where { (table.userA eq a) and (table.userB eq b) }.singleOrNull()?.get(table.chat)?.value
    }

    /**
     * Update the remark for a friend. The remark is stored from the perspective of the current user.
     */
    suspend fun updateRemark(currentUserId: UserId, friendId: UserId, remark: String?): Boolean = query()
    {
        val a = minOf(currentUserId, friendId)
        val b = maxOf(currentUserId, friendId)
        val remarkColumn = if (currentUserId < friendId) table.remarkForA else table.remarkForB
        table.update({ (table.userA eq a) and (table.userB eq b) })
        {
            it[remarkColumn] = remark
        } > 0
    }

    /**
     * Get the remark for a specific friend
     */
    suspend fun getFriendRemark(currentUserId: UserId, friendId: UserId): String? = query()
    {
        val a = minOf(currentUserId, friendId)
        val b = maxOf(currentUserId, friendId)
        val row = table.selectAll().where { (table.userA eq a) and (table.userB eq b) }.singleOrNull() ?: return@query null
        if (currentUserId < friendId) row[table.remarkForA] else row[table.remarkForB]
    }
}