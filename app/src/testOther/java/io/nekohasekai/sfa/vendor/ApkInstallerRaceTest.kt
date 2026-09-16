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
    fun `стоп опаздывает почти до нового потолка в 100 тиков и флаг всё равно возвращается верно`() {
        // Окно расширено до 100 тиков (10 с) — приёмка потребовала конечный потолок вместо
        // безлимитного ожидания (см. отказ по прошлой версии правки). 99-й тик — внутри
        // нового окна, значит подтверждённое снятие обязано быть учтено, а не потеряно.
        val itog = stend(tikStopa = 99, byloVklyucheno = true)
        assertTrue("подтверждённое снятие внутри 10-секундного окна обязано вернуть true", itog)
    }

    @Test
    fun `человек выключил автозапуск пока служба ещё работала — возврат не включает его обратно`() {
        // Флаг был false ДО остановки (человек снял тумблер в настройках, VPN ещё горел).
        // Возврат обязан вернуть именно false, а не жёсткую true.
        val itog = stend(tikStopa = 10, byloVklyucheno = false)
        assertTrue("false до остановки должно остаться false после", !itog)
    }

    @Test
    fun `стоп упал и флаг не снимается никогда — возврат обязан завершиться за конечное время`() {
        // BoxService.stop() рухнул посреди остановки и так и не дописал false. Ожидание
        // без потолка виснет здесь навсегда (тик растёт бесконечно, flag не меняется) —
        // именно это и было ценой прошлой правки, отклонённой приёмкой. Потолок обязан
        // оборвать ожидание за конечное число шагов и вернуть byloVklyucheno.
        var flag = true
        var tikov = 0
        var itog: Boolean? = null
        runBlocking {
            ApkInstaller.vernutPriznakZapuska(
                byloVklyucheno = true,
                poluchitFlag = { flag },
                postavitFlag = { itog = it },
                zhdatShag = { tikov++ },
            )
        }
        assertTrue("возврат обязан произойти, а не повиснуть", itog != null)
        assertTrue("byloVklyucheno должно вернуться за конечное число попыток", tikov in 1..1000)
        assertTrue("если стоп так и не снял флаг, byloVklyucheno не подделывается", itog == true)
    }
}
