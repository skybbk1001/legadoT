package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * 模拟追读的起始日期以 [LocalDate] 存入 ReadConfig(经 Gson 序列化到 books 表)。
 * Gson 默认用反射读写 LocalDate 的私有 final 字段，在部分机型上会反序列化出
 * 非法日期(month=0, day=0)，最终导致 `LocalDate.parse("0001-00-00")` 崩溃。
 * 这里锁定 [LocalDateTypeAdapter] 的序列化格式与向后兼容行为。
 */
class LocalDateTypeAdapterTest {

    @Test
    fun roundTripsValidDateAsIsoString() {
        val date = LocalDate.of(2024, 7, 8)
        val json = GSON.toJson(date)
        assertEquals("\"2024-07-08\"", json)
        val back: LocalDate? = GSON.fromJson(json, LocalDate::class.java)
        assertEquals(date, back)
    }

    @Test
    fun deserializesLegacyObjectFormat() {
        val back: LocalDate? = GSON.fromJson(
            """{"year":2024,"month":7,"day":8}""",
            LocalDate::class.java
        )
        assertEquals(LocalDate.of(2024, 7, 8), back)
    }

    @Test
    fun corruptLegacyObjectBecomesNull() {
        val back: LocalDate? = GSON.fromJson(
            """{"year":1,"month":0,"day":0}""",
            LocalDate::class.java
        )
        assertNull(back)
    }

    @Test
    fun corruptIsoStringBecomesNull() {
        val back: LocalDate? = GSON.fromJson("\"0001-00-00\"", LocalDate::class.java)
        assertNull(back)
    }
}
