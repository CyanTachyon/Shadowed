package moe.tachyon.shadowed.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null
)

suspend inline fun <reified T> ApplicationCall.respondApi(data: T, status: HttpStatusCode = HttpStatusCode.OK) {
    respond(status, ApiResponse(success = true, data = data))
}

suspend inline fun ApplicationCall.respondApiError(message: String, status: HttpStatusCode = HttpStatusCode.BadRequest) {
    respond(status, ApiResponse<Unit>(success = false, error = message))
}
