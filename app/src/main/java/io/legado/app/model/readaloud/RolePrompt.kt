package io.legado.app.model.readaloud

import io.legado.app.data.entities.RoleCast
import io.legado.app.data.entities.TtsVoice
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

/**
 * 角色标注的 prompt 构建与响应解析。纯函数, 无 IO。
 */
object RolePrompt {

    const val BATCH_SIZE = 60
    const val MAX_BATCH_CHARS = 12000
    const val ANNOTATION_PROTOCOL_VERSION = "2"

    const val DEFAULT_SYSTEM = """你是中文小说的角色标注器。输入是编号段落，输出 JSON。
1. 把每个段落切成不重叠、无空隙、完整覆盖全段的片段
2. 每个片段标注说话人；非对话内容一律标 "旁白"
3. 心理独白按其主人的角色名标注
4. 无法确定说话人的对话标 "旁白"
5. s / e 是段内字符下标，前闭后开；p 用输入给出的段落编号
6. 每段第一个片段的 s 为 0，最后一个片段的 e 等于该段输入给出的长度
输出：{"roles":[{"name","gender","age"}],"segments":[{"p","s","e","r"}]}
gender 取 male|female|unknown，age 取 child|young|middle|old|unknown"""

    fun effectiveSystem(customPrompt: String): String = buildString {
        append(DEFAULT_SYSTEM)
        if (customPrompt.isNotBlank()) {
            append("\n\n用户补充要求（不得改变上述输出格式和覆盖规则）：\n")
            append(customPrompt.trim())
        }
    }

    fun chunks(total: Int, batchSize: Int = BATCH_SIZE): List<IntRange> {
        if (total <= 0 || batchSize <= 0) return emptyList()
        val out = ArrayList<IntRange>()
        var from = 0
        while (from < total) {
            val to = minOf(from + batchSize, total) - 1
            out.add(from..to)
            from = to + 1
        }
        return out
    }

    /** Keep paragraph boundaries while limiting both request count and approximate context size. */
    fun chunks(
        paragraphs: List<String>,
        batchSize: Int = BATCH_SIZE,
        maxChars: Int = MAX_BATCH_CHARS
    ): List<IntRange> {
        if (paragraphs.isEmpty() || batchSize <= 0 || maxChars <= 0) return emptyList()
        val out = ArrayList<IntRange>()
        var from = 0
        while (from < paragraphs.size) {
            var to = from
            var chars = 0
            while (to < paragraphs.size && to - from < batchSize) {
                val next = paragraphs[to].length
                if (to > from && chars + next > maxChars) break
                chars += next
                to++
            }
            out.add(from until to)
            from = to
        }
        return out
    }

    /** 段落带出字符长度: 模型据此对齐末片段的 e, 省掉一轮尾部偏差 */
    fun buildUser(
        paragraphs: List<String>,
        range: IntRange,
        knownRoles: Collection<String>
    ): String {
        val known = knownRoles.filter { it.isNotBlank() && it != RoleCast.NARRATOR }
        val sb = StringBuilder()
        if (known.isNotEmpty()) {
            sb.append("已知角色：").append(known.joinToString("、")).append('\n')
        }
        sb.append("段落：\n")
        for (p in range) {
            val text = paragraphs.getOrNull(p) ?: continue
            sb.append('[').append(p).append("|len=").append(text.length).append("] ")
                .append(text).append('\n')
        }
        return sb.toString()
    }

    /** @return null 表示响应不是可解析的 JSON; 范围外的 p 一律丢弃 */
    fun parse(json: String, range: IntRange): RoleScript? {
        val dto = GSON.fromJsonObject<ResponseDto>(stripFence(json)).getOrNull() ?: return null
        val segments = dto.segments.orEmpty().filterNotNull()
            .filter { it.p in range }
            .map { Segment(it.p, it.s, it.e, narratorAware(it.r.orEmpty().trim())) }
        val roles = dto.roles.orEmpty().filterNotNull().mapNotNull { role ->
            val name = role.name?.trim()
            if (name.isNullOrEmpty()) null else RoleProfile(
                narratorAware(name),
                TtsVoice.normalizeGender(role.gender),
                TtsVoice.normalizeAge(role.age)
            )
        }
        return RoleScript(segments, roles)
    }

    /**
     * 旁白的身份是 [RoleCast.NARRATOR] 这个字面量, 下游全靠它区分旁白与角色。
     * 模型偶尔改用同义写法, 归一到该字面量, 免得旁白被当成一个普通角色去配音色
     */
    private fun narratorAware(name: String): String =
        if (name.lowercase() in narratorAliases) RoleCast.NARRATOR else name

    private val narratorAliases = setOf(
        RoleCast.NARRATOR, "narrator", "旁白者", "解说", "叙述", "叙述者", "作者"
    )

    /**
     * 去掉 markdown 代码围栏。AiClient 已请求 response_format=json_object,
     * 本地与代理的 OpenAI 兼容端点常忽略它并把 JSON 包进 ```json 块里。
     * 只认「首行是围栏起始行」这一种形态, 其余原样返回交给 GSON
     */
    private fun stripFence(raw: String): String {
        val text = raw.trim()
        if (!text.startsWith("```")) return text
        val firstBreak = text.indexOf('\n')
        if (firstBreak < 0) return text
        // ``` 与换行之间只允许语言标记, 出现空白说明首行是内容而非围栏
        val lang = text.substring(3, firstBreak).trim()
        if (lang.any { it.isWhitespace() }) return text
        return text.substring(firstBreak + 1).trimEnd().removeSuffix("```").trim()
    }

    /** 同名角色以先出现的画像为准 */
    fun merge(parts: List<RoleScript>): RoleScript {
        val segments = ArrayList<Segment>()
        val roles = LinkedHashMap<String, RoleProfile>()
        parts.forEach { part ->
            segments.addAll(part.segments)
            part.roles.forEach { roles.putIfAbsent(it.name, it) }
        }
        return RoleScript(
            segments.sortedWith(compareBy({ it.p }, { it.s })),
            roles.values.toList()
        )
    }

    private data class ResponseDto(
        val roles: List<RoleDto>? = null,
        val segments: List<SegmentDto>? = null
    )

    /** 字段声明为可空: JSON 里的显式 null 会被反射直接写入, 绕过 Kotlin 的非空校验 */
    private data class RoleDto(
        val name: String? = null,
        val gender: String? = null,
        val age: String? = null
    )

    private data class SegmentDto(
        val p: Int = 0,
        val s: Int = 0,
        val e: Int = 0,
        val r: String? = null
    )
}
