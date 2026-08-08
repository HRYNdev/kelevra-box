package io.nekohasekai.sfa.bg.path

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Общая память о путях: единственный владелец их состояния.
 *
 * Правило одно и оно жёсткое: **реестр ничего не решает и никуда не ходит**. Он не
 * поднимает комнату, не выбирает выход, не заводит таймеров и не знает, на чём мы
 * стоим. Ему рассказывают, что увидели, — он это помнит и отдаёт наружу одним
 * неизменяемым снимком. Кто решает — тот и решает; на этом шаге по-прежнему
 * [io.nekohasekai.sfa.bg.AutoMode].
 *
 * Зачем так. Пока каждый читатель достраивал состояние сам, экран и шторка могли
 * одновременно говорить разное, и оба — про одну и ту же секунду. Теперь источник
 * один, и расхождение стало невозможным по конструкции, а не по внимательности.
 *
 * Писать можно из любого потока: правка идёт под замком, наружу уходит уже собранный
 * снимок. Читателю замок не нужен вовсе.
 */
object PathRegistry {
    private val lock = Any()

    /**
     * Часы. Отдельно, потому что «40 секунд назад» и «не отвечает с 12:31» — это стенное
     * время, а проверять переходы статусов надо не по реальным часам.
     */
    @Volatile
    internal var clock: () -> Long = { System.currentTimeMillis() }

    private val defs = linkedMapOf(
        PathId.HOME to PathDef.HOME,
        PathId.MAIN to PathDef.MAIN,
        PathId.ROOM to PathDef.ROOM,
    )

    private val states = LinkedHashMap<PathId, PathState>()

    private val _snapshot = MutableStateFlow(blank())

    /** Единственный выход наружу. Одинаковых снимков подряд не приходит. */
    val snapshot: StateFlow<PathSnapshot> get() = _snapshot

    private fun blank(): PathSnapshot {
        synchronized(lock) {
            states.clear()
            defs.forEach { (id, def) -> states[id] = PathState(def) }
            return PathSnapshot(states, clock())
        }
    }

    /**
     * Забыть всё, что намерили. Зовётся там, где прошлое знание перестало что-либо значить:
     * сервис перезапустился, сеть сменилась, человек взял выбор на себя.
     *
     * Имена выходов при этом остаются: они приходят из конфига, а не из наблюдений, и
     * от смены обстановки не меняются. Забыть их — значит перестать узнавать комнату
     * ровно в тот момент, когда её выбрали руками. Обновляет их [bindExits].
     */
    fun reset() {
        _snapshot.value = blank()
    }

    /**
     * Имена выходов из раскладки конфига. Приходят позже самих путей: раскладку отдаёт
     * работающее ядро, а помнить про пути надо и до него.
     */
    fun bindExits(main: String?, room: String?) {
        edit {
            defs[PathId.MAIN] = defs.getValue(PathId.MAIN).copy(exitTag = main)
            defs[PathId.ROOM] = defs.getValue(PathId.ROOM).copy(exitTag = room)
            states[PathId.MAIN] = states.getValue(PathId.MAIN).copy(def = defs.getValue(PathId.MAIN))
            states[PathId.ROOM] = states.getValue(PathId.ROOM).copy(def = defs.getValue(PathId.ROOM))
        }
    }

    /** Начали мерить. */
    fun probing(id: PathId) = update(id) { it.copy(status = PathStatus.Probing) }

    /**
     * Путь несёт данные.
     *
     * @param latencyMs `null`, когда мерить нечего: дома пути нет, а открытый порт
     *   про задержку самого пути не говорит.
     */
    fun alive(id: PathId, latencyMs: Long? = null, evidence: Evidence = Evidence.Probe, reason: String? = null) =
        update(id) {
            it.copy(
                status = PathStatus.Alive,
                // Прошлую задержку не тащим: подсказка («порт принял соединение») про
                // время ответа не говорит ничего, а показанное старое число выглядело бы
                // свежим замером.
                latencyMs = latencyMs,
                measuredAt = clock(),
                failures = 0,
                reason = reason,
                coolingUntil = 0L,
                raisingSince = 0L,
                evidence = evidence,
            )
        }

    /** Путь не отвечает. Счётчик отказов растёт: по нему видно «мигнул» это или «лёг». */
    fun dead(id: PathId, reason: String, evidence: Evidence = Evidence.Probe) = update(id) {
        it.copy(
            status = PathStatus.Dead,
            latencyMs = null,
            // Время первого отказа не переписываем: человеку нужно «не отвечает с 12:31»,
            // а не «не отвечает с этой секунды», иначе отказ выглядит вечно свежим.
            measuredAt = if (it.status == PathStatus.Dead && it.measuredAt != 0L) it.measuredAt else clock(),
            failures = it.failures + 1,
            reason = reason,
            raisingSince = 0L,
            evidence = evidence,
        )
    }

    /** Путь поднимается. Не отказ и не работа: комната встаёт секундами. */
    fun raising(id: PathId) = update(id) {
        it.copy(
            status = PathStatus.Raising,
            raisingSince = if (it.status == PathStatus.Raising && it.raisingSince != 0L) it.raisingSince else clock(),
            reason = null,
        )
    }

    /** Пути тут нет вообще: пробовать нечего и незачем. */
    fun unavailable(id: PathId, reason: String) = update(id) {
        it.copy(
            status = PathStatus.Unavailable,
            latencyMs = null,
            measuredAt = clock(),
            reason = reason,
            raisingSince = 0L,
            evidence = Evidence.Probe,
        )
    }

    /**
     * Путь в этом заходе не проверяли — и прошлое знание про него больше не годится.
     *
     * Это не отказ. Дома основной канал никто не мерил, и писать про него «не отвечает
     * с 12:31» значит врать: он, может, и отвечает, просто спрашивать было незачем.
     */
    fun unchecked(id: PathId, reason: String? = null) = update(id) {
        it.copy(
            status = PathStatus.Unknown,
            latencyMs = null,
            measuredAt = 0L,
            failures = 0,
            reason = reason,
            raisingSince = 0L,
            evidence = Evidence.Never,
        )
    }

    /** Сети нет: без неё мертвы все пути сразу, и каждый по одной и той же причине. */
    fun allUnavailable(reason: String) = edit {
        val now = clock()
        defs.keys.forEach { id ->
            states[id] = states.getValue(id).copy(
                status = PathStatus.Unavailable,
                latencyMs = null,
                measuredAt = now,
                reason = reason,
                raisingSince = 0L,
                evidence = Evidence.Probe,
            )
        }
    }

    /** Не трогать этот путь до указанного времени. Кто спрашивает — тот и решает, что делать. */
    fun coolDown(id: PathId, until: Long) = update(id) { it.copy(coolingUntil = until) }

    private inline fun update(id: PathId, change: (PathState) -> PathState) = edit {
        states[id] = change(states.getValue(id))
    }

    private inline fun edit(change: () -> Unit) {
        val fresh = synchronized(lock) {
            change()
            PathSnapshot(states, clock())
        }
        _snapshot.value = fresh
    }
}
