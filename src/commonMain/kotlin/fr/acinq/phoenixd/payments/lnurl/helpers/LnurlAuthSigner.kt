package fr.acinq.phoenixd.payments.lnurl.helpers

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.crypto.Digest
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.bitcoin.crypto.hmac
import fr.acinq.lightning.crypto.LocalKeyManager
import io.ktor.http.*

object LnurlAuthSigner {

    /** Signs the challenge with the key provided and returns the public key and the DER-encoded signed data. */
    fun signChallenge(
        challenge: String,
        key: PrivateKey
    ): Pair<PublicKey, ByteVector> {
        return key.publicKey() to Crypto.compact2der(Crypto.sign(data = ByteVector32.fromValidHex(challenge), privateKey = key))
    }

    /**
     * Returns a key to sign a lnurl-auth challenge. This key is derived from the wallet's master key. The derivation
     * path depends on the domain provided and the type of the key.
     */
    fun getAuthLinkingKey(
        localKeyManager: LocalKeyManager,
        serviceUrl: Url,
    ): PrivateKey {
        val hashingKeyPath = KeyPath("m/138'/0")
        val hashingKey = localKeyManager.derivePrivateKey(hashingKeyPath)
        // the domain used for the derivation path may not be the full domain name.
        val path = getDerivationPathForDomain(
            domain = serviceUrl.host,
            hashingKey = hashingKey.privateKey.value.toByteArray()
        )
        return localKeyManager.derivePrivateKey(path).privateKey
    }

    /**
     * Returns lnurl-auth path derivation, as described in spec:
     * https://github.com/fiatjaf/lnurl-rfc/blob/luds/05.md
     *
     * Test vectors exist for path derivation.
     */
    private fun getDerivationPathForDomain(
        domain: String,
        hashingKey: ByteArray
    ): KeyPath {
        val fullHash = Digest.sha256().hmac(
            key = hashingKey,
            data = domain.encodeToByteArray(),
            blockSize = 64
        )
        require(fullHash.size >= 16) { "domain hash must be at least 16 bytes" }
        val path1 = fullHash.sliceArray(IntRange(0, 3)).let { Pack.int32BE(it, 0) }.toUInt()
        val path2 = fullHash.sliceArray(IntRange(4, 7)).let { Pack.int32BE(it, 0) }.toUInt()
        val path3 = fullHash.sliceArray(IntRange(8, 11)).let { Pack.int32BE(it, 0) }.toUInt()
        val path4 = fullHash.sliceArray(IntRange(12, 15)).let { Pack.int32BE(it, 0) }.toUInt()

        return KeyPath("m/138'/$path1/$path2/$path3/$path4")
    }
}