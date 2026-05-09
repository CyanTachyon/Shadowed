package moe.tachyon.shadowed.database

import moe.tachyon.shadowed.dataClass.User
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.utils.singleOrNull
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insertIgnoreAndGetId
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
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