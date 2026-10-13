package io.nekohasekai.sfa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KodPoleTest {
    @Test
    fun grouping() {
        assertEquals("K7QM", KodPole.pokaz("k7qm").text)
        assertEquals("K7QM-2XRT-9PLA", KodPole.pokaz("k7qm2xrt9pla").text)
        assertEquals("K7QM-2", KodPole.pokaz("k7qm2").text)
    }

    @Test
    fun charByCharKeepsOrder() {
        var raw = ""
        for (c in "k7qm2xrt9pla") raw = KodPole.syroe(raw + c)
        assertEquals("k7qm2xrt9pla", raw)
        assertEquals("K7QM-2XRT-9PLA", KodPole.pokaz(raw).text)
    }

    @Test
    fun offsets() {
        val p = KodPole.pokaz("k7qm2xrt9pla")
        assertEquals(0, p.syroeVPokaz[0])
        assertEquals(3, p.syroeVPokaz[3])
        assertEquals(5, p.syroeVPokaz[4])
        assertEquals(14, p.syroeVPokaz[12])
        assertEquals(12, p.pokazVSyroe[14])
        assertEquals(4, p.pokazVSyroe[4])
        assertEquals(4, p.pokazVSyroe[5])
        assertEquals(8, p.pokazVSyroe[10])
        // курсор в конце введённого остаётся в конце
        assertEquals(4, KodPole.pokaz("k7qm").syroeVPokaz[4])
        for (raw in listOf("", "a", "abcd", "abcde", "ab-cd-efgh", "abcdefghijkl")) {
            val q = KodPole.pokaz(raw)
            for (i in 0 until q.syroeVPokaz.size - 1) assertTrue(q.syroeVPokaz[i] <= q.syroeVPokaz[i + 1])
            for (i in 0 until q.pokazVSyroe.size - 1) assertTrue(q.pokazVSyroe[i] <= q.pokazVSyroe[i + 1])
            assertEquals(q.text.length, q.syroeVPokaz.last())
            assertEquals(raw.length, q.pokazVSyroe.last())
        }
    }

    @Test
    fun legacyNotGrouped() {
        val legacy = "aBcDeFgHiJkLm"
        assertEquals(legacy, KodPole.pokaz(legacy).text)
        assertEquals("ab_cdefg", KodPole.pokaz("ab_cdefg").text)
        assertEquals("ab-cd_ef", KodPole.syroe("ab-cd_ef"))
    }

    @Test
    fun paste() {
        assertEquals("k7qm2xrt9pla", KodPole.vstavka(" k7qm-2xrt 9pla\n"))
        assertEquals("abcd-efgh-ijkl-mnop", KodPole.vstavka("abcd-efgh-ijkl-mnop"))
        assertEquals("https://x/y", KodPole.vstavka("https://x/y"))
    }
}
