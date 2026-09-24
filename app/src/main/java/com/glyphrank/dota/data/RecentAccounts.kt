package com.glyphrank.dota.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The last few accounts that were checked successfully, newest first. */
object RecentAccounts {
    data class Entry(val accountId: Long, val name: String?)

    const val MAX = 5

    /** Moves [entry] to the front (updating its name) and keeps at most [MAX]. */
    fun add(list: List<Entry>, entry: Entry): List<Entry> =
        (listOf(entry) + list.filter { it.accountId != entry.accountId }).take(MAX)

    fun remove(list: List<Entry>, accountId: Long): List<Entry> = list.filter { it.accountId != accountId }

    fun encode(list: List<Entry>): String = JSONArray().apply {
        list.forEach { e -> put(JSONObject().put("id", e.accountId).apply { e.name?.let { put("name", it) } }) }
    }.toString()

    fun decode(json: String?): List<Entry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("id", 0L).takeIf { it > 0 } ?: return@mapNotNull null
                Entry(id, if (o.has("name") && !o.isNull("name")) o.getString("name") else null)
            }.take(MAX)
        } catch (e: JSONException) {
            emptyList()
        }
    }
}
