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

    @Test fun `filter by name or id while typing`() {
        val list = listOf(zeitboy, zq, Entry(1543840191, null))
        assertEquals(list, RecentAccounts.filter(list, "  "))
        assertEquals(listOf(zq), RecentAccounts.filter(list, "zq"))
        assertEquals(listOf(zeitboy), RecentAccounts.filter(list, "Z31780y"))
        assertEquals(listOf(zeitboy), RecentAccounts.filter(list, "4045"))
        assertEquals(listOf(Entry(1543840191, null)), RecentAccounts.filter(list, "154"))
        assertEquals(emptyList<Entry>(), RecentAccounts.filter(list, "76561198000718824"))
    }

    @Test fun `the current account goes to the top, also if it isn't a recent one`() {
        val other = Entry(1543840191, null)
        assertEquals(listOf(zq, zeitboy, other), RecentAccounts.withCurrent(listOf(zeitboy, zq, other), zq))
        assertEquals(listOf(Entry(7, null), zeitboy), RecentAccounts.withCurrent(listOf(zeitboy), Entry(7, null)))
        assertEquals(listOf(zeitboy), RecentAccounts.withCurrent(listOf(zeitboy), null))
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
