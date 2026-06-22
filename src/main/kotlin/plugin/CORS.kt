@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.shadowed.plugin.cors

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*

/**
 * Installs CORS. The host list is *always* taken from the configured `serverHost` list;
 * debug mode no longer relaxes CORS (anyHost + allowCredentials is a credential leakage
 * vector). Non-CORS debug behaviour (logging etc.) is unaffected.
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
    listOf("X-Auth-User", "X-Auth-Session", "X-Confirm-Action", "Content-Type", "Authorization").forEach { allowHeader(it) }
}
