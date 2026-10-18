package io.legado.app.help

import io.legado.app.constant.AppConst.dateFormat
import io.legado.app.constant.AppPattern
import io.legado.app.help.crypto.base64ToByteArray
import io.legado.app.help.crypto.hexToByteArray
import io.legado.app.help.crypto.toHexString
import io.legado.app.utils.EncoderUtils
import io.legado.app.utils.StringUtils
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone
import java.util.UUID


/**
 * js扩展类, 在js中通过java变量调用
 * 添加方法，请更新文档/legado/app/src/main/assets/help/JsHelp.md
 * 所有对于文件的读写删操作都是相对路径,只能操作阅读缓存内的文件
 * /android/data/{package}/cache/...
 */
@Suppress("unused")
interface JsExtensions : JsEncodeUtils {

    /* Str转ByteArray */
    fun strToBytes(str: String): ByteArray {
        return str.toByteArray(charset("UTF-8"))
    }

    fun strToBytes(str: String, charset: String): ByteArray {
        return str.toByteArray(charset(charset))
    }

    /* ByteArray转Str */
    fun bytesToStr(bytes: ByteArray): String {
        return String(bytes, charset("UTF-8"))
    }

    fun bytesToStr(bytes: ByteArray, charset: String): String {
        return String(bytes, charset(charset))
    }

    /**
     * js实现base64解码,不能删
     */
    fun base64Decode(str: String?): String? {
        return str?.let { String(it.base64ToByteArray()) }
    }

    fun base64Decode(str: String?, charset: String): String? {
        return str?.let { String(it.base64ToByteArray(), charset(charset)) }
    }

    fun base64Decode(str: String, flags: Int): String {
        return EncoderUtils.base64Decode(str, flags)
    }

    fun base64DecodeToByteArray(str: String?): ByteArray? {
        if (str.isNullOrBlank()) {
            return null
        }
        return EncoderUtils.base64DecodeToByteArray(str, 0)
    }

    fun base64DecodeToByteArray(str: String?, flags: Int): ByteArray? {
        if (str.isNullOrBlank()) {
            return null
        }
        return EncoderUtils.base64DecodeToByteArray(str, flags)
    }

    fun base64Encode(str: String): String? {
        return EncoderUtils.base64Encode(str, 2)
    }

    fun base64Encode(str: String, flags: Int): String? {
        return EncoderUtils.base64Encode(str, flags)
    }

    /* HexString 解码为字节数组 */
    fun hexDecodeToByteArray(hex: String?): ByteArray? {
        return hex?.takeIf(String::isNotEmpty)?.hexToByteArray()
    }

    /* hexString 解码为utf8String*/
    fun hexDecodeToString(hex: String?): String? {
        return hex?.takeIf(String::isNotEmpty)?.let { String(it.hexToByteArray(), Charsets.UTF_8) }
    }

    /* utf8 编码为hexString */
    fun hexEncodeToString(utf8: String?): String? {
        return utf8?.toByteArray()?.toHexString()
    }

    /**
     * 格式化时间
     */
    fun timeFormatUTC(time: Long, format: String, sh: Int): String? {
        val utc = SimpleTimeZone(sh, "UTC")
        return SimpleDateFormat(format, Locale.getDefault()).run {
            timeZone = utc
            format(Date(time))
        }
    }

    /**
     * 时间格式化
     */
    fun timeFormat(time: Long): String {
        return dateFormat.format(Date(time))
    }

    fun encodeURI(str: String): String {
        return try {
            URLEncoder.encode(str, "UTF-8")
        } catch (e: Exception) {
            ""
        }
    }

    fun encodeURI(str: String, enc: String): String {
        return try {
            URLEncoder.encode(str, enc)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 章节数转数字
     */
    fun toNumChapter(s: String?): String? {
        s ?: return null
        val matcher = AppPattern.titleNumPattern.matcher(s)
        if (matcher.find()) {
            val intStr = StringUtils.stringToInt(matcher.group(2))
            return "${matcher.group(1)}${intStr}${matcher.group(3)}"
        }
        return s
    }


    /**
     * 生成UUID
     */
    fun randomUUID(): String {
        return UUID.randomUUID().toString()
    }
}
