package io.nekohasekai.sfa.update

import io.nekohasekai.sfa.bg.pult.Pult
import io.nekohasekai.sfa.bg.pult.PultSostoyanie
import io.nekohasekai.sfa.bg.pult.PultYadro
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Выкатка через пульт: блок `obnovlenie` главнее выпусков, канал proba/stop, без повтора по кругу. */
class ObnovleniyaPultTest {
    private val sha = "ab".repeat(32)
    private val blok = PultYadro.Obnovlenie(versiyaKod = 150, url = "https://example.org/kelevra-150.apk", sha256 = sha, razmer = 1234, zametka = "проба")

    @After
    fun sbros() = Pult.dlyaTesta(PultSostoyanie())

    @Test
    fun `блок пульта выше установленной — главнее выпусков при любом канале кроме stop`() {
        for (kanal in listOf("obychnyy", "proba")) {
            val ist = ObnovleniyaPult.vybrat(kanal, blok, ustanovlenKod = 147, plohie = emptySet())
            assertTrue(ist is ObnovleniyaPult.Istochnik.IzPulta)
            val info = (ist as ObnovleniyaPult.Istochnik.IzPulta).info
            assertEquals(150, info.versionCode)
            assertEquals(sha, info.sha256)
            assertEquals(1234L, info.fileSize)
            assertEquals(blok.url, info.downloadUrl)
        }
        assertEquals(ObnovleniyaPult.Istochnik.Nichego, ObnovleniyaPult.vybrat("stop", blok, 147, emptySet()))
    }

    @Test
    fun `без блока — выпуски на обычном, ничего на пробе и стопе`() {
        assertEquals(ObnovleniyaPult.Istochnik.Vypuski, ObnovleniyaPult.vybrat("obychnyy", null, 147, emptySet()))
        assertEquals(ObnovleniyaPult.Istochnik.Nichego, ObnovleniyaPult.vybrat("proba", null, 147, emptySet()))
        assertEquals(ObnovleniyaPult.Istochnik.Nichego, ObnovleniyaPult.vybrat("stop", null, 147, emptySet()))
    }

    @Test
    fun `та же или старая сборка уже стоит — не ставится повторно`() {
        assertEquals(ObnovleniyaPult.Istochnik.Vypuski, ObnovleniyaPult.vybrat("obychnyy", blok, 150, emptySet()))
        assertEquals(ObnovleniyaPult.Istochnik.Nichego, ObnovleniyaPult.vybrat("proba", blok, 151, emptySet()))
    }

    @Test
    fun `неверный хеш — сборка больше не берётся, ошибка в заголовке`() {
        Pult.dlyaTesta(PultSostoyanie(versiya = 9, obnovlenie = blok, nastroyki = mapOf("obnovleniya.kanal" to "proba")))
        val info = ObnovleniyaPult.vUpdateInfo(blok)
        assertTrue(ObnovleniyaPult.vybratSeychas(147) is ObnovleniyaPult.Istochnik.IzPulta)
        assertTrue(ObnovleniyaPult.posleSboya(info, FaylNeGoden("хеш не совпал со списком релизов")))
        assertEquals(ObnovleniyaPult.Istochnik.Nichego, ObnovleniyaPult.vybratSeychas(147))
        assertTrue(Pult.zagolovok().contains("oshibki=obnovlenie:hesh"))
    }

    @Test
    fun `сбой сети — сборка остаётся в работе, ошибка сообщается`() {
        Pult.dlyaTesta(PultSostoyanie(versiya = 9, obnovlenie = blok))
        assertTrue(ObnovleniyaPult.posleSboya(ObnovleniyaPult.vUpdateInfo(blok), java.io.IOException("reset")))
        assertTrue(ObnovleniyaPult.vybratSeychas(147) is ObnovleniyaPult.Istochnik.IzPulta)
        assertTrue(Pult.zagolovok().contains("obnovlenie:skachat"))
    }

    @Test
    fun `сбой обычного выпуска пульт не трогает`() {
        Pult.dlyaTesta(PultSostoyanie(versiya = 9, obnovlenie = blok))
        val vypusk = UpdateInfo(148, "1.14.148", "https://github.com/x.apk", "https://github.com/x", null, false)
        assertFalse(ObnovleniyaPult.posleSboya(vypusk, FaylNeGoden("хеш не совпал со списком релизов")))
        assertFalse(Pult.zagolovok().contains("obnovlenie"))
    }

    @Test
    fun `причина отказа файла словом`() {
        assertEquals("hesh", ObnovleniyaPult.prichinaFayla("хеш не совпал со списком релизов"))
        assertEquals("razmer", ObnovleniyaPult.prichinaFayla("размер 1 вместо 2"))
        assertEquals("versiya", ObnovleniyaPult.prichinaFayla("в файле версия 147, не новее установленной"))
    }

    @Test
    fun `блок выкатки без https или с кривым хешем не принимается`() {
        val j = blok.vJson()
        assertEquals(blok, PultYadro.Obnovlenie.izJson(j))
        assertEquals(null, PultYadro.Obnovlenie.izJson(org.json.JSONObject(j.toString()).put("url", "http://example.org/a.apk")))
        assertEquals(null, PultYadro.Obnovlenie.izJson(org.json.JSONObject(j.toString()).put("sha256", "xyz")))
    }

    @Test
    fun `напоминание пульта доходит до лестницы отказов`() {
        assertEquals(360L, IshodUstanovki.zaderzhkaMinut(1, OtkazUstanovki.OTMENENO, 360L))
        assertEquals(IshodUstanovki.NAPOMINANIE_MINUT, IshodUstanovki.zaderzhkaMinut(1, OtkazUstanovki.OTMENENO))
        assertTrue(IshodUstanovki.mozhnoUvedomit(7 * 3600_000L, 0L + 1, napominanieMinut = 360L))
    }
}
