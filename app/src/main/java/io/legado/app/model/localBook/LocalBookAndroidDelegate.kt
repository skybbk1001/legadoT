package io.legado.app.model.localBook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.DefaultData
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocalModified
import io.legado.app.utils.DebugLog
import io.legado.app.utils.FileUtils
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * localBook 解析器(已迁入 shared)平台桥接: SAF/PFD 文件访问、封面解码落盘、
 * txt 目录规则、本地修改检测、调试日志在 app 侧的实现。
 * 参照 WebBookAndroidDelegate 先例, App 启动时 install。
 */
object LocalBookAndroidDelegate {

    fun install() {
        LocalBookBridge.getBookInputStream = { book ->
            LocalBook.getBookInputStream(book)
        }
        LocalBookBridge.openBookChannel = { book ->
            openBookChannel(book)
        }
        LocalBookBridge.getCoverPath = { book ->
            LocalBook.getCoverPath(book)
        }
        LocalBookBridge.saveCoverJpeg = { bytes, path ->
            saveCoverJpeg(bytes, path)
        }
        LocalBookBridge.writeCoverBytes = { path, bytes ->
            FileUtils.writeBytes(path, bytes)
        }
        LocalBookBridge.getTxtTocRules = {
            getTxtTocRules()
        }
        LocalBookBridge.isLocalModified = { book ->
            book.isLocalModified()
        }
        LocalBookBridge.putDebug = { message ->
            AppLog.putDebug(message)
        }
        LocalBookBridge.logDebug = { tag, msg ->
            DebugLog.d(tag, msg)
        }
    }

    /**
     * BookHelp.getBookPFD 的 FileChannel 形态(迁移前 EpubFile/MobiFile
     * FileInputStream(pfd.fileDescriptor).channel 等价实现)。
     */
    private fun openBookChannel(book: Book): LocalBookBridge.BookChannel? {
        return BookHelp.getBookPFD(book)?.let { pfd ->
            LocalBookBridge.BookChannel(FileInputStream(pfd.fileDescriptor).channel, pfd)
        }
    }

    /**
     * 封面字节解码重编码为 JPEG90 落盘(迁移前 EpubFile/MobiFile 的
     * BitmapFactory + Bitmap.compress 路径)。
     */
    private fun saveCoverJpeg(bytes: ByteArray, path: String) {
        val cover = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val out = FileOutputStream(FileUtils.createFileIfNotExist(path))
        out.use {
            cover.compress(Bitmap.CompressFormat.JPEG, 90, it)
            it.flush()
        }
    }

    /**
     * 启用的 txt 目录规则(迁移前 TextFile.getTocRules 的 appDb 逻辑)。
     */
    private fun getTxtTocRules(): List<TxtTocRule> {
        var rules = appDb.txtTocRuleDao.enabled
        if (appDb.txtTocRuleDao.count == 0) {
            rules = DefaultData.txtTocRules.apply {
                appDb.txtTocRuleDao.insert(*this.toTypedArray())
            }.filter {
                it.enable
            }
        }
        return rules
    }

}
