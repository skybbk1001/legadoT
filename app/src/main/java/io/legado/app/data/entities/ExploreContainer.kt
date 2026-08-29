package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.legado.app.constant.AppPattern
import io.legado.app.utils.splitNotBlank

/**
 * 发现页容器:绑定某书源的一个或多个发现分类,自选展示样式;
 * 多个分类以卡片顶部分类标签切换,kindTitle/kindUrl 为当前展示分类
 */
@Entity(tableName = "exploreContainers")
data class ExploreContainer(
    @PrimaryKey(autoGenerate = true)
    var id: Long = System.currentTimeMillis(),
    /** 书源标识 bookSourceUrl */
    var sourceUrl: String = "",
    /** 书源名快照(显示用) */
    var sourceName: String = "",
    /** 添加时的分类名,用于显示和在当前分类列表中动态匹配 */
    var kindTitle: String = "",
    /** 添加时的分类 URL 快照(动态匹配不到时兜底) */
    var kindUrl: String = "",
    /** 添加时选中的分类名,逗号分隔;空 = 旧数据/未指定,动态展示书源全部分类 */
    @ColumnInfo(defaultValue = "")
    var kindTitles: String = "",
    /** 添加时选中的分类 URL 快照,逗号分隔(与 kindTitles 一一对应) */
    @ColumnInfo(defaultValue = "")
    var kindUrls: String = "",
    /** 自定义标题,null/空白时显示 kindTitle */
    var customTitle: String? = null,
    /** 展示样式 */
    var style: Int = STYLE_FLOW,
    /** 列表样式展示数量(横滑样式忽略) */
    var listCount: Int = 3,
    var sortOrder: Int = 0,
    var enabled: Boolean = true,
    /** 分组名,空串 = 未分组(Room 新增 NOT NULL 列必须声明 defaultValue) */
    @ColumnInfo(defaultValue = "")
    var groupName: String = "",
) {

    fun getDisplayTitle(): String {
        return customTitle?.takeUnless { it.isBlank() } ?: kindTitle
    }

    /** 追加分组(逗号分隔多组,自动去重,分隔符归一为逗号) */
    fun addGroup(groups: String) {
        val set = linkedSetOf<String>()
        set.addAll(groupName.splitNotBlank(AppPattern.splitGroupRegex))
        set.addAll(groups.splitNotBlank(AppPattern.splitGroupRegex))
        groupName = set.joinToString(",")
    }

    /** 移除分组(可一次移除多个,分隔符同上) */
    fun removeGroup(groups: String) {
        val set = linkedSetOf<String>()
        set.addAll(groupName.splitNotBlank(AppPattern.splitGroupRegex))
        set.removeAll(groups.splitNotBlank(AppPattern.splitGroupRegex).toSet())
        groupName = set.joinToString(",")
    }

    /** 是否含指定分组(精确匹配,非子串) */
    fun hasGroup(group: String): Boolean {
        return groupName.splitNotBlank(AppPattern.splitGroupRegex).contains(group)
    }

    companion object {
        /** 横滑封面 */
        const val STYLE_FLOW = 0
        /** 列表带简介 */
        const val STYLE_LIST = 1
    }
}
