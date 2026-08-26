package io.legado.app.ui.association

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import com.jayway.jsonpath.JsonPath
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.jsSource.JsSourceConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.inputStream
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isUri
import io.legado.app.utils.splitNotBlank
import kotlin.coroutines.coroutineContext


class ImportBookSourceViewModel(app: Application) : BaseViewModel(app) {
    var isAddGroup = false
    var groupName: String? = null
    val errorLiveData = MutableLiveData<String>()
    val successLiveData = MutableLiveData<Int>()

    val allSources = arrayListOf<BookSource>()
    val checkSources = arrayListOf<BookSourcePart?>()
    val selectStatus = arrayListOf<Boolean>()
    val newSourceStatus = arrayListOf<Boolean>()
    val updateSourceStatus = arrayListOf<Boolean>()

    val isSelectAll: Boolean
        get() {
            selectStatus.forEach {
                if (!it) {
                    return false
                }
            }
            return true
        }

    val isSelectAllNew: Boolean
        get() {
            newSourceStatus.forEachIndexed { index, b ->
                if (b && !selectStatus[index]) {
                    return false
                }
            }
            return true
        }

    val isSelectAllUpdate: Boolean
        get() {
            updateSourceStatus.forEachIndexed { index, b ->
                if (b && !selectStatus[index]) {
                    return false
                }
            }
            return true
        }

    val selectCount: Int
        get() {
            var count = 0
            selectStatus.forEach {
                if (it) {
                    count++
                }
            }
            return count
        }

    fun importSelect(finally: () -> Unit) {
        execute {
            val group = groupName?.trim()
            val keepName = AppConfig.importKeepName
            val keepGroup = AppConfig.importKeepGroup
            val keepEnable = AppConfig.importKeepEnable
            val selectSource = arrayListOf<BookSource>()
            selectStatus.forEachIndexed { index, b ->
                if (b) {
                    val source = allSources[index]
                    checkSources[index]?.let {
                        if (keepName) {
                            source.bookSourceName = it.bookSourceName
                        }
                        if (keepGroup) {
                            source.bookSourceGroup = it.bookSourceGroup
                        }
                        if (keepEnable) {
                            source.enabled = it.enabled
                            source.enabledExplore = it.enabledExplore
                        }
                        source.customOrder = it.customOrder
                    }
                    if (!group.isNullOrEmpty()) {
                        if (isAddGroup) {
                            val groups = linkedSetOf<String>()
                            source.bookSourceGroup?.splitNotBlank(AppPattern.splitGroupRegex)?.let {
                                groups.addAll(it)
                            }
                            groups.add(group)
                            source.bookSourceGroup = groups.joinToString(",")
                        } else {
                            source.bookSourceGroup = group
                        }
                    }
                    selectSource.add(source)
                }
            }
            SourceHelp.insertBookSource(*selectSource.toTypedArray())
            ContentProcessor.upReplaceRules()
        }.onFinally {
            finally.invoke()
        }
    }

    fun importSource(text: String) {
        execute {
            val mText = text.trim()
            when {
                mText.isJsonObject() -> {
                    kotlin.runCatching {
                        val json = JsonPath.parse(mText)
                        json.read<List<String>>("$.sourceUrls")
                    }.onSuccess { listUrl ->
                        listUrl.forEach {
                            importSourceUrl(it)
                        }
                    }.onFailure {
                        GSON.fromJsonObject<BookSource>(mText).getOrThrow().let {
                            if (it.bookSourceUrl.isEmpty()) {
                                throw NoStackTraceException("不是书源")
                            }
                            allSources.add(it)
                        }
                    }
                }

                mText.isJsonArray() -> GSON.fromJsonArray<BookSource>(mText).getOrThrow()
                    .let { items ->
                        val source = items.firstOrNull() ?: return@let
                        if (source.bookSourceUrl.isEmpty()) {
                            throw NoStackTraceException("不是书源")
                        }
                        allSources.addAll(items)
                    }

                mText.isAbsUrl() -> {
                    importSourceUrl(mText)
                }

                mText.isUri() -> {
                    val uri = Uri.parse(mText)
                    uri.inputStream(context).getOrThrow().use { inputS ->
                        importSourceText(inputS.reader().readText())
                    }
                }

                else -> {
                    runCatching {
                        allSources.add(JsSourceConfig.extract(mText, coroutineContext))
                    }.onFailure {
                        throw NoStackTraceException(
                            "${context.getString(R.string.wrong_format)}\n${it.localizedMessage}"
                        )
                    }
                }
            }
        }.onError {
            errorLiveData.postValue("ImportError:${it.localizedMessage}")
            AppLog.put("ImportError:${it.localizedMessage}", it)
        }.onSuccess {
            comparisonSource()
        }
    }

    private suspend fun importSourceUrl(url: String) {
        okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.decompressed().byteStream().use {
            importSourceText(it.reader().readText())
        }
    }

    /** 文件/URL 拉到的文本:JSON 数组 / 单对象 / JS 脚本 三形态统一解析 */
    private suspend fun importSourceText(text: String) {
        val mText = text.trim()
        when {
            mText.isJsonArray() -> GSON.fromJsonArray<BookSource>(mText).getOrThrow().let {
                val source = it.firstOrNull() ?: return
                if (source.bookSourceUrl.isEmpty()) {
                    throw NoStackTraceException("不是书源")
                }
                allSources.addAll(it)
            }

            mText.isJsonObject() -> GSON.fromJsonObject<BookSource>(mText).getOrThrow().let {
                if (it.bookSourceUrl.isEmpty()) {
                    throw NoStackTraceException("不是书源")
                }
                allSources.add(it)
            }

            else -> allSources.add(JsSourceConfig.extract(mText, coroutineContext))
        }
    }

    /** 版本语义:导入侧 lastUpdateTime 大于库内才算"更新"(JS源同款,其值来自脚本内声明) */
    private fun comparisonSource() {
        execute {
            allSources.forEach {
                val local = appDb.bookSourceDao.getBookSourcePart(it.bookSourceUrl)
                val update = local != null && local.lastUpdateTime < it.lastUpdateTime
                checkSources.add(local)
                selectStatus.add(local == null || update)
                newSourceStatus.add(local == null)
                updateSourceStatus.add(update)
            }
            successLiveData.postValue(allSources.size)
        }
    }

    /** 弹窗内经 CodeDialog 改写条目后,同步条目与新增/更新状态 */
    fun updateSource(index: Int, source: BookSource, success: () -> Unit) {
        execute {
            allSources[index] = source
            val local = appDb.bookSourceDao.getBookSourcePart(source.bookSourceUrl)
            checkSources[index] = local
            newSourceStatus[index] = local == null
            updateSourceStatus[index] = local != null && local.lastUpdateTime < source.lastUpdateTime
        }.onSuccess {
            success.invoke()
        }
    }

}