package io.legado.app.model.readaloud

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.ChapterRoleScript
import io.legado.app.data.entities.RoleCast
import io.legado.app.help.ai.AiClient
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 章节角色标注。命中缓存直接返回, 未命中调 LLM 并落缓存。
 */
object RoleAnnotator {

    /** 每本书保留的章节标注上限, 由启动期清理落实 */
    const val CACHE_CHAPTERS_PER_BOOK = 100

    /** 连续失败到这个数就放弃整章, 避开鉴权/配额故障下的无谓等待 */
    private const val MAX_CHUNK_FAILURES = 2

    fun contentMd5(paragraphs: List<String>): String =
        MD5Utils.md5Encode(paragraphs.joinToString("\n"))

    fun annotationKey(paragraphs: List<String>, model: String, systemPrompt: String): String =
        MD5Utils.md5Encode(
            listOf(
                RolePrompt.ANNOTATION_PROTOCOL_VERSION,
                model,
                systemPrompt,
                paragraphs.joinToString("\n")
            ).joinToString("\u0000")
        )

    /** 缓存只存片段, 角色名由片段反推; 画像留给 roleCasts 的既有记录 */
    fun rolesFrom(segments: List<Segment>): List<RoleProfile> = segments
        .map { it.role }
        .filter { it.isNotBlank() && it != RoleCast.NARRATOR }
        .distinct()
        .map { RoleProfile(it) }

    /**
     * 片段里实际发声的角色, 画像取 profiles 的同名项。
     * 净化会把不合法的段落整段还原为旁白, 该段落的角色可能因此不再出现在片段中。
     *
     * @return 名字与 [rolesFrom] 一致, 按片段出现序
     */
    internal fun rolesIn(segments: List<Segment>, profiles: List<RoleProfile>): List<RoleProfile> {
        val known = profiles.associateBy { it.name }
        return rolesFrom(segments).map { known[it.name] ?: it }
    }

    /** @return null 表示无法标注, 调用方降级为纯旁白 */
    suspend fun annotate(
        bookUrl: String,
        chapterIndex: Int,
        paragraphs: List<String>
    ): RoleScript? {
        if (paragraphs.isEmpty()) return null
        if (!AppConfig.multiRoleReadAloud) return null
        val system = RolePrompt.effectiveSystem(AppConfig.aiRolePrompt)
        val md5 = contentMd5(paragraphs)
        val annotationKey = annotationKey(paragraphs, AppConfig.aiModel, system)
        readCache(bookUrl, chapterIndex, md5, annotationKey, paragraphs)?.let { return it }
        if (!AppConfig.aiRoleConsent) return null
        if (!AiClient.isConfigured()) return null
        val known = LinkedHashSet<String>()
        val parts = ArrayList<RoleScript>()
        // 单个分片失败只丢该片, 其段落在 sanitize 里退化为旁白, 整章标注不作废
        var consecutiveFailures = 0
        for (range in RolePrompt.chunks(paragraphs)) {
            currentCoroutineContext().ensureActive()
            val part = try {
                val userPrompt = RolePrompt.buildUser(paragraphs, range, known)
                RolePrompt.parse(AiClient.chatJson(system, userPrompt), range)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 取消 OkHttp 调用会以 IOException 冒出, ensureActive 把它还原成取消
                currentCoroutineContext().ensureActive()
                AppLog.put("角色标注失败\n${e.localizedMessage}", e)
                null
            }
            if (part == null) {
                consecutiveFailures++
                // 连续失败多为鉴权或配额问题, 早退省掉剩余分片的等待与开销
                if (consecutiveFailures >= MAX_CHUNK_FAILURES) return null
                continue
            }
            consecutiveFailures = 0
            part.roles.forEach { known.add(it.name) }
            parts.add(part)
        }
        if (parts.isEmpty()) return null
        val merged = RolePrompt.merge(parts)
        val segments = SpeechScript.sanitize(paragraphs, merged.segments)
        val roles = rolesIn(segments, merged.roles)
        // 全旁白说明本次标注没有产出, 不落缓存, 留给下次重试
        if (roles.isNotEmpty()) {
            writeCache(bookUrl, chapterIndex, md5, annotationKey, roles, segments)
        }
        return RoleScript(segments, roles)
    }

    /** 预取, 任何失败都吞掉 */
    suspend fun prefetch(bookUrl: String, chapterIndex: Int, paragraphs: List<String>) {
        try {
            annotate(bookUrl, chapterIndex, paragraphs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("角色标注预取失败\n${e.localizedMessage}", e)
        }
    }

    /** DB 失败一律当缓存未命中, 走重新标注 */
    private suspend fun readCache(
        bookUrl: String,
        chapterIndex: Int,
        md5: String,
        annotationKey: String,
        paragraphs: List<String>
    ): RoleScript? {
        val cached = try {
            withContext(IO) { appDb.chapterRoleScriptDao.get(bookUrl, chapterIndex) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("角色标注缓存读取失败\n${e.localizedMessage}", e)
            null
        } ?: return null
        if (cached.contentMd5 != md5 || cached.annotationKey != annotationKey) return null
        val raw = GSON.fromJsonArray<Segment>(cached.segmentsJson).getOrNull()
            ?.filterNotNull()
            ?: return null
        if (raw.isEmpty()) return null
        // md5 相符只保证段落文本一致, segmentsJson 仍可能被外部改写; sanitize 对自身输出幂等
        val segments = SpeechScript.sanitize(paragraphs, raw)
        val roles = GSON.fromJsonArray<RoleProfile>(cached.profilesJson).getOrNull()
            ?.filterNotNull()
            ?.let { rolesIn(segments, it) }
            ?: return null
        if (roles.isEmpty()) return null
        return RoleScript(segments, roles)
    }

    /** 写失败不影响本次标注结果, 只是下次仍要重新标注 */
    private suspend fun writeCache(
        bookUrl: String,
        chapterIndex: Int,
        md5: String,
        annotationKey: String,
        profiles: List<RoleProfile>,
        segments: List<Segment>
    ) {
        try {
            withContext(IO) {
                appDb.chapterRoleScriptDao.insert(
                    ChapterRoleScript(
                        bookUrl = bookUrl,
                        chapterIndex = chapterIndex,
                        contentMd5 = md5,
                        segmentsJson = GSON.toJson(segments),
                        annotationKey = annotationKey,
                        profilesJson = GSON.toJson(profiles)
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("角色标注缓存写入失败\n${e.localizedMessage}", e)
        }
    }
}
