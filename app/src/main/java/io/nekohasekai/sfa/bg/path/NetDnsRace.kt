package io.nekohasekai.sfa.bg.path

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Опрос нескольких источников ответа разом, в пределах одного общего бюджета.
 *
 * Вынесено из [NetDns] и [io.nekohasekai.sfa.bg.HomeProbe] отдельно и без единого
 * обращения к Android: утверждение «молчание одного резолвера не съедает бюджет второго»
 * должно проверяться тестом, а не пересказом.
 *
 * Зачем разом. Резолверы сети спрашивались по очереди, IPv4 первым, и первый забирал весь
 * бюджет. При роуминге между точками запрос телефона до одного из них не доходит вовсе, а
 * второй ответил бы сразу — но очередь до него не доходила, и заход получал «резолверы
 * молчат» там, где ответ был в полушаге. Системный путь в [io.nekohasekai.sfa.bg.HomeProbe]
 * запускался только после своего — то есть молчание стоило два бюджета подряд.
 */
internal object NetDnsRace {

    /** Чем кончился опрос. */
    data class Result<T>(
        /** Первый содержательный ответ; `null` — такого не пришло. */
        val winner: T?,
        /** Пустые исходы, пришедшие до конца опроса, в порядке прихода. */
        val silences: List<T>,
        /** Сколько источников так и не ответили ничем до конца опроса. */
        val unfinished: Int,
    )

    /**
     * Спрашивает всех разом. Каждому отдаётся весь бюджет — они идут параллельно, а не
     * делят его между собой.
     *
     * Первый ответ, для которого [answered] говорит «да», закрывает опрос сразу. Пустой исход
     * (или исключение) опрос не закрывает: ждём остальных, пока есть бюджет. Промолчали все
     * раньше срока — возвращаемся раньше срока, досиживать нечего.
     */
    fun <T : Any> first(
        budgetMillis: Long,
        askers: List<(budgetMillis: Long) -> T>,
        answered: (T) -> Boolean,
        clock: () -> Long = ::nowMillis,
    ): Result<T> {
        if (askers.isEmpty() || budgetMillis <= 0) return Result(null, emptyList(), askers.size)
        val pool = Executors.newFixedThreadPool(askers.size) { job ->
            Thread(job, "netdns-race").apply { isDaemon = true }
        }
        try {
            val done = ExecutorCompletionService<T>(pool)
            askers.forEach { ask -> done.submit(Callable { ask(budgetMillis) }) }
            val deadline = clock() + budgetMillis
            val silences = ArrayList<T>()
            var finished = 0
            while (finished < askers.size) {
                val left = deadline - clock()
                if (left <= 0) break
                val next = runCatching { done.poll(left, TimeUnit.MILLISECONDS) }.getOrNull() ?: break
                finished++
                // Источник, который бросил вместо ответа, — это тоже молчание, а не повод
                // бросать остальных.
                val value = runCatching { next.get() }.getOrNull() ?: continue
                if (answered(value)) return Result(value, silences, askers.size - finished)
                silences += value
            }
            return Result(null, silences, askers.size - finished)
        } finally {
            // Проигравших не ждём: у каждого свой срок сокета, он истечёт сам. Прерывание
            // приём UDP не снимает, но новых задач пул уже не примет и потоки уйдут сами.
            pool.shutdownNow()
        }
    }

    /**
     * Основной путь и запасной — разом, но запасной берётся, только если основной промолчал.
     *
     * Основной идёт в вызывающем потоке и решает первым: запасной хуже (системный резолвер
     * при поднятом туннеле отвечает за наше ядро и помнит кеш), поэтому его быстрый ответ
     * не перебивает основной. Выигрыш в другом: когда основной промолчал, запасной уже
     * отработал тот же срок рядом, и ждать его второй бюджет не нужно.
     *
     * @param backup запасной путь; `null` — не ответил. Получает весь бюджет.
     * @param cancelBackup снять запасной, когда он больше не нужен.
     * @return исход основного и ответ запасного (только если основной промолчал).
     */
    fun <P, B : Any> withBackup(
        budgetMillis: Long,
        primary: () -> P,
        primaryAnswered: (P) -> Boolean,
        backup: (budgetMillis: Long) -> B?,
        cancelBackup: () -> Unit = {},
        clock: () -> Long = ::nowMillis,
    ): Pair<P, B?> {
        val startedAt = clock()
        val backupAnswer = AtomicReference<B?>(null)
        val worker = Thread(
            { backupAnswer.set(runCatching { backup(budgetMillis) }.getOrNull()) },
            "netdns-backup",
        ).apply {
            isDaemon = true
            start()
        }
        val own = primary()
        if (primaryAnswered(own)) {
            runCatching(cancelBackup)
            return own to null
        }
        val left = budgetMillis - (clock() - startedAt)
        if (left > 0) runCatching { worker.join(left) }
        val got = backupAnswer.get()
        if (got == null) runCatching(cancelBackup)
        return own to got
    }

    private fun nowMillis(): Long = System.nanoTime() / 1_000_000
}
