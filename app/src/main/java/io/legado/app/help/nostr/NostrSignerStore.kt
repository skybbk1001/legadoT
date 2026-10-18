package io.legado.app.help.nostr

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.legado.app.help.config.AppConfig
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object NostrSignerStore {

    private const val keyAlias = "legado.nostr.client-key"
    private const val preferenceKey = "encryptedSession"
    private const val externalKey = "externalSigner"
    private const val activeSignerKey = "activeSigner"

    /** 当前使用的签名器：[SIGNER_NIP46] / [SIGNER_NIP55]；两个账号可同时保留。 */
    var activeSigner: String?
        get() = preferences.getString(activeSignerKey, null)
        set(value) {
            val editor = preferences.edit()
            if (value == null) editor.remove(activeSignerKey) else editor.putString(activeSignerKey, value)
            editor.apply()
        }
    private const val transformation = "AES/GCM/NoPadding"
    private val preferences by lazy {
        appCtx.getSharedPreferences("nostr_signer", Context.MODE_PRIVATE)
    }

    data class Session(
        val bunkerUri: String,
        val clientPrivateKey: ByteArray,
        val clientPublicKey: String,
        val signerPubkey: String,
        val userPubkey: String,
        val relays: List<String>
    )

    /**
     * 可随备份迁移的账号信息：不含 client 私钥（且原会话用设备绑定的 AndroidKeyStore 加密），
     * 恢复后只能把 bunker URI 带过去，由用户重新连接签名器完成配对。
     */
    data class Account(
        val bunkerUri: String,
        val signerPubkey: String,
        val userPubkey: String,
        val relays: List<String>
    )

    /**
     * NIP-55 本机签名器账号：只有签名器包名与用户公钥，没有会话密钥，也没有中继。
     * 签名经由 Intent/ContentResolver 交给该 App，不经过中继。
     */
    data class ExternalAccount(
        val packageName: String,
        val userPubkey: String
    )

    fun exportAccount(): Account? = load()?.let {
        Account(
            bunkerUri = it.bunkerUri,
            signerPubkey = it.signerPubkey,
            userPubkey = it.userPubkey,
            relays = it.relays
        )
    }

    fun save(session: Session) {
        AppConfig.nostrRestoredBunkerUri = null
        val json = JSONObject()
            .put("bunkerUri", session.bunkerUri)
            .put("clientPrivateKey", session.clientPrivateKey.toHex())
            .put("clientPublicKey", session.clientPublicKey)
            .put("signerPubkey", session.signerPubkey)
            .put("userPubkey", session.userPubkey)
            .put("relays", JSONArray(session.relays))
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.iv + cipher.doFinal(json)
        preferences.edit().putString(preferenceKey, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
        activeSigner = SIGNER_NIP46
    }

    fun load(): Session? = runCatching {
        val encoded = preferences.getString(preferenceKey, null) ?: return null
        val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
        require(encrypted.size > IV_LENGTH)
        val iv = encrypted.copyOfRange(0, IV_LENGTH)
        val ciphertext = encrypted.copyOfRange(IV_LENGTH, encrypted.size)
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val json = JSONObject(cipher.doFinal(ciphertext).toString(Charsets.UTF_8))
        val relays = json.getJSONArray("relays")
        Session(
            bunkerUri = json.getString("bunkerUri"),
            clientPrivateKey = json.getString("clientPrivateKey").hexToByteArray(),
            clientPublicKey = json.getString("clientPublicKey"),
            signerPubkey = json.getString("signerPubkey"),
            userPubkey = json.getString("userPubkey"),
            relays = (0 until relays.length()).map { relays.getString(it) }
        )
    }.getOrNull()

    fun saveExternal(account: ExternalAccount) {
        preferences.edit().putString(
            externalKey,
            JSONObject()
                .put("packageName", account.packageName)
                .put("userPubkey", account.userPubkey)
                .toString()
        ).apply()
        activeSigner = SIGNER_NIP55
    }

    fun loadExternal(): ExternalAccount? = runCatching {
        val encoded = preferences.getString(externalKey, null) ?: return null
        val json = JSONObject(encoded)
        val packageName = json.optString("packageName")
        val userPubkey = json.optString("userPubkey")
        if (packageName.isBlank() || userPubkey.isBlank()) return null
        ExternalAccount(packageName, userPubkey)
    }.getOrNull()

    fun clearExternal() {
        preferences.edit().remove(externalKey).apply()
        if (activeSigner == SIGNER_NIP55) {
            activeSigner = if (load() != null) SIGNER_NIP46 else null
        }
    }

    fun clear() {
        preferences.edit().remove(preferenceKey).apply()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
        if (activeSigner == SIGNER_NIP46) {
            activeSigner = if (loadExternal() != null) SIGNER_NIP55 else null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToByteArray(): ByteArray {
        require(length == 64 && all { it.digitToIntOrNull(16) != null })
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    const val SIGNER_NIP46 = "nip46"
    const val SIGNER_NIP55 = "nip55"
}
