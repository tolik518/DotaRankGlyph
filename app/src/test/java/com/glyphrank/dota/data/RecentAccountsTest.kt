package com.glyphrank.dota.data

import com.glyphrank.dota.data.RecentAccounts.Entry
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentAccountsTest {
    private val zeitboy = Entry(40453096, "Zeitboy | Z31780Y")
    private val zq = Entry(116233682, "ZQuixotix")

    @Test fun `newest first, no duplicates, names updated`() {
        var list = RecentAccounts.add(emptyList(), zeitboy)
        list = RecentAccounts.add(list, zq)
        assertEquals(listOf(zq, zeitboy), list)
        list = RecentAccounts.add(list, zeitboy.copy(name = "Zeitboy"))
        assertEquals(listOf(Entry(40453096, "Zeitboy"), zq), list)
    }

    @Test fun `keeps at most 5`() {
        val list = (1L..7L).fold(emptyList<Entry>()) { acc, id -> RecentAccounts.add(acc, Entry(id, null)) }
        assertEquals(listOf(7L, 6, 5, 4, 3), list.map { it.accountId })
    }

    @Test fun `remove`() =
        assertEquals(listOf(zq), RecentAccounts.remove(listOf(zeitboy, zq), zeitboy.accountId))

    @Test fun `json round trip, including unicode and missing names`() {
        val list = listOf(zeitboy, Entry(2, "Игрок 2 ★"), Entry(3, null))
        assertEquals(list, RecentAccounts.decode(RecentAccounts.encode(list)))
    }

    @Test fun `broken json is an empty list`() {
        assertEquals(emptyList<Entry>(), RecentAccounts.decode("{oops"))
        assertEquals(emptyList<Entry>(), RecentAccounts.decode(null))
        assertEquals(listOf(zq), RecentAccounts.decode("""[{"id":0},{"name":"x"},{"id":116233682,"name":"ZQuixotix"}]"""))
    }
}
