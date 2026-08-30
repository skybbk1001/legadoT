package io.legado.app.help.storage

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.AppConst.androidId
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.ExploreContainer
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssStar
import io.legado.app.data.entities.RuleSub
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.data.entities.Server
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.HighlightStyle
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.upType
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.model.BookCover
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRule
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.ACache
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.getSharedPreferences
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.openInputStream
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream

/**
 * 恢复
 */
object Restore {

    private val mutex = Mutex()

    private const val TAG = "Restore"

    suspend fun restore(context: Context, uri: Uri) {
        LogUtils.d(TAG, "开始恢复备份 uri:$uri")
        kotlin.runCatching {
            FileUtils.delete(Backup.backupPath)
            if (uri.isContentScheme()) {
                DocumentFile.fromSingleUri(context, uri)!!.openInputStream()!!.use {
                    ZipUtils.unZipToPath(it, Backup.backupPath)
                }
            } else {
                ZipUtils.unZipToPath(File(uri.path!!), Backup.backupPath)
            }
        }.onFailure {
            AppLog.put("复制解压文件出错\n${it.localizedMessage}", it)
            return
        }
        kotlin.runCatching {
            restoreLocked(Backup.backupPath)
            LocalConfig.lastBackup = System.currentTimeMillis()
        }.onFailure {
            appCtx.toastOnUi("恢复备份出错\n${it.localizedMessage}")
            AppLog.put("恢复备份出错\n${it.localizedMessage}", it)
        }
    }

    suspend fun restoreLocked(path: String) {
        mutex.withLock {
            restore(path)
        }
    }

    private suspend fun restore(path: String) {
        val aes = BackupAES()
        fileToListT<Book>(path, "bookshelf.json")?.let {
            it.forEach { book ->
                book.upType()
            }
            it.filter { book -> book.isLocal }
                .forEach { book ->
                    book.coverUrl = LocalBook.getCoverPath(book)
                }
            val newBooks = arrayListOf<Book>()
            val ignoreLocalBook = BackupConfig.ignoreLocalBook
            it.forEach { book ->
                if (ignoreLocalBook && book.isLocal) {
                    return@forEach
                }
                if (appDb.bookDao.has(book.bookUrl)) {
                    try {
                        appDb.bookDao.update(book)
                    } catch (_: SQLiteConstraintException) {
                        appDb.bookDao.insert(book)
                    }
                } else {
                    newBooks.add(book)
                }
            }
            appDb.bookDao.insert(*newBooks.toTypedArray())
        }
        fileToListT<Bookmark>(path, "bookmark.json")?.let { bookmarks ->
            kotlin.runCatching {
                appDb.bookmarkDao.insert(*bookmarks.toTypedArray())
            }.onFailure {
                AppLog.put("恢复书签出错\n${it.localizedMessage}", it)
            }
        }
        fileToListT<BookHighlight>(path, "highlight.json")?.let { highlights ->
            kotlin.runCatching {
                // 兼容旧备份(只有 bgColor/textColor, 无 style): 从原始 JSON 补回颜色
                val legacy = GSON.fromJsonObject<List<Map<String, Any?>>>(
                    String(File(path, "highlight.json").readBytes())
                ).getOrNull()
                highlights.forEachIndexed { i, h ->
                    if (h.style.isEmpty()) {
                        val raw = legacy?.getOrNull(i)
                        val bg = (raw?.get("bgColor") as? Number)?.toInt() ?: 0
                        val tc = (raw?.get("textColor") as? Number)?.toInt() ?: 0
                        if (bg != 0 || tc != 0) h.applyStyle(HighlightStyle(fill = bg, textColor = tc))
                    }
                }
                appDb.bookHighlightDao.insert(*highlights.toTypedArray())
            }.onFailure {
                AppLog.put("恢复高亮出错\n${it.localizedMessage}", it)
            }
        }
        fileToListT<BookGroup>(path, "bookGroup.json")?.let { groups ->
            kotlin.runCatching {
                appDb.bookGroupDao.insert(*groups.toTypedArray())
            }.onFailure {
                AppLog.put("恢复分组出错\n${it.localizedMessage}", it)
            }
        }
        fileToListT<BookSource>(path, "bookSource.json")?.let { sources ->
            sources.forEach { source ->
                kotlin.runCatching {
                    if (appDb.bookSourceDao.getBookSource(source.bookSourceUrl) != null) {
                        appDb.bookSourceDao.update(source)
                    } else {
                        appDb.bookSourceDao.insert(source)
                    }
                }.onFailure {
                    AppLog.put("恢复书源 ${source.bookSourceName} 出错\n${it.localizedMessage}", it)
                }
            }
        } ?: run {
            val bookSourceFile = File(path, "bookSource.json")
            if (bookSourceFile.exists()) {
                val json = bookSourceFile.readText()
                ImportOldData.importOldSource(json)
            }
        }
        fileToListT<RssSource>(path, "rssSources.json")?.let { sources ->
            sources.forEach { source ->
                kotlin.runCatching {
                    if (appDb.rssSourceDao.has(source.sourceUrl)) {
                        appDb.rssSourceDao.update(source)
                    } else {
                        appDb.rssSourceDao.insert(source)
                    }
                }.onFailure {
                    AppLog.put("恢复订阅源 ${source.sourceName} 出错\n${it.localizedMessage}", it)
                }
            }
        }
        fileToListT<RssStar>(path, "rssStar.json")?.let { stars ->
            stars.forEach { star ->
                kotlin.runCatching {
                    if (appDb.rssStarDao.get(star.origin, star.link) == null) {
                        appDb.rssStarDao.insert(star)
                    }
                }.onFailure {
                    AppLog.put("恢复RSS收藏出错\n${it.localizedMessage}", it)
                }
            }
        }
        fileToListT<ReplaceRule>(path, "replaceRule.json")?.let { replaceRules ->
            kotlin.runCatching {
                appDb.replaceRuleDao.insert(*replaceRules.toTypedArray())
            }.onFailure {
                AppLog.put("恢复替换规则出错\n${it.localizedMessage}", it)
            }
        }
        fileToListT<HighlightRule>(path, "highlightRule.json")?.let { rules ->
            kotlin.runCatching {
                appDb.highlightRuleDao.insert(*rules.toTypedArray())
            }.onFailure {
                AppLog.put("恢复高亮规则出错\n${it.localizedMessage}", it)
            }
        }
        fileToListT<SearchKeyword>(path, "searchHistory.json")?.let { keywords ->
            keywords.forEach { keyword ->
                kotlin.runCatching {
                    appDb.searchKeywordDao.insert(keyword)
                }.onFailure { /* 忽略重复 */ }
            }
        }
        fileToListT<RuleSub>(path, "sourceSub.json")?.let { subs ->
            kotlin.runCatching {
                appDb.ruleSubDao.insert(*subs.toTypedArray())
            }.onFailure {
                AppLog.put("恢复订阅源规则出错\n${it.localizedMessage}", it)
            }
        }
        fileToListT<TxtTocRule>(path, "txtTocRule.json")?.let { rules ->
            kotlin.runCatching {
                appDb.txtTocRuleDao.insert(*rules.toTypedArray())
            }.onFailure {
                AppLog.put("恢复TXT目录规则出错\n${it.localizedMessage}", it)
            }
        }
        fileToListT<HttpTTS>(path, "httpTTS.json")?.let { ttsList ->
            kotlin.runCatching {
                appDb.httpTTSDao.insert(*ttsList.toTypedArray())
            }.onFailure {
                AppLog.put("恢复TTS配置出错\n${it.localizedMessage}", it)
            }
        }
        fileToListT<DictRule>(path, "dictRule.json")?.let { dicts ->
            kotlin.runCatching {
                appDb.dictRuleDao.insert(*dicts.toTypedArray())
            }.onFailure {
                AppLog.put("恢复字典规则出错\n${it.localizedMessage}", it)
            }
        }
        fileToListT<KeyboardAssist>(path, "keyboardAssists.json")?.let {
            it.forEach { assist ->
                kotlin.runCatching {
                    appDb.keyboardAssistsDao.insert(assist)
                }.onFailure { /* 忽略重复 */ }
            }
        }
        fileToListT<AutoTaskRule>(path, "autoTask.json")?.let { tasks ->
            kotlin.runCatching {
                appDb.autoTaskRuleDao.insert(*tasks.toTypedArray())
                AutoTask.refreshSchedule()
            }.onFailure {
                AppLog.put("恢复定时任务出错\n${it.localizedMessage}", it)
            }
        }
        fileToListT<ExploreContainer>(path, "exploreContainer.json")?.let { containers ->
            kotlin.runCatching {
                appDb.exploreContainerDao.insert(*containers.toTypedArray())
            }.onFailure {
                AppLog.put("恢复发现容器出错\n${it.localizedMessage}", it)
            }
        }
        fileToListT<ReadRecord>(path, "readRecord.json")?.let {
            it.forEach { readRecord ->
                //判断是不是本机记录
                if (readRecord.deviceId != androidId) {
                    kotlin.runCatching {
                        appDb.readRecordDao.insert(readRecord)
                    }
                } else {
                    val time = appDb.readRecordDao
                        .getReadTime(readRecord.deviceId, readRecord.bookName)
                    if (time == null || time < readRecord.readTime) {
                        kotlin.runCatching {
                            appDb.readRecordDao.insert(readRecord)
                        }
                    }
                }
            }
        }
        File(path, "servers.json").takeIf {
            it.exists()
        }?.runCatching {
            var json = readText()
            if (!json.isJsonArray()) {
                json = aes.decryptStr(json)
            }
            GSON.fromJsonArray<Server>(json).getOrNull()?.let {
                appDb.serverDao.insert(*it.toTypedArray())
            }
        }?.onFailure {
            AppLog.put("恢复服务器配置出错\n${it.localizedMessage}", it)
        }
        File(path, DirectLinkUpload.ruleFileName).takeIf {
            it.exists()
        }?.runCatching {
            val json = readText()
            ACache.get(cacheDir = false).put(DirectLinkUpload.ruleFileName, json)
        }?.onFailure {
            AppLog.put("恢复直链上传出错\n${it.localizedMessage}", it)
        }
        //恢复主题配置
        File(path, ThemeConfig.configFileName).takeIf {
            it.exists()
        }?.runCatching {
            FileUtils.delete(ThemeConfig.configFilePath)
            copyTo(File(ThemeConfig.configFilePath))
            ThemeConfig.upConfig()
        }?.onFailure {
            AppLog.put("恢复主题出错\n${it.localizedMessage}", it)
        }
        File(path, BookCover.configFileName).takeIf {
            it.exists()
        }?.runCatching {
            val json = readText()
            BookCover.saveCoverRule(json)
        }?.onFailure {
            AppLog.put("恢复封面规则出错\n${it.localizedMessage}", it)
        }
        if (!BackupConfig.ignoreReadConfig) {
            //恢复阅读界面配置
            File(path, ReadBookConfig.configFileName).takeIf {
                it.exists()
            }?.runCatching {
                FileUtils.delete(ReadBookConfig.configFilePath)
                copyTo(File(ReadBookConfig.configFilePath))
                ReadBookConfig.initConfigs()
            }?.onFailure {
                AppLog.put("恢复阅读界面出错\n${it.localizedMessage}", it)
            }
            File(path, ReadBookConfig.shareConfigFileName).takeIf {
                it.exists()
            }?.runCatching {
                FileUtils.delete(ReadBookConfig.shareConfigFilePath)
                copyTo(File(ReadBookConfig.shareConfigFilePath))
                ReadBookConfig.initShareConfig()
            }?.onFailure {
                AppLog.put("恢复阅读界面出错\n${it.localizedMessage}", it)
            }
        }
        //AppWebDav.downBgs()
        appCtx.getSharedPreferences(path, "config")?.all?.let { map ->
            appCtx.defaultSharedPreferences.edit {
                map.forEach { (key, value) ->
                    if (BackupConfig.keyIsNotIgnore(key)) {
                        when (key) {
                            PreferKey.webDavPassword -> {
                                kotlin.runCatching {
                                    aes.decryptStr(value.toString())
                                }.getOrNull()?.let {
                                    putString(key, it)
                                } ?: let {
                                    if (appCtx.getPrefString(PreferKey.webDavPassword)
                                            .isNullOrBlank()
                                    ) {
                                        putString(key, value.toString())
                                    }
                                }
                            }

                            else -> when (value) {
                                is Int -> putInt(key, value)
                                is Boolean -> putBoolean(key, value)
                                is Long -> putLong(key, value)
                                is Float -> putFloat(key, value)
                                is String -> putString(key, value)
                            }
                        }
                    }
                }
            }
        }
        ReadBookConfig.apply {
            comicStyleSelect = appCtx.getPrefInt(PreferKey.comicStyleSelect)
            readStyleSelect = appCtx.getPrefInt(PreferKey.readStyleSelect)
            shareLayout = appCtx.getPrefBoolean(PreferKey.shareLayout)
            hideStatusBar = appCtx.getPrefBoolean(PreferKey.hideStatusBar)
            hideNavigationBar = appCtx.getPrefBoolean(PreferKey.hideNavigationBar)
            autoReadSpeed = appCtx.getPrefInt(PreferKey.autoReadSpeed, 46)
        }
        // 恢复书架封面
        File(path, "covers").takeIf { it.exists() && it.isDirectory }?.let { coversDir ->
            val targetDir = appCtx.externalFiles.getFile("covers")
            kotlin.runCatching {
                FileUtils.delete(targetDir)
                targetDir.mkdirs()
                coversDir.copyRecursively(targetDir, overwrite = true)
            }.onFailure {
                AppLog.put("恢复封面出错\n${it.localizedMessage}", it)
            }
        }
        // 恢复阅读背景图
        File(path, "bg").takeIf { it.exists() && it.isDirectory }?.let { bgDir ->
            val targetDir = appCtx.externalFiles.getFile("bg")
            kotlin.runCatching {
                FileUtils.delete(targetDir)
                targetDir.mkdirs()
                bgDir.copyRecursively(targetDir, overwrite = true)
            }.onFailure {
                AppLog.put("恢复背景图出错\n${it.localizedMessage}", it)
            }
        }
        appCtx.toastOnUi(R.string.restore_success)
        withContext(Main) {
            delay(100)
            if (!BuildConfig.DEBUG) {
                LauncherIconHelp.changeIcon(appCtx.getPrefString(PreferKey.launcherIcon))
            }
            ThemeConfig.applyDayNight(appCtx)
        }
    }

    private inline fun <reified T> fileToListT(path: String, fileName: String): List<T>? {
        try {
            val file = File(path, fileName)
            if (file.exists()) {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件大小 ${file.length()}")
                FileInputStream(file).use {
                    return GSON.fromJsonArray<T>(it).getOrThrow().also { list ->
                        LogUtils.d(TAG, "阅读恢复备份 $fileName 列表大小 ${list.size}")
                    }
                }
            } else {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件不存在")
            }
        } catch (e: Exception) {
            AppLog.put("$fileName\n读取解析出错\n${e.localizedMessage}", e)
            appCtx.toastOnUi("$fileName\n读取文件出错\n${e.localizedMessage}")
        }
        return null
    }

}