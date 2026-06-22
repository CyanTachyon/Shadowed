@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.shadowed.plugin.cors

import moe.tachyon.shadowed.debug
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*

/**
 * 安装跨域请求相关处理, 此功能主要是为了前端开发时方便调试, 故仅在debug模式下开启
 */
fun Application.installCORS() = install(CORS)
{
    val serverHost = this@installCORS.environment.config.propertyOrNull("serverHost")

    val servers =
        if (serverHost == null) emptyList()
        else runCatching { serverHost.getList() }.getOrElse { listOf(serverHost.getString()) }

    servers.forEach { allowHost(it, schemes = listOf("http", "https", "ws", "wss")) }
    allowNonSimpleContentTypes = true
    HttpMethod.DefaultMethods.forEach { allowMethod(it) }
    allowCredentials = true
    listOf("X-Auth-User", "X-Auth-Token", "Content-Type", "Authorization").forEach { allowHeader(it) }

    if (debug)
    {
        anyHost()
    }
}