package fr.acinq.phoenixd.conf

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.*
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat


data class LSP(val walletParams: WalletParams, val swapInXpub: String) {

    companion object {

        private val trampolineFees = listOf(
            TrampolineFees(
                feeBase = 4.sat,
                feeProportional = 4_000,
                cltvExpiryDelta = CltvExpiryDelta(576)
            )
        )

        private val invoiceDefaultRoutingFees = InvoiceDefaultRoutingFees(
            feeBase = 1_000.msat,
            feeProportional = 100,
            cltvExpiryDelta = CltvExpiryDelta(144)
        )

        private val swapInParams = SwapInParams(
            minConfirmations = DefaultSwapInParams.MinConfirmations,
            maxConfirmations = DefaultSwapInParams.MaxConfirmations,
            refundDelay = DefaultSwapInParams.RefundDelay,
        )

        fun from(chain: Chain) = when (chain) {
            is Chain.Mainnet -> LSP(
                swapInXpub = "xpub69q3sDXXsLuHVbmTrhqmEqYqTTsXJKahdfawXaYuUt6muf1PbZBnvqzFcwiT8Abpc13hY8BFafakwpPbVkatg9egwiMjed1cRrPM19b2Ma7",
                walletParams = WalletParams(
                    trampolineNode = NodeUri(PublicKey.fromHex("03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"), "3.33.236.230", 9735),
                    trampolineFees,
                    invoiceDefaultRoutingFees,
                    swapInParams
                )
            )
            is Chain.Testnet3 -> LSP(
                swapInXpub = "tpubDAmCFB21J9ExKBRPDcVxSvGs9jtcf8U1wWWbS1xTYmnUsuUHPCoFdCnEGxLE3THSWcQE48GHJnyz8XPbYUivBMbLSMBifFd3G9KmafkM9og",
                walletParams = WalletParams(
                    trampolineNode = NodeUri(PublicKey.fromHex("03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134"), "13.248.222.197", 9735),
                    trampolineFees,
                    invoiceDefaultRoutingFees,
                    swapInParams
                )
            )
            else -> error("unsupported chain $chain")
        }
    }
}
