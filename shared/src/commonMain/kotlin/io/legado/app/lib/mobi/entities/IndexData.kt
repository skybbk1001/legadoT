package io.legado.app.lib.mobi.entities


data class IndexData(
    val table: List<IndexEntry>,
    val cncx: HashMap<Int, String>
)
