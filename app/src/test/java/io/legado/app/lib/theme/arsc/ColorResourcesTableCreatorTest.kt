package io.legado.app.lib.theme.arsc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ColorResourcesTableCreatorTest {

    private fun fakeIds(): LinkedHashMap<Int, Int> = linkedMapOf(
        0x7F1C0001 to 0xFF112233.toInt(),
        0x7F1C0002 to 0xFF445566.toInt(),
        0x7F1C0003 to 0xFFFFFFFF.toInt(),
    )

    private fun fakeNames(id: Int): String = "color_${(id and 0xFFFF).toString(16)}"

    @Test
    fun `table bytes round trip through an independent minimal reader`() {
        val ids = fakeIds()
        val bytes = ColorResourcesTableCreator.create("io.legado.app", ids, ::fakeNames)

        val table = ArscTableReader(bytes).readTable()
        assertEquals(1, table.packages.size)
        val pkg = table.packages[0]
        assertEquals(0x7F, pkg.id)
        assertEquals("io.legado.app", pkg.name)
        assertEquals(0x1C, pkg.typeId)
        assertEquals(4, pkg.entryCount) // 最大 entryId(3) + 1,含 index 0 空洞
        assertEquals(ids, pkg.entries)
        assertEquals(listOf("color_1", "color_2", "color_3"), pkg.keyNames)
    }

    @Test
    fun `table handles entries up to the highest entry id with gaps`() {
        val ids = linkedMapOf(
            0x7F1C0001 to 0xFF000000.toInt(),
            0x7F1C0005 to 0xFFFFFFFF.toInt(),
        )
        val bytes = ColorResourcesTableCreator.create("io.legado.app", ids, ::fakeNames)
        val pkg = ArscTableReader(bytes).readTable().packages[0]
        assertEquals(6, pkg.entryCount)
        assertEquals(ids, pkg.entries)
    }

    @Test
    fun `empty mapping is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ColorResourcesTableCreator.create("io.legado.app", emptyMap(), ::fakeNames)
        }
    }

    @Test
    fun `unknown or android package id is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ColorResourcesTableCreator.create(
                "io.legado.app",
                mapOf(0x01060001 to 0xFF000000.toInt()),
                ::fakeNames,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ColorResourcesTableCreator.create(
                "io.legado.app",
                mapOf(0x5F000001 to 0xFF000000.toInt()),
                ::fakeNames,
            )
        }
    }

    @Test
    fun `zero type id is rejected as non color resource`() {
        assertThrows(IllegalArgumentException::class.java) {
            ColorResourcesTableCreator.create(
                "io.legado.app",
                mapOf(0x7F000001 to 0xFF000000.toInt()),
                ::fakeNames,
            )
        }
    }
}
