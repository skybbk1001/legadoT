package io.legado.app.help.nostr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * NIP-55（Android Signer Application）：与本机签名器 App 通信的 Intent / ContentResolver 协议。
 *
 * 只实现段评用到的两个方法：`get_public_key` 与 `sign_event`。签名器与客户端在同一台设备上，
 * 请求不经过任何中继。
 */
object NostrNip55 {

    const val COMMAND_GET_PUBLIC_KEY = "get_public_key"
    const val COMMAND_SIGN_EVENT = "sign_event"

    private const val SCHEME = "nostrsigner"

    /** 预授权项：签名器据此允许客户端在后台直接查询（用户勾选「记住选择」后生效）。 */
    data class Permission(val type: String, val kind: Int? = null)

    /** 只申请段评会签名的事件类型，避免索要多余权限。 */
    val paragraphReviewPermissions: List<Permission> = NostrNip46.paragraphReviewKinds
        .sorted()
        .map { Permission(COMMAND_SIGN_EVENT, it) }

    /** 签名器回包：`result` 为方法结果（公钥或签名），`event` 为已签名事件 JSON。 */
    data class Result(
        val result: String?,
        val event: String?,
        val packageName: String?,
        val rejected: Boolean
    )

    /** 一次前台请求的结局，由 Activity 回调转交给等待方。 */
    sealed interface Outcome {
        data class Success(val result: Result) : Outcome
        data class Failed(val reason: String) : Outcome
        data object Rejected : Outcome
    }

    fun isSignerInstalled(context: Context): Boolean = runCatching {
        context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse("$SCHEME:")),
            0
        ).isNotEmpty()
    }.getOrDefault(false)

    /** `get_public_key` 不指定包名（此时还不知道装的是哪个签名器）。 */
    fun buildGetPublicKeyIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("$SCHEME:")).apply {
            putExtra("type", COMMAND_GET_PUBLIC_KEY)
            putExtra("permissions", permissionsJson(paragraphReviewPermissions))
        }

    fun buildSignEventIntent(
        payloadJson: String,
        userPubkey: String,
        packageName: String
    ): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("$SCHEME:$payloadJson")).apply {
        `package` = packageName
        putExtra("type", COMMAND_SIGN_EVENT)
        putExtra("current_user", userPubkey)
    }

    /**
     * 待签名事件载荷：`sign_event` 的载荷是事件 JSON，且必须带 pubkey——
     * 签名器只回签名时，缺 pubkey 就算不出事件 id。
     */
    fun signPayload(unsignedEvent: JSONObject, userPubkey: String): JSONObject =
        JSONObject(unsignedEvent.toString()).put("pubkey", userPubkey)

    /**
     * resultCode 非 RESULT_OK 表示签名器自身出错（如崩溃），与用户拒绝不同：
     * 用户拒绝时签名器返回 RESULT_OK 并置 `rejected`。
     */
    fun outcomeOf(resultCode: Int, data: Intent?): Outcome {
        if (resultCode != Activity.RESULT_OK) {
            return Outcome.Failed("Signer failed with resultCode=$resultCode")
        }
        val intent = data ?: return Outcome.Failed("Signer returned no result")
        if (intent.getBooleanExtra("rejected", false)) return Outcome.Rejected
        return Outcome.Success(
            Result(
                result = intent.getStringExtra("result"),
                event = intent.getStringExtra("event"),
                packageName = intent.getStringExtra("package"),
                rejected = false
            )
        )
    }

    /** `get_public_key` 的用户公钥。 */
    fun publicKeyFrom(result: Result): String? =
        result.result?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.length == 64 && it.isHexKey() }

    /** 签名器包名：只有 `get_public_key` 会回 `package`，其余请求沿用调用方已知的包名。 */
    fun signerPackageFrom(result: Result, fallback: String? = null): String? =
        result.packageName?.takeIf { it.isNotBlank() } ?: fallback

    /**
     * 还原已签名事件：优先用 `event` 原样，缺失时用 `result`（128 位 hex 签名）配上
     * 未签名事件的字段自行拼装（部分签名器只回签名）。
     */
    fun signedEventFrom(result: Result, unsignedEvent: JSONObject): JSONObject {
        result.event?.takeIf { it.startsWith("{") }?.let { return JSONObject(it) }
        val signature = result.result?.trim()?.lowercase(Locale.ROOT).orEmpty()
        require(signature.length == 128 && signature.isHexKey()) {
            "Signer returned neither a signed event nor a signature"
        }
        val pubkey = unsignedEvent.optString("pubkey")
        require(pubkey.length == 64 && pubkey.isHexKey()) {
            "The user public key is required to sign an event"
        }
        val createdAt = unsignedEvent.optLong("created_at", -1)
        val kind = unsignedEvent.optInt("kind", -1)
        val content = unsignedEvent.optString("content")
        val tags = unsignedEvent.optJSONArray("tags") ?: JSONArray()
        val parsedTags = jsonTagsToLists(tags)
        return JSONObject()
            .put("id", nostrEventId(pubkey, createdAt, kind, parsedTags, content).toHex())
            .put("pubkey", pubkey)
            .put("created_at", createdAt)
            .put("kind", kind)
            .put("tags", tags)
            .put("content", content)
            .put("sig", signature)
    }

    private fun permissionsJson(permissions: List<Permission>): String {
        val array = JSONArray()
        permissions.forEach { permission ->
            val item = JSONObject().put("type", permission.type)
            permission.kind?.let { item.put("kind", it) }
            array.put(item)
        }
        return array.toString()
    }

    private fun jsonTagsToLists(tags: JSONArray): List<List<String>> =
        (0 until tags.length()).map { index ->
            val tag = tags.optJSONArray(index) ?: JSONArray()
            (0 until tag.length()).map { tag.optString(it) }
        }

    private fun String.isHexKey(): Boolean = length % 2 == 0 && all { it.digitToIntOrNull(16) != null }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
