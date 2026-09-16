package io.nekohasekai.sfa.bg

/**
 * Будильник фонового цикла автомата: сон до срока, который событие может оборвать.
 *
 * Голый `notifyAll` будит только того, кто УЖЕ спит. Если цикл в этот момент занят
 * заходом (проверяет канал), сигнал пропадает, и цикл потом засыпает на полный срок.
 * Поймано 16.09.2026: человек выбрал «Комната», а подъём начался через 60 секунд —
 * ровно ритм ручного режима. Поэтому пробуждение здесь не только звонок, но и отметка
 * «есть необработанное событие»: сон, начатый после неё, не засыпает вовсе.
 *
 * Отметка одна, не счётчик: десять событий за время захода дают один немедленный
 * заход, а не десять — следующий сон уже ждёт свой срок.
 *
 * Без Android внутри — чтобы гонку можно было проверить тестом.
 *
 * @param lock монитор, под которым живёт и остальное состояние цикла (флаг `active`).
 */
internal class AutoModeAlarm(private val lock: Object = Object()) {

    /** Под [lock]: было событие, которого цикл ещё не видел. */
    private var pending = false

    /** Разбудить цикл: сейчас, если он спит, или на первом же его сне, если занят. */
    fun wake() {
        synchronized(lock) {
            pending = true
            lock.notifyAll()
        }
    }

    /** Забыть несъеденное событие — на старте цикла, который и так начинает с захода. */
    fun reset() {
        synchronized(lock) { pending = false }
    }

    /**
     * Спать [millis] миллисекунд или пока не позовут [wake]; [Long.MAX_VALUE] — без срока.
     *
     * Ложные пробуждения монитора сна не обрывают: выход только по сроку или по событию.
     *
     * @return true — разбудило событие (в том числе случившееся ещё до сна), false — вышел срок.
     * @throws InterruptedException поток прервали; отметка события при этом не трогается.
     */
    fun sleep(millis: Long): Boolean {
        synchronized(lock) {
            val forever = millis == Long.MAX_VALUE
            val deadline = if (forever) 0L else System.nanoTime() + millis.coerceIn(0L, MAX_MILLIS) * 1_000_000L
            while (!pending) {
                if (forever) {
                    lock.wait()
                } else {
                    val leftNanos = deadline - System.nanoTime()
                    if (leftNanos <= 0L) return false
                    // Округляем вверх: wait(0) означало бы «вечно».
                    lock.wait((leftNanos + 999_999L) / 1_000_000L)
                }
            }
            pending = false
            return true
        }
    }

    private companion object {
        /** Срок больше этого не выражается в наносекундах без переполнения; это ~29 лет. */
        const val MAX_MILLIS = Long.MAX_VALUE / 10_000_000L
    }
}
