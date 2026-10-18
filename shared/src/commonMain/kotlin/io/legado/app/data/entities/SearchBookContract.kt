package io.legado.app.data.entities

/**
 * 搜索结果书(SearchBook, 留 app: Room 实体外键指向 BookSource/Parcelable)
 * 在 shared 层的最小契约。webBook 书籍列表解析(BookList/WebBook)经本契约
 * 产出搜索结果, 实例由 app 侧工厂(WebBookBridge.newSearchBook)创建,
 * 运行时始终是 app 的 SearchBook。
 */
interface SearchBookContract : BaseBook {

    /** 书源 */
    var origin: String

    /** 书源名称 */
    var originName: String

    /** 书源排序 */
    var originOrder: Int

    /** BookType */
    var type: Int

    var coverUrl: String?

    var intro: String?

    var latestChapterTitle: String?

    /** 多源命中时合并书源标识 */
    fun addOrigin(origin: String)

    /** 转为 Book(换源/详情加载用) */
    fun toBook(): Book

}
