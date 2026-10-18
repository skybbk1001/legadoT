package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

/**
 * 服务器
 */
@Entity(tableName = "servers")
data class Server(
    @PrimaryKey
    var id: Long = System.currentTimeMillis(),
    var name: String = "",
    var type: TYPE = TYPE.WEBDAV,
    var config: String? = null,
    var sortNumber: Int = 0
) {

    enum class TYPE {
        WEBDAV
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other is Server) {
            return id == other.id
        }
        return false
    }

    fun getWebDavConfig(): WebDavConfig? {
        return if (type == TYPE.WEBDAV) GSON.fromJsonObject<WebDavConfig>(config).getOrNull() else null
    }

    data class WebDavConfig(
        var url: String,
        var username: String,
        var password: String
    )

}
