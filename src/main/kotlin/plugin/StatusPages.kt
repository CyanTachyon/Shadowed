@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.shadowed.plugin.statusPages

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import moe.tachyon.shadowed.logger.ShadowedLogger
import moe.tachyon.shadowed.route.respondApiError

/**
 * 对于不同的状态码返回不同的页面
 */
fun Application.installStatusPages() = install(StatusPages)
{
    val logger = ShadowedLogger.getLogger()

    exception<BadRequestException> { call, cause ->
        logger.config("请求参数错误, 访问接口: ${call.request.path()}", cause)
        call.respondApiError("Bad request", HttpStatusCode.BadRequest)
    }

    exception<Throwable>
    { call, throwable ->
        logger.warning("未处理的异常, 访问接口: ${call.request.path()}", throwable)
        if (!call.response.isSent) {
            call.respondApiError("Internal server error", HttpStatusCode.InternalServerError)
        }
    }
}