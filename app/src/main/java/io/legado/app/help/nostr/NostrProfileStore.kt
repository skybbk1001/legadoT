package io.legado.app.help.nostr

import io.legado.app.utils.ACache
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Nostr 账号资料（kind 0）本地缓存：评论列表优先展示头像与昵称。
 *
 * 落盘 ACache("nostr_profiles")，key = pubkey，TTL 7 天；超过 [STALE_MILLIS] 才重新拉取。
 */
object NostrProfileStore {

    data class Profile(
        val pubkey: String,
        val name: String?,
        val displayName: String?,
        val picture: String?,
        val updatedAt: Long
    ) {
        /** 优先昵称，其次用户名。 */
        val bestName: String?
            get() = displayName?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }
    }

    private const val CACHE_NAME = "nostr_profiles"
    private const val TTL_SECONDS = 7 * 24 * 60 * 60
    private const val STALE_MILLIS = 24 * 60 * 60 * 1000L

    private val disk by lazy { ACache.get(CACHE_NAME) }
    private val memory = ConcurrentHashMap<String, Profile>()

    fun get(pubkey: String): Profile? {
        val key = pubkey.lowercase()
        if (key.isBlank()) return null
        memory[key]?.let { return it }
        val json = disk.getAsString(key) ?: return null
        val profile = runCatching { fromJson(key, JSONObject(json)) }.getOrNull() ?: return null
        memory[key] = profile
        return profile
    }

    fun putAll(profiles: Collection<Profile>) {
        profiles.forEach { profile ->
            val key = profile.pubkey.lowercase()
            if (key.isBlank()) return@forEach
            memory[key] = profile
            runCatching { disk.put(key, toJson(profile), TTL_SECONDS) }
        }
    }

    /** 只对「本地没有或已过期」的 pubkey 发请求；返回实际拉到的条数。 */
    suspend fun sync(pubkeys: Collection<String>): Int {
        val now = System.currentTimeMillis()
        val targets = pubkeys.asSequence()
            .map { it.lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .filter { pubkey ->
                val cached = get(pubkey) ?: return@filter true
                now - cached.updatedAt > STALE_MILLIS
            }
            .toList()
        if (targets.isEmpty()) return 0
        val fetched = NostrReviewClient.loadProfiles(targets)
        if (fetched.isEmpty()) return 0
        putAll(fetched)
        return fetched.size
    }

    private fun toJson(profile: Profile): String = JSONObject()
        .put("name", profile.name)
        .put("displayName", profile.displayName)
        .put("picture", profile.picture)
        .put("updatedAt", profile.updatedAt)
        .toString()

    private fun fromJson(pubkey: String, json: JSONObject): Profile = Profile(
        pubkey = pubkey,
        name = json.optString("name").takeIf { it.isNotBlank() },
        displayName = json.optString("displayName").takeIf { it.isNotBlank() },
        picture = json.optString("picture").takeIf { it.isNotBlank() },
        updatedAt = json.optLong("updatedAt", 0L)
    )
}
