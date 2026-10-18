@file:Suppress("DEPRECATION")

package io.legado.app.ui.main

import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.format.DateUtils
import android.view.MenuItem
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.core.view.get
import androidx.core.view.postDelayed
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.AppConst
import io.legado.app.constant.appInfo
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityMainBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.AppWebDav
import io.legado.app.help.BottomBarSkinManager
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
import io.legado.app.help.update.AppUpdate
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.elevation
import io.legado.app.lib.theme.primaryColor
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.about.CrashLogsDialog
import io.legado.app.ui.about.UpdateDialog
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.association.ImportDictRuleDialog
import io.legado.app.ui.association.ImportHttpTtsDialog
import io.legado.app.ui.association.ImportReplaceRuleDialog
import io.legado.app.ui.association.ImportRssSourceDialog
import io.legado.app.ui.association.ImportTxtTocRuleDialog
import io.legado.app.ui.autoTask.ImportAutoTaskDialog
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.style1.BookshelfFragment1
import io.legado.app.ui.main.bookshelf.style2.BookshelfFragment2
import io.legado.app.ui.main.explore.ExploreFragment
import io.legado.app.ui.main.my.MyFragment
import io.legado.app.ui.main.rss.RssFragment
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.ui.widget.text.BadgeView
import io.legado.app.utils.Shibboleth
import io.legado.app.utils.ShibbolethParseResult
import io.legado.app.utils.ShibbolethType
import io.legado.app.utils.clearClip
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getClipText
import io.legado.app.utils.observeEvent
import io.legado.app.utils.reduceDragSensitivity
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 主界面
 */
@Suppress("PrivatePropertyName")
class MainActivity : VMBaseActivity<ActivityMainBinding, MainViewModel>(),
    BottomNavigationView.OnNavigationItemSelectedListener,
    BottomNavigationView.OnNavigationItemReselectedListener {

    override val binding by viewBinding(ActivityMainBinding::inflate)
    override val viewModel by viewModels<MainViewModel>()
    private val idBookshelf = 0
    private val idBookshelf1 = 11
    private val idBookshelf2 = 12
    private val idExplore = 1
    private val idRss = 2
    private val idMy = 3
    private var exitTime: Long = 0
    private var bookshelfReselected: Long = 0
    private var exploreReselected: Long = 0
    private var pagePosition = 0
    private var bottomMenuCount = 4
    private val EXIT_INTERVAL = 2000L
    private val UPDATE_CHECK_INTERVAL = 24 * 60 * 60 * 1000L
    private val realPositions = arrayOf(idBookshelf, idExplore, idRss, idMy)
    private val menuIdToSlot = linkedMapOf(
        R.id.menu_bookshelf to "bookshelf",
        R.id.menu_discovery to "home",
        R.id.menu_rss to "notes",
        R.id.menu_my_config to "settings",
    )
    private val adapter by lazy {
        TabFragmentPageAdapter()
    }
    private var onUpBooksBadgeView: BadgeView? = null
    private var pendingShibbolethCheck = false

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        upBottomMenu()
        initView()
        upHomePage()
        upBottomBarSkin()
        onBackPressedDispatcher.addCallback(this) {
            if (pagePosition != 0) {
                binding.viewPagerMain.currentItem = 0
                return@addCallback
            }
            (findFragmentById(getFragmentId(0)) as? BookshelfFragment2)?.let {
                if (it.back()) {
                    return@addCallback
                }
            }
            if (System.currentTimeMillis() - exitTime > EXIT_INTERVAL) {
                toastOnUi(R.string.double_click_exit)
                exitTime = System.currentTimeMillis()
            } else {
                if (BaseReadAloudService.pause) {
                    finish()
                } else {
                    moveTaskToBack(true)
                }
            }
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        lifecycleScope.launch {
            //隐私协议
            if (!privacyPolicy()) return@launch
            scheduleShibbolethImport()
            //版本更新
            upVersion()
            //设置本地密码
            setLocalPassword()
            notifyAppCrash()
            //备份同步
            backupSync()
            //自动检查APP更新
            checkAppUpdate()
            //自动更新书籍
            val isAutoRefreshedBook = savedInstanceState?.getBoolean("isAutoRefreshedBook") ?: false
            if (AppConfig.autoRefreshBook && !isAutoRefreshedBook) {
                binding.viewPagerMain.postDelayed(1000) {
                    viewModel.upAllBookToc()
                }
            }
            binding.viewPagerMain.postDelayed(3000) {
                viewModel.postLoad()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (LocalConfig.privacyPolicyOk) {
            scheduleShibbolethImport()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pendingShibbolethCheck) {
            consumeShibbolethImport()
        }
    }

    /**
     * Android 10+ 剪贴板仅焦点应用可读,识别挂起到获得窗口焦点后执行;
     * 每次 onResume 至多读一次剪贴板
     */
    private fun scheduleShibbolethImport() {
        pendingShibbolethCheck = true
        if (binding.root.hasWindowFocus()) {
            consumeShibbolethImport()
        }
    }

    private fun consumeShibbolethImport() {
        pendingShibbolethCheck = false
        binding.root.postDelayed(200) {
            importShibbolethFromClipboard()
        }
    }

    private fun importShibbolethFromClipboard() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            supportFragmentManager.isStateSaved
        ) {
            return
        }
        val text = getClipText() ?: return
        when (val parsed = Shibboleth.parse(text)) {
            ShibbolethParseResult.NotShibboleth -> Unit
            is ShibbolethParseResult.Invalid -> {
                toastOnUi(R.string.shibboleth_invalid)
                clearClip()
            }
            is ShibbolethParseResult.Expired -> {
                toastOnUi(R.string.shibboleth_expired)
                clearClip()
            }
            is ShibbolethParseResult.Valid -> {
                val url = parsed.token.url
                val dialog = when (parsed.token.type) {
                    ShibbolethType.BOOK_SOURCE -> ImportBookSourceDialog(url)
                    ShibbolethType.RSS_SOURCE -> ImportRssSourceDialog(url)
                    ShibbolethType.DICT_RULE -> ImportDictRuleDialog(url)
                    ShibbolethType.REPLACE_RULE -> ImportReplaceRuleDialog(url)
                    ShibbolethType.TOC_RULE -> ImportTxtTocRuleDialog(url)
                    ShibbolethType.TTS_RULE -> ImportHttpTtsDialog(url)
                    ShibbolethType.AUTO_TASK -> ImportAutoTaskDialog(url)
                }
                showDialogFragment(dialog)
                clearClip()
            }
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean = binding.run {
        when (item.itemId) {
            R.id.menu_bookshelf ->
                viewPagerMain.setCurrentItem(0, false)

            R.id.menu_discovery ->
                viewPagerMain.setCurrentItem(realPositions.indexOf(idExplore), false)

            R.id.menu_rss ->
                viewPagerMain.setCurrentItem(realPositions.indexOf(idRss), false)

            R.id.menu_my_config ->
                viewPagerMain.setCurrentItem(realPositions.indexOf(idMy), false)
        }
        return false
    }

    override fun onNavigationItemReselected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_bookshelf -> {
                if (System.currentTimeMillis() - bookshelfReselected > 300) {
                    bookshelfReselected = System.currentTimeMillis()
                } else {
                    (findFragmentById(getFragmentId(0)) as? BaseBookshelfFragment)?.gotoTop()
                }
            }

            R.id.menu_discovery -> {
                if (System.currentTimeMillis() - exploreReselected > 300) {
                    exploreReselected = System.currentTimeMillis()
                } else {
                    (findFragmentById(idExplore) as? ExploreFragment)?.gotoTop()
                }
            }
        }
    }

    private fun initView() = binding.run {
        viewPagerMain.setEdgeEffectColor(primaryColor)
        viewPagerMain.offscreenPageLimit = 3
        // 主 tab 翻页降敏:书架分组/发现横滑封面时,轻微横移不再误切标签(需更明确横拖)
        viewPagerMain.reduceDragSensitivity()
        viewPagerMain.adapter = adapter
        viewPagerMain.registerOnPageChangeCallback(PageChangeCallback())
        bottomNavigationView.elevation = elevation
        bottomNavigationView.setOnNavigationItemSelectedListener(this@MainActivity)
        bottomNavigationView.setOnNavigationItemReselectedListener(this@MainActivity)
        if (AppConfig.isEInkMode) {
            bottomNavigationView.setBackgroundResource(R.drawable.bg_eink_border_top)
        }
        // 导航栏 inset 由 ThemeBottomNavigationVIew.init 的 applyNavigationBarPadding 接管
    }

    /**
     * 用户隐私与协议
     */
    private suspend fun privacyPolicy(): Boolean = suspendCancellableCoroutine sc@{ block ->
        if (LocalConfig.privacyPolicyOk) {
            block.resume(true)
            return@sc
        }
        val privacyPolicy = String(assets.open("privacyPolicy.md").readBytes())
        alert(getString(R.string.privacy_policy), privacyPolicy) {
            positiveButton(R.string.agree) {
                LocalConfig.privacyPolicyOk = true
                block.resume(true)
            }
            negativeButton(R.string.refuse) {
                finish()
                block.resume(false)
            }
        }
    }

    /**
     * 版本更新日志
     */
    private suspend fun upVersion() = suspendCancellableCoroutine sc@{ block ->
        if (LocalConfig.versionCode == AppConst.appInfo.versionCode) {
            block.resume(null)
            return@sc
        }
        LocalConfig.versionCode = AppConst.appInfo.versionCode
        if (LocalConfig.isFirstOpenApp) {
            val help = String(assets.open("web/help/md/appHelp.md").readBytes())
            val dialog = TextDialog(getString(R.string.help), help, TextDialog.Mode.MD)
            dialog.setOnDismissListener {
                block.resume(null)
            }
            showDialogFragment(dialog)
        } else if (!BuildConfig.DEBUG) {
            val log = String(assets.open("updateLog.md").readBytes())
            val dialog = TextDialog(getString(R.string.update_log), log, TextDialog.Mode.MD)
            dialog.setOnDismissListener {
                block.resume(null)
            }
            showDialogFragment(dialog)
        } else {
            block.resume(null)
        }
    }

    /**
     * 自动检查APP新版本,24小时最多一次;失败与已忽略版本均静默
     */
    private fun checkAppUpdate() {
        if (BuildConfig.DEBUG || !AppConfig.autoCheckUpdate) return
        if (System.currentTimeMillis() - LocalConfig.lastUpdateCheckTime < UPDATE_CHECK_INTERVAL) {
            return
        }
        LocalConfig.lastUpdateCheckTime = System.currentTimeMillis()
        AppUpdate.gitHubUpdate?.run {
            check(lifecycleScope)
                .onSuccess {
                    if (it.tagName == LocalConfig.ignoreUpdateVersion) return@onSuccess
                    if (supportFragmentManager.isStateSaved) return@onSuccess
                    showDialogFragment(UpdateDialog(it))
                }.onError {
                    AppLog.put("自动检查更新: ${it.localizedMessage}")
                }
        }
    }

    /**
     * 设置本地密码
     */
    private suspend fun setLocalPassword() = suspendCancellableCoroutine sc@{ block ->
        if (LocalConfig.password != null) {
            block.resume(null)
            return@sc
        }
        alert(R.string.set_local_password, R.string.set_local_password_summary) {
            val editTextBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "password"
            }
            customView {
                editTextBinding.root
            }
            onDismiss {
                block.resume(null)
            }
            okButton {
                LocalConfig.password = editTextBinding.editView.text.toString()
            }
            cancelButton {
                LocalConfig.password = ""
            }
        }
    }

    private fun notifyAppCrash() {
        if (!LocalConfig.appCrash || BuildConfig.DEBUG) {
            return
        }
        LocalConfig.appCrash = false
        alert(getString(R.string.draw), "检测到阅读发生了崩溃，是否打开崩溃日志以便报告问题？") {
            yesButton {
                showDialogFragment<CrashLogsDialog>()
            }
            noButton()
        }
    }

    /**
     * 备份同步
     */
    private fun backupSync() {
        if (!AppConfig.autoCheckNewBackup) {
            return
        }
        lifecycleScope.launch {
            val lastBackupFile =
                withContext(IO) { AppWebDav.lastBackUp().getOrNull() } ?: return@launch
            if (lastBackupFile.lastModify - LocalConfig.lastBackup > DateUtils.MINUTE_IN_MILLIS) {
                LocalConfig.lastBackup = lastBackupFile.lastModify
                alert(R.string.restore, R.string.webdav_after_local_restore_confirm) {
                    cancelButton()
                    okButton {
                        viewModel.restoreWebDav(lastBackupFile.displayName)
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (AppConfig.autoRefreshBook) {
            outState.putBoolean("isAutoRefreshedBook", true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Coroutine.async {
            BookHelp.clearInvalidCache()
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
    }

    /**
     * 如果重启太快fragment不会重建,这里更新一下书架的排序
     */
    override fun recreate() {
        (findFragmentById(getFragmentId(0)) as? BaseBookshelfFragment)?.run {
            upSort()
        }
        super.recreate()
    }

    override fun observeLiveBus() {
        viewModel.onUpBooksLiveData.observe(this) {
            if (onUpBooksBadgeView == null) {
                onUpBooksBadgeView = binding.bottomNavigationView.addBadgeView(0)
            }
            onUpBooksBadgeView!!.setBadgeCount(it)
        }
        observeEvent<String>(EventBus.RECREATE) {
            recreate()
        }
        observeEvent<Boolean>(EventBus.NOTIFY_MAIN) {
            binding.apply {
                if (it) {
                    bottomNavigationView.menu.clear()
                    bottomNavigationView.inflateMenu(R.menu.main_bnv)
                    onUpBooksBadgeView = null
                }
                upBottomMenu()
                upBottomBarSkin()
                if (it) {
                    viewPagerMain.setCurrentItem(bottomMenuCount - 1, false)
                }
            }
        }
        observeEvent<String>(EventBus.BOTTOM_BAR_SKIN) {
            upBottomBarSkin()
        }
        observeEvent<String>(PreferKey.threadCount) {
            viewModel.upPool()
        }
        observeEvent<List<Book>>(EventBus.UP_BOOKS_TOC) {
            viewModel.upToc(it, cache = false)
        }
    }

    private fun upBottomMenu() {
        val showDiscovery = AppConfig.showDiscovery
        val showRss = AppConfig.showRSS
        binding.bottomNavigationView.menu.let { menu ->
            menu.findItem(R.id.menu_discovery).isVisible = showDiscovery
            menu.findItem(R.id.menu_rss).isVisible = showRss
        }
        var index = 0
        if (showDiscovery) {
            index++
            realPositions[index] = idExplore
        }
        if (showRss) {
            index++
            realPositions[index] = idRss
        }
        index++
        realPositions[index] = idMy
        bottomMenuCount = index + 1
        adapter.notifyDataSetChanged()
    }

    private fun upBottomBarSkin() {
        val skin = BottomBarSkinManager.active
        if (skin.isEmpty() || !BottomBarSkinManager.skinDir(skin).exists()) {
            binding.bottomNavigationView.applySkin(null, 0)
            return
        }
        val sizePx = 30.dpToPx()
        val map = HashMap<Int, StateListDrawable>()
        menuIdToSlot.forEach { (id, slot) ->
            BottomBarSkinManager.getStateDrawable(skin, slot, sizePx)?.let { map[id] = it }
        }
        binding.bottomNavigationView.applySkin(map, sizePx)
    }

    private fun upHomePage() {
        when (AppConfig.defaultHomePage) {
            "bookshelf" -> {}
            "explore" -> if (AppConfig.showDiscovery) {
                binding.viewPagerMain.setCurrentItem(realPositions.indexOf(idExplore), false)
            }

            "rss" -> if (AppConfig.showRSS) {
                binding.viewPagerMain.setCurrentItem(realPositions.indexOf(idRss), false)
            }

            "my" -> binding.viewPagerMain.setCurrentItem(realPositions.indexOf(idMy), false)
        }
    }

    private fun getFragmentId(position: Int): Int {
        val id = realPositions[position]
        if (id == idBookshelf) {
            return if (AppConfig.bookGroupStyle == 1) idBookshelf2 else idBookshelf1
        }
        return id
    }

    /**
     * FragmentStateAdapter 固定按 "f" + itemId 建 tag,本页 itemId = getFragmentId(position)。
     * 书架样式/tab 显隐会改变各 position 的 fragmentId,故用 fragmentId(而非 position)定位存活 fragment。
     */
    private fun findFragmentById(fragmentId: Int): Fragment? {
        return supportFragmentManager.findFragmentByTag("f$fragmentId")
    }

    private inner class PageChangeCallback : ViewPager2.OnPageChangeCallback() {

        override fun onPageSelected(position: Int) {
            pagePosition = position
            binding.bottomNavigationView.menu[realPositions[position]].isChecked = true
        }

    }

    private inner class TabFragmentPageAdapter :
        FragmentStateAdapter(this@MainActivity) {

        override fun getItemCount(): Int {
            return bottomMenuCount
        }

        /**
         * itemId 编码 fragment 身份(书架样式/tab 显隐决定)。样式切换或 tab 增删使某 position 的
         * fragmentId 改变→id 变→自动销毁重建,等价原 getItemPosition 的 POSITION_NONE 强刷协议。
         */
        override fun getItemId(position: Int): Long {
            return getFragmentId(position).toLong()
        }

        override fun containsItem(itemId: Long): Boolean {
            return (0 until itemCount).any { getItemId(it) == itemId }
        }

        override fun createFragment(position: Int): Fragment {
            return when (getFragmentId(position)) {
                idBookshelf1 -> BookshelfFragment1(position)
                idBookshelf2 -> BookshelfFragment2(position)
                idExplore -> ExploreFragment(position)
                idRss -> RssFragment(position)
                else -> MyFragment(position)
            }
        }

    }

}
