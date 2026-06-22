package moe.tachyon.shadowed.database

import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.utils.singleOrNull
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

class Sessions : SqlDao<Sessions.SessionTable>(SessionTable)
{
    object SessionTable : LongIdTable("sessions")
    {
        val userId = reference("user_id", Users.UserTable, onDelete = ReferenceOption.CASCADE)
        val tokenHash = varchar("token_hash", 64).uniqueIndex()
        val createdAt = long("created_at")
        val expiresAt = long("expires_at")
    }

    suspend fun create(userId: UserId): String = query()
    {
        val token = UUID.randomUUID().toString()
        val now = Instant.now().epochSecond
        table.insert {
            it[table.userId] = userId
            it[table.tokenHash] = sha256(token)
            it[table.createdAt] = now
            it[table.expiresAt] = now + SESSION_TTL_SECONDS
        }
        token
    }

    suspend fun verify(token: String): UserId? = query()
    {
        val now = Instant.now().epochSecond
        table.selectAll()
            .where { (table.tokenHash eq sha256(token)) and (table.expiresAt greater now) }
            .singleOrNull()?.let { it[table.userId].value }
    }

    suspend fun deleteExpired(): Int = query()
    {
        val now = Instant.now().epochSecond
        table.deleteWhere { table.expiresAt lessEq now }
    }

    suspend fun deleteByUserId(userId: UserId): Int = query()
    {
        table.deleteWhere { table.userId eq userId }
    }

    private fun sha256(input: String): String
    {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object
    {
        private const val SESSION_TTL_SECONDS = 7L * 24 * 60 * 60
    }
}
