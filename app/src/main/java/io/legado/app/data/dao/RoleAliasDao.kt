package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.RoleAlias

@Dao
interface RoleAliasDao {

    @Query("select * from roleAliases where bookUrl = :bookUrl")
    fun getByBook(bookUrl: String): List<RoleAlias>

    @get:Query("select * from roleAliases")
    val all: List<RoleAlias>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg aliases: RoleAlias)

    @Query("update roleAliases set canonicalName = :newName where bookUrl = :bookUrl and canonicalName = :oldName")
    fun redirect(bookUrl: String, oldName: String, newName: String)

    @Query("delete from roleAliases where bookUrl = :bookUrl")
    fun deleteByBook(bookUrl: String)

    @Query("delete from roleAliases where bookUrl not in (select bookUrl from books)")
    fun deleteOrphans()
}
