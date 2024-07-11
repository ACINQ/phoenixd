package fr.acinq.lightning.bin.payments.lnurl.models

import fr.acinq.lightning.MilliSatoshi
import io.ktor.http.*

data class LnurlWithdraw(
    override val initialUrl: Url,
    val callback: Url,
    val k1: String,
    val defaultDescription: String,
    val minWithdrawable: MilliSatoshi,
    val maxWithdrawable: MilliSatoshi
) : Lnurl.Qualified {
    override fun toString(): String {
        return "LnurlWithdraw(defaultDescription='$defaultDescription', minWithdrawable=$minWithdrawable, maxWithdrawable=$maxWithdrawable, initialUrl=$initialUrl, callback=$callback)".take(100)
    }
}
