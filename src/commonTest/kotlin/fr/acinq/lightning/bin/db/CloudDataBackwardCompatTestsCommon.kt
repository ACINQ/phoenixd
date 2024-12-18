package fr.acinq.lightning.bin.db

import fr.acinq.bitcoin.byteVector32
import fr.acinq.lightning.bin.db.migrations.v3.types.mapIncomingPaymentFromV3
import fr.acinq.phoenix.db.cloud.*
import fr.acinq.phoenix.db.cloud.payments.InboundLiquidityLegacyWrapper
import fr.acinq.phoenix.db.cloud.payments.InboundLiquidityPaymentWrapper
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.Cbor
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test

@OptIn(ExperimentalSerializationApi::class)
class CloudDataBackwardCompatTestsCommon {

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun `read v3 db`() = runTest {
        val cbor = Cbor { ignoreUnknownKeys = true }
        FileSystem.RESOURCES.read("/clouddata/ios-testnet-cbor_payments.txt".toPath()) {
            while (true) {
                val line = readUtf8Line() ?: break
                val blob = line.hexToByteArray()
                val cloudData = cbor.decodeFromByteArray(CloudData.serializer(), blob)
                val walletPayment = when {
                    cloudData.incoming != null -> mapIncomingPaymentFromV3(
                        payment_hash = cloudData.incoming.preimage.byteVector32().sha256().toByteArray(), // unused
                        preimage = cloudData.incoming.preimage,
                        created_at = cloudData.incoming.createdAt,
                        origin_type = cloudData.incoming.origin.type,
                        origin_blob = cloudData.incoming.origin.blob,
                        received_amount_msat = null, // unused
                        received_at = cloudData.incoming.received?.ts,
                        received_with_type = cloudData.incoming.received?.type,
                        received_with_blob = cloudData.incoming.received?.blob
                    )
                    else -> null
                }
                println(walletPayment)
            }
        }
    }

}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CloudData(
    @SerialName("i")
    val incoming: IncomingPaymentWrapper?,
    @SerialName("o")
    val outgoing: LightningOutgoingPaymentWrapper?,
    @SerialName("so")
    val spliceOutgoing: SpliceOutgoingPaymentWrapper? = null,
    @SerialName("cc")
    val channelClose: ChannelClosePaymentWrapper? = null,
    @SerialName("sc")
    val spliceCpfp: SpliceCpfpPaymentWrapper? = null,
    @SerialName("il")
    val inboundLegacyLiquidity: InboundLiquidityLegacyWrapper? = null,
    @SerialName("ip")
    val inboundPurchaseLiquidity: InboundLiquidityPaymentWrapper? = null,
    @SerialName("v")
    val version: Int,
    @ByteString
    @SerialName("p")
    val padding: ByteArray?,
)