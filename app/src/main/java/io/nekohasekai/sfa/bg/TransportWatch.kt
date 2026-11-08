package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.pult.Pult

/**
 * Сторож транспорта на основном канале: короткое окно проб после смены сети и счётчик
 * провалов, по которому заход будится раньше срока.
 *
 * Зачем. После смены сети (вайфай и мобильная, новая сеть) транспорт основного узла
 * может оказаться принятым, но пустым, и полный заход идёт только раз в пять минут.
 * Поэтому первые [WINDOW_MILLIS] после смены сети раз в [STEP_MILLIS] идёт короткая
 * проба через закреплённый вход основного узла (мимо селектора, живые соединения не
 * трогаются); два провала подряд будят полный заход. Решение остаётся за заходом и его
 * задвижкой: проба ничего не переключает, она только зовёт.
 *
 * Вне окна проб нет вовсе: на основном канале новых проб сверх обычных заходов не
 * бывает (бюджет скрытности). Остальное закрывает самоподъём ядра, который судит по
 * обычным заходам автомата. Новая смена сети открывает окно заново.
 *
 * Без Android внутри, время приходит параметром, чтобы окно и счётчик проверялись тестом.
 */
internal object TransportWatch {
    /** Сколько после смены сети идут пробы. */
    const val WINDOW_MILLIS = 180_000L

    /** Шаг между пробами внутри окна. */
    const val STEP_MILLIS = 30_000L

    /** Сколько провалов подряд будят заход. */
    const val FAILS_TO_WAKE = 2

    /**
     * Окно проб после смены сети. [open] зовёт смена сети (можно из другого потока),
     * [close] зовёт новый подъём автомата.
     */
    class Okno(private val windowMillis: () -> Long = { Pult.long("avtomat.transport_window_millis", WINDOW_MILLIS) }) {
        @Volatile
        private var openedAt: Long = NEVER

        fun open(now: Long) {
            openedAt = now
        }

        fun close() {
            openedAt = NEVER
        }

        /** Сколько окну осталось жить; 0 — закрыто (или не открывалось). */
        fun left(now: Long): Long {
            val at = openedAt
            if (at == NEVER) return 0L
            return (at + windowMillis() - now).coerceAtLeast(0L)
        }

        fun isOpen(now: Long): Boolean = left(now) > 0L
    }

    /**
     * Сколько ждать до следующей пробы; 0 — окно закрыто, проб нет.
     *
     * @param left сколько осталось до полного захода: его не перешагиваем.
     * @param windowLeft сколько осталось окну ([Okno.left]); на его конце ждём ровно до конца.
     * @param step шаг между пробами.
     */
    fun slice(left: Long, windowLeft: Long, step: Long): Long {
        if (windowLeft <= 0L) return 0L
        return minOf(left, windowLeft, step.coerceAtLeast(1_000L))
    }

    private const val NEVER = Long.MIN_VALUE

    /**
     * Счётчик провалов подряд. Удача обнуляет; несостоявшийся замер (вход не принял
     * соединение — ядро пересобрано) не считается ни тем, ни другим.
     */
    class Schetchik(private val failsToWake: () -> Int = { Pult.int("avtomat.transport_fails_to_wake", FAILS_TO_WAKE) }) {
        @Volatile
        var fails: Int = 0
            private set

        fun ok() {
            fails = 0
        }

        /** @return true — провалов подряд набралось, заход пора будить (счётчик при этом обнуляется). */
        fun fail(): Boolean {
            fails++
            if (fails < failsToWake().coerceAtLeast(1)) return false
            fails = 0
            return true
        }

        fun reset() {
            fails = 0
        }
    }
}
