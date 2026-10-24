package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.AutoMode.Knock
import io.nekohasekai.sfa.bg.pult.Pult
import io.nekohasekai.sfa.bg.pult.PultReestr
import io.nekohasekai.sfa.bg.pult.PultSostoyanie
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException

/**
 * Быстрее до комнаты WB под белым списком (24.10.2026, МегаФон): Wi-Fi выключили — звонок WB
 * встал через 92–95 с, из них ~85 с автомат ждал окна смены сети и второго провала. Четыре
 * правила, каждое здесь проверено отдельно, и рядом — то, на чём они не должны срабатывать
 * (дома, обычная сотовая, лежащий VPS):
 *  1. мгновенный отказ (RST) узла основного — повод сверить режим сети сейчас, а отказ всех
 *     неразрешённых при живом контроле — белый список;
 *  2. при свежей подсказке «белый список» комната (сначала WB) — с первого провала;
 *  3. при подсказке окно смены сети провал не прячет;
 *  4. пороги и выключатели — на пульте.
 */
class AutoModeWbBystreeTest {

    @After
    fun sbros() = Pult.dlyaTesta(PultSostoyanie())

    private fun pult(vararg pary: Pair<String, Any>) = Pult.dlyaTesta(PultSostoyanie(nastroyki = mapOf(*pary)))

    private fun report(
        mode: NetworkMode,
        atMillis: Long,
        tcpUnlisted: ProbeOutcome = ProbeOutcome.Skipped,
        tcpAllowed: ProbeOutcome = ProbeOutcome.Skipped,
    ) = NetworkModeReport(
        mode = mode,
        signals = NetworkSignals(physicalNetwork = true, tcpUnlisted = tcpUnlisted, tcpAllowed = tcpAllowed),
        atMillis = atMillis,
        tookMillis = 0,
        note = "",
    )

    // ------------------------------------------------ 1. мгновенный отказ узла

    @Test
    fun `RST за десятки миллисекунд — мгновенный отказ`() {
        val outcome = ProbeFailure.onConnect(ConnectException("failed to connect: ECONNREFUSED (Connection refused)"))
        assertEquals(Knock.Refused, AutoMode.refusalKnock(outcome, tookMillis = 0))
        assertEquals(Knock.Refused, AutoMode.refusalKnock(outcome, tookMillis = 80))
    }

    @Test
    fun `тишина до таймаута (MOTIV) и долгий отказ мгновенными не считаются`() {
        assertEquals(Knock.Other, AutoMode.refusalKnock(ProbeOutcome.Silence, tookMillis = 4_000))
        assertEquals(Knock.Other, AutoMode.refusalKnock(ProbeOutcome.Refused, tookMillis = 3_000))
        assertEquals(Knock.Other, AutoMode.refusalKnock(ProbeOutcome.Unreachable, tookMillis = 10))
        assertEquals(Knock.Other, AutoMode.refusalKnock(ProbeOutcome.Failed, tookMillis = 10))
    }

    @Test
    fun `порог мгновенного отказа — с пульта`() {
        assertEquals(Knock.Refused, AutoMode.refusalKnock(ProbeOutcome.Refused, tookMillis = 1_200))
        pult("avtomat.otkaz_mgnovennyy_millis" to 500)
        assertEquals(Knock.Other, AutoMode.refusalKnock(ProbeOutcome.Refused, tookMillis = 1_200))
        assertEquals(Knock.Refused, AutoMode.refusalKnock(ProbeOutcome.Refused, tookMillis = 300))
    }

    @Test
    fun `отказ узла засчитывается, только если отказали все адреса`() {
        assertEquals(Knock.Refused, AutoMode.knockVerdict(listOf(Knock.Refused)))
        assertEquals(Knock.Refused, AutoMode.knockVerdict(listOf(Knock.Refused, Knock.Refused)))
        assertEquals(Knock.Other, AutoMode.knockVerdict(listOf(Knock.Refused, Knock.Other)))
        assertEquals(Knock.Other, AutoMode.knockVerdict(emptyList()))
        // Принял хоть один — узел жив, как и раньше.
        assertEquals(Knock.Open, AutoMode.knockVerdict(listOf(Knock.Refused, Knock.Open)))
    }

    @Test
    fun `отказ узла — замер режима сейчас, если про эту сеть ещё не мерили`() {
        assertTrue(AutoMode.refusalAsksDetector(cached = null, nowWallMillis = 1_000_000L))
    }

    @Test
    fun `свежий белый список не перемеряем`() {
        val now = 1_000_000L
        val fresh = report(NetworkMode.Whitelist, atMillis = now - 30_000L, tcpUnlisted = ProbeOutcome.Refused, tcpAllowed = ProbeOutcome.Ok)
        assertFalse(AutoMode.refusalAsksDetector(fresh, now))
    }

    @Test
    fun `отказ всех без контроля (сеть настраивалась) переспрашиваем через 10 с, не через 5 мин`() {
        val now = 1_000_000L
        val early = report(NetworkMode.Unknown, atMillis = now - 3_000L, tcpUnlisted = ProbeOutcome.Refused, tcpAllowed = ProbeOutcome.Silence)
        assertFalse(AutoMode.refusalAsksDetector(early, now))
        val later = early.copy(atMillis = now - 11_000L)
        assertTrue(AutoMode.refusalAsksDetector(later, now))
        pult("avtomat.otkaz_perezamer_millis" to 20_000)
        assertFalse(AutoMode.refusalAsksDetector(later, now))
    }

    @Test
    fun `лежит один наш VPS — резолверы ответили, дорогой замер не повторяем весь срок подсказки`() {
        val now = 10_000_000L
        val vpsDown = report(NetworkMode.Normal, atMillis = now - 60_000L, tcpUnlisted = ProbeOutcome.Ok)
        assertFalse(AutoMode.refusalAsksDetector(vpsDown, now))
        assertTrue(AutoMode.refusalAsksDetector(vpsDown.copy(atMillis = now - AutoMode.HINT_TTL_MILLIS), now))
    }

    @Test
    fun `отказ всех неразрешённых при живом российском контроле — белый список`() {
        val s = NetworkSignals(physicalNetwork = true, tcpUnlisted = ProbeOutcome.Refused, tcpAllowed = ProbeOutcome.Ok)
        assertTrue(NetworkModeDecision.refusedWhitelist(s))
        assertEquals(NetworkMode.Whitelist, NetworkModeDecision.decide(s))
    }

    @Test
    fun `отказ всех, а российские тоже молчат — не белый список`() {
        for (control in listOf(ProbeOutcome.Silence, ProbeOutcome.Refused, ProbeOutcome.Failed, ProbeOutcome.Skipped)) {
            val s = NetworkSignals(physicalNetwork = true, tcpUnlisted = ProbeOutcome.Refused, tcpAllowed = control)
            assertEquals("контроль $control", NetworkMode.Unknown, NetworkModeDecision.decide(s))
        }
    }

    @Test
    fun `выключатель отказа возвращает старое «отказ — не подпись»`() {
        pult("vykl.otkaz_kak_belyy" to true)
        val s = NetworkSignals(physicalNetwork = true, tcpUnlisted = ProbeOutcome.Refused, tcpAllowed = ProbeOutcome.Ok)
        assertFalse(NetworkModeDecision.refusedWhitelist(s))
        assertEquals(NetworkMode.Unknown, NetworkModeDecision.decide(s))
    }

    @Test
    fun `белый список по отказу опровергается открытым портом узла`() {
        // Под белым списком с RST отказывают всем — принятое соединение с узлом его опровергает.
        val r = report(NetworkMode.Whitelist, atMillis = 0L, tcpUnlisted = ProbeOutcome.Refused, tcpAllowed = ProbeOutcome.Ok)
        assertFalse(NetworkModeDecision.survivesOpenPort(r))
    }

    @Test
    fun `обычная сотовая и лежащий VPS — неразрешённые достижимы — белого списка нет`() {
        // Узел отказал (VPS лежит), но 8.8.8.8 принял: определитель идёт дальше, к канарейке.
        val s = NetworkSignals(
            physicalNetwork = true,
            tcpUnlisted = ProbeOutcome.Ok,
            tlsCanary = ProbeOutcome.Ok,
            bulkCanary = ProbeOutcome.Ok,
        )
        assertNotEquals(NetworkMode.Whitelist, NetworkModeDecision.decide(s))
        // Отказали не все — детектор отдаёт «недостижимо», а это не белый список.
        assertNotEquals(
            NetworkMode.Whitelist,
            NetworkModeDecision.decide(NetworkSignals(physicalNetwork = true, tcpUnlisted = ProbeOutcome.Unreachable, tcpAllowed = ProbeOutcome.Ok)),
        )
    }

    // ------------------------------------------------ 2. WB с первого захода

    @Test
    fun `при подсказке белого списка комната — после первого провала, без неё — после второго`() {
        assertEquals(1, AutoMode.roomTrialAfter(whitelistHint = true))
        assertEquals(AutoMode.ROOM_TRIAL_AFTER, AutoMode.roomTrialAfter(whitelistHint = false))
    }

    @Test
    fun `порог при белом списке — с пульта`() {
        pult("avtomat.room_trial_after_belyy" to 2)
        assertEquals(2, AutoMode.roomTrialAfter(whitelistHint = true))
        assertEquals(AutoMode.ROOM_TRIAL_AFTER, AutoMode.roomTrialAfter(whitelistHint = false))
    }

    // ------------------------------------------------ 3. окно смены сети и подсказка

    @Test
    fun `подсказка белого списка засчитывает провал и в окне смены сети`() {
        assertTrue(AutoMode.failureCounts(settled = false, whitelistHint = true, off = false))
        assertTrue(AutoMode.failureCounts(settled = true, whitelistHint = false, off = false))
    }

    @Test
    fun `без подсказки окно смены сети провал по-прежнему прячет`() {
        // Дома и на обычной сотовой вайфай моргнул — комнату не будим.
        assertFalse(AutoMode.failureCounts(settled = false, whitelistHint = false, off = false))
    }

    @Test
    fun `выключатель окна возвращает старое поведение`() {
        assertFalse(AutoMode.failureCounts(settled = false, whitelistHint = true, off = true))
        pult("vykl.belyy_bez_okna" to true)
        assertFalse(AutoMode.failureCounts(settled = false, whitelistHint = true))
    }

    // ------------------------------------------------ сквозной счёт по заходам

    @Test
    fun `МегаФон 24-10 - первый же заход после смены сети будит комнату WB`() {
        // Заход 1 (через секунду после выключения Wi-Fi): узел отказал мгновенно, замер режима
        // в этом же заходе — отказ всех неразрешённых, российский контроль отвечает.
        val hint = NetworkModeDecision.decide(
            NetworkSignals(physicalNetwork = true, tcpUnlisted = ProbeOutcome.Refused, tcpAllowed = ProbeOutcome.Ok),
        )
        val whitelist = hint == NetworkMode.Whitelist
        val failures = AutoMode.nextMainFailures(0, ok = false, settled = AutoMode.failureCounts(settled = false, whitelistHint = whitelist))
        assertTrue(failures >= AutoMode.roomTrialAfter(whitelist))
    }

    @Test
    fun `MOTIV без подсказки - как раньше, окно и второй провал`() {
        var failures = 0
        // Окно смены сети: тишина, подсказки ещё нет.
        failures = AutoMode.nextMainFailures(failures, ok = false, settled = AutoMode.failureCounts(settled = false, whitelistHint = false))
        assertFalse(failures >= AutoMode.roomTrialAfter(false))
        // Сеть устоялась, подсказки всё ещё нет.
        failures = AutoMode.nextMainFailures(failures, ok = false, settled = AutoMode.failureCounts(settled = true, whitelistHint = false))
        assertFalse(failures >= AutoMode.roomTrialAfter(false))
        // Пришла подсказка из фонового замера — следующий заход будит комнату.
        failures = AutoMode.nextMainFailures(failures, ok = false, settled = AutoMode.failureCounts(settled = true, whitelistHint = true))
        assertTrue(failures >= AutoMode.roomTrialAfter(true))
    }

    @Test
    fun `MOTIV - подсказка пришла ещё в окне, комната с первого засчитанного захода`() {
        val failures = AutoMode.nextMainFailures(0, ok = false, settled = AutoMode.failureCounts(settled = false, whitelistHint = true))
        assertTrue(failures >= AutoMode.roomTrialAfter(true))
    }

    // ------------------------------------------------ 4. пульт

    @Test
    fun `новые ключи в реестре с умолчаниями из кода`() {
        assertEquals(AutoMode.OTKAZ_MGNOVENNYY_MILLIS.toInt(), PultReestr.klyuch("avtomat.otkaz_mgnovennyy_millis")!!.umolch)
        assertEquals(AutoMode.OTKAZ_PEREZAMER_MILLIS.toInt(), PultReestr.klyuch("avtomat.otkaz_perezamer_millis")!!.umolch)
        assertEquals(AutoMode.ROOM_TRIAL_AFTER_WHITELIST, PultReestr.klyuch("avtomat.room_trial_after_belyy")!!.umolch)
        assertNotNull(PultReestr.klyuch("vykl.otkaz_kak_belyy"))
        assertNotNull(PultReestr.klyuch("vykl.belyy_bez_okna"))
    }
}
