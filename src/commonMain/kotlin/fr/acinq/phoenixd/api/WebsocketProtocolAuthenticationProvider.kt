package fr.acinq.phoenixd.api

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*

/**
 * Browsers do not support basic auth for websocket.
 * This [AuthenticationProvider] uses protocols header as a workaround.
 * See https://stackoverflow.com/a/77060459
 */
class WebsocketProtocolAuthenticationProvider(private val _name: String, val validate: suspend (List<HeaderValue>) -> Principal?) : AuthenticationProvider(object : Config(_name) {}) {

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val call = context.call
        val protocols = parseHeaderValue(call.request.headers[HttpHeaders.SecWebSocketProtocol])
        val principal = validate(protocols)

        val cause = when {
            principal == null -> AuthenticationFailedCause.InvalidCredentials
            else -> null
        }

        if (cause != null) {
            @Suppress("NAME_SHADOWING")
            context.challenge("websocket-protocol", cause) { challenge, call ->
                call.respond(HttpStatusCode.Unauthorized)
                challenge.complete()
            }
        }
        if (principal != null) {
            context.principal(name, principal)
        }
    }

}