package io.legado.app.ui.config

import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.base.AppContextWrapper
import io.legado.app.constant.AppConst
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.prefs.PresetThemesPreference
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.prefs.ThemePreviewPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.WallpaperSeed
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import java.io.File
import java.net.URLDecoder

@Suppress("SameParameterValue")
class ThemeConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    FontSelectDialog.CallBack {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_theme)
        if (Build.VERSION.SDK_INT < 26) {
            preferenceScreen.removePreferenceRecursively(PreferKey.launcherIcon)
        }
        if (!WallpaperSeed.isAvailable()) {
            // 壁纸取色 API 需 12+(S=31),低版本隐藏开关入口(主+子开关一并隐藏)
            findPreference<SwitchPreference>(PreferKey.wallpaperFollow)?.isVisible = false
            findPreference<SwitchPreference>(PreferKey.wallpaperAutoUpdate)?.isVisible = false
        }
        upPreferenceSummary(PreferKey.barElevation, AppConfig.elevation.toString())
        upPreferenceSummary(PreferKey.fontScale)
        upPreferenceSummary(PreferKey.globalFont)
        findPreference<SwitchPreference>(PreferKey.wallpaperFollow)?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            val autoUpdate = getPrefBoolean(PreferKey.wallpaperAutoUpdate, true)
            val ok = WallpaperSeed.setFollow(requireContext(), enabled, autoUpdate)
            if (enabled && !ok) {
                // 取不到壁纸颜色:toast 提示 + 监听器返回 false。
                // TwoStatePreference.onClick 契约:callChangeListener 返回 false 时 setChecked()/
                // notifyChanged() 均不会执行——开关小部件本身 clickable=false(纯数据绑定展示,
                // 见 view_preference_widget_switch.xml),视觉状态自始至终未离开"关闭"外观,
                // 不是"回弹动画"而是"根本没变过";效果上等价于回滚,但机制是"未提交"而非"提交后复原"。
                toastOnUi(R.string.wallpaper_no_color)
                return@setOnPreferenceChangeListener false
            }
            true
        }
        findPreference<SwitchPreference>(PreferKey.wallpaperAutoUpdate)?.setOnPreferenceChangeListener { _, newValue ->
            // 仅在主开关已开启时才重新 setFollow(true, 新值);主开关关闭时此开关本就因
            // android:dependency 禁用不可交互,这里是双保险,不做失败回弹(取色早已成功过一次)
            if (getPrefBoolean(PreferKey.wallpaperFollow)) {
                WallpaperSeed.setFollow(requireContext(), true, newValue as Boolean)
            }
            true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.theme_setting)
        listView.setEdgeEffectColor(primaryColor)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    /**
     * 自定义配色二级页编辑非当前显示模式的色值时不触发 RECREATE,返回本页时预设排选中描边
     * 与英雄卡不会经重建自愈——onResume 补刷一次(notifyChanged 重绑,首次进入多刷无害)。
     * 子页手动改色的钩子可能已静默把跟随开关写为 false(WallpaperSeed.abandonFollowIfActive),
     * 同样无 RECREATE 可依赖,故一并重新同步开关勾选状态。
     */
    override fun onResume() {
        super.onResume()
        findPreference<PresetThemesPreference>("presetThemes")?.refresh()
        findPreference<ThemePreviewPreference>("themePreview")?.refresh()
        findPreference<SwitchPreference>(PreferKey.wallpaperFollow)?.let {
            it.isChecked = getPrefBoolean(PreferKey.wallpaperFollow)
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        sharedPreferences ?: return
        when (key) {
            PreferKey.launcherIcon -> LauncherIconHelp.changeIcon(getPrefString(key))
            PreferKey.transparentStatusBar -> recreateActivities()
            PreferKey.transparentActionBar -> recreateActivities()
            PreferKey.immNavigationBar -> recreateActivities()
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            PreferKey.barElevation -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.bar_elevation))
                .setMaxValue(32)
                .setMinValue(0)
                .setValue(AppConfig.elevation)
                .setCustomButton((R.string.btn_default_s)) {
                    AppConfig.elevation = AppConst.sysElevation
                    recreateActivities()
                }
                .show {
                    AppConfig.elevation = it
                    recreateActivities()
                }

            PreferKey.fontScale -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.font_scale))
                .setMaxValue(16)
                .setMinValue(8)
                .setValue(10)
                .setCustomButton((R.string.btn_default_s)) {
                    putPrefInt(PreferKey.fontScale, 0)
                    recreateActivities()
                }
                .show {
                    putPrefInt(PreferKey.fontScale, it)
                    recreateActivities()
                }

            PreferKey.globalFont -> showDialogFragment(FontSelectDialog())

            "customColorConfig" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.THEME_COLOR_CONFIG)
            }

            "themeList" -> ThemeListDialog().show(childFragmentManager, "themeList")
            "bottomBarSkin" -> startActivity<BottomBarSkinActivity>()

            "coverConfig" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.COVER_CONFIG)
            }

            "welcomeStyle" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.WELCOME_CONFIG)
            }
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun recreateActivities() {
        postEvent(EventBus.RECREATE, "")
    }

    // --- FontSelectDialog.CallBack: 全局 UI 字体 ---
    override val curFontPath: String
        get() = AppConfig.globalFontPath

    override fun selectFont(path: String) {
        AppConfig.globalFontPath = path
        recreateActivities()
    }

    override fun selectSystemTypeface(index: Int) {
        AppConfig.globalTypefaces = index
        AppConfig.globalFontPath = ""
        recreateActivities()
    }

    private fun globalFontSummary(): String {
        val path = AppConfig.globalFontPath
        if (path.isNotEmpty()) {
            return kotlin.runCatching {
                URLDecoder.decode(path, "utf-8")
                    .substringAfterLast(File.separator)
                    .substringAfterLast("/")
            }.getOrNull() ?: path
        }
        val typefaces = requireContext().resources.getStringArray(R.array.system_typefaces)
        return typefaces.getOrElse(AppConfig.globalTypefaces) { typefaces[0] }
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String? = null) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            PreferKey.barElevation -> preference.summary =
                getString(R.string.bar_elevation_s, value)

            PreferKey.fontScale -> {
                val fontScale = AppContextWrapper.getFontScale(requireContext())
                preference.summary = getString(R.string.font_scale_summary, fontScale)
            }

            PreferKey.globalFont -> preference.summary = globalFontSummary()

            else -> preference.summary = value
        }
    }

}
