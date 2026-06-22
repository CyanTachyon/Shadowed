@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.shadowed.plugin.defaultHeaders

import io.ktor.server.application.*
import io.ktor.server.plugins.defaultheaders.*

fun Application.installDefaultHeaders() = install(DefaultHeaders) {
    // DefaultHeaders already adds X-Content-Type-Options: nosniff by default.
    header("X-Frame-Options", "DENY")
    header("Referrer-Policy", "strict-origin-when-cross-origin")
    header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
    header("Permissions-Policy", "geolocation=(), microphone=(), camera=()")
    header(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self' wss: https:; " +
            "font-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"
    )
}
