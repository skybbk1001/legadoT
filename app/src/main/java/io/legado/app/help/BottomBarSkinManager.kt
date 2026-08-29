package io.legado.app.help

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.StateListDrawable
import io.legado.app.constant.PreferKey
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import splitties.init.appCtx
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 底栏图集存储与渲染。图集 = filesDir/bottomBarSkins/<名>/ 下用户分配落盘的一组图片。
 * 列表即目录列表、目录名即显示名,刻意不引入 Gson 模型(规避 R8 删序列化模型)。
 */
object BottomBarSkinManager {

    private val rootDir: File
        get() = File(appCtx.filesDir, "bottomBarSkins").apply { if (!exists()) mkdirs() }

    private const val STAGING_DIR_NAME = ".staging"

    /** SVG 源保存时栅格化的边长(px); 矢量放大也应清晰, 渲染时再按 iconSize 降采样。 */
    private const val SVG_RASTER_SIZE = 512

    private val stagingDir: File
        get() = File(rootDir, STAGING_DIR_NAME)

    var active: String
        get() = appCtx.getPrefString(PreferKey.bottomBarSkin) ?: ""
        set(value) {
            appCtx.putPrefString(PreferKey.bottomBarSkin, value)
        }

    fun list(): List<String> =
        rootDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.map { it.name }?.sorted()
            ?: emptyList()

    fun skinDir(name: String) = File(rootDir, name)

    fun delete(name: String) {
        skinDir(name).deleteRecursively()
        if (active == name) active = ""
    }

    /**
     * 为某槽位构建 selected/normal 的 StateListDrawable。
     * 选中图缺失 → 返回 null(调用方回退默认图标);未选图缺失 → 复用选中位图按 40% alpha 淡化。
     */
    fun getStateDrawable(skinName: String, slot: String, iconSizePx: Int): StateListDrawable? {
        val dir = skinDir(skinName)
        val res = appCtx.resources
        val selected = decodeSquared(File(dir, "${slot}_selected.png"), iconSizePx) ?: return null
        val normalFile = File(dir, "${slot}_normal.png")
        val normalBmp = if (normalFile.exists()) decodeSquared(normalFile, iconSizePx) else null
        val normalDrawable: BitmapDrawable = if (normalBmp != null) {
            BitmapDrawable(res, normalBmp)
        } else {
            // 未选态缺失: 复用选中位图, 另建一个 BitmapDrawable 设 40% alpha(两 drawable 各自独立)
            BitmapDrawable(res, selected).apply { alpha = 102 }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), BitmapDrawable(res, selected))
            addState(intArrayOf(), normalDrawable)
        }
    }

    /** 管理页卡片预览: 被映射槽位的 selected 小图 */
    fun getPreviewBitmaps(skinName: String, iconSizePx: Int): List<Bitmap> {
        val dir = skinDir(skinName)
        return BottomBarSkinFormat.MAPPED_SLOTS.mapNotNull { slot ->
            decodeSquared(File(dir, "${slot}_selected.png"), iconSizePx)
        }
    }

    /** 文件名预填建议: 某槽位的选中/未选图 */
    data class Prefill(val selected: File?, val normal: File?)

    /** 用户对某槽位的分配: 选中必有, 未选可空 */
    data class SlotAssign(val selected: File, val normal: File?)

    /**
     * 解压 zip 内全部图片到暂存目录(先清空)。只取图片条目, 取 basename 落地(防 Zip Slip),
     * 重名在扩展名前加 " (2)"。无任何图片则失败。
     */
    fun extractImages(bytes: ByteArray): Result<Unit> = runCatching {
        val staging = stagingDir
        staging.deleteRecursively()
        staging.mkdirs()
        val used = HashSet<String>()
        var count = 0
        ZipInputStream(bytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && BottomBarSkinFormat.isImageName(entry.name)) {
                    val base = entry.name.substringAfterLast('/').substringAfterLast('\\')
                        .replace(Regex("[:*?\"<>|]"), "_")
                    val outName = uniqueStagingName(base, used)
                    File(staging, outName).writeBytes(zis.readBytes())
                    count++
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        require(count > 0) { "no images in zip" }
    }

    /**
     * 编辑既有图集: 把该图集已落盘的图片复制进暂存目录(先清空), 供分配页当调色板/预填重新分配。
     * 图集不存在或无图片则失败。
     */
    fun stageExisting(skinName: String): Result<Unit> = runCatching {
        val src = skinDir(skinName)
        require(src.isDirectory) { "skin not found" }
        val staging = stagingDir
        staging.deleteRecursively()
        staging.mkdirs()
        var count = 0
        src.listFiles { f -> f.isFile && BottomBarSkinFormat.isImageName(f.name) }?.forEach { f ->
            f.copyTo(File(staging, f.name), overwrite = true)
            count++
        }
        require(count > 0) { "no images" }
    }

    /** 暂存目录内唯一文件名: 冲突时在扩展名前加 " (2)"、" (3)" … */
    private fun uniqueStagingName(base: String, used: MutableSet<String>): String {
        if (used.add(base)) return base
        val dot = base.lastIndexOf('.')
        val stem = if (dot >= 0) base.substring(0, dot) else base
        val ext = if (dot >= 0) base.substring(dot) else ""
        var i = 2
        while (!used.add("$stem ($i)$ext")) i++
        return "$stem ($i)$ext"
    }

    /** 暂存目录里的图片文件, 给分配页做调色板 */
    fun stagingImages(): List<File> =
        stagingDir.listFiles { f -> f.isFile && BottomBarSkinFormat.isImageName(f.name) }
            ?.sortedBy { it.name } ?: emptyList()

    /** 按文件名给各槽位生成预填建议(同槽多张取首个) */
    fun buildPrefill(images: List<File>): Map<String, Prefill> {
        val sel = HashMap<String, File>()
        val nor = HashMap<String, File>()
        images.forEach { f ->
            val e = BottomBarSkinFormat.parseEntryName(f.name) ?: return@forEach
            if (e.selected) sel.putIfAbsent(e.slot, f) else nor.putIfAbsent(e.slot, f)
        }
        return (sel.keys + nor.keys).associateWith { Prefill(sel[it], nor[it]) }
    }

    /**
     * 把用户分配落盘成一套图集; 拷贝为既有的 {slot}_{state}.png 结构; 清空暂存; 返回最终图集名。
     * editName 非空 = 编辑模式:
     *  - 名称未改 → 原地覆盖并清理本次未分配/已清空未选 的残留槽位文件;
     *  - 名称已改 → 按新名(去重)另存、删除原图集,原图集若在用则把 active 迁到新名。
     */
    fun saveSkin(
        desiredName: String,
        assigns: Map<String, SlotAssign>,
        editName: String? = null,
    ): Result<String> = runCatching {
        require(assigns.isNotEmpty()) { "no assignment" }
        val sanitized = BottomBarSkinFormat.sanitize(desiredName)
        when {
            editName == null -> {
                // 新建
                val name = BottomBarSkinFormat.uniqueName(sanitized, list())
                writeAssigns(skinDir(name).apply { mkdirs() }, assigns)
                stagingDir.deleteRecursively()
                name
            }
            sanitized == editName -> {
                // 原地覆盖: 清理本次未分配 / 已清空未选 的残留槽位文件
                val dir = skinDir(editName).apply { mkdirs() }
                writeAssigns(dir, assigns)
                BottomBarSkinFormat.MAPPED_SLOTS.forEach { slot ->
                    val a = assigns[slot]
                    if (a == null) {
                        File(dir, "${slot}_selected.png").delete()
                        File(dir, "${slot}_normal.png").delete()
                    } else if (a.normal == null) {
                        File(dir, "${slot}_normal.png").delete()
                    }
                }
                stagingDir.deleteRecursively()
                editName
            }
            else -> {
                // 改名另存: 新名去重 → 删原图集 → active 跟随改名
                val name = BottomBarSkinFormat.uniqueName(sanitized, list())
                writeAssigns(skinDir(name).apply { mkdirs() }, assigns)
                skinDir(editName).deleteRecursively()
                if (active == editName) active = name
                stagingDir.deleteRecursively()
                name
            }
        }
    }

    private fun writeAssigns(dir: File, assigns: Map<String, SlotAssign>) {
        assigns.forEach { (slot, a) ->
            writeSlotImage(a.selected, File(dir, "${slot}_selected.png"))
            a.normal?.let { writeSlotImage(it, File(dir, "${slot}_normal.png")) }
        }
    }

    /**
     * 把分配的图片落成 PNG。SVG 源先光栅化为 PNG(BitmapFactory 无法直接解码 SVG),
     * 其余格式直接拷贝字节——内容由 BitmapFactory 嗅探, 命名统一为 .png 以维持导出/再导入往返契约。
     */
    private fun writeSlotImage(src: File, dest: File) {
        if (!isSvgName(src.name)) {
            src.copyTo(dest, overwrite = true)
            return
        }
        val bmp = SvgUtils.createBitmap(src.absolutePath, SVG_RASTER_SIZE, SVG_RASTER_SIZE)
            ?: error("cannot decode svg: ${src.name}")
        try {
            FileOutputStream(dest).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
        } finally {
            bmp.recycle()
        }
    }

    /** 把图集打包成 zip(沿用 {slot}_{state}.png 命名, 便于再导入时智能预填往返)。内存内构建, 体积极小。 */
    fun buildZipBytes(skinName: String): Result<ByteArray> = runCatching {
        val dir = skinDir(skinName)
        require(dir.isDirectory) { "skin not found" }
        val files = dir.listFiles { f -> f.isFile && BottomBarSkinFormat.isImageName(f.name) }
            ?.sortedBy { it.name } ?: emptyList()
        require(files.isNotEmpty()) { "empty skin" }
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            files.forEach { f ->
                zos.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        bos.toByteArray()
    }

    /** 分享用: 打包 zip 落到缓存目录(先清空), 返回该文件供 FileProvider 分享 */
    fun cacheShareZip(skinName: String): Result<File> = runCatching {
        val bytes = buildZipBytes(skinName).getOrThrow()
        val dir = File(appCtx.cacheDir, "bottomBarSkinShare")
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, "$skinName.zip").apply { writeBytes(bytes) }
    }

    /** 给分配页/调色板解码缩略图(降采样、按比例居中) */
    fun previewBitmap(file: File, sizePx: Int): Bitmap? = decodeSquared(file, sizePx)

    /** 解码并按比例缩放进 size×size 透明正方形居中(解决非正方形拉伸)。SVG 走矢量解码路径。 */
    private fun decodeSquared(file: File, sizePx: Int): Bitmap? {
        if (!file.exists() || sizePx <= 0) return null
        return if (isSvgName(file.name)) decodeSvgSquared(file, sizePx) else decodeBitmapSquared(file, sizePx)
    }

    /** 位图格式解码: BitmapFactory 嗅探内容 + 降采样。 */
    private fun decodeBitmapSquared(file: File, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxSide / sample > sizePx * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return centerOnSquare(src, sizePx)
    }

    /** SVG 解码: 渲染为适配 size×size 的位图(保持宽高比), 再居中到透明正方形。 */
    private fun decodeSvgSquared(file: File, sizePx: Int): Bitmap? {
        val src = SvgUtils.createBitmap(file.absolutePath, sizePx, sizePx) ?: return null
        return centerOnSquare(src, sizePx)
    }

    /** 把 src 按比例缩放后居中画进 size×size 透明正方形, 并回收中间位图。 */
    private fun centerOnSquare(src: Bitmap, sizePx: Int): Bitmap {
        val scale = sizePx.toFloat() / maxOf(src.width, src.height)
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        if (scaled != src) src.recycle()
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(scaled, (sizePx - w) / 2f, (sizePx - h) / 2f, null)
        scaled.recycle()
        return out
    }

    /** 文件名是否为 SVG(大小写不敏感; 先取 basename) */
    private fun isSvgName(name: String): Boolean =
        name.substringAfterLast('/').substringAfterLast('\\').lowercase().endsWith(".svg")
}
