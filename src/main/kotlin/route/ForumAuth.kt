package moe.tachyon.shadowed.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import moe.tachyon.shadowed.dataClass.ForumPostZone
import moe.tachyon.shadowed.dataClass.ForumZone
import moe.tachyon.shadowed.dataClass.User
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.Sessions
import moe.tachyon.shadowed.database.Users

/**
 * 论坛权限检查工具
 */
object ForumAuth
{
    /**
     * 从 HTTP 请求头中验证用户身份
     */
    suspend fun authenticate(request: ApplicationRequest): User?
    {
        val username = request.header("X-Auth-User") ?: return null
        val sessionToken = request.header("X-Auth-Session") ?: return null

        val users = getKoin().get<Users>()
        val sessions = getKoin().get<Sessions>()
        val userId = sessions.verify(sessionToken) ?: return null
        val user = users.getUser(userId) ?: return null
        if (user.username != username) return null
        return user
    }

    /**
     * 验证用户是否为论坛管理员
     */
    suspend fun isAdmin(userId: UserId): Boolean
    {
        return getKoin().get<Users>().isForumAdmin(userId)
    }

    /**
     * 验证用户是否有权访问指定分区
     * 管理员始终可以访问所有分区
     */
    suspend fun canAccessZone(userId: UserId, zone: ForumPostZone): Boolean
    {
        if (zone == ForumPostZone.PUBLIC) return true
        val users = getKoin().get<Users>()
        if (users.isForumAdmin(userId)) return true
        return users.getUserForumZone(userId) == ForumZone.INVITED
    }

    /**
     * 验证用户是否有权邀请
     */
    suspend fun canInvite(userId: UserId): Boolean
    {
        val users = getKoin().get<Users>()
        return users.isForumAdmin(userId) || users.canInvite(userId)
    }
}

suspend fun ApplicationCall.authenticateSession(): User?
{
    val username = request.header("X-Auth-User") ?: return null
    val sessionToken = request.header("X-Auth-Session") ?: return null
    val users = getKoin().get<Users>()
    val sessions = getKoin().get<Sessions>()
    val userId = sessions.verify(sessionToken) ?: return null
    val user = users.getUser(userId) ?: return null
    if (user.username != username) return null
    return user
}

/**
 * 扩展函数：从请求中获取已认证用户，失败返回 401
 */
suspend fun RoutingContext.getForumUser(): User?
{
    val user = ForumAuth.authenticate(call.request) ?: run {
        call.respondApiError("Authentication required", HttpStatusCode.Unauthorized)
        return null
    }
    return user
}

/**
 * 扩展函数：验证论坛管理员权限，失败返回 403
 */
suspend fun RoutingContext.requireForumAdmin(user: User): Boolean
{
    if (!ForumAuth.isAdmin(user.id)) {
        call.respondApiError("Admin access required", HttpStatusCode.Forbidden)
        return false
    }
    return true
}
