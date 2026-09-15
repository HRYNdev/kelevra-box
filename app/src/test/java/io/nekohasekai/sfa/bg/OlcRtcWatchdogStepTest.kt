package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.AutoMode.Situation
import io.nekohasekai.sfa.bg.OlcRtcWatchdog.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Решение хода присмотра за комнатой: когда поднимать снова после неудачного подъёма.
 *
 * Беда, ради которой это написано: подъём не удался, ядро осталось в отказе, и присмотр
 * дальше пропускал ход за ходом — ни повтора, ни «сдался». Нога комнаты возвращалась, а
 * телефон не пытался войти, пока приложение не перезапустят.
 */
class OlcRtcWatchdogStepTest {

    private val failed = OlcRtcCore.State.Failed("OpenStream failed: timeout")

    private fun step(
        state: OlcRtcCore.State = failed,
        needed: Boolean = true,
        elsewhere: Boolean = false,
        inARow: Int = 1,
        since: Long = 10 * 60_000L,
        permanent: Boolean = false,
    ) = OlcRtcWatchdog.nextStep(state, needed, elsewhere, inARow, since, permanent)

    // ------------------------------------------------------------ что не поднимаем

    @Test
    fun `нарочно погашенное ядро не поднимаем, даже если комната нужна и пауза вышла`() {
        // Idle ставят остановка сервиса, уход домой и «комната больше не нужна».
        assertEquals(Step.Wait, step(state = OlcRtcCore.State.Idle))
    }

    @Test
    fun `olcRTC нет в сборке — поднимать нечего`() {
        assertEquals(Step.Wait, step(state = OlcRtcCore.State.Unavailable))
    }

    @Test
    fun `комната не нужна — после отказа не поднимаем`() {
        assertEquals(Step.Wait, step(needed = false))
    }

    @Test
    fun `сервис сам поднимает комнату — второй подъём поверх не запускаем`() {
        assertEquals(Step.Wait, step(elsewhere = true))
        assertEquals(Step.Wait, step(state = OlcRtcCore.State.Starting))
    }

    @Test
    fun `стоящее ядро спрашиваем всегда, как раньше`() {
        // По этой пробе живёт вердикт автомата о комнате — в том числе при пробном подъёме,
        // когда обстановка ещё не «комната».
        assertEquals(Step.Probe, step(state = OlcRtcCore.State.Ready))
        assertEquals(Step.Probe, step(state = OlcRtcCore.State.Ready, needed = false))
    }

    // ------------------------------------------------------------ пауза

    @Test
    fun `после отказа поднимаем снова, когда вышла пауза по номеру попытки`() {
        val pause = OlcRtcWatchdog.pauseBeforeRaise(3)
        assertEquals(Step.Wait, step(inARow = 2, since = pause - 1))
        assertEquals(Step.Raise, step(inARow = 2, since = pause))
    }

    @Test
    fun `паузы растут, а после потолка частых попыток идут редкие, но не прекращаются`() {
        val pauses = (1..OlcRtcWatchdog.MAX_RESTARTS + 3).map(OlcRtcWatchdog::pauseBeforeRaise)
        assertEquals(pauses.sorted(), pauses)
        for (attempt in OlcRtcWatchdog.MAX_RESTARTS + 1..OlcRtcWatchdog.MAX_RESTARTS + 50) {
            assertEquals(OlcRtcWatchdog.RARE_RETRY_MILLIS, OlcRtcWatchdog.pauseBeforeRaise(attempt))
        }
        assertEquals(Step.Raise, step(inARow = 40, since = OlcRtcWatchdog.RARE_RETRY_MILLIS))
        assertTrue("редкие попытки — порядка двух минут", OlcRtcWatchdog.RARE_RETRY_MILLIS in 60_000L..180_000L)
    }

    @Test
    fun `отвергнутый токен частыми попытками не долбим`() {
        assertEquals(Step.Wait, step(inARow = 0, since = OlcRtcWatchdog.pauseBeforeRaise(1), permanent = true))
        assertEquals(Step.Raise, step(inARow = 0, since = OlcRtcWatchdog.RARE_RETRY_MILLIS, permanent = true))
    }

    // ------------------------------------------------------------ сценарий целиком

    /**
     * Нога недоступна [downMillis], потом возвращается. Ход присмотра — раз в пять секунд,
     * подъём занимает [raiseMillis]. Возвращает момент, когда комната встала, и сколько было
     * подъёмов.
     */
    private fun legComesBack(downMillis: Long, raiseMillis: Long = 5_000L, limitMillis: Long = 60 * 60_000L): Pair<Long?, Int> {
        var now = 0L
        var state: OlcRtcCore.State = failed
        var inARow = 1
        var raiseEndedAt = 0L
        var raises = 0
        while (now < limitMillis) {
            now += OlcRtcWatchdog.CHECK_INTERVAL_MILLIS
            when (step(state = state, inARow = inARow, since = now - raiseEndedAt)) {
                Step.Probe -> return now to raises
                Step.Wait -> Unit
                Step.Raise -> {
                    raises++
                    inARow++
                    now += raiseMillis
                    raiseEndedAt = now
                    state = if (now >= downMillis) OlcRtcCore.State.Ready else failed
                }
            }
        }
        return null to raises
    }

    @Test
    fun `подъём не удался пять раз, нога вернулась через четыре минуты — комната встала сама`() {
        val (upAt, raises) = legComesBack(downMillis = 4 * 60_000L)
        assertTrue("комната так и не встала", upAt != null)
        assertTrue(
            "после возвращения ноги подъём обязан случиться не позже редкой попытки: встала на $upAt мс",
            upAt!! <= 4 * 60_000L + OlcRtcWatchdog.RARE_RETRY_MILLIS + 2 * OlcRtcWatchdog.CHECK_INTERVAL_MILLIS + 5_000L,
        )
        assertTrue("за четыре минуты подъёмов было подозрительно много: $raises", raises <= OlcRtcWatchdog.MAX_RESTARTS + 3)
    }

    @Test
    fun `нога лежит полчаса — попытки редкие и не прекращаются`() {
        val (upAt, raises) = legComesBack(downMillis = 30 * 60_000L)
        assertTrue("комната так и не встала после возвращения ноги", upAt != null)
        // Полчаса по две минуты — около пятнадцати попыток, а не сотни.
        assertTrue("попыток $raises", raises in 10..25)
    }

    // ------------------------------------------------------------ нужна ли комната

    @Test
    fun `комната нужна, пока автомат стоит на ней или ищет путь`() {
        assertTrue(AutoMode.roomNeededIn(auto = true, situation = Situation.Room, manualRoom = false))
        assertTrue(AutoMode.roomNeededIn(auto = true, situation = Situation.Searching, manualRoom = false))
        for (situation in listOf(Situation.Main, Situation.Home, Situation.NoNetwork, Situation.Unknown)) {
            assertFalse("$situation", AutoMode.roomNeededIn(auto = true, situation = situation, manualRoom = false))
        }
    }

    @Test
    fun `при ручном выборе комната нужна, только если выбрана она`() {
        assertTrue(AutoMode.roomNeededIn(auto = false, situation = Situation.Unknown, manualRoom = true))
        assertFalse(AutoMode.roomNeededIn(auto = false, situation = Situation.Unknown, manualRoom = false))
    }
}
