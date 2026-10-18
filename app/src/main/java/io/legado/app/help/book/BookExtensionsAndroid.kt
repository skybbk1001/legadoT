@file:Suppress("unused")

package io.legado.app.help.book

import android.net.Uri
import androidx.core.net.toUri
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.getDisplayTitle
import io.legado.app.data.entities.getUseReplaceRule
import io.legado.app.data.entities.save
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.RuleBigDataHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.exists
import io.legado.app.utils.find
import io.legado.app.utils.inputStream
import io.legado.app.utils.isUri
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Book 的 app 侧扩展(本地 Uri/导出书名/数据库同步, 依赖 appCtx/AppConfig/appDb)。
 * 纯逻辑扩展已迁 shared 同包 BookExtensions.kt。
 */

private val localUriCache by lazy {
    ConcurrentHashMap<String, Uri>()
}

fun Book.getLocalUri(): Uri {
    if (!isLocal) {
        throw NoStackTraceException("不是本地书籍")
    }
    var uri = localUriCache[bookUrl]
    if (uri != null) {
        return uri
    }
    uri = if (bookUrl.isUri()) {
        bookUrl.toUri()
    } else {
        Uri.fromFile(File(bookUrl))
    }
    //先检测uri是否有效,这个比较快
    uri.inputStream(appCtx).getOrNull()?.use {
        localUriCache[bookUrl] = uri
    }?.let {
        return uri
    }
    //不同的设备书籍保存路径可能不一样, uri无效时尝试寻找当前保存路径下的文件
    val defaultBookDir = AppConfig.defaultBookTreeUri
    val importBookDir = AppConfig.importBookPath

    // 查找书籍保存目录
    if (!defaultBookDir.isNullOrBlank()) {
        val treeUri = defaultBookDir.toUri()
        val treeFileDoc = FileDoc.fromUri(treeUri, true)
        if (!treeFileDoc.exists()) {
            appCtx.toastOnUi("书籍保存目录失效，请重新设置！")
        } else {
            val fileDoc = treeFileDoc.find(originName, 5, 100)
            if (fileDoc != null) {
                localUriCache[bookUrl] = fileDoc.uri
                //更新bookUrl 重启不用再找一遍
                bookUrl = fileDoc.toString()
                save()
                return fileDoc.uri
            }
        }
    }

    // 查找添加本地选择的目录
    if (!importBookDir.isNullOrBlank() && defaultBookDir != importBookDir) {
        val treeUri = if (importBookDir.isUri()) {
            importBookDir.toUri()
        } else {
            Uri.fromFile(File(importBookDir))
        }
        val treeFileDoc = FileDoc.fromUri(treeUri, true)
        val fileDoc = treeFileDoc.find(originName, 5, 100)
        if (fileDoc != null) {
            localUriCache[bookUrl] = fileDoc.uri
            bookUrl = fileDoc.toString()
            save()
            return fileDoc.uri
        }
    }

    localUriCache[bookUrl] = uri
    return uri
}


fun Book.getArchiveUri(): Uri? {
    val defaultBookDir = AppConfig.defaultBookTreeUri
    return if (isArchive && !defaultBookDir.isNullOrBlank()) {
        FileDoc.fromUri(defaultBookDir.toUri(), true)
            .find(archiveName)?.uri
    } else {
        null
    }
}

fun Book.cacheLocalUri(uri: Uri) {
    localUriCache[bookUrl] = uri
}

fun Book.removeLocalUriCache() {
    localUriCache.remove(bookUrl)
}

fun Book.hasVariable(key: String): Boolean {
    return variableMap.contains(key) || RuleBigDataHelp.hasBookVariable(bookUrl, key)
}

fun Book.sync(oldBook: Book) {
    val curBook = appDb.bookDao.getBook(oldBook.bookUrl)!!
    durChapterTime = curBook.durChapterTime
    durChapterPos = curBook.durChapterPos
    if (durChapterIndex != curBook.durChapterIndex) {
        durChapterIndex = curBook.durChapterIndex
        val replaceRules = ContentProcessor.get(this).getTitleReplaceRules()
        appDb.bookChapterDao.getChapter(bookUrl, durChapterIndex)?.let {
            durChapterTitle = it.getDisplayTitle(replaceRules, getUseReplaceRule())
        }
    }
    canUpdate = curBook.canUpdate
    readConfig = curBook.readConfig
}

fun Book.update() {
    appDb.bookDao.update(this)
}

fun Book.getBookSource(): BookSource? {
    return appDb.bookSourceDao.getBookSource(origin)
}

fun Book.isLocalModified(): Boolean {
    return isLocal && LocalBook.getLastModified(this).getOrDefault(0L) > latestChapterTime
}

fun Book.getExportFileName(suffix: String): String {
    val default = "$name 作者：${getRealAuthor()}.$suffix".normalizeFileName()
    val jsStr = AppConfig.bookExportFileName
    if (jsStr.isNullOrBlank()) {
        return default
    }
    val bindings = buildScriptBindings { bindings ->
        bindings["epubIndex"] = ""// 兼容老版本,修复可能存在的错误
        bindings["name"] = name
        bindings["author"] = getRealAuthor()
    }
    return kotlin.runCatching {
        (RhinoScriptEngine.eval(jsStr, bindings).toString() + "." + suffix).normalizeFileName()
    }.onFailure {
        AppLog.put("导出书名规则错误,使用默认规则\n${it.localizedMessage}", it)
    }.getOrDefault(default)
}

/**
 * 获取分割文件后的文件名
 */
fun Book.getExportFileName(
    suffix: String,
    epubIndex: Int,
    jsStr: String? = AppConfig.episodeExportFileName
): String {
    // 默认规则
    val default = "$name 作者：${getRealAuthor()} [${epubIndex}].$suffix"
    if (jsStr.isNullOrBlank()) {
        return default
    }
    val bindings = buildScriptBindings { bindings ->
        bindings["name"] = name
        bindings["author"] = getRealAuthor()
        bindings["epubIndex"] = epubIndex
    }
    return kotlin.runCatching {
        RhinoScriptEngine.eval(jsStr, bindings).toString() + "." + suffix
    }.onFailure {
        AppLog.put("导出书名规则错误,使用默认规则\n${it.localizedMessage}", it)
    }.getOrDefault(default).normalizeFileName()
}

fun Book.updateTo(newBook: Book): Book {
    newBook.durChapterIndex = durChapterIndex
    newBook.durChapterTitle = durChapterTitle
    newBook.durChapterPos = durChapterPos
    newBook.durChapterTime = durChapterTime
    newBook.group = group
    newBook.order = order
    newBook.customCoverUrl = customCoverUrl
    newBook.customIntro = customIntro
    newBook.customTag = customTag
    newBook.canUpdate = canUpdate
    newBook.readConfig = readConfig
    val variableMap = variableMap.toMutableMap()
    variableMap.keys.removeIf {
        newBook.hasVariable(it)
    }
    newBook.variableMap.putAll(variableMap)
    newBook.variable = GSON.toJson(newBook.variableMap)
    return newBook
}

fun tryParesExportFileName(jsStr: String): Boolean {
    val bindings = buildScriptBindings { bindings ->
        bindings["name"] = "name"
        bindings["author"] = "author"
        bindings["epubIndex"] = "epubIndex"
    }
    return runCatching {
        RhinoScriptEngine.eval(jsStr, bindings)
        true
    }.getOrDefault(false)
}
