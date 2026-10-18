package io.legado.app.ui.config

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.postDelayed
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.jeremyliao.liveeventbus.LiveEventBus
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.AppFreezeMonitor
import io.legado.app.help.DispatchersMonitor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.nostr.NostrRelays
import io.legado.app.help.nostr.NostrSignerStore
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.prefs.NostrSignerPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.CheckSource
import io.legado.app.model.ImageProvider
import io.legado.app.receiver.SharedReceiverActivity
import io.legado.app.service.WebService
import io.legado.app.service.McpService
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.nostr.NostrSignerDialogController
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.LogUtils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.restart
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 其它设置
 */
class OtherConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val viewModel by activityViewModels<ConfigViewModel>()
    private val packageManager = appCtx.packageManager
    private val componentName = ComponentName(
        appCtx,
        SharedReceiverActivity::class.java.name
    )
    private val localBookTreeSelect = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { treeUri ->
            AppConfig.defaultBookTreeUri = treeUri.toString()
        }
    }

    /** NIP-55：本机签名器用 get_public_key 回包（公钥 + 包名）完成连接。 */
    private val nostrExternalSignerConnect: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            nostrSignerDialog.onNip55Result(result.resultCode, result.data)
        }

    /** 签名器连接弹窗：二维码配对 / 本机签名器（NIP-55） / bunker，与阅读页段评弹窗共用。 */
    private val nostrSignerDialog by lazy {
        NostrSignerDialogController(this) { intent -> nostrExternalSignerConnect.launch(intent) }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        putPrefBoolean(PreferKey.processText, isProcessTextEnabled())
        addPreferencesFromResource(R.xml.pref_config_other)
        upPreferenceSummary(PreferKey.userAgent, AppConfig.userAgent)
        upPreferenceSummary(PreferKey.preDownloadNum, AppConfig.preDownloadNum.toString())
        upPreferenceSummary(PreferKey.threadCount, AppConfig.threadCount.toString())
        upPreferenceSummary(PreferKey.webPort, AppConfig.webPort.toString())
        upPreferenceSummary(PreferKey.mcpPort, AppConfig.mcpPort.toString())
        upPreferenceSummary(
            PreferKey.mcpToken,
            getString(R.string.mcp_token_summary, maskToken(McpService.ensureToken()))
        )
        AppConfig.defaultBookTreeUri?.let {
            upPreferenceSummary(PreferKey.defaultBookTreeUri, it)
        }
        upPreferenceSummary(PreferKey.checkSource, CheckSource.summary)
        upPreferenceSummary(PreferKey.bitmapCacheSize, AppConfig.bitmapCacheSize.toString())
        upPreferenceSummary(PreferKey.imageRetainNum, AppConfig.imageRetainNum.toString())
        upPreferenceSummary(PreferKey.sourceEditMaxLine, AppConfig.sourceEditMaxLine.toString())
        findPreference<Preference>("nostrSigner")?.let { preference ->
            (preference as? NostrSignerPreference)?.apply {
                onConnectClick = { nostrSignerDialog.show { updateNostrSignerSummary() } }
                onLogoutClick = { logoutNostrSigner() }
            }
        }
        updateNostrSignerSummary()
        updateNostrRelaysSummary()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.other_setting)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        listView.setEdgeEffectColor(primaryColor)
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            PreferKey.userAgent -> showUserAgentDialog()
            PreferKey.defaultBookTreeUri -> localBookTreeSelect.launch {
                title = getString(R.string.select_book_folder)
                mode = HandleFileContract.DIR_SYS
            }

            PreferKey.preDownloadNum -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.pre_download))
                .setMaxValue(9999)
                .setMinValue(0)
                .setValue(AppConfig.preDownloadNum)
                .show {
                    AppConfig.preDownloadNum = it
                }

            PreferKey.threadCount -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.threads_num_title))
                .setMaxValue(999)
                .setMinValue(1)
                .setValue(AppConfig.threadCount)
                .show {
                    AppConfig.threadCount = it
                }

            PreferKey.webPort -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.web_port_title))
                .setMaxValue(60000)
                .setMinValue(1024)
                .setValue(AppConfig.webPort)
                .show {
                    AppConfig.webPort = it
                }

            PreferKey.mcpPort -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.mcp_port_title))
                .setMaxValue(60000)
                .setMinValue(1024)
                .setValue(AppConfig.mcpPort)
                .show {
                    AppConfig.mcpPort = it
                }

            PreferKey.mcpToken -> {
                val token = McpService.ensureToken()
                appCtx.sendToClip(token)
                toastOnUi(getString(R.string.mcp_token_copied))
            }

            "mcpTokenRegenerate" -> {
                McpService.regenerateToken()
                upPreferenceSummary(
                    PreferKey.mcpToken,
                    getString(R.string.mcp_token_summary, maskToken(AppConfig.mcpToken))
                )
                toastOnUi(getString(R.string.mcp_token_regenerated))
            }

            "nostrRelays" -> showNostrRelaysDialog()
            "nostrRelayDiscover" -> discoverNostrRelays()

            PreferKey.cleanCache -> clearCache()
            PreferKey.uploadRule -> showDialogFragment<DirectLinkUploadConfig>()
            PreferKey.checkSource -> showDialogFragment<CheckSourceConfig>()
            PreferKey.bitmapCacheSize -> {
                NumberPickerDialog(requireContext())
                    .setTitle(getString(R.string.bitmap_cache_size))
                    .setMaxValue(1024)
                    .setMinValue(1)
                    .setValue(AppConfig.bitmapCacheSize)
                    .show {
                        AppConfig.bitmapCacheSize = it
                        ImageProvider.bitmapLruCache.resize(ImageProvider.cacheSize)
                    }
            }
            PreferKey.imageRetainNum -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.image_retain_number))
                .setMaxValue(999)
                .setMinValue(0)
                .setValue(AppConfig.imageRetainNum)
                .show {
                    AppConfig.imageRetainNum = it
                }

            PreferKey.sourceEditMaxLine -> {
                NumberPickerDialog(requireContext())
                    .setTitle(getString(R.string.source_edit_text_max_line))
                    .setMaxValue(Int.MAX_VALUE)
                    .setMinValue(10)
                    .setValue(AppConfig.sourceEditMaxLine)
                    .show {
                        AppConfig.sourceEditMaxLine = it
                    }
            }

            PreferKey.clearWebViewData -> clearWebViewData()
            "localPassword" -> alertLocalPassword()
            PreferKey.shrinkDatabase -> shrinkDatabase()
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.preDownloadNum -> {
                upPreferenceSummary(key, AppConfig.preDownloadNum.toString())
            }

            PreferKey.threadCount -> {
                upPreferenceSummary(key, AppConfig.threadCount.toString())
                postEvent(PreferKey.threadCount, "")
            }

            PreferKey.webPort -> {
                upPreferenceSummary(key, AppConfig.webPort.toString())
                if (WebService.isRun) {
                    WebService.stop(requireContext())
                    WebService.start(requireContext())
                }
            }

            PreferKey.mcpPort -> {
                upPreferenceSummary(key, AppConfig.mcpPort.toString())
                if (McpService.isRun) {
                    McpService.stop(requireContext())
                    McpService.start(requireContext())
                }
            }

            PreferKey.defaultBookTreeUri -> {
                upPreferenceSummary(key, AppConfig.defaultBookTreeUri)
            }

            PreferKey.recordLog -> {
                AppConfig.recordLog = appCtx.getPrefBoolean(PreferKey.recordLog)
                LogUtils.upLevel()
                LogUtils.logDeviceInfo()
                LiveEventBus.config().enableLogger(AppConfig.recordLog)
                AppFreezeMonitor.init(appCtx)
                DispatchersMonitor.init()
            }

            PreferKey.processText -> sharedPreferences?.let {
                setProcessTextEnable(it.getBoolean(key, true))
            }

            PreferKey.showDiscovery, PreferKey.showRss -> postEvent(EventBus.NOTIFY_MAIN, true)
            PreferKey.language -> listView.postDelayed(1000) {
                appCtx.restart()
            }

            PreferKey.userAgent -> listView.post {
                upPreferenceSummary(PreferKey.userAgent, AppConfig.userAgent)
            }

            PreferKey.checkSource -> listView.post {
                upPreferenceSummary(PreferKey.checkSource, CheckSource.summary)
            }

            PreferKey.bitmapCacheSize -> {
                upPreferenceSummary(key, AppConfig.bitmapCacheSize.toString())
            }

            PreferKey.imageRetainNum -> {
                upPreferenceSummary(key, AppConfig.imageRetainNum.toString())
            }

            PreferKey.sourceEditMaxLine -> {
                upPreferenceSummary(key, AppConfig.sourceEditMaxLine.toString())
            }
        }
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String?) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            PreferKey.preDownloadNum -> preference.summary =
                getString(R.string.pre_download_s, value)

            PreferKey.threadCount -> preference.summary = getString(R.string.threads_num, value)
            PreferKey.webPort -> preference.summary = getString(R.string.web_port_summary, value)
            PreferKey.mcpPort -> preference.summary = getString(R.string.mcp_port_summary, value)
            PreferKey.bitmapCacheSize -> preference.summary =
                getString(R.string.bitmap_cache_size_summary, value)
            PreferKey.imageRetainNum -> preference.summary =
                getString(R.string.image_retain_number_summary, value)

            PreferKey.sourceEditMaxLine -> preference.summary =
                getString(R.string.source_edit_max_line_summary, value)

            else -> if (preference is ListPreference) {
                val index = preference.findIndexOfValue(value)
                // Set the summary to reflect the new value.
                preference.summary = if (index >= 0) preference.entries[index] else null
            } else {
                preference.summary = value
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun showUserAgentDialog() {
        alert(getString(R.string.user_agent)) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = getString(R.string.user_agent)
                editView.setText(AppConfig.userAgent)
            }
            customView { alertBinding.root }
            okButton {
                val userAgent = alertBinding.editView.text?.toString()
                if (userAgent.isNullOrBlank()) {
                    removePref(PreferKey.userAgent)
                } else {
                    putPrefString(PreferKey.userAgent, userAgent)
                }
            }
            cancelButton()
        }
    }

    /** 退出当前使用的签名器；另一台已连接时由 store 的回退逻辑自动接上。 */
    private fun logoutNostrSigner() {
        val session = NostrSignerStore.load()
        val external = NostrSignerStore.loadExternal()
        if (session == null && external == null) return
        alert(getString(R.string.nostr_signer_logout), getString(R.string.nostr_signer_logout_confirm)) {
            okButton {
                when (NostrSignerStore.activeSigner) {
                    NostrSignerStore.SIGNER_NIP55 -> NostrSignerStore.clearExternal()
                    NostrSignerStore.SIGNER_NIP46 -> NostrSignerStore.clear()
                    else -> if (NostrSignerStore.load() != null) NostrSignerStore.clear()
                    else NostrSignerStore.clearExternal()
                }
                updateNostrSignerSummary()
                // 退出后清掉可能残留的「可首评」占位标记。
                postEvent(EventBus.REFRESH_REVIEW_SUMMARY, "")
                toastOnUi(getString(R.string.nostr_signer_disconnected))
            }
            cancelButton()
        }
    }

    private fun updateNostrSignerSummary() {
        findPreference<Preference>("nostrSigner")?.let { (it as? NostrSignerPreference)?.refresh() }
    }

    private fun updateNostrRelaysSummary() {
        val preference = findPreference<Preference>("nostrRelays") ?: return
        val relays = NostrRelays.effective()
        val current = if (relays.isEmpty()) {
            getString(R.string.nostr_relays_empty)
        } else {
            relays.joinToString(" ")
        }
        val builder = StringBuilder(getString(R.string.nostr_relays_summary, current))
        val cached = NostrRelays.cached()
        if (cached.isNotEmpty()) {
            builder.append('\n')
                .append(getString(R.string.nostr_relays_cached, cached.size))
            val time = NostrRelays.cacheTime
            if (time > 0) {
                builder.append('\n')
                    .append(
                        getString(
                            R.string.nostr_relays_last_check,
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                .format(Date(time))
                        )
                    )
            }
        }
        preference.summary = builder.toString()
    }

    /**
     * 对「用户配置 + 内置种子」的中继逐个做 NIP-11 + WebSocket 健康检查，
     * 过滤掉要求鉴权/付费/禁止写入或连不上的中继，把可用结果写回设置与缓存。
     */
    private fun discoverNostrRelays() {
        var job: Job? = null
        val dialog = alert(
            getString(R.string.nostr_relays_discover),
            getString(R.string.nostr_relays_discover_summary)
        ) {
            setCancelable(false)
            negativeButton(R.string.cancel) { job?.cancel() }
        }
        job = viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    NostrRelays.discover { done, total, relay ->
                        val message = getString(
                            R.string.nostr_relays_discovering, done, total, relay
                        )
                        activity?.runOnUiThread {
                            if (isAdded && dialog.isShowing) {
                                dialog.setMessage(message)
                            }
                        }
                    }
                }
            }.onFailure { if (it is CancellationException) throw it }
            result.onSuccess { relays ->
                dialog.dismiss()
                updateNostrRelaysSummary()
                toastOnUi(
                    if (relays.isEmpty()) {
                        getString(R.string.nostr_relays_discover_none)
                    } else {
                        getString(R.string.nostr_relays_discover_result, relays.size)
                    }
                )
            }.onFailure {
                AppLog.put("Nostr relay discovery failed", it, tag = "Nostr")
                dialog.dismiss()
                toastOnUi(
                    getString(
                        R.string.nostr_relays_discover_failed,
                        it.localizedMessage.orEmpty()
                    )
                )
            }
        }
    }

    private fun showNostrRelaysDialog() {
        alert(getString(R.string.nostr_relays)) {
            val editBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = getString(R.string.nostr_relays_hint)
                editView.inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE
                editView.setText(AppConfig.nostrRelays)
            }
            customView { editBinding.root }
            okButton {
                AppConfig.nostrRelays = editBinding.editView.text?.toString().orEmpty()
                updateNostrRelaysSummary()
            }
            cancelButton()
        }
    }

    private fun clearCache() {
        requireContext().alert(
            titleResource = R.string.clear_cache,
            messageResource = R.string.sure_del
        ) {
            okButton {
                viewModel.clearCache()
            }
            noButton()
        }
    }

    private fun shrinkDatabase() {
        alert(R.string.sure, R.string.shrink_database) {
            okButton {
                viewModel.shrinkDatabase()
            }
            noButton()
        }
    }

    private fun clearWebViewData() {
        alert(R.string.clear_webview_data, R.string.sure_del) {
            okButton {
                viewModel.clearWebViewData()
            }
            noButton()
        }
    }

    private fun isProcessTextEnabled(): Boolean {
        return packageManager.getComponentEnabledSetting(componentName) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    private fun maskToken(token: String): String =
        if (token.length > 8) token.take(8) + "…" else token

    private fun setProcessTextEnable(enable: Boolean) {
        if (enable) {
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
            )
        } else {
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
        }
    }

    private fun alertLocalPassword() {
        context?.alert(R.string.set_local_password, R.string.set_local_password_summary) {
            val editTextBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "password"
            }
            customView {
                editTextBinding.root
            }
            okButton {
                LocalConfig.password = editTextBinding.editView.text.toString()
            }
            cancelButton()
        }
    }

}