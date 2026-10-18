package io.legado.app.help.crypto

import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "Hex input must contain an even number of characters" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

fun String.base64ToByteArray(): ByteArray {
    val normalized = replace("\\s".toRegex(), "")
    return try {
        Base64.getDecoder().decode(normalized)
    } catch (e: IllegalArgumentException) {
        // hutool 的 Base64 同时接受 URL-safe 字符表(-/_)，书源数据可能使用
        Base64.getUrlDecoder().decode(normalized)
    }
}

fun digest(algorithm: String, data: ByteArray): ByteArray =
    MessageDigest.getInstance(algorithm).digest(data)

fun digest(algorithm: String, input: InputStream): ByteArray {
    val digest = MessageDigest.getInstance(algorithm)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read > 0) digest.update(buffer, 0, read)
    }
    return digest.digest()
}

fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance(algorithm)
    mac.init(SecretKeySpec(key, algorithm))
    return mac.doFinal(data)
}
