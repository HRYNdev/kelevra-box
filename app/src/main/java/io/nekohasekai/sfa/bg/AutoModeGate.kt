package io.nekohasekai.sfa.bg

/**
 * Задвижка между «увидел» и «сделал».
 *
 * Одиночное наблюдение ничего не значит: канал моргает, DNS отвечает не с первого раза,
 * сеть переезжает между точками. Поэтому обстановка меняется только после
 * [confirmations] одинаковых наблюдений подряд — тот же принцип, что у [OlcRtcWatchdog]
 * с тремя отказами.
 *
 * Два исключения, и оба осознанные:
 *  - **смена сети** ([offer] с `trust`): обстановка доказанно изменилась, ждать
 *    подтверждений нечего;
 *  - **сети нет**: действие всё равно «ничего не делать», подтверждать нечего.
 *
 * Вынесено из [AutoMode] отдельным классом без единого обращения к Android — чтобы
 * поведение «не дёргается» можно было проверить, а не пересказать.
 */
internal class AutoModeGate(private val confirmations: Int) {

    /** Обстановка, на которой стоим. */
    var current: AutoMode.Situation = AutoMode.Situation.Unknown
        private set

    /** Видим другое, но подтверждений ещё не набрали. */
    var pending: Boolean = false
        private set

    private var candidate: AutoMode.Situation = AutoMode.Situation.Unknown
    private var hits: Int = 0

    fun reset(situation: AutoMode.Situation) {
        current = situation
        candidate = situation
        hits = confirmations
        pending = false
    }

    /**
     * @param trust наблюдение сделано сразу после доказанной смены сети.
     * @return true — обстановка сменилась, пора действовать.
     */
    fun offer(observed: AutoMode.Situation, trust: Boolean = false): Boolean {
        if (observed == current) {
            candidate = observed
            hits = confirmations
            pending = false
            return false
        }

        if (observed == candidate) {
            hits++
        } else {
            candidate = observed
            hits = 1
        }

        val needed = if (trust || observed == AutoMode.Situation.NoNetwork) 1 else confirmations
        if (hits < needed) {
            pending = true
            return false
        }
        pending = false
        current = observed
        return true
    }
}
