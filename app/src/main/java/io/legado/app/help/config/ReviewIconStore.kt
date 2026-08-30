package io.legado.app.help.config

import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import splitties.init.appCtx
import java.io.File

/**
 * 段评图标库:保存多份自定义段评图标 SVG,供正文布局设置里预览选择应用,
 * 替代旧的"单个替换"模式,已保存的图标不再因替换而丢失
 */
@Suppress("ConstPropertyName")
@Keep
object ReviewIconStore {

    const val fileName = "reviewIcons.json"
    private val filePath = FileUtils.getPath(appCtx.filesDir, fileName)

    @Keep
    data class ReviewIcon(
        var name: String = "",
        var svg: String = ""
    )

    val iconList: ArrayList<ReviewIcon> = arrayListOf()

    init {
        initIcons()
    }

    private fun initIcons() {
        val file = File(filePath)
        if (file.exists()) {
            try {
                iconList.addAll(GSON.fromJsonArray<ReviewIcon>(file.readText()).getOrThrow())
            } catch (e: Exception) {
                AppLog.put("读取段评图标库出错", e)
            }
        }
        // 首次使用:把各排版配置里已有的自定义段评图标迁移入库,避免升级后失去管理入口
        if (iconList.isEmpty()) {
            val svgs = linkedSetOf<String>()
            ReadBookConfig.configList.forEach { config ->
                config.reviewIconSvg.trim().takeIf { it.isNotBlank() }?.let { svgs.add(it) }
            }
            ReadBookConfig.shareConfig.reviewIconSvg.trim()
                .takeIf { it.isNotBlank() }?.let { svgs.add(it) }
            if (svgs.isNotEmpty()) {
                svgs.forEachIndexed { index, svg ->
                    iconList.add(ReviewIcon("图标${index + 1}", svg))
                }
                save()
            }
        }
    }

    fun save() {
        val json = kotlin.runCatching { GSON.toJson(iconList.toList()) }.getOrNull() ?: return
        Coroutine.async {
            kotlin.runCatching {
                synchronized(this@ReviewIconStore) {
                    FileUtils.delete(filePath)
                    FileUtils.createFileIfNotExist(filePath).writeText(json)
                }
            }.onFailure {
                AppLog.put("保存段评图标库出错", it)
            }
        }
    }

    fun addIcon(name: String, svg: String): ReviewIcon {
        val icon = ReviewIcon(name, svg)
        iconList.add(icon)
        save()
        return icon
    }

    fun removeIcon(icon: ReviewIcon) {
        iconList.remove(icon)
        save()
    }

    fun containsSvg(svg: String): Boolean {
        return iconList.any { it.svg.trim() == svg.trim() }
    }
}
