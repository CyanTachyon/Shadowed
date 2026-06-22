package moe.tachyon.shadowed.route

import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import moe.tachyon.shadowed.contentNegotiationJson
import moe.tachyon.shadowed.dataClass.User
import moe.tachyon.shadowed.logger.ShadowedLogger
import moe.tachyon.shadowed.route.packets.*
import java.util.concurrent.ConcurrentHashMap

private val packetHandlers: Map<String, PacketHandler> = listOf(
    // Chat packets
    GetChatsHandler,
    GetMessagesHandler,
    SendMessageHandler,
    EditMessageHandler,
    GetChatDetailsHandler,
    RenameChatHandler,
    SetDoNotDisturb,
    SetBurnTimeHandler,
    MarkMessageReadHandler,
    ToggleReactionHandler,
    // Friend packets
    GetFriendsHandler,
    AddFriendHandler,
    SendFriendRequestHandler,
    AcceptFriendRequestHandler,
    RejectFriendRequestHandler,
    GetFriendRequestsHandler,
    UpdateNicknameHandler,
    UpdateFriendRemarkHandler,
    // Group packets
    CreateGroupHandler,
    AddMemberToChatHandler,
    KickMemberFromChatHandler,
    SetRequireApprovalHandler,
    HandleGroupInvitationHandler,
    GetGroupInvitationsHandler,
    // Broadcast packets
    SendBroadcastHandler,
    GetBroadcastsHandler,
    // Moment packets
    GetMomentsHandler,
    PostMomentHandler,
    ToggleMomentPermissionHandler,
    GetMomentPermissionHandler,
    GetMyMomentKeyHandler,
    DeleteMomentHandler,
    EditMomentHandler,
    CommentMomentHandler,
    GetMomentCommentsHandler,
    // Login packets (except login itself)
    GetPublicKeyByUsernameHandler,
    UpdateSignatureHandler,
).associateBy { it.packetName.lowercase() }

/**
 * In-memory rate bucket for failed WebSocket login attempts.
 *
 * In-memory only (no Redis); per-instance. For multi-instance deployment, replace this
 * with a Redis-backed store keyed by `username + "|" + remoteHost` so the limit is shared.
 */
private class LoginRateBucket(val timestamps: MutableList<Long> = mutableListOf())

/** Keys are `username + "|" + remoteHost`. */
private val loginRateStore = ConcurrentHashMap<String, LoginRateBucket>()

private const val LOGIN_MAX_FAILURES = 5

private const val LOGIN_WINDOW_MS = 60_000L

/**
 * Returns true if a login attempt for [key] is still under the failure threshold,
 * false if the identity has already exhausted its quota in the current window.
 *
 * Does NOT mutate the bucket; call [recordLoginFailure] after a real failure.
 */
private fun checkLoginRate(key: String): Boolean
{
    val now = System.currentTimeMillis()
    val bucket = loginRateStore[key] ?: return true
    synchronized(bucket.timestamps)
    {
        bucket.timestamps.removeAll { it < now - LOGIN_WINDOW_MS }
        return bucket.timestamps.size < LOGIN_MAX_FAILURES
    }
}

/**
 * Records a failed login attempt for [key], adding a timestamp to its bucket.
 * Drops the bucket entirely if pruning leaves it empty so the store cannot
 * grow unboundedly under username-rotation attacks.
 */
private fun recordLoginFailure(key: String)
{
    val now = System.currentTimeMillis()
    val bucket = loginRateStore.computeIfAbsent(key) { LoginRateBucket() }
    val shouldRemove = synchronized(bucket.timestamps)
    {
        bucket.timestamps.removeAll { it < now - LOGIN_WINDOW_MS }
        bucket.timestamps.add(now)
        bucket.timestamps.isEmpty()
    }
    if (shouldRemove) loginRateStore.remove(key, bucket)
}

/**
 * Clears the failure history for [key] after a successful authentication.
 */
private fun clearLoginFailures(key: String)
{
    loginRateStore.remove(key)
}

/**
 * Best-effort username extraction from a `login` packet payload. Returns null if the
 * payload is malformed or omits the `username` field; in that case the rate-limit key
 * falls back to an empty username so per-IP limiting still applies.
 */
private fun extractLoginUsername(packetData: String): String? = runCatching()
{
    contentNegotiationJson.parseToJsonElement(packetData)
        .jsonObject["username"]?.jsonPrimitive?.content
}.getOrNull()

fun Route.webSocketRoute() = webSocket("/socket") socket@
{
    // CSWSH guard: always enforce Origin allow-list. The previous debug-mode
    // bypass was removed because it was an easy-to-misconfigure foot-gun.
    val serverHost = application.environment.config.propertyOrNull("serverHost")
    val servers =
        if (serverHost == null) emptyList()
        else try { serverHost.getList() } catch (_: Throwable) { listOf(serverHost.getString()) }
    val origin = call.request.headers[HttpHeaders.Origin]
    val allowed = origin != null && servers.any { host ->
        listOf("http", "https", "ws", "wss").any { scheme ->
            origin == "$scheme://$host" || origin.startsWith("$scheme://$host:")
        }
    }
    if (!allowed)
    {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Origin not allowed"))
        return@socket
    }

    val wsLogger = ShadowedLogger.getLogger()
    var loginUser: User? = null
    try
    {
        launch()
        {
            // Ping-pong to keep connection alive
            while (true)
            {
                val packet = buildJsonObject()
                {
                    put("packet", "time")
                    put("t", Clock.System.now().toEpochMilliseconds())
                }
                send(contentNegotiationJson.encodeToString(packet))
                delay(30000L) // 30 seconds
            }
        }

        incoming.consumeAsFlow().filterIsInstance<Frame.Text>().collect()
        { frame ->
            try {
                val data = frame.readText().split("\n")
                val packetName = data[0]
                val packetData = data.subList(1, data.size).joinToString("\n")

                // Handle login packet separately
                if (packetName.equals("login", ignoreCase = true))
                {
                    val username = extractLoginUsername(packetData) ?: ""
                    val remoteHost = call.request.local.remoteHost
                    val rateKey = "$username|$remoteHost"
                    if (!checkLoginRate(rateKey))
                    {
                        val response = buildJsonObject()
                        {
                            put("packet", "login_result")
                            put("success", false)
                            put("error", "too_many_attempts")
                        }
                        send(contentNegotiationJson.encodeToString(response))
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Too many login attempts"))
                        return@collect
                    }
                    loginUser = LoginHandler.handle(this@socket, packetData)
                    if (loginUser == null) recordLoginFailure(rateKey) else clearLoginFailures(rateKey)
                    return@collect
                }

                // Require login for all other packets
                val user = loginUser
                if (user == null)
                {
                    val response = buildJsonObject()
                    {
                        put("packet", "require_login")
                    }
                    send(contentNegotiationJson.encodeToString(response))
                    return@collect
                }

                // Find and execute packet handler
                val handler = packetHandlers[packetName.lowercase()]
                handler?.handle(this@socket, packetData, user)
            } catch (e: Exception) {
                wsLogger.warning("Error handling WebSocket packet: ${e.message}", e)
            }
        }
    }
    finally
    {
        loginUser?.let()
        {
            SessionManager.removeSession(it.id, this)
        }
    }
}
