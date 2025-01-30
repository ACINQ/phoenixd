package fr.acinq.phoenixd.payments.lnurl.models

import io.ktor.http.*

/**
 * This class describes the various types of Lnurls supported by phoenixd:
 * - auth
 * - pay
 * - withdraw
 *
 * It also contains the possible errors related to the Lnurl flow:
 * errors that break the specs, or errors raised when the data returned
 * by the Lnurl service are not valid.
 */
sealed interface Lnurl {

    val initialUrl: Url

    /**
     * Most lnurls must be executed first to be of any use, as they don't contain any info by themselves.
     */
    data class Request(override val initialUrl: Url, val tag: Tag?) : Lnurl

    /**
     * Qualified lnurls objects contain all the necessary data needed from the lnurl service for the user
     * to decide how to proceed.
     */
    sealed interface Qualified : Lnurl

    /** Tag associated to a Lnurl, usually in a `?tag=<tag>` parameter. */
    enum class Tag(val label: String) {
        Auth("login"),
        Withdraw("withdrawRequest"),
        Pay("payRequest")
    }
}
