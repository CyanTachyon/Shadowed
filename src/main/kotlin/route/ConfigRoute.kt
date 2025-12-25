package moe.tachyon.shadowed.route

import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun Route.configRoute()
{
    get("/config")
    {
        call.respond(
            buildJsonObject()
            {
                put("checkingKey", environment.config.property("checkingKey").getString())
            }
        )
    }

    get("/auth/params")
    {
        call.respond(
            buildJsonObject()
            {
                put("authKey", SERVER_AUTH_KEY)
            }
        )
    }
}
