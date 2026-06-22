package moe.tachyon.shadowed.database

import moe.tachyon.shadowed.dataClass.ForumZone
import moe.tachyon.shadowed.dataClass.User
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.utils.singleOrNull
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnoreAndGetId
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.update

class Users: SqlDao<Users.UserTable>(UserTable)
{
    /**
     * 用户信息表
     */
    object UserTable: IdTable<UserId>("users")
    {
        override val id = userId("id").autoIncrement().entityId()
        override val primaryKey = PrimaryKey(id)

        val username = varchar("username", 50).uniqueIndex()
        val password = text("encrypted_key")
        val publicKey = text("public_key")
        val privateKey = text("private_key")
        val signature = text("signature").default("")
        val donationAmount = long("donation_amount").default(0)
        val nickname = varchar("nickname", 50).nullable().default(null)

        // Forum fields
        val forumZone = varchar("forum_zone", 10).default(ForumZone.PUBLIC.name)
        val canInvite = bool("can_invite").default(false)
        val inviteSlots = integer("invite_slots").default(0)
        val invitedBy = reference("invited_by", UserTable, onDelete = ReferenceOption.SET_NULL, onUpdate = ReferenceOption.CASCADE).nullable().default(null)
        val isForumAdmin = bool("is_forum_admin").default(false)
    }

    private fun deserialize(row: ResultRow): User = User(
        id = row[table.id].value,
        username = row[table.username],
        password = row[table.password],
        publicKey = row[table.publicKey],
        privateKey = row[table.privateKey],
        signature = row[table.signature],
        isDonor = row[table.donationAmount] > 0,
        nickname = row[table.nickname],
    )

    suspend fun getUserForumZone(userId: UserId): ForumZone = query()
    {
        table.selectAll().where { table.id eq userId }
            .singleOrNull()?.let { ForumZone.valueOf(it[table.forumZone]) }
    } ?: ForumZone.PUBLIC

    suspend fun canInvite(userId: UserId): Boolean = query()
    {
        table.selectAll().where { table.id eq userId }
            .singleOrNull()?.let { it[table.canInvite] && (it[table.inviteSlots] == -1 || it[table.inviteSlots] > 0) }
    } ?: false

    suspend fun useInviteSlot(userId: UserId): Boolean = query()
    {
        val unlimited = table.selectAll()
            .where { (table.id eq userId) and (table.inviteSlots eq -1) }
            .singleOrNull() != null
        if (unlimited) return@query true
        val updated = table.update(
            { (table.id eq userId) and (table.inviteSlots greater 0) }
        ) {
            it[inviteSlots] = inviteSlots - 1
        }
        updated > 0
    }

    suspend fun setForumZone(userId: UserId, zone: ForumZone) = query()
    {
        update({ table.id eq userId }) { it[forumZone] = zone.name }
    }

    suspend fun setCanInvite(userId: UserId, canInvite: Boolean, slots: Int = 0) = query()
    {
        update({ table.id eq userId }) {
            it[this.canInvite] = canInvite
            it[inviteSlots] = slots
        }
    }

    suspend fun setInvitedBy(userId: UserId, inviterId: UserId) = query()
    {
        update({ table.id eq userId }) { it[invitedBy] = inviterId }
    }

    suspend fun isForumAdmin(userId: UserId): Boolean = query()
    {
        table.selectAll().where { table.id eq userId }
            .singleOrNull()?.get(table.isForumAdmin) ?: false
    }

    suspend fun getForumAdminIds(): List<Int> = query()
    {
        table.selectAll().where { table.isForumAdmin eq true }
            .map { it[table.id].value.value }
    }

    suspend fun getInviteSlots(userId: UserId): Int = query()
    {
        table.selectAll().where { table.id eq userId }
            .singleOrNull()?.get(table.inviteSlots) ?: 0
    }

    suspend fun getInvitedUserIds(): List<Int> = query()
    {
        table.selectAll().where { table.forumZone eq ForumZone.INVITED.name }
            .map { it[table.id].value.value }
    }

    suspend fun setForumAdmin(userId: UserId, isAdmin: Boolean) = query()
    {
        update({ table.id eq userId }) { it[isForumAdmin] = isAdmin }
    }

    suspend fun createUser(
        username: String,
        encryptedPassword: String,
        publicKey: String,
        encryptedPrivateKey: String
    ): UserId? = query()
    {
        insertIgnoreAndGetId()
        {
            it[this.username] = username
            it[this.password] = encryptedPassword
            it[this.publicKey] = publicKey
            it[this.privateKey] = encryptedPrivateKey
        }?.value
    }
    
    suspend fun getUserByUsername(username: String): User? = query()
    {
        table.selectAll().where { table.username.lowerCase() eq username.lowercase() }.singleOrNull()?.let(::deserialize)
    }

    suspend fun getUser(id: UserId): User? = query()
    {
        table.selectAll().where { table.id eq id }.singleOrNull()?.let(::deserialize)
    }

    suspend fun getUsers(ids: List<UserId>): Map<Int, User> = query()
    {
        if (ids.isEmpty()) return@query emptyMap()
        table.selectAll().where { table.id inList ids }
            .associate { it[table.id].value.value to deserialize(it) }
    }

    suspend fun updatePasswordAndKey(
        userId: UserId,
        newEncryptedPassword: String,
        newEncryptedPrivateKey: String
    ) = query()
    {
        update({ table.id eq userId })
        {
            it[password] = newEncryptedPassword
            it[privateKey] = newEncryptedPrivateKey
        }
    }

    suspend fun updateSignature(userId: UserId, newSignature: String) = query()
    {
        update({ table.id eq userId })
        {
            it[signature] = newSignature
        }
    }

    suspend fun updateNickname(userId: UserId, newNickname: String?) = query()
    {
        update({ table.id eq userId })
        {
            it[nickname] = newNickname
        }
    }

    suspend fun getDonors(): List<Pair<User, Long>> = query()
    {
        table.selectAll().where { table.donationAmount greater 0 }.orderBy(table.donationAmount to SortOrder.DESC)
            .map { row ->
                val user = deserialize(row)
                val amount = row[table.donationAmount]
                user to amount
            }
    }
}