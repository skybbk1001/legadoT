package io.legado.app.help

import android.util.Base64
import io.legado.app.help.crypto.digest
import io.legado.app.help.crypto.hmac


/**
 * js加解密扩展类, 在js中通过java变量调用
 * 添加方法，请更新文档/legado/app/src/main/assets/help/JsHelp.md
 */
@Suppress("unused")
interface JsEncodeUtilsAndroid : JsEncodeUtils {

    /**
     * AES解码为String，算法参数经过Base64加密
     *
     * @param data 加密的字符串
     * @param key Base64后的密钥
     * @param mode 模式
     * @param padding 补码方式
     * @param iv Base64后的加盐
     * @return 解密后的字符串
     */
    @Deprecated(
        "过于繁琐弃用",
        ReplaceWith("createSymmetricCrypto(transformation, key, iv).decryptStr(data)")
    )
    fun aesDecodeArgsBase64Str(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String
    ): String? {
        return createSymmetricCrypto(
            "AES/${mode}/${padding}",
            Base64.decode(key, Base64.NO_WRAP),
            Base64.decode(iv, Base64.NO_WRAP)
        ).decryptStr(data)
    }

    /**
     * 3DES解密，算法参数经过Base64加密
     *
     * @param data 加密的字符串
     * @param key Base64后的密钥
     * @param mode 模式
     * @param padding 补码方式
     * @param iv Base64后的加盐
     * @return 解密后的字符串
     */
    @Deprecated(
        "过于繁琐弃用",
        ReplaceWith("createSymmetricCrypto(transformation, key, iv).decryptStr(data)")
    )
    fun tripleDESDecodeArgsBase64Str(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String
    ): String? {
        return createSymmetricCrypto(
            "DESede/${mode}/${padding}",
            Base64.decode(key, Base64.NO_WRAP),
            iv.encodeToByteArray()
        ).decryptStr(data)
    }

    /**
     * 3DES加密并转为Base64，算法参数经过Base64加密
     *
     * @param data 被加密的字符串
     * @param key Base64后的密钥
     * @param mode 模式
     * @param padding 补码方式
     * @param iv Base64后的加盐
     * @return 加密后的Base64
     */
    @Deprecated(
        "过于繁琐弃用",
        ReplaceWith("createSymmetricCrypto(transformation, key, iv).encryptBase64(data)")
    )
    fun tripleDESEncodeArgsBase64Str(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String
    ): String? {
        return createSymmetricCrypto(
            "DESede/${mode}/${padding}",
            Base64.decode(key, Base64.NO_WRAP),
            iv.encodeToByteArray()
        ).encryptBase64(data)
    }

    /**
     * 生成摘要，并转为Base64字符串
     *
     * @param data 被摘要数据
     * @param algorithm 签名算法
     * @return Base64字符串
     */
    fun digestBase64Str(
        data: String,
        algorithm: String,
    ): String {
        return Base64.encodeToString(digest(algorithm, data.toByteArray()), Base64.NO_WRAP)
    }

    /**
     * 生成散列消息鉴别码，并转为Base64字符串
     *
     * @param data 被摘要数据
     * @param algorithm 签名算法
     * @param key 密钥
     * @return Base64字符串
     */
    @Suppress("FunctionName")
    fun HMacBase64(
        data: String,
        algorithm: String,
        key: String
    ): String {
        return Base64.encodeToString(
            hmac(algorithm, key.toByteArray(), data.toByteArray()),
            Base64.NO_WRAP
        )
    }

}
