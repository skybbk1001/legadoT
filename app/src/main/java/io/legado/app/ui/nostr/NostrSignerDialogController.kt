package io.legado.app.ui.nostr

import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.databinding.DialogNostrSignerBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.nostr.NostrNip46
import io.legado.app.help.nostr.NostrNip46Transport
import io.legado.app.help.nostr.NostrNip55
import io.legado.app.help.nostr.NostrProfileStore
import io.legado.app.help.nostr.NostrRelays
import io.legado.app.help.nostr.NostrSignerStore
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.QRCodeUtils
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.postEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * 签名器连接弹窗（二维码配对 / 本机签名器 NIP-55 / bunker）的可复用封装，
 * 设置页与阅读页段评弹窗共用同一入口，避免两处各自维护一份连接流程。
 *
 * 宿主需提供 [launchLocalSigner] 用于启动 NIP-55 Intent，并在 [onNip55Result] 里回传结果；
 * 弹窗挂在传入的 [fragment] 上（取它的 `alert`/`lifecycleScope`/`startActivity`）。
 */
class NostrSignerDialogController(
    private val fragment: Fragment,
    private val launchLocalSigner: (Intent) -> Unit,
) {

    private var dialog: AlertDialog? = null
    private var connectJob: Job? = null

    /** 连接/断开/切换/资料同步后回调，宿主据此刷新自身 UI。 */
    private var onSignerChanged: () -> Unit = {}

    /** 签名器从无到有时回调一次（用于接续宿主的原动作，如打开撰写框）。 */
    private var onConnected: () -> Unit = {}
    private var connectedNotified = false

    fun show(onSignerChanged: () -> Unit = {}, onConnected: () -> Unit = {}) {
        this.onSignerChanged = onSignerChanged
        this.onConnected = onConnected
        connectedNotified = false
        val context = fragment.requireContext()
        val binding = DialogNostrSignerBinding.inflate(fragment.layoutInflater)
        val secondaryColor = context.getCompatColor(R.color.secondaryText)
        binding.tvQrSummary.setTextColor(secondaryColor)
        binding.tvLocalSummary.setTextColor(secondaryColor)
        val external = NostrSignerStore.loadExternal()
        binding.tvLocalSummary.text = when {
            external == null && !NostrNip55.isSignerInstalled(appCtx) ->
                context.getString(R.string.nostr_external_signer_not_installed)

            external == null -> context.getString(R.string.nostr_signer_local_connect)
            NostrSignerStore.activeSigner == NostrSignerStore.SIGNER_NIP55 ->
                context.getString(R.string.nostr_signer_local_in_use, appName(external.packageName))

            else -> context.getString(R.string.nostr_signer_local_switch, appName(external.packageName))
        }
        binding.etBunker.setText(
            NostrSignerStore.load()?.bunkerUri ?: AppConfig.nostrRestoredBunkerUri.orEmpty()
        )

        binding.rowLocal.setOnClickListener {
            if (external == null) {
                connectLocalSigner()
            } else {
                dismiss()
                showLocalSignerManageDialog(external)
            }
        }
        binding.btnBunkerLogin.setOnClickListener {
            val bunkerUri = binding.etBunker.text?.toString().orEmpty().trim()
            if (bunkerUri.isBlank()) {
                context.toastOnUi(context.getString(R.string.nostr_signer_invalid_uri))
                return@setOnClickListener
            }
            connectNostrSigner(bunkerUri) { dismiss() }
        }

        dialog = fragment.alert(context.getString(R.string.nostr_signer)) {
            customView { binding.root }
            // 关窗即停止等待签名器回连，避免后台残留一个 120s 的订阅。
            onDismiss { connectJob?.takeIf { it.isActive }?.cancel() }
        }
        connectJob = showConnectQr(binding)
    }

    fun dismiss() {
        dialog?.takeIf { it.isShowing }?.dismiss()
        dialog = null
    }

    /** NIP-55 本机签名器回包：由宿主在 `registerForActivityResult` 回调里转发。 */
    fun onNip55Result(resultCode: Int, data: Intent?) {
        val context = fragment.context ?: return
        when (val outcome = NostrNip55.outcomeOf(resultCode, data)) {
            is NostrNip55.Outcome.Success -> {
                val userPubkey = NostrNip55.publicKeyFrom(outcome.result)
                val packageName = NostrNip55.signerPackageFrom(outcome.result)
                if (userPubkey == null || packageName.isNullOrBlank()) {
                    context.toastOnUi(context.getString(R.string.nostr_external_signer_failed))
                    return
                }
                NostrSignerStore.saveExternal(
                    NostrSignerStore.ExternalAccount(packageName, userPubkey)
                )
                onSignerChanged()
                notifyConnected()
                dismiss()
                syncNostrProfile(userPubkey)
                // 登录成功后让阅读页重新统计段评，以便给无评论的段落显示可首评图标。
                postEvent(EventBus.REFRESH_REVIEW_SUMMARY, "")
                context.toastOnUi(context.getString(R.string.nostr_signer_connected))
            }

            is NostrNip55.Outcome.Failed -> {
                AppLog.put("Nostr external signer failed: ${outcome.reason}", tag = "Nostr")
                context.toastOnUi(outcome.reason)
            }

            NostrNip55.Outcome.Rejected ->
                context.toastOnUi(context.getString(R.string.nostr_external_signer_rejected))
        }
    }

    private fun notifyConnected() {
        if (connectedNotified) return
        connectedNotified = true
        onConnected()
    }

    /** 签名器 App 名（取不到时回退包名），用于弹窗内的行副标题。 */
    private fun appName(packageName: String): String = runCatching {
        val pm = appCtx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun connectNostrSigner(bunkerUri: String, onSuccess: () -> Unit = {}) {
        val context = fragment.requireContext()
        val connection = runCatching { NostrNip46.parseBunkerUri(bunkerUri) }
            .getOrElse {
                context.toastOnUi(
                    it.localizedMessage ?: context.getString(R.string.nostr_signer_invalid_uri)
                )
                return
            }
        val keyPair = runCatching { NostrNip46.newClientKeyPair() }
            .getOrElse {
                context.toastOnUi(context.getString(R.string.nostr_signer_failed))
                return
            }
        context.toastOnUi(context.getString(R.string.nostr_signer_connecting))
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val connectResponse = NostrNip46Transport.request(
                        connection,
                        keyPair,
                        NostrNip46.buildConnectRequest(keyPair, connection),
                        onAuthUrl = ::showNostrAuthUrl
                    )
                    if (connectResponse.error != null) error(connectResponse.error)
                    if (connection.secret != null && connectResponse.result != "ack" &&
                        connectResponse.result != connection.secret
                    ) error("Remote signer did not confirm the bunker secret")

                    val publicKeyResponse = NostrNip46Transport.request(
                        connection,
                        keyPair,
                        NostrNip46.buildGetPublicKeyRequest(keyPair, connection.signerPubkey),
                        onAuthUrl = ::showNostrAuthUrl
                    )
                    if (publicKeyResponse.error != null) error(publicKeyResponse.error)
                    val userPubkey = publicKeyResponse.result.orEmpty().lowercase()
                    require(userPubkey.length == 64 && userPubkey.all { it.digitToIntOrNull(16) != null }) {
                        "Remote signer returned an invalid user public key"
                    }

                    val relayResponse = runCatching {
                        NostrNip46Transport.request(
                            connection,
                            keyPair,
                            NostrNip46.buildSwitchRelaysRequest(keyPair, connection.signerPubkey)
                        ).result
                    }.getOrNull()
                    val relays = relayResponse?.let { value ->
                        runCatching { org.json.JSONArray(value) }.getOrNull()?.let { array ->
                            (0 until array.length()).map { array.getString(it) }
                        }
                    }?.filter { it.startsWith("wss://", ignoreCase = true) }
                        ?.takeIf { it.isNotEmpty() } ?: connection.relays

                    NostrSignerStore.save(
                        NostrSignerStore.Session(
                            bunkerUri = bunkerUri,
                            clientPrivateKey = keyPair.privateKey,
                            clientPublicKey = keyPair.publicKey,
                            signerPubkey = connection.signerPubkey,
                            userPubkey = userPubkey,
                            relays = relays
                        )
                    )
                    userPubkey
                }
            }.onFailure { if (it is CancellationException) throw it }
            result.onSuccess { userPubkey ->
                onSignerChanged()
                notifyConnected()
                onSuccess()
                syncNostrProfile(userPubkey)
                // 登录成功后让阅读页重新统计段评，以便给无评论的段落显示可首评图标。
                postEvent(EventBus.REFRESH_REVIEW_SUMMARY, "")
                context.toastOnUi(context.getString(R.string.nostr_signer_connected))
            }.onFailure {
                AppLog.put("Nostr signer connection failed", it, tag = "Nostr")
                context.toastOnUi(
                    it.localizedMessage ?: context.getString(R.string.nostr_signer_failed)
                )
            }
        }
    }

    /** 本机签名器：未连接时发起 NIP-55 配对（多个签名器由系统弹选择）。 */
    private fun connectLocalSigner() {
        val context = fragment.requireContext()
        if (!NostrNip55.isSignerInstalled(appCtx)) {
            context.toastOnUi(context.getString(R.string.nostr_external_signer_not_installed))
            return
        }
        launchLocalSigner(NostrNip55.buildGetPublicKeyIntent())
    }

    /** 本机签名器已连接：断开（远程也已连接时可切换为当前使用）。 */
    private fun showLocalSignerManageDialog(account: NostrSignerStore.ExternalAccount) {
        val context = fragment.requireContext()
        fragment.alert(context.getString(R.string.nostr_external_signer)) {
            setMessage(account.userPubkey)
            positiveButton(context.getString(R.string.nostr_signer_disconnect)) {
                NostrSignerStore.clearExternal()
                onSignerChanged()
                // 断开后清掉可能残留的「可首评」占位标记。
                postEvent(EventBus.REFRESH_REVIEW_SUMMARY, "")
                context.toastOnUi(context.getString(R.string.nostr_signer_disconnected))
            }
            if (NostrSignerStore.load() != null &&
                NostrSignerStore.activeSigner != NostrSignerStore.SIGNER_NIP55
            ) {
                neutralButton(context.getString(R.string.nostr_signer_switch_to)) {
                    activateSigner(NostrSignerStore.SIGNER_NIP55)
                }
            }
            cancelButton()
        }
    }

    /** 切换当前使用的签名器；两个签名器可同时保留，连接谁就用谁。 */
    private fun activateSigner(signer: String) {
        NostrSignerStore.activeSigner = signer
        onSignerChanged()
        postEvent(EventBus.REFRESH_REVIEW_SUMMARY, "")
        fragment.requireContext().toastOnUi(R.string.nostr_signer_switched)
    }

    /** 登录后补拉账号资料（头像/昵称），拉完回调宿主刷新。 */
    private fun syncNostrProfile(pubkey: String) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { NostrProfileStore.sync(listOf(pubkey)) }
            }
            onSignerChanged()
        }
    }

    private fun showNostrAuthUrl(url: String) {
        fragment.activity?.runOnUiThread {
            if (!fragment.isAdded) return@runOnUiThread
            val uri = Uri.parse(url)
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                AppLog.put("Nostr signer returned an invalid authorization URL", tag = "Nostr")
                return@runOnUiThread
            }
            fragment.alert(fragment.getString(R.string.nostr_signer_connecting), url) {
                positiveButton(R.string.open_in_browser) {
                    runCatching {
                        fragment.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }.onFailure {
                        AppLog.put("Unable to open Nostr signer authorization URL", it, tag = "Nostr")
                    }
                }
            }
        }
    }

    /**
     * 在弹窗内直接展示配对二维码并等待签名器回连（不再另弹二维码窗口）。
     * 返回等待中的 Job，由弹窗的 onDismiss 取消。
     */
    private fun showConnectQr(binding: DialogNostrSignerBinding): Job? {
        val context = fragment.requireContext()
        // 配对二维码里的中继必须由用户配置或自动发现：签名器只会向这些中继回传结果。
        val relays = NostrRelays.effective()
        if (relays.isEmpty()) {
            binding.tvQrSummary.setText(R.string.nostr_relays_missing)
            binding.ivQr.visibility = View.GONE
            return null
        }
        val keyPair = NostrNip46.newClientKeyPair()
        val token = NostrNip46.createNostrConnectToken(keyPair, relays)
        val bitmap = QRCodeUtils.createQRCode(token.uri, 720)
        if (bitmap == null) {
            binding.tvQrSummary.setText(R.string.nostr_signer_failed)
            binding.ivQr.visibility = View.GONE
            return null
        }
        binding.tvQrSummary.setText(
            if (NostrSignerStore.load() != null) R.string.nostr_signer_qr_connected
            else R.string.nostr_signer_qr_summary
        )
        binding.ivQr.visibility = View.VISIBLE
        binding.ivQr.setImageBitmap(bitmap)
        // 部分签名器无法扫码时，用户可以点击二维码复制配对链接手动粘贴。
        binding.ivQr.setOnClickListener { context.sendToClip(token.uri) }

        return fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val message = NostrNip46Transport.awaitNostrConnect(
                        token.relays,
                        keyPair,
                        token.secret
                    )
                    val approval = message.approval
                    if (message.isConnectRequest) {
                        // 部分签名器先发 connect 请求，客户端回 ack 后才会继续。
                        NostrNip46Transport.publish(
                            token.relays,
                            NostrNip46.buildConnectAck(
                                keyPair = keyPair,
                                signerPubkey = approval.signerPubkey,
                                requestId = approval.requestId
                            )
                        )
                    }
                    val connection = NostrNip46.BunkerConnection(
                        signerPubkey = approval.signerPubkey,
                        relays = token.relays,
                        secret = null
                    )
                    val publicKeyResponse = NostrNip46Transport.request(
                        connection,
                        keyPair,
                        NostrNip46.buildGetPublicKeyRequest(keyPair, approval.signerPubkey),
                        onAuthUrl = ::showNostrAuthUrl
                    )
                    if (publicKeyResponse.error != null) error(publicKeyResponse.error)
                    val userPubkey = publicKeyResponse.result.orEmpty().lowercase()
                    require(userPubkey.length == 64 && userPubkey.all { it.digitToIntOrNull(16) != null }) {
                        "Remote signer returned an invalid user public key"
                    }
                    val relays = runCatching {
                        NostrNip46Transport.request(
                            connection,
                            keyPair,
                            NostrNip46.buildSwitchRelaysRequest(keyPair, approval.signerPubkey)
                        ).result
                    }.getOrNull()?.let { value ->
                        runCatching { org.json.JSONArray(value) }.getOrNull()?.let { array ->
                            (0 until array.length()).map { array.getString(it) }
                        }
                    }?.filter { it.startsWith("wss://", ignoreCase = true) }
                        ?.takeIf { it.isNotEmpty() } ?: token.relays
                    NostrSignerStore.save(
                        NostrSignerStore.Session(
                            bunkerUri = token.uri,
                            clientPrivateKey = keyPair.privateKey,
                            clientPublicKey = keyPair.publicKey,
                            signerPubkey = approval.signerPubkey,
                            userPubkey = userPubkey,
                            relays = relays
                        )
                    )
                    userPubkey
                }
            }.onFailure { if (it is CancellationException) throw it }
            result.onSuccess { userPubkey ->
                onSignerChanged()
                notifyConnected()
                syncNostrProfile(userPubkey)
                // 登录成功后让阅读页重新统计段评，以便给无评论的段落显示可首评图标。
                postEvent(EventBus.REFRESH_REVIEW_SUMMARY, "")
                context.toastOnUi(context.getString(R.string.nostr_signer_connected))
                dismiss()
            }.onFailure {
                AppLog.put("Nostr signer pairing failed", it, tag = "Nostr")
                context.toastOnUi(
                    it.localizedMessage ?: context.getString(R.string.nostr_signer_failed)
                )
                dismiss()
            }
        }
    }
}
