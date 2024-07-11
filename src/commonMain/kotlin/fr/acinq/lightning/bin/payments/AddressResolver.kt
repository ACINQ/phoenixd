package fr.acinq.lightning.bin.payments

import fr.acinq.bitcoin.utils.Either
import fr.acinq.bitcoin.utils.Try
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.bin.payments.lnurl.LnurlHandler
import fr.acinq.lightning.bin.payments.lnurl.models.LnurlPay
import fr.acinq.lightning.wire.OfferTypes
import io.ktor.http.*

class AddressResolver(val dnsAddress: PayDnsAddress, val lnurlHandler: LnurlHandler) {

    suspend fun resolveLnUrl(username: String, domain: String, amount: MilliSatoshi, note: String?): Try<LnurlPay.InvoiceToPay> {
        val url = Url("https://$domain/.well-known/lnurlp/$username")
        return try {
            val lnurl = lnurlHandler.executeLnurl(url)
            val paymentParameters = lnurl as LnurlPay.PaymentParameters
            if (amount < paymentParameters.minSendable) throw IllegalArgumentException("amount too small (min=${paymentParameters.minSendable})")
            if (amount > paymentParameters.maxSendable) throw IllegalArgumentException("amount too big (max=${paymentParameters.maxSendable})")
            val invoice = lnurlHandler.getLnurlPayInvoice(lnurl, amount, note)
            Try.Success(invoice)
        } catch (e: Exception) {
            Try.Failure(e)
        }
    }

    suspend fun resolveAddress(username: String, domain: String, amount: MilliSatoshi, note: String?): Try<Either<LnurlPay.InvoiceToPay, OfferTypes.Offer>> {
        return when (val offer = dnsAddress.resolveBip353Offer(username, domain)) {
            null -> when (val lnurl = resolveLnUrl(username, domain, amount, note)) {
                is Try.Success -> Try.Success(Either.Left(lnurl.result))
                is Try.Failure -> Try.Failure(lnurl.error)
            }
            else -> Try.Success(Either.Right(offer))
        }
    }

}