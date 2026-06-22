package moe.tachyon.shadowed.route.packets

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.tachyon.shadowed.contentNegotiationJson
import moe.tachyon.shadowed.dataClass.ForumNotification
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.route.SessionManager

suspend fun pushForumNotification(userId: Int, notification: ForumNotification)
{
    SessionManager.forEachSession(UserId(userId)) { session ->
        val pushData = buildJsonObject {
            put("packet", "forum_notification")
            put("notification", contentNegotiationJson.encodeToString(ForumNotification.serializer(), notification))
        }
        session.send(contentNegotiationJson.encodeToString(pushData))
    }
}
