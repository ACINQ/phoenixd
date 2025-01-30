package fr.acinq.phoenixd.payments.lnurl.models

import io.ktor.http.*

data class LnurlAuth(
    override val initialUrl: Url,
    val k1: String
) : Lnurl.Qualified {

    enum class Action {
        Register, Login, Link, Auth
    }

    val action = initialUrl.parameters["action"]?.let { action ->
        when (action.lowercase()) {
            "register" -> Action.Register
            "login" -> Action.Login
            "link" -> Action.Link
            "auth" -> Action.Auth
            else -> null
        }
    }

    override fun toString(): String {
        return "LnurlAuth(action=$action, initialUrl=$initialUrl)".take(100)
    }
}