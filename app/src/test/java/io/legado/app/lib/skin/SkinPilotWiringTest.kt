package io.legado.app.lib.skin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R2a 试点接线锚点:引擎安装点、弹窗通路、试点布局的角色声明、手动施色已让位。
 */
class SkinPilotWiringTest {

    @Test
    fun `theme installer installs before super onCreate in BaseActivity`() {
        val src = readProjectFile("src/main/java/io/legado/app/base/BaseActivity.kt")
        val install = src.indexOf("AppThemeInstaller.install(this)")
        val superCreate = src.indexOf("super.onCreate(savedInstanceState)")
        assertTrue("BaseActivity 未安装统一主题入口", install > 0)
        assertTrue("主题须在 super.onCreate 前安装", install < superCreate)
    }

    @Test
    fun `dialog inflater route installs factory as fallback`() {
        val src = readProjectFile("src/main/java/io/legado/app/base/BaseDialogFragment.kt")
        assertTrue(src.contains("onGetLayoutInflater"))
        assertTrue(src.contains("SkinInflaterFactory.installOn"))
    }

    @Test
    fun `pilot manage card layouts declare role and adapters dropped manual paint`() {
        listOf(
            "src/main/res/layout/item_manage.xml",
            "src/main/res/layout/item_book_source.xml",
            "src/main/res/layout/item_explore_manage.xml",
            "src/main/res/layout/item_arrange_book.xml",
        ).forEach { layout ->
            assertTrue(
                "$layout 缺 skin_background 角色声明",
                readProjectFile(layout).contains("""app:skin_background="surfaceContainerLow"""")
            )
        }
        listOf(
            "src/main/java/io/legado/app/ui/rss/source/manage/RssSourceAdapter.kt",
            "src/main/java/io/legado/app/ui/replace/ReplaceRuleAdapter.kt",
            "src/main/java/io/legado/app/ui/dict/rule/DictRuleAdapter.kt",
            "src/main/java/io/legado/app/ui/book/toc/rule/TxtTocRuleAdapter.kt",
            "src/main/java/io/legado/app/ui/autoTask/AutoTaskAdapter.kt",
            "src/main/java/io/legado/app/ui/highlight/HighlightRuleAdapter.kt",
            "src/main/java/io/legado/app/ui/book/source/manage/BookSourceAdapter.kt",
            "src/main/java/io/legado/app/ui/book/manage/BookAdapter.kt",
            "src/main/java/io/legado/app/ui/main/explore/manage/ExploreManageAdapter.kt",
        ).forEach { adapter ->
            assertFalse(
                "$adapter 仍有手动卡底施色(引擎已接管,双写会掩盖引擎故障)",
                readProjectFile(adapter).contains("setCardBackgroundColor(AppColorScheme")
            )
        }
    }

    /** R2b:纯施色类已删、行为壳无施色残留、引擎接管四个行为壳 */
    @Test
    fun `theme widget family retired to behavior shells with engine-owned tinting`() {
        // 纯施色类退役:类文件与 XML 引用双清零
        listOf("ThemeSeekBar", "ThemeProgressBar").forEach { name ->
            assertFalse(
                "$name 应已删除",
                File("src/main/java/io/legado/app/lib/theme/view/$name.kt").isFile ||
                    File("app/src/main/java/io/legado/app/lib/theme/view/$name.kt").isFile
            )
        }
        // 行为壳:无任何施色代码
        listOf("ThemeCheckBox", "ThemeSwitch", "ThemeRadioButton", "ThemeEditText").forEach { name ->
            val src = readProjectFile("src/main/java/io/legado/app/lib/theme/view/$name.kt")
            assertFalse("$name 应无施色残留", src.contains("TintList") || src.contains("applyTint"))
        }
        // 引擎接管:四个行为壳进精确匹配与反射兜底名单
        val factory = readProjectFile("src/main/java/io/legado/app/lib/skin/SkinInflaterFactory.kt")
        listOf("ThemeCheckBox", "ThemeSwitch", "ThemeRadioButton", "ThemeEditText").forEach { name ->
            assertTrue("引擎缺 $name 精确匹配", factory.contains("$name::class.java"))
            assertTrue(
                "引擎兜底名单缺 $name",
                factory.contains("\"io.legado.app.lib.theme.view.$name\"")
            )
        }
    }

    /** R2c-w1:弹窗 toolbar 施色已声明化,ui/ 无手动残留(双写会掩盖引擎故障) */
    @Test
    fun `dialog toolbars declare themePrimary and manual paint is gone`() {
        listOf("dialog_recycler_view", "dialog_book_change_source", "dialog_text_view").forEach {
            assertTrue(
                "$it 的 tool_bar 缺 themePrimary 声明",
                readProjectFile("src/main/res/layout/$it.xml")
                    .contains("""app:skin_background="themePrimary"""")
            )
        }
        val uiRoot = listOf(File("src/main/java/io/legado/app/ui"), File("app/src/main/java/io/legado/app/ui"))
            .first { it.isDirectory }
        val offenders = uiRoot.walkTopDown().filter { it.extension == "kt" }
            .filter { it.readText().contains("toolBar.setBackgroundColor(primaryColor)") }
            .map { it.name }.toList()
        assertTrue("仍有手动 toolbar 施色: $offenders", offenders.isEmpty())
    }

    /** R2c-w3:background/accent/error 静态族已声明角色,分组/服务器行手动施色让位,仿真翻页 Switch 入引擎 */
    @Test
    fun `static background accent error migrated and group row manual paint is gone`() {
        // 代表性布局:双写(静态留作兜底 + skin 角色声明)
        mapOf(
            "item_group_manage" to """app:skin_background="background"""",
            "dialog_chapter_change_source" to """app:skin_background="background"""",
            "activity_welcome" to """app:skin_background="primary"""",
            "view_preference_category" to """app:skin_textColor="primary"""",
            "dialog_bookmark" to """app:skin_textColor="error"""",
        ).forEach { (layout, decl) ->
            assertTrue(
                "$layout 缺 $decl 声明",
                readProjectFile("src/main/res/layout/$layout.xml").contains(decl)
            )
        }
        // 仿真翻页 Switch 已换 MaterialSwitch(引擎规则 A 接管)
        val sim = readProjectFile("src/main/res/layout/dialog_simulated_reading.xml")
        assertTrue(sim.contains("com.google.android.material.materialswitch.MaterialSwitch"))
        assertFalse(sim.contains("androidx.appcompat.widget.SwitchCompat"))
        // 分组/服务器行底色手动施色让位(双写会掩盖引擎故障)
        listOf(
            "src/main/java/io/legado/app/ui/book/group/GroupManageDialog.kt",
            "src/main/java/io/legado/app/ui/book/group/GroupSelectDialog.kt",
            "src/main/java/io/legado/app/ui/widget/dialog/GroupManageDialog.kt",
            "src/main/java/io/legado/app/ui/book/import/remote/ServersDialog.kt",
        ).forEach { file ->
            assertFalse(
                "$file 仍有行底色手动施色",
                readProjectFile(file).contains("setBackgroundColor(context.backgroundColor)")
            )
        }
        // 阅读器画布不进引擎:view_book_page 保持无 skin_* 声明
        assertFalse(
            "view_book_page 不应有 skin 声明(阅读器自绘)",
            readProjectFile("src/main/res/layout/view_book_page.xml").contains("app:skin_")
        )
        assertFalse(
            "主界面底栏背景由 ThemeBottomNavigationVIew 按沉浸式设置管理",
            readProjectFile("src/main/res/layout/activity_main.xml")
                .contains("app:skin_background")
        )
    }

    /** R2c-w4:background_menu/card 核对波——平涂同值声明化+代码让位;读书菜单族/沉浸式条件卡有意留代码域 */
    @Test
    fun `menu and card statics migrated where flat-painted and reader menus stay code-owned`() {
        mapOf(
            "activity_book_info" to """app:skin_background="themeBottomBackground"""",
            "activity_book_read" to """app:skin_background="themeBottomBackground"""",
            "activity_book_info_edit" to """app:skin_background="surfaceContainerLow"""",
            "activity_file_manage" to """app:skin_background="surfaceContainerLow"""",
        ).forEach { (layout, decl) ->
            assertTrue(
                "$layout 缺 $decl 声明",
                readProjectFile("src/main/res/layout/$layout.xml").contains(decl)
            )
        }
        // 平涂同值代码已让位
        val bookInfo = readProjectFile("src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt")
        assertFalse(bookInfo.contains("flAction.setBackgroundColor"))
        assertFalse(bookInfo.contains("llInfo.setBackgroundColor"))
        assertFalse(
            readProjectFile("src/main/java/io/legado/app/ui/book/read/BaseReadBookActivity.kt")
                .contains("navigationBar.setBackgroundColor")
        )
        // 读书菜单族背景=代码自管理域(豁免模板/FAB ColorStateList/亮暗联动),bg 通道不声明角色
        // (w2 的文字/图标 skin_textColor 等声明不在此限)
        listOf("view_search_menu", "view_manga_menu", "dialog_auto_read", "dialog_read_aloud").forEach {
            assertFalse(
                "$it 属读书菜单族,背景不应声明 skin_background",
                readProjectFile("src/main/res/layout/$it.xml").contains("app:skin_background")
            )
        }
        // 沉浸式条件卡(cardBackgroundColor 行为取色)背景保持代码域
        listOf("item_bookshelf_grid", "item_explore_cover").forEach {
            assertFalse(
                "$it 的封面卡由 cardBackgroundColor 条件染色,不应声明 skin_background",
                readProjectFile("src/main/res/layout/$it.xml").contains("app:skin_background")
            )
        }
        // 沉浸式条件条:源编辑 tab 栏/调试帮助面板背景由代码按沉浸式设置染色
        listOf("activity_book_source_edit", "activity_rss_source_edit", "activity_source_debug").forEach {
            assertFalse(
                "$it 的背景条由代码按沉浸式设置染色,不应声明 skin_background=\"background\"",
                readProjectFile("src/main/res/layout/$it.xml")
                    .contains("app:skin_background=\"background\"")
            )
        }
        // adaptation/FLOATING 模板重涂区不放哑弹声明(模板 onViewCreated 末尾必覆盖):
        // explore 三弹窗 vw_bg + 分组编辑体面板(验收回合查实回收,防再犯)
        listOf(
            "dialog_explore_container_edit", "dialog_explore_source_picker",
            "dialog_kind_picker", "dialog_book_group_edit",
        ).forEach {
            assertFalse(
                "$it 的模板重涂区不应有 skin_background 哑弹声明",
                readProjectFile("src/main/res/layout/$it.xml").contains("app:skin_background=\"background\"")
            )
        }
        // 发现页容器卡定案为可见卡面(surfaceContainerLow 引擎接管),不再走沉浸式透明+代码施色
        assertTrue(
            "item_explore_container 缺 surfaceContainerLow 声明",
            readProjectFile("src/main/res/layout/item_explore_container.xml")
                .contains("""app:skin_background="surfaceContainerLow"""")
        )
        assertFalse(
            "ExploreContainerAdapter 不应再手动施 rootCard 卡底色(引擎已接管)",
            readProjectFile("src/main/java/io/legado/app/ui/main/explore/ExploreContainerAdapter.kt")
                .contains("rootCard.setCardBackgroundColor")
        )
    }

    /** R2d:运行时收敛——ThemeStore 瘦身为四色存储层、prefs 同值双写让位引擎、ATH 死类/死色退场 */
    @Test
    fun `runtime converged - theme store slimmed prefs manual paint gone dead colors purged`() {
        // ATH 死类退场(零引用查实)
        listOf(
            "src/main/java/io/legado/app/lib/theme/ThemeStoreInterface.kt",
            "src/main/java/io/legado/app/lib/theme/ViewUtils.kt",
            "src/main/java/io/legado/app/ui/widget/text/PrimaryTextView.kt",
        ).forEach { path ->
            assertFalse("$path 应已删除", File(path).isFile || File("app/$path").isFile)
        }
        // ThemeStore 只剩四色读写:从未写入的 ATH 键族(状态栏/导航栏/文字色/primaryColorDark)已退役,
        // 消费端改直读四色或现算 darken(BaseActivity/BaseReadBookActivity)
        val store = readProjectFile("src/main/java/io/legado/app/lib/theme/ThemeStore.kt")
        listOf("statusBarColor", "navigationBarColor", "textColorPrimary", "primaryColorDark")
            .forEach { assertFalse("ThemeStore 不应再有 $it", store.contains(it)) }
        // prefs 波:引擎可达处声明化,手动同值双写删除(双写会掩盖引擎故障)
        assertFalse(
            "PreferenceCategory 标题重涂应已删(布局已声明 skin_textColor=primary)",
            readProjectFile("src/main/java/io/legado/app/lib/prefs/PreferenceCategory.kt")
                .contains("setTextColor")
        )
        assertFalse(
            "Preference 图标 tint 应由 skin_tint 声明接管",
            readProjectFile("src/main/java/io/legado/app/lib/prefs/Preference.kt")
                .contains("setColorFilter")
        )
        assertTrue(
            "view_preference 图标缺 skin_tint=primary 声明",
            readProjectFile("src/main/res/layout/view_preference.xml")
                .contains("""app:skin_tint="primary"""")
        )
        assertFalse(
            "SwitchPreference 开关施色应由引擎规则 A 接管(MaterialSwitch 同值)",
            readProjectFile("src/main/java/io/legado/app/lib/prefs/SwitchPreference.kt")
                .contains("trackTintList")
        )
        assertFalse(
            "EditTextPreference 输入框施色应由引擎规则 A 接管(AppCompatEditText 同值)",
            readProjectFile("src/main/java/io/legado/app/lib/prefs/EditTextPreference.kt")
                .contains("applyTint")
        )
        assertFalse(
            "IconListPreference toolbar 施色应由 dialog_recycler_view 的 themePrimary 声明接管",
            readProjectFile("src/main/java/io/legado/app/lib/prefs/IconListPreference.kt")
                .contains("toolBar.setBackgroundColor")
        )
        // 死色清除后不回流(抽查代表:ATH 遗留/零引用长尾)
        val colors = readProjectFile("src/main/res/values/colors.xml")
        listOf("<color name=\"primary\">", "ate_icon_light", "background_prefs", "btn_write")
            .forEach { assertFalse("colors.xml 死色 $it 不应回流", colors.contains(it)) }
    }

    private fun readProjectFile(pathInApp: String): String {
        val candidates = listOf(File(pathInApp), File("app/$pathInApp"))
        return candidates.first { it.isFile }.readText()
    }
}
