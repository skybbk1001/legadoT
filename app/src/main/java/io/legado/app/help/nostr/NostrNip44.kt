package io.legado.app.help.nostr

import fr.acinq.secp256k1.Secp256k1
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object NostrNip44 {

    private const val MAX_PLAINTEXT_BYTES = 1024 * 1024
    private val secureRandom = SecureRandom()

    fun conversationKey(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(privateKey.size == 32 && Secp256k1.secKeyVerify(privateKey)) {
            "Invalid Nostr client private key"
        }
        require(publicKey.size == 32) { "Invalid x-only Nostr public key" }

        val compressedPublicKey = byteArrayOf(2) + publicKey
        val sharedPoint = Secp256k1.pubKeyTweakMul(compressedPublicKey, privateKey)
        val sharedX = sharedPoint.copyOfRange(1, 33)
        return hkdfExtract("nip44-v2".toByteArray(Charsets.UTF_8), sharedX)
    }

    fun encrypt(plaintext: String, conversationKey: ByteArray): String {
        require(conversationKey.size == 32) { "Invalid conversation key length" }
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        require(plaintextBytes.isNotEmpty() && plaintextBytes.size <= MAX_PLAINTEXT_BYTES) {
            "NIP-44 plaintext length is outside the supported range"
        }
        val nonce = ByteArray(32).also(secureRandom::nextBytes)
        return encrypt(plaintextBytes, conversationKey, nonce)
    }

    internal fun encrypt(plaintext: ByteArray, conversationKey: ByteArray, nonce: ByteArray): String {
        require(conversationKey.size == 32 && nonce.size == 32)
        require(plaintext.isNotEmpty() && plaintext.size <= MAX_PLAINTEXT_BYTES)
        val keys = hkdfExpand(conversationKey, nonce, 76)
        val chachaKey = keys.copyOfRange(0, 32)
        val chachaNonce = keys.copyOfRange(32, 44)
        val hmacKey = keys.copyOfRange(44, 76)
        val padded = pad(plaintext)
        val ciphertext = chacha20(chachaKey, chachaNonce, padded)
        val mac = hmacSha256(hmacKey, nonce + ciphertext)
        return Base64.getEncoder().encodeToString(byteArrayOf(2) + nonce + ciphertext + mac)
    }

    fun decrypt(payload: String, conversationKey: ByteArray): String {
        require(conversationKey.size == 32) { "Invalid conversation key length" }
        require(payload.length in 132..(MAX_PLAINTEXT_BYTES * 2)) { "Invalid NIP-44 payload length" }
        val decoded = Base64.getDecoder().decode(payload)
        require(decoded.size >= 99 && decoded[0].toInt() == 2) { "Unsupported NIP-44 payload" }
        val nonce = decoded.copyOfRange(1, 33)
        val ciphertext = decoded.copyOfRange(33, decoded.size - 32)
        val mac = decoded.copyOfRange(decoded.size - 32, decoded.size)
        val keys = hkdfExpand(conversationKey, nonce, 76)
        val expectedMac = hmacSha256(keys.copyOfRange(44, 76), nonce + ciphertext)
        require(MessageDigest.isEqual(mac, expectedMac)) { "NIP-44 authentication failed" }
        val padded = chacha20(keys.copyOfRange(0, 32), keys.copyOfRange(32, 44), ciphertext)
        return unpad(padded).toString(Charsets.UTF_8)
    }

    private fun pad(plaintext: ByteArray): ByteArray {
        val prefix = if (plaintext.size < 65536) {
            byteArrayOf((plaintext.size shr 8).toByte(), plaintext.size.toByte())
        } else {
            byteArrayOf(0, 0) + intToBigEndian(plaintext.size)
        }
        val paddedLength = paddedLength(plaintext.size)
        return prefix + plaintext + ByteArray(paddedLength - plaintext.size)
    }

    private fun unpad(padded: ByteArray): ByteArray {
        require(padded.size >= 34) { "Invalid NIP-44 padding" }
        val shortLength = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        val prefixLength: Int
        val plaintextLength: Long
        if (shortLength == 0) {
            require(padded.size >= 38) { "Invalid extended NIP-44 padding" }
            prefixLength = 6
            plaintextLength = bigEndianToLong(padded, 2)
            require(plaintextLength >= 65536) { "Invalid extended NIP-44 length" }
        } else {
            prefixLength = 2
            plaintextLength = shortLength.toLong()
        }
        require(plaintextLength in 1..MAX_PLAINTEXT_BYTES.toLong()) { "Invalid NIP-44 plaintext length" }
        val expectedPaddedLength = paddedLength(plaintextLength.toInt())
        require(padded.size == prefixLength + expectedPaddedLength) { "Invalid NIP-44 padded size" }
        val end = prefixLength + plaintextLength.toInt()
        require((end until padded.size).all { padded[it] == 0.toByte() }) { "Invalid NIP-44 padding bytes" }
        return padded.copyOfRange(prefixLength, end)
    }

    private fun paddedLength(length: Int): Int {
        require(length in 1..MAX_PLAINTEXT_BYTES)
        if (length <= 32) return 32
        val nextPowerOfTwo = Integer.highestOneBit(length - 1) shl 1
        val chunk = if (nextPowerOfTwo <= 256) 32 else nextPowerOfTwo / 8
        return chunk * ((length - 1) / chunk + 1)
    }

    private fun chacha20(key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        val engine = ChaCha7539Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        return ByteArray(input.size).also { output ->
            engine.processBytes(input, 0, input.size, output, 0)
        }
    }

    private fun hkdfExtract(salt: ByteArray, inputKeyMaterial: ByteArray): ByteArray =
        hmacSha256(salt, inputKeyMaterial)

    private fun hkdfExpand(pseudoRandomKey: ByteArray, info: ByteArray, size: Int): ByteArray {
        val output = ByteArray(size)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < size) {
            previous = hmacSha256(pseudoRandomKey, previous + info + byteArrayOf(counter.toByte()))
            val copyLength = minOf(previous.size, size - offset)
            previous.copyInto(output, offset, 0, copyLength)
            offset += copyLength
            counter++
        }
        return output
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun intToBigEndian(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    private fun bigEndianToLong(bytes: ByteArray, offset: Int): Long =
        (0 until 4).fold(0L) { value, index ->
            (value shl 8) or (bytes[offset + index].toLong() and 0xff)
        }
}