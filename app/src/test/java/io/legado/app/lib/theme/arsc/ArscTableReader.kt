package io.legado.app.lib.theme.arsc

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * 测试用最小 ARSC 读取器:只解析 [ColorResourcesTableCreator] 生成的表结构
 * (每包单 type=color、默认配置、AARRGGBB 值),与生成器互为独立实现,构成字节级往返验证。
 */
class ArscTableReader(bytes: ByteArray) {

    private val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    fun readTable(): ParsedTable {
        require(buf.short == HEADER_TYPE_RES_TABLE.toShort()) { "不是 res_table chunk" }
        buf.short // headerSize
        buf.int // chunkSize
        val packageCount = buf.int
        skipChunk() // 全局 string pool(空)

        val packages = ArrayList<ParsedPackage>(packageCount)
        repeat(packageCount) {
            packages.add(readPackage())
        }
        return ParsedTable(packages)
    }

    private fun readPackage(): ParsedPackage {
        val packagePos = buf.position()
        require(buf.short == HEADER_TYPE_PACKAGE.toShort()) { "不是 package chunk" }
        buf.short // headerSize
        buf.int // chunkSize
        val packageId = buf.int
        val nameBytes = ByteArray(PACKAGE_NAME_BYTES) // 128 UTF-16 字符 = 256 字节
        buf.get(nameBytes)
        val packageName = decodeUtf16(nameBytes)
        val typeStringsOffset = buf.int
        buf.int // lastPublicType
        val keyStringsOffset = buf.int
        buf.int // lastPublicKey
        buf.int // note

        val typeStringsPos = packagePos + typeStringsOffset
        val keyStringsPos = packagePos + keyStringsOffset
        val (_, typePoolEnd) = readStringPool(typeStringsPos)
        require(typePoolEnd == keyStringsPos) { "typeStrings 后应紧接 keyStrings" }
        val (keyNames, keyPoolEnd) = readStringPool(keyStringsPos)

        buf.position(keyPoolEnd)
        require(buf.short == HEADER_TYPE_TYPE_SPEC.toShort()) { "不是 typeSpec chunk" }
        buf.short // headerSize
        buf.int // chunkSize
        val typeId = readTypeId()
        val entryCount = buf.int
        val flags = IntArray(entryCount) { buf.int }

        val typeChunkPos = buf.position()
        require(buf.short == HEADER_TYPE_TYPE.toShort()) { "不是 type chunk" }
        buf.short // headerSize
        buf.int // chunkSize
        require(readTypeId() == typeId) { "type chunk 与 typeSpec typeId 不一致" }
        require(buf.int == entryCount) { "type chunk 与 typeSpec entryCount 不一致" }
        val entriesStart = buf.int
        val configSize = buf.get().toInt() and 0xFF
        require(configSize == CONFIG_SIZE) { "config 尺寸应为 $CONFIG_SIZE,实际 $configSize" }
        buf.position(buf.position() + configSize - 1)
        val offsetTable = IntArray(entryCount) { buf.int }

        val entries = mutableMapOf<Int, Int>()
        for (entryId in 0 until entryCount) {
            if (flags[entryId] and SPEC_PUBLIC == 0) continue
            val offset = offsetTable[entryId]
            require(offset != OFFSET_NO_ENTRY) { "public entry $entryId 缺失 offset" }
            val entryPos = typeChunkPos + entriesStart + offset
            buf.position(entryPos)
            require(buf.short == ENTRY_SIZE.toShort()) { "entry $entryId 尺寸异常" }
            val entryFlags = buf.short
            require(entryFlags.toInt() and FLAG_PUBLIC != 0) { "entry $entryId 未标记 public" }
            val keyIndex = buf.int
            require(buf.short == VALUE_SIZE.toShort()) { "entry $entryId 值尺寸异常" }
            buf.get() // res0
            require(buf.get().toInt() and 0xFF == DATA_TYPE_AARRGGBB) { "entry $entryId 非 ARGB 类型" }
            val data = buf.int
            val resId = (packageId shl 24) or (typeId shl 16) or entryId
            entries[resId] = data
        }
        return ParsedPackage(
            id = packageId,
            name = packageName,
            typeId = typeId,
            entryCount = entryCount,
            entries = entries,
            keyNames = keyNames,
        )
    }

    private fun skipChunk() {
        val pos = buf.position()
        buf.position(pos + 4) // chunkSize 字段
        val size = buf.int
        buf.position(pos + size)
    }

    private fun readTypeId(): Int {
        val id = buf.get().toInt() and 0xFF
        buf.get(); buf.get(); buf.get() // 3 reserved
        return id
    }

    private fun readStringPool(pos: Int): Pair<List<String>, Int> {
        buf.position(pos)
        require(buf.short == HEADER_TYPE_STRING_POOL.toShort()) { "不是 string pool chunk" }
        buf.short // headerSize
        val chunkSize = buf.int
        val stringCount = buf.int
        buf.int // styleCount
        val flags = buf.int
        val stringsStart = buf.int
        buf.int // stylesStart
        val offsets = IntArray(stringCount) { buf.int }
        val utf8 = flags and FLAG_UTF8 != 0
        val names = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            names.add(readStringAt(pos + stringsStart + offsets[i], utf8))
        }
        return names to (pos + chunkSize)
    }

    private fun readStringAt(pos: Int, utf8: Boolean): String {
        buf.position(pos)
        return if (utf8) {
            var charLength = buf.get().toInt() and 0xFF
            if (charLength and 0x80 != 0) {
                val lo = buf.get().toInt() and 0xFF
                charLength = ((charLength and 0x7F) shl 8) or lo
            }
            var byteLength = buf.get().toInt() and 0xFF
            if (byteLength and 0x80 != 0) {
                val lo = buf.get().toInt() and 0xFF
                byteLength = ((byteLength and 0x7F) shl 8) or lo
            }
            val bytes = ByteArray(byteLength)
            buf.get(bytes)
            String(bytes, StandardCharsets.UTF_8)
        } else {
            val charLength = buf.short.toInt() and 0xFFFF
            val chars = CharArray(charLength)
            repeat(charLength) { chars[it] = buf.char }
            String(chars)
        }
    }

    private fun decodeUtf16(bytes: ByteArray): String {
        val chars = CharArray(bytes.size / 2)
        for (i in chars.indices) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt() and 0xFF
            chars[i] = ((hi shl 8) or lo).toChar()
        }
        return String(chars).trimEnd('\u0000')
    }

    data class ParsedTable(val packages: List<ParsedPackage>)

    data class ParsedPackage(
        val id: Int,
        val name: String,
        val typeId: Int,
        val entryCount: Int,
        val entries: Map<Int, Int>,
        val keyNames: List<String>,
    )

    private companion object {
        const val HEADER_TYPE_RES_TABLE = 0x0002
        const val HEADER_TYPE_STRING_POOL = 0x0001
        const val HEADER_TYPE_PACKAGE = 0x0200
        const val HEADER_TYPE_TYPE = 0x0201
        const val HEADER_TYPE_TYPE_SPEC = 0x0202

        const val PACKAGE_NAME_BYTES = 128 * 2 // 128 UTF-16 字符
        const val FLAG_UTF8 = 0x00000100
        const val SPEC_PUBLIC = 0x40000000
        const val OFFSET_NO_ENTRY = -1 // 0xFFFFFFFF
        const val ENTRY_SIZE = 8
        const val FLAG_PUBLIC = 0x0002
        const val VALUE_SIZE = 8
        const val DATA_TYPE_AARRGGBB = 0x1C
        const val CONFIG_SIZE = 0x40
    }
}
