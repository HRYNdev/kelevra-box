package io.nekohasekai.sfa.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разбор отказа установки, проверка файла, лестница повторов, тексты для человека. */
class IshodUstanovkiTest {

    @Test
    fun `отказ проверки с кодом отмены опознаётся по сообщению`() {
        // Настоящая строка журнала телефона на Android 16, сентябрь 2026.
        val msg = "INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed for file:///data/app/vmdl89830996.tmp"
        assertEquals(OtkazUstanovki.PROVERKA, IshodUstanovki.razobrat(3, msg))
    }

    @Test
    fun `нечитаемый файл это битый файл`() {
        val msg = "INSTALL_PARSE_FAILED_NOT_APK: Failed to parse /data/app/vmdl1448321323.tmp/update.apk"
        assertEquals(OtkazUstanovki.BITYY_FAYL, IshodUstanovki.razobrat(4, msg))
    }

    @Test
    fun `старая версия поверх новой лечится перекачкой`() {
        assertEquals(OtkazUstanovki.BITYY_FAYL, IshodUstanovki.razobrat(4, "INSTALL_FAILED_VERSION_DOWNGRADE"))
    }

    @Test
    fun `без сообщения решает код`() {
        assertEquals(OtkazUstanovki.PROVERKA, IshodUstanovki.razobrat(2, null))
        assertEquals(OtkazUstanovki.OTMENENO, IshodUstanovki.razobrat(3, null))
        assertEquals(OtkazUstanovki.BITYY_FAYL, IshodUstanovki.razobrat(4, ""))
        assertEquals(OtkazUstanovki.NESOVMESTIMO, IshodUstanovki.razobrat(5, null))
        assertEquals(OtkazUstanovki.MESTO, IshodUstanovki.razobrat(6, null))
        assertEquals(OtkazUstanovki.NESOVMESTIMO, IshodUstanovki.razobrat(7, null))
        assertEquals(OtkazUstanovki.DRUGOE, IshodUstanovki.razobrat(1, null))
    }

    @Test
    fun `HyperOS Permission denied это не отмена человеком`() {
        // Та же строка, что в status=3 на Redmi Note 14 Pro+ 28.09: HyperOS рубит тихий
        // self-update своей проверкой установщика, а не человек жмёт «Отмена».
        val msg = "INSTALL_FAILED_ABORTED: Permission denied"
        assertEquals(OtkazUstanovki.DRUGOE, IshodUstanovki.razobrat(3, msg))
    }

    @Test
    fun `чужая подпись не повторяется никогда`() {
        val p = IshodUstanovki.razobrat(7, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match")
        assertEquals(OtkazUstanovki.NESOVMESTIMO, p)
        assertNull(IshodUstanovki.zaderzhkaMinut(1, p))
        assertFalse(IshodUstanovki.mozhnoPovtorit(p))
    }

    @Test
    fun `повторы не кончаются ни для одной причины кроме чужой подписи`() {
        for (p in OtkazUstanovki.values()) {
            assertNull(IshodUstanovki.zaderzhkaMinut(0, p))
            for (n in listOf(1, 2, 3, 4, 10, 1000)) {
                val minut = IshodUstanovki.zaderzhkaMinut(n, p)
                if (p == OtkazUstanovki.NESOVMESTIMO) {
                    assertNull("$p/$n", minut)
                } else {
                    assertNotNull("$p/$n", minut)
                    assertTrue("$p/$n: $minut", minut!! in 15L..IshodUstanovki.NAPOMINANIE_MINUT)
                }
            }
        }
    }

    @Test
    fun `сетевой сбой по лестнице и дальше раз в 6 часов`() {
        for (p in listOf(OtkazUstanovki.BITYY_FAYL, OtkazUstanovki.DRUGOE)) {
            assertEquals(15L, IshodUstanovki.zaderzhkaMinut(1, p))
            assertEquals(60L, IshodUstanovki.zaderzhkaMinut(2, p))
            assertEquals(360L, IshodUstanovki.zaderzhkaMinut(3, p))
            assertEquals(360L, IshodUstanovki.zaderzhkaMinut(4, p))
            assertEquals(360L, IshodUstanovki.zaderzhkaMinut(50, p))
        }
    }

    @Test
    fun `отмена и отказ проверки напоминаются раз в сутки без конца`() {
        for (p in listOf(OtkazUstanovki.OTMENENO, OtkazUstanovki.PROVERKA)) {
            for (n in 1..5) assertEquals(1440L, IshodUstanovki.zaderzhkaMinut(n, p))
        }
    }

    @Test
    fun `счётчики раздельные по причине и привязаны к версии`() {
        var zapis = ""
        zapis = IshodUstanovki.zapisatSchetchik(zapis, 711116, OtkazUstanovki.DRUGOE, 2)
        zapis = IshodUstanovki.zapisatSchetchik(zapis, 711116, OtkazUstanovki.OTMENENO, 1)
        assertEquals(2, IshodUstanovki.prochestSchetchik(zapis, 711116, OtkazUstanovki.DRUGOE))
        assertEquals(1, IshodUstanovki.prochestSchetchik(zapis, 711116, OtkazUstanovki.OTMENENO))
        assertEquals(0, IshodUstanovki.prochestSchetchik(zapis, 711116, OtkazUstanovki.PROVERKA))
        // Отмена не сбивает счёт сетевых сбоев.
        zapis = IshodUstanovki.zapisatSchetchik(zapis, 711116, OtkazUstanovki.OTMENENO, 2)
        assertEquals(2, IshodUstanovki.prochestSchetchik(zapis, 711116, OtkazUstanovki.DRUGOE))
        // Новая версия начинает с нуля по всем причинам.
        assertEquals(0, IshodUstanovki.prochestSchetchik(zapis, 711117, OtkazUstanovki.DRUGOE))
        val novaya = IshodUstanovki.zapisatSchetchik(zapis, 711117, OtkazUstanovki.DRUGOE, 1)
        assertEquals(0, IshodUstanovki.prochestSchetchik(novaya, 711117, OtkazUstanovki.OTMENENO))
        // Пустое, мусор и прежний формат «версия:число» читаются как ноль.
        assertEquals(0, IshodUstanovki.prochestSchetchik("", 711116, OtkazUstanovki.DRUGOE))
        assertEquals(0, IshodUstanovki.prochestSchetchik("мусор", 711116, OtkazUstanovki.DRUGOE))
        assertEquals(0, IshodUstanovki.prochestSchetchik("711116:2", 711116, OtkazUstanovki.DRUGOE))
        assertEquals(0, IshodUstanovki.prochestSchetchik("711116|DRUGOE=-5", 711116, OtkazUstanovki.DRUGOE))
    }

    @Test
    fun `хеш берётся только из полного sha256`() {
        val hex = "c013cee6c1c28c3d8b8e3f2ded690aba8c155b48c8615fb603fb653997f86586"
        assertEquals(hex, IshodUstanovki.sha256IzDigest("sha256:$hex"))
        assertEquals(hex, IshodUstanovki.sha256IzDigest("sha256:${hex.uppercase()}"))
        assertNull(IshodUstanovki.sha256IzDigest(null))
        assertNull(IshodUstanovki.sha256IzDigest("sha1:$hex"))
        assertNull(IshodUstanovki.sha256IzDigest("sha256:${hex.dropLast(1)}"))
    }

    @Test
    fun `проверка файла`() {
        val h = "ab".repeat(32)
        assertEquals(ProverkaFayla.OK, IshodUstanovki.proveritFayl(100, 100, h, h))
        assertEquals(ProverkaFayla.PUSTOY, IshodUstanovki.proveritFayl(0, 100, h, h))
        assertEquals(ProverkaFayla.RAZMER, IshodUstanovki.proveritFayl(99, 100, h, h))
        assertEquals(ProverkaFayla.HESH, IshodUstanovki.proveritFayl(100, 100, "cd".repeat(32), h))
        assertEquals(ProverkaFayla.HESH, IshodUstanovki.proveritFayl(100, 100, null, h))
        // Неизвестное ожидаемое не валит проверку: старые релизы хеша не несут.
        assertEquals(ProverkaFayla.OK, IshodUstanovki.proveritFayl(100, 0, null, null))
    }

    @Test
    fun `имя файла содержит версию`() {
        assertEquals("update-711116.apk", IshodUstanovki.imyaFayla(711116))
    }

    @Test
    fun `тексты для человека без запретного слова и без служебных кодов`() {
        for (p in OtkazUstanovki.values()) {
            val vse = IshodUstanovki.tekst(p) + (IshodUstanovki.instrukciya(p) ?: "")
            assertFalse(p.name, vse.lowercase().contains("защит"))
            assertFalse(p.name, vse.contains("INSTALL_"))
            assertFalse(p.name, vse.contains(p.name))
        }
        assertNotNull(IshodUstanovki.instrukciya(OtkazUstanovki.PROVERKA))
        assertNull(IshodUstanovki.instrukciya(OtkazUstanovki.BITYY_FAYL))
    }

    @Test
    fun `строка журнала несёт версию причину и код`() {
        val s = IshodUstanovki.strokaZhurnala("1.14.116", OtkazUstanovki.PROVERKA, 3, "INSTALL_FAILED_VERIFICATION_FAILURE")
        assertTrue(s.contains("1.14.116"))
        assertTrue(s.contains("PROVERKA"))
        assertTrue(s.contains("код 3"))
    }

    // Журнал 12-1 (Samsung SM-A525F, Android 14), 22.10.2026: тихая сессия отказ за 6 с без окна.
    private val otkazMamy = "INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed"

    @Test
    fun `отказ проверки тихой сессии повторяется с окном`() {
        assertTrue(IshodUstanovki.povtoritSOknom(3, otkazMamy, bylaTihaya = true, uzhePovtoryali = false))
    }

    @Test
    fun `сессия с окном второй раз не повторяется`() {
        assertFalse(IshodUstanovki.povtoritSOknom(3, otkazMamy, bylaTihaya = false, uzhePovtoryali = false))
    }

    @Test
    fun `повтор с окном один на версию`() {
        assertFalse(IshodUstanovki.povtoritSOknom(3, otkazMamy, bylaTihaya = true, uzhePovtoryali = true))
    }

    @Test
    fun `повтор с окном только на VERIFICATION_FAILURE с кодом 3`() {
        // Отказ HyperOS «Permission denied» тем же кодом — не сюда, у него своя дорога.
        assertFalse(IshodUstanovki.povtoritSOknom(3, "INSTALL_FAILED_ABORTED: Permission denied", true, false))
        // Таймаут проверки — другой отказ, повтор с окном его не лечит.
        assertFalse(IshodUstanovki.povtoritSOknom(3, "INSTALL_FAILED_VERIFICATION_TIMEOUT", true, false))
        assertFalse(IshodUstanovki.povtoritSOknom(2, otkazMamy, true, false))
        assertFalse(IshodUstanovki.povtoritSOknom(1, otkazMamy, true, false))
        assertFalse(IshodUstanovki.povtoritSOknom(3, null, true, false))
        assertTrue(IshodUstanovki.povtoritSOknom(3, otkazMamy.lowercase(), true, false))
    }

    @Test
    fun `строка журнала несёт телефон и режим установки`() {
        val s = IshodUstanovki.strokaZhurnala(
            "1.14.150", OtkazUstanovki.PROVERKA, 3, otkazMamy,
            IshodUstanovki.telefon("samsung", "SM-A525F"), tihaya = true,
        )
        assertTrue(s, s.contains("телефон: samsung SM-A525F"))
        assertTrue(s, s.contains("установка: тихая"))
        val sOknom = IshodUstanovki.strokaZhurnala("1.14.150", OtkazUstanovki.PROVERKA, 3, otkazMamy, "x", tihaya = false)
        assertTrue(sOknom, sOknom.contains("установка: с окном"))
    }

    @Test
    fun `старая строка журнала без телефона не меняется`() {
        assertEquals(
            "установка обновления не прошла: версия 1.14.116, причина PROVERKA, код 3, система: —",
            IshodUstanovki.strokaZhurnala("1.14.116", OtkazUstanovki.PROVERKA, 3, null),
        )
    }

    @Test
    fun `телефон без повтора производителя`() {
        assertEquals("OnePlus CPH2581", IshodUstanovki.telefon("OnePlus", "CPH2581"))
        assertEquals("HUAWEI ELE-L29", IshodUstanovki.telefon("HUAWEI", "HUAWEI ELE-L29"))
        assertEquals("Google", IshodUstanovki.telefon("Google", ""))
        assertEquals("—", IshodUstanovki.telefon(null, null))
    }

    @Test
    fun `номер версии из имени файла обновления`() {
        assertEquals(711151, IshodUstanovki.versiyaIzImeni(IshodUstanovki.imyaFayla(711151)))
        assertEquals(0, IshodUstanovki.versiyaIzImeni("update-711151.apk.123.part"))
        assertEquals(0, IshodUstanovki.versiyaIzImeni("chuzhoy.apk"))
    }
}
