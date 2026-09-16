package io.nekohasekai.sfa.vendor

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Гонка `vernutPriznakZapuska()` (наряд 0916-021102, кромка «стоп опаздывает»).
 *
 * `BoxService.stop()` снимает `Settings.startedByUser` в самом конце своей асинхронной
 * остановки — независимо от того, дождался ли её `ApkInstaller`. Старая версия ждала
 * снятия максимум 30×100мс и ПОСЛЕ ЭТОГО ставила флаг вслепую, не проверяя, снялся ли
 * он на самом деле. Если стоп опаздывал (тяжёлая очистка: `Zapisi.stop()` дважды,
 * `CoreLog.stop()`, `stopOlcRtc()`), его запоздавшая запись `false` приходила уже ПОСЛЕ
 * нашей `true` — и телефон после самообновления по ROOT/SHIZUKU оставался без VPN.
 *
 * Тест моделирует именно этот порядок: стоп «срабатывает» на шаге позже старого окна
 * ожидания, а не в его пределах.
 */
class ApkInstallerRaceTest {

    /** Тики `zhdatShag`, на котором стоп (в реальности — независимая корутина) снимает флаг. */
    private fun stend(
        tikStopa: Int,
        byloVklyucheno: Boolean,
    ): Boolean {
        var flag = true
        var tik = 0
        runBlocking {
            ApkInstaller.vernutPriznakZapuska(
                byloVklyucheno = byloVklyucheno,
                poluchitFlag = { flag },
                postavitFlag = { flag = it },
                zhdatShag = {
                    tik++
                    if (tik == tikStopa) flag = false
                },
            )
        }
        // Стоп в `BoxService` — независимая корутина: она идёт к своему тику сама по
        // себе, даже если `vernutPriznakZapuska` уже вернулась раньше срока (это и есть
        // старая беда — возврат считал себя готовым, не дождавшись реального снятия).
        while (tik < tikStopa) {
            tik++
            if (tik == tikStopa) flag = false
        }
        return flag
    }

    @Test
    fun `стоп опаздывает за старое окно в 30 тиков и флаг всё равно возвращается верно`() {
        // Раньше окно было ровно 30 тиков (3 с). Стоп снимает флаг на 45-м — позже
        // старого предела, значит старый код такой порядок не переживал бы.
        val itog = stend(tikStopa = 45, byloVklyucheno = true)
        assertTrue("после подтверждённого снятия флаг обязан вернуться в true", itog)
    }

    @Test
    fun `стоп опаздывает сильно за старое окно и результат не зависит от того как долго ждать`() {
        val itog = stend(tikStopa = 500, byloVklyucheno = true)
        assertTrue(itog)
    }

    @Test
    fun `человек выключил автозапуск пока служба ещё работала — возврат не включает его обратно`() {
        // Флаг был false ДО остановки (человек снял тумблер в настройках, VPN ещё горел).
        // Возврат обязан вернуть именно false, а не жёсткую true.
        val itog = stend(tikStopa = 10, byloVklyucheno = false)
        assertTrue("false до остановки должно остаться false после", !itog)
    }
}
