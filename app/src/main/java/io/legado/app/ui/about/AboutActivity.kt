package io.legado.app.ui.about

import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppConst
import io.legado.app.constant.appInfo
import io.legado.app.databinding.ActivityAboutBinding
import io.legado.app.help.CrashHandler
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.update.AppUpdate
import io.legado.app.lib.theme.accentColor
import io.legado.app.shared.about.AboutAction
import io.legado.app.shared.about.AboutEntry
import io.legado.app.shared.about.AboutScreen
import io.legado.app.shared.about.AboutUiState
import io.legado.app.shared.theme.LegadoTheme
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.externalCache
import io.legado.app.utils.find
import io.legado.app.utils.list
import io.legado.app.utils.openInputStream
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.openUrl
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.delay
import splitties.init.appCtx
import java.io.File


class AboutActivity : BaseActivity<ActivityAboutBinding>() {

    override val binding by viewBinding(ActivityAboutBinding::inflate)

    private val waitDialog by lazy {
        WaitDialog(this)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeView.setContent {
            LegadoTheme(
                isDark = AppConfig.isNightTheme,
                accentColor = Color(accentColor)
            ) {
                AboutScreen(state = aboutState(), onAction = ::onAction)
            }
        }
    }

    private fun aboutState() = AboutUiState(
        appName = getString(R.string.app_name),
        appSummary = getString(R.string.about_description),
        appSummaryHighlight = getString(R.string.legado_gzh),
        versionName = AppConst.appInfo.versionName,
        versionCode = AppConst.appInfo.versionCode,
        mainEntries = listOf(
            AboutEntry(
                title = getString(R.string.contributors),
                subtitle = getString(R.string.contributors_summary),
                action = AboutAction.OpenContributors
            ),
            AboutEntry(
                title = getString(R.string.update_log),
                subtitle = "${getString(R.string.version)} ${AppConst.appInfo.versionName}",
                action = AboutAction.ShowUpdateLog
            ),
            AboutEntry(
                title = getString(R.string.check_update),
                action = AboutAction.CheckUpdate
            )
        ),
        otherTitle = getString(R.string.other),
        otherEntries = listOf(
            AboutEntry(
                title = getString(R.string.crash_log),
                action = AboutAction.ShowCrashLogs
            ),
            AboutEntry(
                title = getString(R.string.save_log),
                action = AboutAction.SaveLog
            ),
            AboutEntry(
                title = getString(R.string.create_heap_dump),
                action = AboutAction.CreateHeapDump
            ),
            AboutEntry(
                title = getString(R.string.privacy_policy),
                action = AboutAction.ShowPrivacyPolicy
            ),
            AboutEntry(
                title = getString(R.string.license),
                action = AboutAction.ShowLicense
            ),
            AboutEntry(
                title = getString(R.string.disclaimer),
                action = AboutAction.ShowDisclaimer
            )
        )
    )

    private fun onAction(action: AboutAction) {
        when (action) {
            AboutAction.OpenContributors -> openUrl(getString(R.string.contributors_url))
            AboutAction.ShowUpdateLog -> showMdFile(getString(R.string.update_log), "updateLog.md")
            AboutAction.CheckUpdate -> checkUpdate()
            AboutAction.ShowCrashLogs -> showDialogFragment<CrashLogsDialog>()
            AboutAction.SaveLog -> saveLog()
            AboutAction.CreateHeapDump -> createHeapDump()
            AboutAction.ShowPrivacyPolicy -> showMdFile(getString(R.string.privacy_policy), "privacyPolicy.md")
            AboutAction.ShowLicense -> showMdFile(getString(R.string.license), "LICENSE.md")
            AboutAction.ShowDisclaimer -> showMdFile(getString(R.string.disclaimer), "disclaimer.md")
        }
    }

    /**
     * 显示md文件
     */
    private fun showMdFile(title: String, fileName: String) {
        val mdText = String(assets.open(fileName).readBytes())
        showDialogFragment(TextDialog(title, mdText, TextDialog.Mode.MD))
    }

    /**
     * 检测更新
     */
    private fun checkUpdate() {
        waitDialog.show()
        AppUpdate.gitHubUpdate?.run {
            check(lifecycleScope)
                .onSuccess {
                    showDialogFragment(
                        UpdateDialog(it)
                    )
                }.onError {
                    appCtx.toastOnUi("${getString(R.string.check_update)}\n${it.localizedMessage}")
                }.onFinally {
                    waitDialog.dismiss()
                }
        }
    }

    private fun saveLog() {
        Coroutine.async {
            val backupPath = AppConfig.backupPath ?: let {
                appCtx.toastOnUi("未设置备份目录")
                return@async
            }
            if (!AppConfig.recordLog) {
                appCtx.toastOnUi("未开启日志记录，请去其他设置里打开记录日志")
                delay(3000)
            }
            val doc = FileDoc.fromUri(Uri.parse(backupPath), true)
            copyLogs(doc)
            copyHeapDump(doc)
            appCtx.toastOnUi("已保存至备份目录")
        }.onError {
            AppLog.put("保存日志出错\n${it.localizedMessage}", it, true)
        }
    }

    private fun createHeapDump() {
        Coroutine.async {
            val backupPath = AppConfig.backupPath ?: let {
                appCtx.toastOnUi("未设置备份目录")
                return@async
            }
            if (!AppConfig.recordHeapDump) {
                appCtx.toastOnUi("未开启堆转储记录，请去其他设置里打开记录堆转储")
                delay(3000)
            }
            appCtx.toastOnUi("开始创建堆转储")
            System.gc()
            CrashHandler.doHeapDump(true)
            val doc = FileDoc.fromUri(Uri.parse(backupPath), true)
            if (!copyHeapDump(doc)) {
                appCtx.toastOnUi("未找到堆转储文件")
            } else {
                appCtx.toastOnUi("已保存至备份目录")
            }
        }.onError {
            AppLog.put("保存堆转储失败\n${it.localizedMessage}", it)
        }
    }

    private fun copyLogs(doc: FileDoc) {
        val cacheDir = appCtx.externalCache
        val logFiles = File(cacheDir, "logs")
        val crashFiles = File(cacheDir, "crash")
        val logcatFile = File(cacheDir, "logcat.txt")

        dumpLogcat(logcatFile)

        val zipFile = File(cacheDir, "logs.zip")
        ZipUtils.zipFiles(arrayListOf(logFiles, crashFiles, logcatFile), zipFile)

        doc.find("logs.zip")?.delete()

        zipFile.inputStream().use { input ->
            doc.createFileIfNotExist("logs.zip").openOutputStream().getOrNull()
                ?.use {
                    input.copyTo(it)
                }
        }
        zipFile.delete()
    }

    private fun copyHeapDump(doc: FileDoc): Boolean {
        val heapFile = FileDoc.fromFile(File(appCtx.externalCache, "heapDump")).list()
            ?.firstOrNull() ?: return false
        doc.find("heapDump")?.delete()
        val heapDumpDoc = doc.createFolderIfNotExist("heapDump")
        heapFile.openInputStream().getOrNull()?.use { input ->
            heapDumpDoc.createFileIfNotExist(heapFile.name).openOutputStream().getOrNull()
                ?.use {
                    input.copyTo(it)
                }
        }
        return true
    }

    private fun dumpLogcat(file: File) {
        try {
            val process = Runtime.getRuntime().exec("logcat -d")
            file.outputStream().use {
                process.inputStream.copyTo(it)
            }
        } catch (e: Exception) {
            AppLog.put("保存Logcat失败\n$e", e)
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.about, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_scoring -> openUrl("market://details?id=$packageName")
            R.id.menu_share_it -> share(
                getString(R.string.app_share_description),
                getString(R.string.app_name)
            )
        }
        return super.onCompatOptionsItemSelected(item)
    }

}
