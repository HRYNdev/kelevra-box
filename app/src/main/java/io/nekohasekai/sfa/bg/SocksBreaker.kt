package io.nekohasekai.sfa.bg

/**
 * Предохранитель на шторм отказов в локальный сокс.
 *
 * Последний рубеж на случай, который не предусмотрели ни автомат, ни присмотр: выход
 * смотрит в сокс на петле, а его никто не слушает. Ядро отвечает `connection refused`
 * за миллисекунду, приложения повторяют без остановки — 12.09.2026 так набежало 13073
 * отказа за два всплеска по десять секунд, до 1240 в секунду, и следом упало ядро.
 *
 * Считаем по адресу петли, а не по имени выхода: имя в строке журнала кириллическое, и
 * общий разбор имён выходов его не ловит. Наружу на петлю ядро не ходит, так что отказ на
 * `127.0.0.1` значит ровно одно — не слушает наш же локальный сокс.
 *
 * Чистый класс без Android: порог, окно и пауза проверяются тестами на JVM.
 */
internal class SocksBreaker(
    private val threshold: Int = 50,
    private val windowMillis: Long = 2_000L,
    private val cooldownMillis: Long = 30_000L,
) {
    private val times = ArrayDeque<Long>()
    private var trippedAt: Long? = null

    /** @return `true` ровно тогда, когда пора уводить выход с мёртвого сокса. */
    @Synchronized
    fun offer(line: String, now: Long): Boolean {
        if (!line.contains(LOOPBACK_DIAL) || !line.contains(REFUSED)) return false
        times.addLast(now)
        while (times.isNotEmpty() && now - times.first() > windowMillis) times.removeFirst()
        if (times.size < threshold) return false
        val last = trippedAt
        if (last != null && now - last < cooldownMillis) return false
        trippedAt = now
        times.clear()
        return true
    }

    companion object {
        const val LOOPBACK_DIAL = "dial tcp 127.0.0.1:"
        const val REFUSED = "connection refused"
    }
}
