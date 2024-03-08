package fr.acinq.lightning.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.sources.MapValueSource
import fr.acinq.bitcoin.Base58Check
import fr.acinq.bitcoin.Bech32
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.bin.conf.readConfFile
import fr.acinq.lightning.bin.homeDirectory
import fr.acinq.lightning.payment.Bolt11Invoice
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.util.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

fun main(args: Array<String>) =
    PhoenixCli()
        .subcommands(GetInfo(), GetBalance(), ListChannels(), CreateInvoice(), PayInvoice(), SendToAddress(), CloseChannel())
        .main(args)

data class HttpConf(val baseUrl: Url, val httpClient: HttpClient)

class PhoenixCli : CliktCommand() {
    private val datadir = homeDirectory / ".phoenix"
    private val confFile = datadir / "phoenix.conf"

    private val httpBindIp by option("--http-bind-ip", help = "Bind ip for the http api").default("127.0.0.1")
    private val httpBindPort by option("--http-bind-port", help = "Bind port for the http api").int().default(9740)
    private val httpPassword by option("--http-password", help = "Password for the http api").required()

    init {
        context {
            valueSource = MapValueSource(readConfFile(confFile))
            helpFormatter = { MordantHelpFormatter(it, showDefaultValues = true) }
        }
    }

    override fun run() {
        currentContext.obj = HttpConf(
            baseUrl = Url(
                url {
                    protocol = URLProtocol.HTTP
                    host = httpBindIp
                    port = httpBindPort
                }
            ),
            httpClient = HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(json = Json {
                        prettyPrint = true
                        isLenient = true
                    })
                }
                install(Auth) {
                    basic {
                        credentials {
                            BasicAuthCredentials("phoenix-cli", httpPassword)
                        }
                    }
                }
            }
        )
    }
}

class GetInfo : CliktCommand(name = "getinfo", help = "Show basic info about your node") {
    private val commonOptions by requireObject<HttpConf>()
    override fun run() {
        runBlocking {
            val res = commonOptions.httpClient.get(
                url = commonOptions.baseUrl / "getinfo"
            )
            echo(res.bodyAsText())
        }
    }
}

class GetBalance : CliktCommand(name = "getbalance", help = "Returns your current balance") {
    private val commonOptions by requireObject<HttpConf>()
    override fun run() {
        runBlocking {
            val res = commonOptions.httpClient.get(
                url = commonOptions.baseUrl / "getbalance"
            )
            echo(res.bodyAsText())
        }
    }
}

class ListChannels : CliktCommand(name = "listchannels", help = "List all channels") {
    private val commonOptions by requireObject<HttpConf>()
    override fun run() {
        runBlocking {
            val res = commonOptions.httpClient.get(
                url = commonOptions.baseUrl / "listchannels"
            )
            echo(res.bodyAsText())
        }
    }
}

class CreateInvoice : CliktCommand(name = "createinvoice", help = "Create a Lightning invoice", printHelpOnEmptyArgs = true) {
    private val commonOptions by requireObject<HttpConf>()
    private val amountSat by option("--amountSat").long()
    private val description by option("--description", "--desc").required()
    override fun run() {
        runBlocking {
            val res = commonOptions.httpClient.submitForm(
                url = (commonOptions.baseUrl / "createinvoice").toString(),
                formParameters = parameters {
                    amountSat?.let { append("amountSat", amountSat.toString()) }
                    append("description", description)
                }
            )
            echo(res.bodyAsText())
        }
    }
}

class PayInvoice : CliktCommand(name = "payinvoice", help = "Pay a Lightning invoice", printHelpOnEmptyArgs = true) {
    private val commonOptions by requireObject<HttpConf>()
    private val amountSat by option("--amountSat").long()
    private val invoice by option("--invoice").required().check { Bolt11Invoice.read(it).isSuccess }
    override fun run() {
        runBlocking {
            val res = commonOptions.httpClient.submitForm(
                url = (commonOptions.baseUrl / "payinvoice").toString(),
                formParameters = parameters {
                    amountSat?.let { append("amountSat", amountSat.toString()) }
                    append("invoice", invoice)
                }
            )
            echo(res.bodyAsText())
        }
    }
}

class SendToAddress : CliktCommand(name = "sendtoaddress", help = "Send to a Bitcoin address", printHelpOnEmptyArgs = true) {
    private val commonOptions by requireObject<HttpConf>()
    private val amountSat by option("--amountSat").long().required()
    private val address by option("--address").required().check { runCatching { Base58Check.decode(it) }.isSuccess || runCatching { Bech32.decodeWitnessAddress(it) }.isSuccess }
    private val feerateSatByte by option("--feerateSatByte").int().required()
    override fun run() {
        runBlocking {
            val res = commonOptions.httpClient.submitForm(
                url = (commonOptions.baseUrl / "sendtoaddress").toString(),
                formParameters = parameters {
                    append("amountSat", amountSat.toString())
                    append("address", address)
                    append("feerateSatByte", feerateSatByte.toString())
                }
            )
            echo(res.bodyAsText())
        }
    }
}

class CloseChannel : CliktCommand(name = "closechannel", help = "Close all channels", printHelpOnEmptyArgs = true) {
    private val commonOptions by requireObject<HttpConf>()
    private val channelId by option("--channelId").convert { ByteVector32.fromValidHex(it) }.required()
    private val address by option("--address").required().check { runCatching { Base58Check.decode(it) }.isSuccess || runCatching { Bech32.decodeWitnessAddress(it) }.isSuccess }
    private val feerateSatByte by option("--feerateSatByte").int().required()
    override fun run() {
        runBlocking {
            val res = commonOptions.httpClient.submitForm(
                url = (commonOptions.baseUrl / "closechannel").toString(),
                formParameters = parameters {
                    append("channelId", channelId.toHex())
                    append("address", address)
                    append("feerateSatByte", feerateSatByte.toString())
                }
            )
            echo(res.bodyAsText())
        }
    }
}

operator fun Url.div(path: String) = Url(URLBuilder(this).appendPathSegments(path))