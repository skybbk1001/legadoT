package io.legado.app.data.entities

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.exception.RegexTimeoutException
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.replace
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import splitties.init.appCtx

/**
 * BookChapter 中依赖 app 层(AppConfig/中文转换/替换规则/MD5)的行为,
 * 迁移实体到 shared 后拆为同包扩展
 */

fun BookChapter.getDisplayTitle(
    replaceRules: List<ReplaceRule>? = null,
    useReplace: Boolean = true,
    chineseConvert: Boolean = true,
): String {
    var displayTitle = title.replace(AppPattern.rnRegex, "")
    if (chineseConvert) {
        when (AppConfig.chineseConverterType) {
            1 -> displayTitle = ChineseUtils.t2s(displayTitle)
            2 -> displayTitle = ChineseUtils.s2t(displayTitle)
        }
    }
    if (useReplace && replaceRules != null) kotlin.run {
        replaceRules.forEach { item ->
            if (item.pattern.isNotEmpty()) {
                try {
                    val mDisplayTitle = if (item.isRegex) {
                        displayTitle.replace(
                            item.regex,
                            item.replacement,
                            item.getValidTimeoutMillisecond()
                        )
                    } else {
                        displayTitle.replace(item.pattern, item.replacement)
                    }
                    if (mDisplayTitle.isNotBlank()) {
                        displayTitle = mDisplayTitle
                    }
                } catch (e: RegexTimeoutException) {
                    item.isEnabled = false
                    appDb.replaceRuleDao.update(item)
                } catch (e: CancellationException) {
                    return@run
                } catch (e: Exception) {
                    AppLog.put("${item.name}替换出错\n替换内容\n${displayTitle}", e)
                    appCtx.toastOnUi("${item.name}替换出错")
                }
            }
        }
    }
    return displayTitle
}

private fun BookChapter.ensureTitleMD5Init() {
    if (titleMD5 == null) {
        titleMD5 = MD5Utils.md5Encode16(title)
    }
}

@Suppress("unused")
fun BookChapter.getFileName(suffix: String = "nb"): String {
    ensureTitleMD5Init()
    return String.format("%05d-%s.%s", index, titleMD5, suffix)
}

@Suppress("unused")
fun BookChapter.getFontName(): String {
    ensureTitleMD5Init()
    return String.format("%05d-%s.ttf", index, titleMD5)
}
