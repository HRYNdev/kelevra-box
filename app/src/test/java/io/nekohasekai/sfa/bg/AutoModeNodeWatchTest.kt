package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.AutoMode.NodeCheck
import io.nekohasekai.sfa.bg.AutoModeNodeWatch.Death
import io.nekohasekai.sfa.bg.AutoModeNodeWatch.Return
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Очередь узлов и смена сети (журнал телефона 17.10.2026, 22:44 и 23:54): смена сети сама по себе
 * на запасной не переводит, смерть узла засчитывается только на устоявшейся сети и подтверждённо,
 * из дома выходим с первого узла, возврат на первый — после паузы, на мёртвый узел не встаём.
 */
class AutoModeNodeWatchTest {

    private val nl = "Нидерланды"
    private val fi = "Финляндия"

    /** Вайфай мигнул, телефон на секунды ушёл на мобильную — как 17.10.2026 в 22:44:52. */
    @Test
    fun `смена вайфая на мобильную с провалами проб в первые секунды не переводит на запасной`() {
        val w = AutoModeNodeWatch()
        val t0 = 1_000_000L
        w.reset(t0 - 3_600_000L)
        // 22:44:52.351 вайфай пропал, заход сразу: проба узла молчит (ENETUNREACH).
        w.networkChanged(t0)
        assertEquals(Death.Window, w.nodeDead(nl, t0 + 46, validated = false))
        // 22:44:52.561 мобильная появилась, ещё не проверена; 52.617 — новые резолверы.
        w.networkChanged(t0 + 210)
        w.networkChanged(t0 + 266)
        // 22:44:55.611 адрес узла не ответил (имя не резолвится, DnsResolver ETIMEDOUT).
        assertEquals(Death.Window, w.nodeDead(nl, t0 + 3_260, validated = false))
        // 22:44:55.985 система проверила мобильную — тоже событие о сети.
        w.networkChanged(t0 + 3_634)
        // 22:44:56.554 и 57.139: проба основного и снова провал, теперь на проверенной сети.
        assertEquals(Death.Window, w.nodeDead(nl, t0 + 4_203, validated = true))
        assertEquals(Death.Window, w.nodeDead(nl, t0 + 4_788, validated = true))
        // Отказы соединений в эти секунды — от самой смены сети, а не от узла.
        w.liveFailures(nl, t0 + 4_000)
        assertEquals(Death.Window, w.nodeDead(nl, t0 + 9_000, validated = true))
    }

    @Test
    fun `провалы окна не копятся и после окна смерть снова требует двух провалов`() {
        val w = AutoModeNodeWatch()
        w.reset(0L)
        w.networkChanged(100_000L)
        w.nodeDead(nl, 101_000L, validated = true)
        w.nodeDead(nl, 105_000L, validated = true)
        // Окно прошло: это первый провал, а не третий.
        assertEquals(Death.FirstMiss, w.nodeDead(nl, 111_000L, validated = true))
    }

    @Test
    fun `отказы соединений из окна смены сети смертью не засчитываются`() {
        val w = AutoModeNodeWatch()
        w.reset(0L)
        w.networkChanged(100_000L)
        w.liveFailures(nl, 103_000L)
        assertEquals(Death.FirstMiss, w.nodeDead(nl, 115_000L, validated = true))
    }

    @Test
    fun `настоящая смерть основного на рабочей сети - переход после второго провала`() {
        val w = AutoModeNodeWatch()
        w.reset(0L)
        val t = 600_000L
        // Плановый заход: порт узла молчит на сети, которая давно устоялась.
        assertEquals(Death.FirstMiss, w.nodeDead(nl, t, validated = true))
        // Побудка через секунду — ещё рано, один пик не довод.
        assertEquals(Death.FirstMiss, w.nodeDead(nl, t + 1_000, validated = true))
        // Следующий заход набора подтверждений (через 12 с) — смерть подтверждена.
        assertEquals(Death.Confirmed, w.nodeDead(nl, t + 12_000, validated = true))
        assertTrue(Death.Confirmed.confirmed)
    }

    @Test
    fun `смерть по живым соединениям на рабочей сети - сразу`() {
        val w = AutoModeNodeWatch()
        w.reset(0L)
        w.liveFailures(nl, 600_000L)
        val death = w.nodeDead(nl, 600_500L, validated = true)
        assertEquals(Death.ConfirmedByLive, death)
        assertTrue(death.confirmed)
    }

    @Test
    fun `отказы через другой узел или давние - не довод`() {
        val w = AutoModeNodeWatch()
        w.reset(0L)
        w.liveFailures(fi, 600_000L)
        assertEquals(Death.FirstMiss, w.nodeDead(nl, 600_500L, validated = true))
        val v = AutoModeNodeWatch()
        v.reset(0L)
        v.liveFailures(nl, 600_000L)
        assertEquals(Death.FirstMiss, v.nodeDead(nl, 600_000L + AutoModeNodeWatch.LIVE_FRESH_MILLIS + 1, validated = true))
    }

    @Test
    fun `удача между провалами начинает счёт сначала`() {
        val w = AutoModeNodeWatch()
        w.reset(0L)
        assertEquals(Death.FirstMiss, w.nodeDead(nl, 600_000L, validated = true))
        w.nodeAlive()
        assertEquals(Death.FirstMiss, w.nodeDead(nl, 612_000L, validated = true))
    }

    @Test
    fun `давний первый провал - не первый из двух`() {
        val w = AutoModeNodeWatch()
        w.reset(0L)
        assertEquals(Death.FirstMiss, w.nodeDead(nl, 600_000L, validated = true))
        assertEquals(Death.FirstMiss, w.nodeDead(nl, 600_000L + AutoModeNodeWatch.STALE_MILLIS + 1, validated = true))
    }

    @Test
    fun `непроверенная сеть устаивается позже`() {
        val w = AutoModeNodeWatch()
        w.reset(0L)
        w.networkChanged(100_000L)
        assertFalse(w.settled(115_000L, validated = false))
        assertTrue(w.settled(115_000L, validated = true))
        assertTrue(w.settled(130_000L, validated = false))
    }

    @Test
    fun `новый подъём автомата начинается с окна смены сети`() {
        val w = AutoModeNodeWatch()
        w.reset(500_000L)
        assertEquals(Death.Window, w.nodeDead(nl, 502_000L, validated = true))
    }

    @Test
    fun `дома очередь с первого узла, вне дома узел прежний`() {
        assertEquals(0, AutoMode.queueIndexAfter(AutoMode.Situation.Home, 1))
        assertEquals(1, AutoMode.queueIndexAfter(AutoMode.Situation.Main, 1))
        assertEquals(1, AutoMode.queueIndexAfter(AutoMode.Situation.Searching, 1))
        assertEquals(1, AutoMode.queueIndexAfter(AutoMode.Situation.NoNetwork, 1))
    }

    /** 23:54: из дома вышли на мобильную — стартуем с первого узла и в окне его не хороним. */
    @Test
    fun `дома и вышел - старт с первого узла, провалы окна не переводят`() {
        val w = AutoModeNodeWatch()
        w.reset(0L)
        // Вечером стояли на запасном, потом пришли домой: очередь с первого.
        assertEquals(0, AutoMode.queueIndexAfter(AutoMode.Situation.Home, 1))
        w.queueReset()
        val t0 = 3_000_000L
        w.networkChanged(t0) // 23:54:15.756 вайфай пропал
        assertEquals(Death.Window, w.nodeDead(nl, t0 + 89, validated = false))
        w.networkChanged(t0 + 610) // мобильная появилась
        w.networkChanged(t0 + 4_144) // проверена
        assertEquals(Death.Window, w.nodeDead(nl, t0 + 7_074, validated = true)) // 23:54:22.830
    }

    @Test
    fun `возврат на первый узел после паузы`() {
        val w = AutoModeNodeWatch()
        w.reset(0L)
        val t = 1_000_000L
        assertEquals(Return.Started, w.primaryAlive(t))
        assertEquals(Return.Waiting, w.primaryAlive(t + 60_000))
        assertEquals(Return.Due, w.primaryAlive(t + AutoModeNodeWatch.RETURN_AFTER_MILLIS))
        assertEquals(AutoModeNodeWatch.RETURN_AFTER_MILLIS, w.primaryOkFor(t + AutoModeNodeWatch.RETURN_AFTER_MILLIS))
    }

    @Test
    fun `возврат - мигнувший первый узел начинает паузу сначала`() {
        val w = AutoModeNodeWatch()
        w.reset(0L)
        assertEquals(Return.Started, w.primaryAlive(1_000_000L))
        w.primaryDead()
        assertEquals(Return.Started, w.primaryAlive(1_200_000L))
        assertEquals(Return.Waiting, w.primaryAlive(1_300_000L))
    }

    @Test
    fun `возврат - смена сети начинает паузу сначала`() {
        val w = AutoModeNodeWatch()
        w.reset(0L)
        assertEquals(Return.Started, w.primaryAlive(1_000_000L))
        w.networkChanged(1_100_000L)
        assertEquals(Return.Started, w.primaryAlive(1_250_000L))
    }

    @Test
    fun `после перехода счёт про прежний узел забыт`() {
        val w = AutoModeNodeWatch()
        w.reset(0L)
        w.liveFailures(nl, 600_000L)
        w.switched()
        assertEquals(Death.FirstMiss, w.nodeDead(nl, 600_500L, validated = true))
    }

    /** 23:54:24: опрос путей назвал «Финляндию» мёртвой — встать на неё было нельзя. */
    @Test
    fun `проба запасного мёртв в тот же заход - не вставать`() {
        assertFalse(AutoMode.mayStandOn(NodeCheck.Dead, measuredDeadRecently = false))
        assertFalse(AutoMode.mayStandOn(NodeCheck.Unmeasured, measuredDeadRecently = false))
        assertFalse(AutoMode.mayStandOn(NodeCheck.ByAddress, measuredDeadRecently = true))
        assertTrue(AutoMode.mayStandOn(NodeCheck.ByAddress, measuredDeadRecently = false))
        assertTrue(AutoMode.mayStandOn(NodeCheck.Live, measuredDeadRecently = true))
    }

    @Test
    fun `отказ конечного выхода относится к своему узлу`() {
        val dva = """
            {"outbounds":[
               {"tag":"Соединение","type":"selector","outbounds":["Нидерланды","Финляндия","Комната"]},
               {"tag":"Нидерланды","type":"urltest","outbounds":["Нидерланды · XHTTP"]},
               {"tag":"Финляндия","type":"urltest","outbounds":["Финляндия · XHTTP","Финляндия · Vision"]},
               {"tag":"Нидерланды · XHTTP","type":"vless","server":"nodekv.example","server_port":2083},
               {"tag":"Финляндия · XHTTP","type":"vless","server":"node2kv.example","server_port":2053},
               {"tag":"Финляндия · Vision","type":"vless","server":"node2kv.example","server_port":443},
               {"tag":"Комната","type":"socks","server":"127.0.0.1","server_port":8808}]}
        """.trimIndent()
        val l = AutoModeExits.parse(dva, 8808)
        assertEquals(nl, l.nodeOf("Нидерланды · XHTTP"))
        assertEquals(fi, l.nodeOf("Финляндия · Vision"))
        assertEquals(fi, l.nodeOf(fi))
        assertNull(l.nodeOf("direct"))
    }
}
