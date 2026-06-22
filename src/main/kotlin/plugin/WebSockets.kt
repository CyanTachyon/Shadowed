@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.shadowed.plugin.webSockets

import io.ktor.serialization.kotlinx.*
import io.ktor.server.application.*
import io.ktor.server.websocket.*
import moe.tachyon.shadowed.contentNegotiationJson
import kotlin.time.Duration.Companion.seconds

fun Application.installWebSockets() = install(WebSockets)
{
    pingPeriod = 15.seconds
    timeout = 15.seconds
    maxFrameSize = 256 * 1024L
    masking = true
    contentConverter = KotlinxWebsocketSerializationConverter(contentNegotiationJson)
}