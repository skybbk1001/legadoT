package io.legado.app.api.controller


import android.text.TextUtils
import io.legado.app.api.ReturnData
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.jsSource.JsSourceConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import kotlin.coroutines.coroutineContext

object BookSourceController {

    val sources: ReturnData
        get() {
            val bookSources = appDb.bookSourceDao.all
            val returnData = ReturnData()
            return if (bookSources.isEmpty()) {
                returnData.setErrorMsg("设备源列表为空")
            } else returnData.setData(bookSources)
        }

    /**
     * [keepUserState] 为真时覆盖旧记录保留用户态字段(反复推送同一源调试的场景)。
     */
    fun saveSource(postData: String?, keepUserState: Boolean = false): ReturnData {
        val returnData = ReturnData()
        postData ?: return returnData.setErrorMsg("数据不能为空")
        val bookSource = GSON.fromJsonObject<BookSource>(postData).getOrNull()
        if (bookSource != null) {
            if (TextUtils.isEmpty(bookSource.bookSourceName) || TextUtils.isEmpty(bookSource.bookSourceUrl)) {
                returnData.setErrorMsg("源名称和URL不能为空")
            } else {
                if (keepUserState) {
                    preserveUserState(bookSource)
                }
                appDb.bookSourceDao.insert(bookSource)
                returnData.setData("")
            }
        } else {
            returnData.setErrorMsg("转换源失败")
        }
        return returnData
    }

    /**
     * 用户态字段不归源内容管,覆盖旧记录时保留(与编辑器保存同边界)。
     * 分组仅在新内容未声明时沿用旧值。
     */
    private fun preserveUserState(source: BookSource) {
        appDb.bookSourceDao.getBookSource(source.bookSourceUrl)?.let { old ->
            source.enabled = old.enabled
            source.enabledExplore = old.enabledExplore
            source.customOrder = old.customOrder
            source.weight = old.weight
            source.respondTime = old.respondTime
            if (source.bookSourceGroup.isNullOrBlank()) {
                source.bookSourceGroup = old.bookSourceGroup
            }
        }
    }

    fun saveSources(postData: String?): ReturnData {
        postData ?: return ReturnData().setErrorMsg("数据为空")
        val okSources = arrayListOf<BookSource>()
        val bookSources = GSON.fromJsonArray<BookSource>(postData).getOrNull()
        if (bookSources.isNullOrEmpty()) {
            return ReturnData().setErrorMsg("转换源失败")
        }
        bookSources.forEach { bookSource ->
            if (bookSource.bookSourceName.isNotBlank()
                && bookSource.bookSourceUrl.isNotBlank()
            ) {
                appDb.bookSourceDao.insert(bookSource)
                okSources.add(bookSource)
            }
        }
        return ReturnData().setData(okSources)
    }

    fun getSource(parameters: Map<String, List<String>>): ReturnData {
        val url = parameters["url"]?.firstOrNull()
        val returnData = ReturnData()
        if (url.isNullOrEmpty()) {
            return returnData.setErrorMsg("参数url不能为空，请指定源地址")
        }
        val bookSource = appDb.bookSourceDao.getBookSource(url)
            ?: return returnData.setErrorMsg("未找到源，请检查书源地址")
        return returnData.setData(bookSource)
    }

    fun deleteSources(postData: String?): ReturnData {
        kotlin.runCatching {
            GSON.fromJsonArray<BookSource>(postData).getOrThrow().let {
                SourceHelp.deleteBookSources(it)
            }
        }.onFailure {
            return ReturnData().setErrorMsg(it.localizedMessage ?: "数据格式错误")
        }
        return ReturnData().setData("已执行"/*okSources*/)
    }

    /**
     * JS 源保存:body 为脚本原文,与导入同一条提取/校验路径。
     * 脚本是元数据唯一真理源,不改写 lastUpdateTime。
     */
    suspend fun saveJsSource(postData: String?): ReturnData {
        val returnData = ReturnData()
        if (postData.isNullOrBlank()) {
            return returnData.setErrorMsg("数据不能为空")
        }
        return try {
            val source = JsSourceConfig.extract(postData, coroutineContext)
            preserveUserState(source)
            appDb.bookSourceDao.insert(source)
            returnData.setData(source)
        } catch (e: Exception) {
            returnData.setErrorMsg(e.localizedMessage ?: "JS源解析失败")
        }
    }
}
