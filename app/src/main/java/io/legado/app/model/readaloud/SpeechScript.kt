package io.legado.app.model.readaloud

import io.legado.app.data.entities.RoleCast

/**
 * 段落 + 片段 + casting 合成的可播放序列。纯数据, 无 IO。
 *
 * segments 取 [SpeechScript.sanitize] 的输出, 由它保证有序、首尾相接、每段都有片段。
 */
class SpeechScript(
    private val paragraphs: List<String>,
    segments: List<Segment>,
    private val cast: Map<String, RoleCast>,
    private val fallback: RoleCast
) {

    private val byParagraph: List<List<Segment>> = run {
        val grouped = segments.groupBy { it.p }
        List(paragraphs.size) { p -> grouped[p]?.sortedBy { it.s } ?: emptyList() }
    }

    fun segmentsOf(paragraphIndex: Int): List<Segment> =
        byParagraph.getOrElse(paragraphIndex) { emptyList() }

    /** startOffsetInParagraph 用于「从段中间朗读」, 只对覆盖它的那个片段起截断作用 */
    fun textOf(seg: Segment, startOffsetInParagraph: Int = 0): String {
        val text = paragraphs.getOrNull(seg.p) ?: return ""
        val from = maxOf(seg.s, startOffsetInParagraph).coerceIn(0, text.length)
        val to = seg.e.coerceIn(from, text.length)
        return text.substring(from, to)
    }

    fun castOf(seg: Segment): RoleCast = cast[seg.role] ?: fallback

    /** 未知角色所用的 casting, 即旁白 */
    fun fallbackCast(): RoleCast = fallback

    /** 覆盖 posInParagraph 的片段下标; 越界钳到末片段, 无片段返回 0 */
    fun segmentIndexAt(paragraphIndex: Int, posInParagraph: Int): Int {
        val segs = segmentsOf(paragraphIndex)
        if (segs.isEmpty()) return 0
        val index = segs.indexOfFirst { posInParagraph < it.e }
        return if (index < 0) segs.lastIndex else index
    }

    companion object {

        /**
         * 校验并修复 LLM 返回的片段。段内空隙、重叠、角色名为空即整段归旁白;
         * 越界的结束下标钳到段尾、结尾漏掉的字补一个旁白片段, 两类尾部偏差不废掉整段标注。
         * 空段落产出一个零长旁白片段, 使每个段落下标都持有片段。
         *
         * 尾部宽容的理由: LLM 对 CJK 段落的字符下标常差几位, 全段回退会让标注大面积失效。
         *
         * @return 按 (p, s) 有序, 每个段落下标恰好被其片段完整覆盖; 对自身输出幂等
         */
        fun sanitize(paragraphs: List<String>, raw: List<Segment>): List<Segment> {
            val grouped = raw.groupBy { it.p }
            val out = ArrayList<Segment>(paragraphs.size)
            for (p in paragraphs.indices) {
                val text = paragraphs[p]
                val segs = grouped[p]?.sortedBy { it.s }
                if (text.isEmpty() || segs.isNullOrEmpty()) {
                    out.add(Segment(p, 0, text.length, RoleCast.NARRATOR))
                    continue
                }
                val kept = keepCovering(p, text, segs)
                out.addAll(kept ?: listOf(Segment(p, 0, text.length, RoleCast.NARRATOR)))
            }
            return out
        }

        /** @return null 表示该段落的片段无法修复成完整覆盖, 由调用方整段归旁白 */
        private fun keepCovering(p: Int, text: String, segs: List<Segment>): List<Segment>? {
            val kept = ArrayList<Segment>(segs.size + 1)
            var cursor = 0
            for (seg in segs) {
                // 已覆盖到段尾, 其后的片段全部溢出段外
                if (cursor >= text.length) break
                val end = minOf(seg.e, text.length)
                if (seg.s != cursor || end <= seg.s || seg.role.isBlank()) return null
                kept.add(if (end == seg.e) seg else seg.copy(e = end))
                cursor = end
            }
            if (cursor >= text.length) return kept
            val last = kept.last()
            // 尾巴接在旁白片段后就并进去, 免得多出一个只念标点的片段
            if (last.role == RoleCast.NARRATOR) {
                kept[kept.lastIndex] = last.copy(e = text.length)
            } else {
                kept.add(Segment(p, cursor, text.length, RoleCast.NARRATOR))
            }
            return kept
        }

        /** 无标注时的退化脚本: 每段一个旁白片段 */
        fun narratorOnly(paragraphs: List<String>, fallback: RoleCast) = SpeechScript(
            paragraphs = paragraphs,
            segments = sanitize(paragraphs, emptyList()),
            cast = emptyMap(),
            fallback = fallback
        )
    }
}
