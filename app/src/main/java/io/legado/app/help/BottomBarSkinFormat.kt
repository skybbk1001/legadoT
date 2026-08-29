package io.legado.app.help

/**
 * 底栏图集的纯文件名逻辑(无 Android 依赖,便于单测)。
 * 图集 = 一组图片 zip,导入后由用户分配到 4 个底栏槽位。
 */
object BottomBarSkinFormat {

    /** 被底栏使用的 4 个槽位(对应 4 个 Tab) */
    val MAPPED_SLOTS = listOf("bookshelf", "home", "notes", "settings")

    /** 允许的图片后缀(小写,不含点) */
    val IMAGE_EXTS = listOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "svg")

    /** 文件名是否是允许的图片(按后缀,大小写不敏感;先取 basename 再判扩展名) */
    fun isImageName(name: String): Boolean {
        val file = name.substringAfterLast('/').substringAfterLast('\\').lowercase()
        val ext = file.substringAfterLast('.', "")
        return ext in IMAGE_EXTS
    }

    data class Entry(val slot: String, val selected: Boolean)

    /** 解析条目名 -> 槽位+状态; 非图片/非法/未知槽位返回 null。供智能预填用。 */
    fun parseEntryName(name: String): Entry? {
        val file = name.substringAfterLast('/').substringAfterLast('\\').lowercase()
        val dot = file.lastIndexOf('.')
        if (dot <= 0) return null               // 无扩展名,或以点开头(如 ".png")
        if (file.substring(dot + 1) !in IMAGE_EXTS) return null
        val base = file.substring(0, dot)
        val slot: String
        val selected: Boolean
        when {
            base.endsWith("_selected") -> { slot = base.removeSuffix("_selected"); selected = true }
            base.endsWith("_normal") -> { slot = base.removeSuffix("_normal"); selected = false }
            else -> return null
        }
        if (slot !in MAPPED_SLOTS) return null
        return Entry(slot, selected)
    }

    /** 生成不冲突的皮肤名: 重名追加 " (2)"、" (3)" … */
    fun uniqueName(desired: String, existing: Collection<String>): String {
        if (desired !in existing) return desired
        var i = 2
        while ("$desired ($i)" in existing) i++
        return "$desired ($i)"
    }

    /** 去掉文件系统非法字符, 作为目录名; 空白回退 "skin" */
    fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trim('.')
        return cleaned.ifBlank { "skin" }
    }
}
