package io.legado.app.help.nostr

import io.legado.app.constant.EventBus
import io.legado.app.utils.ACache
import io.legado.app.utils.postEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用级待发布段评/回复：发布本身绑进程生命周期，弹窗关闭/退回正文后仍要继续在途或遗留草稿，
 * 故占位状态不挂在弹窗实例上。内存负责本次会话的发送中/草稿态；同时把条目落盘，
 * 使进程被回收/重启后重开段评弹窗仍能恢复显示并可点击重发。
 */
internal object NostrPendingReviews {

    const val STATE_SENDING = "sending"
    const val STATE_DRAFT = "draft"

    /** 落盘草稿保留 30 天，与编辑器草稿(nostr_review_drafts)一致。 */
    private const val DISK_TTL_SECONDS = 30 * 24 * 60 * 60

    data class Entry(
        val localId: String,
        val key: String,
        val text: String,
        val parentId: String? = null,
        val parentAuthor: String? = null,
        val rootId: String? = null,
        val rootAuthor: String? = null,
        val state: String = STATE_SENDING,
        val createdAtMs: Long = System.currentTimeMillis()
    )

    private val entries = LinkedHashMap<String, Entry>()

    // 放 filesDir：草稿属于用户内容，不该被系统清理缓存时一并删掉。
    private val diskCache by lazy { ACache.get("nostr_pending_reviews", cacheDir = false) }

    fun keyOf(bookUrl: String, chapterIndex: Int, paragraphNum: Int): String =
        "$bookUrl|$chapterIndex|$paragraphNum"

    @Synchronized
    fun add(entry: Entry) {
        entries[entry.localId] = entry
        persist(entry.key)
        notifyChanged(entry.key)
    }

    @Synchronized
    fun get(localId: String): Entry? = entries[localId]

    /**
     * 把落盘的草稿补进内存（进程重启后重开弹窗时调用）；已存在的 localId 不覆盖，
     * 避免用磁盘旧值顶掉本次会话里的发送中/已更新内容。恢复条目统一按草稿态显示。
     */
    @Synchronized
    fun hydrate(key: String) {
        val array = diskCache.getAsJSONArray(key) ?: return
        for (i in 0 until array.length()) {
            val json = array.optJSONObject(i) ?: continue
            val entry = entryFromJson(json, key) ?: continue
            if (entries.containsKey(entry.localId)) continue
            entries[entry.localId] = entry.copy(state = STATE_DRAFT)
        }
    }

    @Synchronized
    fun list(key: String): List<Entry> =
        entries.values.filter { it.key == key }.sortedBy { it.createdAtMs }

    @Synchronized
    fun remove(localId: String) {
        val entry = entries.remove(localId) ?: return
        persist(entry.key)
        notifyChanged(entry.key)
    }

    @Synchronized
    fun markDraft(localId: String) {
        val entry = entries[localId] ?: return
        entries[localId] = entry.copy(state = STATE_DRAFT)
        persist(entry.key)
        notifyChanged(entry.key)
    }

    @Synchronized
    fun markSending(localId: String) {
        val entry = entries[localId] ?: return
        entries[localId] = entry.copy(state = STATE_SENDING)
        // 不删落盘草稿：重发在途时进程被杀，重启后仍能作为草稿恢复。
        notifyChanged(entry.key)
    }

    /** 通知已打开的段评弹窗与这里对账；弹窗据此清掉发布成功后残留的占位副本。 */
    private fun notifyChanged(key: String) {
        postEvent(EventBus.REFRESH_REVIEW_PENDING, key)
    }

    /** 把该 key 下内存里的条目写回磁盘；该 key 无条目时清掉磁盘键。 */
    private fun persist(key: String) {
        val array = JSONArray()
        entries.values.filter { it.key == key }.forEach { array.put(entryToJson(it)) }
        if (array.length() == 0) {
            diskCache.remove(key)
        } else {
            diskCache.put(key, array, DISK_TTL_SECONDS)
        }
    }

    private fun entryToJson(entry: Entry): JSONObject = JSONObject().apply {
        put("localId", entry.localId)
        put("text", entry.text)
        entry.parentId?.let { put("parentId", it) }
        entry.parentAuthor?.let { put("parentAuthor", it) }
        entry.rootId?.let { put("rootId", it) }
        entry.rootAuthor?.let { put("rootAuthor", it) }
        put("createdAtMs", entry.createdAtMs)
    }

    private fun entryFromJson(json: JSONObject, key: String): Entry? {
        val localId = json.optString("localId").takeIf { it.isNotBlank() } ?: return null
        val text = json.optString("text")
        return Entry(
            localId = localId,
            key = key,
            text = text,
            parentId = json.optString("parentId").takeIf { it.isNotBlank() },
            parentAuthor = json.optString("parentAuthor").takeIf { it.isNotBlank() },
            rootId = json.optString("rootId").takeIf { it.isNotBlank() },
            rootAuthor = json.optString("rootAuthor").takeIf { it.isNotBlank() },
            state = STATE_DRAFT,
            createdAtMs = json.optLong("createdAtMs", System.currentTimeMillis())
        )
    }
}
