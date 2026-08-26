package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.ChapterRoleScript

@Dao
interface ChapterRoleScriptDao {

    @Query("select * from chapterRoleScripts where bookUrl = :bookUrl and chapterIndex = :chapterIndex")
    fun get(bookUrl: String, chapterIndex: Int): ChapterRoleScript?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg script: ChapterRoleScript)

    @Query("delete from chapterRoleScripts where bookUrl = :bookUrl")
    fun deleteByBook(bookUrl: String)

    @Query("delete from chapterRoleScripts where bookUrl = :bookUrl and chapterIndex = :chapterIndex")
    fun delete(bookUrl: String, chapterIndex: Int)

    @Query("delete from chapterRoleScripts where bookUrl not in (select bookUrl from books)")
    fun deleteOrphans()

    /** 每本书只留最近标注的 :keep 章, 缓存随阅读量线性增长, 靠它收口 */
    @Query(
        "delete from chapterRoleScripts where rowid not in (" +
                "select rowid from chapterRoleScripts as newest " +
                "where (select count(*) from chapterRoleScripts as later " +
                "where later.bookUrl = newest.bookUrl " +
                "and (later.createTime > newest.createTime " +
                "or (later.createTime = newest.createTime " +
                "and later.chapterIndex > newest.chapterIndex))) < :keep)"
    )
    fun trimToRecent(keep: Int)
}
