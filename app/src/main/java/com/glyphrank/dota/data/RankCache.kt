package com.glyphrank.dota.data

import org.json.JSONException
import org.json.JSONObject

/** The last fetched rank of every account the app remembers, as one JSON object keyed by account ID. */
object RankCache {
    data class Entry(val player: PlayerRank, val fetchedAtMs: Long)

    fun encode(entries: Map<Long, Entry>): String = JSONObject().apply {
        for ((id, e) in entries) {
            put(id.toString(), JSONObject().apply {
                e.player.personaName?.let { put("name", it) }
                e.player.rankTier?.let { put("tier", it) }
                e.player.leaderboardRank?.let { put("lb", it) }
                put("at", e.fetchedAtMs)
            })
        }
    }.toString()

    fun decode(json: String?): Map<Long, Entry> {
        if (json.isNullOrBlank()) return emptyMap()
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return emptyMap()
        }
        val out = LinkedHashMap<Long, Entry>()
        for (key in root.keys()) {
            val id = key.toLongOrNull()?.takeIf { it > 0 } ?: continue
            val o = root.optJSONObject(key) ?: continue
            val at = o.optLong("at", 0L).takeIf { it > 0 } ?: continue
            out[id] = Entry(
                PlayerRank(
                    accountId = id,
                    personaName = if (o.has("name") && !o.isNull("name")) o.getString("name") else null,
                    rankTier = if (o.has("tier") && !o.isNull("tier")) o.getInt("tier") else null,
                    leaderboardRank = if (o.has("lb") && !o.isNull("lb")) o.getInt("lb") else null,
                ),
                at,
            )
        }
        return out
    }

    /** Keeps only the accounts in [keep] (the recent list and the current account). */
    fun prune(entries: Map<Long, Entry>, keep: Set<Long>): Map<Long, Entry> = entries.filterKeys { it in keep }
}
