package moe.tachyon.shadowed

import io.ktor.server.application.*
import io.ktor.server.routing.*
import moe.tachyon.shadowed.route.*

fun Application.router() = routing()
{
    route("/api")
    {
        configRoute()
        authRoute()
        userRoute()
        fileRoute()
        webSocketRoute()
    }
}