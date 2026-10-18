package io.legado.app.model.localBook

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.TxtTocRule
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.channels.FileChannel

/**
 * localBook(app 侧依赖)桥接: 文件访问(SAF/PFD)/封面落盘(Bitmap 重编码)/
 * txt 目录规则(appDb)/本地修改检测/调试日志经此转发,
 * 由 app 启动时接线(LocalBookAndroidDelegate.install)。
 * 参照 WebBookBridge/CacheBridge 先例, 未接线时 no-op/抛出明确异常,
 * app 接线后运行时行为与迁移前一致。
 */
object LocalBookBridge {

    /**
     * 书籍文件通道 + 持有者(app 侧为 ParcelFileDescriptor)。
     * shared 解析器持有引用防止底层 fd 被回收, close 时一并释放。
     */
    class BookChannel(val channel: FileChannel, private val owner: Closeable?) : Closeable {
        override fun close() {
            channel.close()
            owner?.close()
        }
    }

    /** 打开书籍输入流(app LocalBook.getBookInputStream, 失败抛 FileNotFoundException) */
    var getBookInputStream: (book: Book) -> InputStream = {
        throw FileNotFoundException("LocalBookBridge.getBookInputStream 未接线")
    }

    /** 打开书籍文件通道(app BookHelp.getBookPFD), 返回 null 表示打开失败 */
    var openBookChannel: (book: Book) -> BookChannel? = { null }

    /** 封面保存路径(app LocalBook.getCoverPath) */
    var getCoverPath: (book: Book) -> String = { "" }

    /** 封面字节解码重编码为 JPEG90 落盘(app BitmapFactory 路径, epub/mobi) */
    var saveCoverJpeg: (bytes: ByteArray, path: String) -> Unit = { _, _ -> }

    /** 封面字节原样写盘(app FileUtils.writeBytes, umd) */
    var writeCoverBytes: (path: String, bytes: ByteArray) -> Unit = { _, _ -> }

    /** 启用的 txt 目录规则(app appDb.txtTocRuleDao + DefaultData 兜底) */
    var getTxtTocRules: () -> List<TxtTocRule> = { emptyList() }

    /** 本地文件是否已修改(app Book.isLocalModified) */
    var isLocalModified: (book: Book) -> Boolean = { false }

    /** 调试日志(app AppLog.putDebug) */
    var putDebug: (message: String) -> Unit = {}

    /** 调试日志(app DebugLog.d) */
    var logDebug: (tag: String, msg: String) -> Unit = { _, _ -> }

}
