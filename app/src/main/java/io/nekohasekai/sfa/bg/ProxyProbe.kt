package io.nekohasekai.sfa.bg

import android.util.Log
import io.nekohasekai.sfa.bg.path.HonestProbe

/**
 * Переходник к [HonestProbe] для старого вызова из автомата.
 *
 * Сама проба отсюда уехала: она была почти дословной копией пробы комнаты в `OlcRtcCore`,
 * и держать две штуки, которые расходятся при первой же правке, смысла нет. Здесь остался
 * только прежний вид вызова, чтобы автомат продолжал компилироваться, пока его не
 * перевели на пути целиком.
 *
 * Что поменялось для звонящего, даже если он не менялся сам:
 *  - **Таймаут поднят до [HonestProbe.MIN_TIMEOUT_MILLIS].** Автомат просил шесть секунд,
 *    а замер 07.08 под нагрузкой показал служебный отклик 2-9 секунд: на шести секундах
 *    проба объявляет смерть живому каналу. Меньше нижней границы теперь не берётся.
 *  - **Цель выбирает проба, а не звонящий.** Один и тот же адрес каждый заход — примета
 *    сама по себе, поэтому цели идут по кругу. Переданные host/port остались в подписи,
 *    но на выбор не влияют.
 */
object ProxyProbe {
    private const val TAG = "ProxyProbe"

    sealed interface Result {
        /** Запрос ушёл и ответ вернулся: канал несёт трафик. */
        data class Live(val latencyMs: Long) : Result

        /** Канал трафик не несёт. [reason] — словами, для лога. */
        data class Dead(val reason: String) : Result

        /**
         * Замер не состоялся: наш же вход не принял соединение (ядро пересобрано, порт из прошлой
         * его жизни) или мерить нечем. Про канал это не говорит ничего — ни «жив», ни «мёртв».
         */
        data class Unmeasured(val reason: String) : Result
    }

    @Suppress("UNUSED_PARAMETER")
    fun through(
        proxy: AutoModeExits.Endpoint,
        targetHost: String,
        targetPort: Int,
        timeoutMillis: Int,
    ): Result {
        val measurement = HonestProbe.measure(proxy, "основной канал", timeoutMillis)
        return when {
            measurement.live -> Result.Live(measurement.latencyMs ?: 0L)
            // «Не проверено» — не «живо», но и не «мёртв»: живым его не считает никто, а смертью
            // узла оно не засчитывается (вход из прошлой жизни ядра узла не касается).
            !measurement.measured || measurement.reason == HonestProbe.ENTRY_REFUSED -> Result.Unmeasured(measurement.reason)
            else -> Result.Dead(measurement.reason)
        }
    }

    fun log(where: String, result: Result) {
        when (result) {
            is Result.Live -> Log.i(TAG, "$where: трафик идёт, ответ за ${result.latencyMs} мс")
            is Result.Dead -> Log.i(TAG, "$where: трафика нет — ${result.reason}")
            is Result.Unmeasured -> Log.i(TAG, "$where: замер не состоялся — ${result.reason}")
        }
    }
}
