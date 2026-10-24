package io.nekohasekai.sfa.bg.pult

import io.nekohasekai.sfa.bg.AutoMode
import io.nekohasekai.sfa.bg.AutoModeGate
import io.nekohasekai.sfa.bg.CoreLog
import io.nekohasekai.sfa.bg.LogUploadWork
import io.nekohasekai.sfa.bg.OlcRtcParams
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.update.IshodUstanovki
import io.nekohasekai.sfa.update.ObnovleniyaPult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Настройки пульта доходят до мест в коде, а без пульта всё — прежние константы.
 */
class PultPodklyuchenieTest {
    @After
    fun sbros() = Pult.dlyaTesta(PultSostoyanie())

    private fun pult(vararg pary: Pair<String, Any>) = Pult.dlyaTesta(PultSostoyanie(nastroyki = mapOf(*pary)))

    @Test
    fun `без пульта — значения из кода`() {
        Pult.dlyaTesta(PultSostoyanie())
        assertEquals(AutoMode.CONFIRMATIONS, AutoMode.podtverzhdeniy())
        assertEquals(AutoMode.ROOM_TRIAL_AFTER, AutoMode.roomTrialAfter())
        assertEquals(AutoMode.SILENCE_HOLD_MILLIS, AutoMode.silenceHoldMillis())
        assertEquals(AutoMode.RETURN_AFTER_MILLIS, AutoMode.returnAfterMillis())
        assertEquals(CoreLog.UZEL_POROG, CoreLog.uzelPorog())
        assertEquals(60L, LogUploadWork.periodMinut())
        assertEquals(45, UpdateProfileWork.intervalMinut(45))
        assertTrue(OlcRtcParams.komnataRazreshenaPultom)
        assertFalse(ObnovleniyaPult.tiho(false))
        assertTrue(ObnovleniyaPult.tiho(true))
        assertEquals(IshodUstanovki.NAPOMINANIE_MINUT, ObnovleniyaPult.napominanieMinut())
        assertTrue(Pult.bool("funkcii.avtorezhim"))
        assertEquals("obychnyy", Pult.vybor("obnovleniya.kanal"))
        assertFalse(Pult.bool("kanal.dolgiy_opros"))
    }

    @Test
    fun `значения пульта доходят до автомата, узлов, журнала, профиля, обновлений`() {
        pult(
            "avtomat.podtverzhdeniy" to 5,
            "avtomat.tishina_rezolvera_min" to 7,
            "uzel.vozvrat_na_pervyy_min" to 10,
            "uzel.porog_otkazov" to 6,
            "zhurnal.otpravka_min" to 120,
            "opros.info_min" to 15,
            "funkcii.komnata" to false,
            "obnovleniya.tiho" to true,
            "obnovleniya.napominat_chas" to 6,
        )
        assertEquals(5, AutoMode.podtverzhdeniy())
        assertEquals(4, AutoMode.roomTrialAfter())
        assertEquals(7 * 60_000L, AutoMode.silenceHoldMillis())
        assertEquals(10 * 60_000L, AutoMode.returnAfterMillis())
        assertEquals(6, CoreLog.uzelPorog())
        assertEquals(120L, LogUploadWork.periodMinut())
        assertEquals(15, UpdateProfileWork.intervalMinut(45))
        assertFalse(OlcRtcParams.komnataRazreshenaPultom)
        assertTrue(ObnovleniyaPult.tiho(false))
        assertEquals(360L, ObnovleniyaPult.napominanieMinut())
    }

    @Test
    fun `тихая установка пультом выключается поверх настройки`() {
        pult("obnovleniya.tiho" to false)
        assertFalse(ObnovleniyaPult.tiho(true))
    }

    @Test
    fun `одно подтверждение не уводит пробный подъём комнаты в ноль`() {
        pult("avtomat.podtverzhdeniy" to 1)
        assertEquals(1, AutoMode.roomTrialAfter())
    }

    @Test
    fun `задвижка читает число подтверждений на каждом наблюдении`() {
        var n = 3
        val gate = AutoModeGate { n }
        assertFalse(gate.offer(AutoMode.Situation.Main)) // 1 из 3
        n = 2
        assertTrue(gate.offer(AutoMode.Situation.Main)) // 2 из 2 — пульт поменял число на ходу
        assertTrue(AutoModeGate { 1 }.offer(AutoMode.Situation.Main))
    }

    @Test
    fun `частая отправка журнала до срока и не после`() {
        assertTrue(LogUploadWork.chastoSeychas(2_000L, 1_000L))
        assertFalse(LogUploadWork.chastoSeychas(2_000L, 2_000L))
        assertFalse(LogUploadWork.chastoSeychas(null, 1_000L))
        pult("zhurnal.chasto_do" to "2026-10-22T12:00:00Z")
        assertEquals(1_792_670_400_000L, Pult.vremya("zhurnal.chasto_do"))
    }

    @Test
    fun `заголовок X-Pult в заголовках устройства`() {
        Pult.dlyaTesta(
            PultSostoyanie(
                versiya = 42,
                komandy = mapOf("k17" to PultSostoyanie.Kvit("ok", 1L)),
                oshibkiKlyuchey = listOf("opros.info_min"),
            ),
        )
        val h = io.nekohasekai.sfa.Kelevra.pultHeader()
        val kod = io.nekohasekai.sfa.BuildConfig.VERSION_CODE
        assertEquals("X-Pult" to "v=1;umeet=1;versiya=42;kod=$kod;kvit=k17:ok;oshibki=opros.info_min", h)
    }

    @Test
    fun `пульс устройства — общие с Desktop ключи, затем свои`() {
        val s = AutoMode.pulsStroka(
            avto = "vne",
            tunnel = true,
            set = true,
            perehodUnix = 1_761_048_000L,
            uzel = "Нидерланды 1",
            obstanovka = "main",
            link = AutoMode.Link.Alive,
        )
        assertEquals(
            "avto=vne;tunnel=1;set=1;perehod=1761048000;" +
                "uzel=%D0%9D%D0%B8%D0%B4%D0%B5%D1%80%D0%BB%D0%B0%D0%BD%D0%B4%D1%8B+1;obst=main;zhiv=1",
            s,
        )
        assertTrue(s.all { it.code in 33..126 })
        assertEquals(
            "avto=stoit;tunnel=0;set=0;perehod=0;obst=unknown;zhiv=?",
            AutoMode.pulsStroka("stoit", false, false, 0L, null, "unknown", AutoMode.Link.Unknown),
        )
    }

    @Test
    fun `долгий опрос — что делать по коду выдачи`() {
        assertTrue(PultSvyaz.shagPoKodu(200, null, 3) is PultSvyaz.Shag.Info)
        assertTrue(PultSvyaz.shagPoKodu(204, null, 3) is PultSvyaz.Shag.Snova)
        assertEquals(PultSvyaz.Shag.Pauza(10 * 60_000L, false), PultSvyaz.shagPoKodu(403, null, 0))
        assertEquals(PultSvyaz.Shag.Pauza(60_000L, false), PultSvyaz.shagPoKodu(429, 60, 0))
        assertEquals(PultSvyaz.Shag.Pauza(60_000L, false), PultSvyaz.shagPoKodu(429, null, 0))
        assertEquals(PultSvyaz.Shag.Pauza(30 * 60_000L, false), PultSvyaz.shagPoKodu(404, null, 0))
        assertEquals(PultSvyaz.Shag.Pauza(30_000L, true), PultSvyaz.shagPoKodu(-1, null, 0))
        assertEquals(PultSvyaz.Shag.Pauza(60_000L, true), PultSvyaz.shagPoKodu(502, null, 1))
    }

    @Test
    fun `опрос из службы по интервалу и пауза долгого опроса растёт до потолка`() {
        assertFalse(PultSvyaz.poraOprosa(nowMs = 10 * 60_000L, proshlyyMs = 0L, intervalMin = 15))
        assertTrue(PultSvyaz.poraOprosa(nowMs = 15 * 60_000L, proshlyyMs = 0L, intervalMin = 15))
        assertEquals(30_000L, PultSvyaz.pauza(1))
        assertEquals(60_000L, PultSvyaz.pauza(2))
        assertEquals(15 * 60_000L, PultSvyaz.pauza(50))
    }

    @Test
    fun `опрос службы считает от удачной сводки, а неудача повторяется не раньше пяти минут`() {
        val min = 60_000L
        // свежий процесс или служба: отсчёт с нуля — первый же круг опрашивает
        assertTrue(PultSvyaz.poraOprosa(nowMs = 100 * min, proshlyyMs = 0L, intervalMin = 60))
        // сводка только что пришла — ждём интервал
        assertFalse(PultSvyaz.poraOprosa(nowMs = 100 * min, proshlyyMs = 99 * min, intervalMin = 60))
        // интервал вышел, но пробовали минуту назад (сбой) — ещё рано
        assertFalse(PultSvyaz.poraOprosa(nowMs = 200 * min, proshlyyMs = 0L, intervalMin = 60, popytkaMs = 199 * min))
        // через пять минут после сбоя — повторяем
        assertTrue(PultSvyaz.poraOprosa(nowMs = 200 * min, proshlyyMs = 0L, intervalMin = 60, popytkaMs = 195 * min))
    }

    @Test
    fun `по поводу — передний план и смена сети — опрос не чаще раза в минуту`() {
        assertTrue(PultSvyaz.mozhnoPoPovodu(nowMs = 1_000_000L, popytkaMs = 0L))
        assertFalse(PultSvyaz.mozhnoPoPovodu(nowMs = 1_000_000L, popytkaMs = 1_000_000L - 59_999L))
        assertTrue(PultSvyaz.mozhnoPoPovodu(nowMs = 1_000_000L, popytkaMs = 1_000_000L - 60_000L))
    }

    @Test
    fun `сеть не дома под подсказкой белого списка — повод спросить пульт один раз на сеть`() {
        val wl = io.nekohasekai.sfa.bg.NetworkMode.Whitelist
        assertTrue(AutoMode.pultPoBelomuSpisku(null, "lte-1", home = false, hint = wl))
        // эту сеть уже опрашивали
        assertFalse(AutoMode.pultPoBelomuSpisku("lte-1", "lte-1", home = false, hint = wl))
        // другая сеть под белым списком — снова повод
        assertTrue(AutoMode.pultPoBelomuSpisku("lte-1", "lte-2", home = false, hint = wl))
        // дома или без подсказки — не повод
        assertFalse(AutoMode.pultPoBelomuSpisku(null, "wifi", home = true, hint = wl))
        assertFalse(AutoMode.pultPoBelomuSpisku(null, "lte-1", home = false, hint = io.nekohasekai.sfa.bg.NetworkMode.Unknown))
        assertFalse(AutoMode.pultPoBelomuSpisku(null, null, home = false, hint = wl))
    }

    @Test
    fun `выключатель подсистемы — ключ vykl`() {
        assertFalse(Pult.vykl("lifeline"))
        pult("vykl.lifeline" to true)
        assertTrue(Pult.vykl("lifeline"))
    }

    @Test
    fun `адреса определителя режима сети и белый список — с пульта поверх вшитых`() {
        val vshitye = io.nekohasekai.sfa.bg.NetworkModeDetector.Targets.DEFAULT
        assertTrue(io.nekohasekai.sfa.bg.NetworkModeDetector.Targets.izPulta() === vshitye)
        pult(
            "rezhim_seti.neraz_adresa" to listOf("9.9.9.9"),
            "belyj_spisok.domeny" to listOf("gosuslugi.ru"),
            "belyj_spisok.podseti" to listOf("10.0.0.0/8"),
        )
        val t = io.nekohasekai.sfa.bg.NetworkModeDetector.Targets.izPulta()
        assertEquals(listOf("9.9.9.9"), t.unlisted.literals)
        assertEquals(vshitye.allowed, t.allowed)
        assertEquals(listOf("gosuslugi.ru"), io.nekohasekai.sfa.bg.BelyjSpisok.domeny())
        assertEquals(listOf("10.0.0.0/8"), io.nekohasekai.sfa.bg.BelyjSpisok.podseti())
    }
}
