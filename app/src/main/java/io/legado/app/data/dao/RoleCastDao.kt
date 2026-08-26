package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.RoleCast

@Dao
interface RoleCastDao {

    @Query("select * from roleCasts where bookUrl = :bookUrl")
    fun getByBook(bookUrl: String): List<RoleCast>

    @get:Query("select * from roleCasts")
    val all: List<RoleCast>

    @Query("select * from roleCasts where bookUrl = :bookUrl and roleName = :roleName")
    fun get(bookUrl: String, roleName: String): RoleCast?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg roleCast: RoleCast)

    @Query("delete from roleCasts where bookUrl = :bookUrl")
    fun deleteByBook(bookUrl: String)

    @Query("delete from roleCasts where bookUrl = :bookUrl and roleName = :roleName")
    fun delete(bookUrl: String, roleName: String)

    @Query("delete from roleCasts where bookUrl not in (select bookUrl from books)")
    fun deleteOrphans()
}
