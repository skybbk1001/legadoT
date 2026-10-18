package io.legado.app.utils

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * 编码工具 escape base64
 */
@Suppress("unused")
object EncoderUtils {

    private const val HEX = "0123456789abcdef"
    private const val UPPER_HEX = "0123456789ABCDEF"

    private const val FLAG_NO_PADDING = 1
    private const val FLAG_NO_WRAP = 2
    private const val FLAG_CRLF = 4
    private const val FLAG_URL_SAFE = 8

    private val stdDecodeTable = IntArray(128) { -1 }
    private val webSafeDecodeTable = IntArray(128) { -1 }

    init {
        val stdAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val webSafeAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        stdAlphabet.forEachIndexed { index, c -> stdDecodeTable[c.code] = index }
        webSafeAlphabet.forEachIndexed { index, c -> webSafeDecodeTable[c.code] = index }
    }

    fun hexEncode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }

    fun hexDecode(value: String): ByteArray {
        require(value.length % 2 == 0) { "Hex input must contain an even number of characters" }
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun percentEncode(value: String, charset: java.nio.charset.Charset, safe: String): String {
        val safeAscii = BooleanArray(128)
        safe.forEach { if (it.code < safeAscii.size) safeAscii[it.code] = true }
        return buildString {
            value.toByteArray(charset).forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                if (unsigned < safeAscii.size && safeAscii[unsigned]) {
                    append(unsigned.toChar())
                } else {
                    append('%')
                    append(UPPER_HEX[unsigned ushr 4])
                    append(UPPER_HEX[unsigned and 0x0f])
                }
            }
        }
    }

    fun percentDecode(value: String, charset: java.nio.charset.Charset): String {
        val output = java.io.ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            if (value[index] == '%' && index + 2 < value.length) {
                val decoded = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (decoded != null) {
                    output.write(decoded)
                    index += 3
                    continue
                }
            }
            val codePoint = value.codePointAt(index)
            output.write(String(Character.toChars(codePoint)).toByteArray(charset))
            index += Character.charCount(codePoint)
        }
        return output.toByteArray().toString(charset)
    }

    fun escape(src: String): String {
        val tmp = StringBuilder()
        for (char in src) {
            val charCode = char.code
            if (charCode in 48..57 || charCode in 65..90 || charCode in 97..122) {
                tmp.append(char)
                continue
            }

            val prefix = when {
                charCode < 16 -> "%0"
                charCode < 256 -> "%"
                else -> "%u"
            }
            tmp.append(prefix).append(charCode.toString(16))
        }
        return tmp.toString()
    }

    @JvmOverloads
    fun base64Decode(str: String, flags: Int = 0): String {
        return String(base64DecodeToByteArray(str, flags))
    }

    @JvmOverloads
    fun base64Encode(str: String, flags: Int = FLAG_NO_WRAP): String? {
        return base64Encode(str.toByteArray(), flags)
    }

    @JvmOverloads
    fun base64Encode(bytes: ByteArray, flags: Int = FLAG_NO_WRAP): String {
        val encoder = if (flags and FLAG_URL_SAFE != 0) {
            Base64.getUrlEncoder()
        } else {
            Base64.getEncoder()
        }
        val paddedEncoder = if (flags and FLAG_NO_PADDING != 0) {
            encoder.withoutPadding()
        } else {
            encoder
        }
        val encoded = paddedEncoder.encodeToString(bytes)
        if (flags and FLAG_NO_WRAP != 0 || encoded.isEmpty()) {
            return encoded
        }
        val separator = if (flags and FLAG_CRLF != 0) "\r\n" else "\n"
        return encoded.chunked(76).joinToString(separator) + separator
    }

    @JvmOverloads
    fun base64DecodeToByteArray(str: String, flags: Int = 0): ByteArray {
        val decodeTable = if (flags and FLAG_URL_SAFE != 0) webSafeDecodeTable else stdDecodeTable
        val output = ByteArrayOutputStream(str.length / 4 * 3 + 3)
        var buffer = 0
        var quantumPos = 0
        var index = 0
        while (index < str.length) {
            val c = str[index]
            index++
            if (c == '\n' || c == '\r') {
                continue
            }
            if (c == '=') {
                when (quantumPos) {
                    2 -> {
                        output.write(buffer shr 4 and 0xff)
                        requireSecondPadding(str, index)
                    }

                    3 -> {
                        output.write(buffer shr 10 and 0xff)
                        output.write(buffer shr 2 and 0xff)
                        requireOnlySeparators(str, index)
                    }

                    else -> throw IllegalArgumentException("bad base-64")
                }
                return output.toByteArray()
            }
            val value = if (c.code < 128) decodeTable[c.code] else -1
            if (value < 0) throw IllegalArgumentException("bad base-64")
            buffer = buffer shl 6 or value
            quantumPos++
            if (quantumPos == 4) {
                output.write(buffer shr 16 and 0xff)
                output.write(buffer shr 8 and 0xff)
                output.write(buffer and 0xff)
                buffer = 0
                quantumPos = 0
            }
        }
        when (quantumPos) {
            0 -> {}
            1 -> throw IllegalArgumentException("bad base-64")
            2 -> output.write(buffer shr 4 and 0xff)
            3 -> {
                output.write(buffer shr 10 and 0xff)
                output.write(buffer shr 2 and 0xff)
            }
        }
        return output.toByteArray()
    }

    private fun requireSecondPadding(str: String, from: Int) {
        var seen = false
        for (i in from until str.length) {
            val c = str[i]
            if (c == '\n' || c == '\r') {
                continue
            }
            if (c == '=' && !seen) {
                seen = true
                continue
            }
            throw IllegalArgumentException("bad base-64")
        }
        if (!seen) throw IllegalArgumentException("bad base-64")
    }

    private fun requireOnlySeparators(str: String, from: Int) {
        for (i in from until str.length) {
            val c = str[i]
            if (c != '\n' && c != '\r') throw IllegalArgumentException("bad base-64")
        }
    }

}
