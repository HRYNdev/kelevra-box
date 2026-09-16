package io.nekohasekai.sfa.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelFactsReloadTest {

    private fun deferred(roomLive: Boolean = false, roomBusy: Boolean = false, byHuman: Boolean = false) =
        TunnelFacts.reloadDeferred(roomLive, roomBusy, byHuman)

    @Test
    fun `подписка принесла новый конфиг посреди звонка — ядро не пересобираем`() {
        assertTrue(deferred(roomLive = true))
    }

    @Test
    fun `комнату поднимают прямо сейчас — перечитывание тоже ждёт`() {
        assertTrue(deferred(roomBusy = true))
    }

    @Test
    fun `комнаты нет — перечитывание идёт сразу, как раньше`() {
        assertFalse(deferred())
    }

    @Test
    fun `человек нажал «Перезапустить» при живой комнате — применяем сейчас`() {
        assertFalse(deferred(roomLive = true, byHuman = true))
        assertFalse(deferred(roomBusy = true, byHuman = true))
    }

    @Test
    fun `отметка человека свежая — это его просьба`() {
        assertTrue(TunnelFacts.reloadByHuman(askedAt = 1_000, now = 1_500, windowMillis = 10_000))
        assertTrue(TunnelFacts.reloadByHuman(askedAt = 1_000, now = 11_000, windowMillis = 10_000))
    }

    @Test
    fun `отметки не было — перечитывание от расписания`() {
        assertFalse(TunnelFacts.reloadByHuman(askedAt = 0, now = 5_000, windowMillis = 10_000))
    }

    @Test
    fun `забытая отметка не пропускает перечитывание по расписанию в звонок`() {
        // Человек нажал при выключенном сервисе, вызов не дошёл; через минуту пришло расписание.
        assertFalse(TunnelFacts.reloadByHuman(askedAt = 1_000, now = 61_000, windowMillis = 10_000))
    }

    @Test
    fun `отметка из прошлой загрузки телефона — не человек`() {
        // elapsedRealtime сбрасывается при перезагрузке: отметка оказалась бы «в будущем».
        assertFalse(TunnelFacts.reloadByHuman(askedAt = 50_000, now = 2_000, windowMillis = 10_000))
    }

    @Test
    fun `отложенный конфиг — первая безопасная пересборка его подхватывает`() {
        // Комната не встала или вход ещё не начался: остальное ядро уже как надо, но профиль прежний.
        assertTrue(
            TunnelFacts.coreConfigStale(
                coreRoom = true,
                coreSelfOutside = true,
                wantRoom = true,
                wantSelfOutside = true,
                roomBusy = false,
                profileOutdated = true,
            ),
        )
    }

    @Test
    fun `отложенный конфиг посреди входа в комнату ядро не трогает`() {
        assertFalse(
            TunnelFacts.coreConfigStale(
                coreRoom = true,
                coreSelfOutside = true,
                wantRoom = true,
                wantSelfOutside = true,
                roomBusy = true,
                profileOutdated = true,
            ),
        )
    }
}
