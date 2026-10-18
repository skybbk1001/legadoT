package io.legado.app.data.entities

import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule

/**
 * 书源(BookSource, 留 app: Room 实体/BaseSource 反射耦合)在 shared 层的最小契约。
 * BookSource 实现本接口后, 已迁入 shared 的 webBook 在线书籍模型
 * (WebBook/BookList/BookInfo/BookChapterList/BookContent)可脱离 app 模块
 * 以契约类型引用书源能力。字段均为 BookSource 既有成员, 无运行时差异。
 */
interface BookSourceContract : SourceContract {

    /** 书源地址(唯一标识, 同 getKey()) */
    val bookSourceUrl: String

    /** 书源名称 */
    val bookSourceName: String

    /** 书源类型(BookSourceType.Type: 0 文本/1 音频/2 图片/3 文件) */
    val bookSourceType: Int

    /** 手动排序编号 */
    val customOrder: Int

    /** 详情页url正则 */
    val bookUrlPattern: String?

    /** 搜索url */
    val searchUrl: String?

    /** 登录检测js */
    val loginCheckJs: String?

    /** 目录页规则(可为空, getTocRule() 惰性补默认) */
    val ruleToc: TocRule?

    fun getSearchRule(): SearchRule

    fun getExploreRule(): ExploreRule

    fun getBookInfoRule(): BookInfoRule

    fun getTocRule(): TocRule

    fun getContentRule(): ContentRule

    /** 是否 JS 单文件源(mainJs 非空) */
    fun isJsSource(): Boolean

}
