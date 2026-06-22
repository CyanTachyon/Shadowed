@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.shadowed.plugin.defaultHeaders

import io.ktor.server.application.*
import io.ktor.server.plugins.defaultheaders.*
import moe.tachyon.shadowed.debug

/**
 * Default security headers.
 *
 * CSP note (M-3): `connect-src` previously allowed `https:` which let any
 * script exfiltrate data to an arbitrary HTTPS origin via fetch/XHR. The
 * frontend has no outbound HTTPS dependencies (all API calls are same-origin
 * under /api; the only non-HTTP transport is WebSocket, covered by `wss:`).
 * In debug mode `ws:` is also permitted so local development without TLS works.
 */
fun Application.installDefaultHeaders() = install(DefaultHeaders) {
    // DefaultHeaders already adds X-Content-Type-Options: nosniff by default.
    header("X-Frame-Options", "DENY")
    header("Referrer-Policy", "strict-origin-when-cross-origin")
    header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
    header("Permissions-Policy", "geolocation=(), microphone=(), camera=()")
    val connectSrc = if (debug) "'self' ws: wss:" else "'self' wss:"
    header(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data: blob:; media-src 'self' blob:; connect-src $connectSrc; " +
            "font-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"
    )
}
