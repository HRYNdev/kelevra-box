package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.database.Settings

/**
 * Свой расход трафика — накопительный, за всё время жизни установки.
 *
 * Панель считает трафик по ключу доступа целиком: телефон и ПК ходят под одним uuid,
 * и разделить их на сервере нечем. Поэтому расход считает само устройство и приносит
 * его серверу заголовком `X-Device-Traffic` (см. [io.nekohasekai.sfa.Kelevra.deviceHeaders]).
 *
 * Источник — счётчик самого ядра ([io.nekohasekai.libbox.StatusMessage]), тот же, по
 * которому шторка и панель показывают скорость и объём. Второго счётчика не заводим:
 * ядро уже считает всё, что через него прошло.
 *
 * Тонкость, ради которой этот объект вообще нужен: счётчик ядра обнуляется на каждом
 * перезапуске, а перезапусков за день десяток (автомат гасит туннель дома, перечитывание
 * конфига, обновление подписки). Поэтому здесь копится ИТОГ, а от ядра берётся прирост.
 */
internal object DeviceTraffic {
    const val HEADER = "X-Device-Traffic"

    /**
     * Пока итог не вырос на столько — в базу не пишем. Тик статуса приходит раз в секунду,
     * а запись идёт синхронно в Room: писать каждую секунду ради байтов незачем.
     * Ценой падения процесса теряется меньше мегабайта.
     */
    private const val FLUSH_STEP = 1L shl 20

    /**
     * @param total итог за всё время — то, что уходит серверу.
     * @param seen последнее показание счётчика ядра, от которого считается прирост.
     */
    data class State(val total: Long, val seen: Long)

    /**
     * Новый итог по очередному показанию счётчика ядра.
     *
     * Показание МЕНЬШЕ прошлого означает ровно одно: ядро перезапустилось и считает
     * заново. Тогда прирост считается от нуля (то есть равен самому показанию), а итог
     * не уменьшается никогда — иначе расход устройства «худел» бы после каждого гашения
     * туннеля, и сервер видел бы отрицательный расход.
     */
    fun next(state: State, reading: Long): State = when {
        reading < 0 -> state
        reading < state.seen -> State(total = state.total + reading, seen = reading)
        else -> State(total = state.total + (reading - state.seen), seen = reading)
    }

    /** Пора ли класть итог в базу. Первый ненулевой расход пишем сразу: без него нет заголовка. */
    fun flushNeeded(saved: Long, total: Long): Boolean =
        total > 0 && (saved == 0L || total - saved >= FLUSH_STEP)

    /**
     * Заголовок с расходом. Ни разу не считали — заголовка нет ВОВСЕ: пустое значение
     * сервер положил бы в реестр как есть, и в списке устройств появился бы прочерк
     * вместо честного «расход неизвестен».
     */
    fun header(total: Long): Pair<String, String>? =
        if (total > 0) HEADER to total.toString() else null

    /**
     * Показание счётчика: сумма отправленного и принятого за жизнь ядра.
     * null — счётчика нет: ядро мертво, мост через Go уронил исключение, показание
     * бессмысленное. Это не поломка, а обычное состояние выключенного туннеля.
     */
    fun reading(counter: () -> Long): Long? =
        runCatching { counter() }.getOrNull()?.takeIf { it >= 0 }

    @Volatile
    private var state: State? = null

    @Volatile
    private var saved: Long = 0L

    /**
     * Очередной тик от ядра. Счётчик берётся лямбдой, а не значением: мост через Go
     * умеет бросать на мёртвом ядре, и падать из-за счётчика байтов приложение не должно.
     */
    @Synchronized
    fun observe(counter: () -> Long) {
        val reading = reading(counter) ?: return
        // База может быть недоступна (ранний старт, снесённые данные) — тогда считаем
        // с чистого листа, но работу приложения этим не рвём.
        val current = next(runCatching { loaded() }.getOrDefault(State(0L, 0L)), reading)
        state = current
        if (flushNeeded(saved, current.total)) {
            runCatching {
                Settings.deviceTrafficTotal = current.total
                Settings.deviceTrafficSeen = current.seen
                saved = current.total
            }
        }
    }

    /** Итог за всё время. Прочитать не вышло (нет базы) — считаем, что расхода нет. */
    fun total(): Long = runCatching { loaded().total }.getOrDefault(0L)

    /** Состояние из памяти, а при первом обращении — из настроек. */
    private fun loaded(): State = state ?: State(
        total = Settings.deviceTrafficTotal,
        seen = Settings.deviceTrafficSeen,
    ).also {
        state = it
        saved = it.total
    }
}
