package io.legado.app.ui.main.bookshelf.style1

import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentBookshelf1Binding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.appBarBackgroundIsLight
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.tabTextColors
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.style1.books.BooksFragment
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.data.entities.getRealBookSort

/**
 * 书架界面
 */
class BookshelfFragment1() : BaseBookshelfFragment(R.layout.fragment_bookshelf1),
    TabLayout.OnTabSelectedListener,
    SearchView.OnQueryTextListener {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentBookshelf1Binding::bind)
    private val adapter by lazy { TabFragmentPageAdapter(this) }
    private val tabLayout: TabLayout by lazy {
        binding.titleBar.findViewById(R.id.tab_layout)
    }
    private val bookGroups = mutableListOf<BookGroup>()
    override val groupId: Long get() = selectedGroup?.groupId ?: 0

    override val books: List<Book>
        get() = findBooksFragment(groupId)?.getBooks() ?: emptyList()

    /** FragmentStateAdapter 固定用 "f" + itemId 建 tag。 */
    private fun findBooksFragment(groupId: Long): BooksFragment? {
        val itemId = groupId + GROUP_ITEM_ID_OFFSET
        return childFragmentManager.findFragmentByTag("f$itemId") as? BooksFragment
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        bindShelfHeader(binding.shelfHeader)
        initView()
        initBookGroupData()
    }

    private val selectedGroup: BookGroup?
        get() = bookGroups.getOrNull(tabLayout.selectedTabPosition)

    private fun initView() {
        binding.viewPagerBookshelf.setEdgeEffectColor(primaryColor)
        tabLayout.isTabIndicatorFullWidth = false
        tabLayout.tabMode = TabLayout.MODE_SCROLLABLE
        tabLayout.setSelectedTabIndicatorColor(requireContext().accentColor)
        // tabs 内嵌于 TitleBar(主色栏,沉浸式时透明露页面背景),
        // 明暗判据在主色与页面背景之间取实际可见的那个
        val tabBarIsLight = appBarBackgroundIsLight(
            transparentActionBar = AppConfig.isTransparentActionBar,
            barBackgroundColor = primaryColor,
            contentBackgroundColor = requireContext().backgroundColor
        )
        val tabColors = tabTextColors(tabBarIsLight)
        tabLayout.setTabTextColors(tabColors.unselected, tabColors.selected)
        binding.viewPagerBookshelf.offscreenPageLimit = 1
        binding.viewPagerBookshelf.adapter = adapter
        TabLayoutMediator(tabLayout, binding.viewPagerBookshelf) { tab, position ->
            tab.text = bookGroups[position].groupName
        }.attach()
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        SearchActivity.start(requireContext(), query)
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        return false
    }

    @Synchronized
    override fun upGroup(data: List<BookGroup>) {
        if (data.isEmpty()) {
            appDb.bookGroupDao.enableGroup(BookGroup.IdAll)
        } else {
            if (data != bookGroups) {
                bookGroups.clear()
                bookGroups.addAll(data)
                adapter.notifyDataSetChanged()
                selectLastTab()
                for (i in 0 until adapter.itemCount) {
                    tabLayout.getTabAt(i)?.view?.setOnLongClickListener {
                        showDialogFragment(GroupEditDialog(bookGroups[i]))
                        true
                    }
                }
                applyBookSortAndRefresh()
            }
        }
    }

    override fun upSort() {
        applyBookSortAndRefresh()
    }

    /**
     * 原 getItemPosition 里对未重建 fragment 的副作用:同步各分组的排序方式与刷新开关。
     * itemId=groupId,分组身份不变时 fragment 不重建,需在此对存活 fragment 手动应用。
     * 新建的分组 fragment 走 createFragment,构造时已带正确参数,findBooksFragment 返回 null 跳过。
     */
    private fun applyBookSortAndRefresh() {
        bookGroups.forEach { group ->
            val fragment = findBooksFragment(group.groupId) ?: return@forEach
            fragment.setEnableRefresh(group.enableRefresh)
            val bookSort = group.getRealBookSort()
            if (fragment.bookSort != bookSort) {
                fragment.upBookSort(bookSort)
            }
        }
    }

    private fun selectLastTab() {
        tabLayout.post {
            tabLayout.removeOnTabSelectedListener(this)
            tabLayout.getTabAt(AppConfig.saveTabPosition)?.select()
            tabLayout.addOnTabSelectedListener(this)
        }
    }

    override fun onTabReselected(tab: TabLayout.Tab) {
        selectedGroup?.let { group ->
            findBooksFragment(group.groupId)?.let {
                toastOnUi("${group.groupName}(${it.getBooksCount()})")
            }
        }
    }

    override fun onTabUnselected(tab: TabLayout.Tab) = Unit

    override fun onTabSelected(tab: TabLayout.Tab) {
        AppConfig.saveTabPosition = tab.position
    }

    override fun gotoTop() {
        findBooksFragment(groupId)?.gotoTop()
    }

    private inner class TabFragmentPageAdapter(fragment: Fragment) :
        FragmentStateAdapter(fragment) {

        override fun getItemCount(): Int {
            return bookGroups.size
        }

        /**
         * itemId=groupId+偏移:分组身份不变则 fragment 复用,分组换位/新增/删除时 id 变→自动重建。
         * 偏移规避内置分组负 id("全部"=-1L)撞上 RecyclerView.NO_ID 哨兵;
         * 真实分组 id 为 2 的幂位掩码(≤63 位),与内置负 id 偏移后不相交,双射成立。
         * 排序/刷新开关变化的副作用由 applyBookSortAndRefresh 在 upGroup/upSort 中补齐。
         */
        override fun getItemId(position: Int): Long {
            return bookGroups[position].groupId + GROUP_ITEM_ID_OFFSET
        }

        override fun containsItem(itemId: Long): Boolean {
            return bookGroups.any { it.groupId + GROUP_ITEM_ID_OFFSET == itemId }
        }

        override fun createFragment(position: Int): Fragment {
            val group = bookGroups[position]
            return BooksFragment(position, group)
        }

    }

    companion object {
        private const val GROUP_ITEM_ID_OFFSET = 10000L
    }
}
