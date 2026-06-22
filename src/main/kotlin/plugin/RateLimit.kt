@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.shadowed.plugin.rateLimit

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

// In-memory only (no Redis); per-instance. Fine for single-instance deployments.
private class RateBucket(val timestamps: MutableList<Long> = mutableListOf())

private val rateStore = ConcurrentHashMap<String, RateBucket>()

private fun consume(key: String, max: Int, windowMs: Long): Boolean
{
    val now = System.currentTimeMillis()
    val bucket = rateStore.computeIfAbsent(key) { RateBucket() }
    synchronized(bucket.timestamps)
    {
        val iter = bucket.timestamps.iterator()
        while (iter.hasNext())
        {
            if (iter.next() < now - windowMs) iter.remove() else break
        }
        if (bucket.timestamps.size >= max) return false
        bucket.timestamps.add(now)
        return true
    }
}

private fun classify(path: String): Triple<String, Int, Long>?
{
    val windowMs = 60_000L
    return when
    {
        path == "/api/register" || path == "/api/resetPassword" -> Triple("auth", 5, windowMs)
        path == "/api/send_file" || path.startsWith("/api/upload/") -> Triple("upload", 20, windowMs)
        path == "/api/socket" -> null
        path.startsWith("/api/") -> Triple("general", 100, windowMs)
        else -> null
    }
}

private val rateLimitPlugin = createApplicationPlugin("ShadowedRateLimit")
{
    onCall { call ->
        val path = call.request.path()
        val (category, max, windowMs) = classify(path) ?: return@onCall
        val authed = call.request.headers["X-Auth-User"]
        val identity = if (authed != null) "u:$authed" else "ip:${call.request.local.remoteHost}"
        val key = "$identity:$category"
        if (!consume(key, max, windowMs))
        {
            call.respond(
                HttpStatusCode.TooManyRequests,
                buildJsonObject {
                    put("success", false)
                    put("message", "Rate limit exceeded. Try again later.")
                }
            )
        }
    }
}

fun Application.installRateLimit() = install(rateLimitPlugin)
