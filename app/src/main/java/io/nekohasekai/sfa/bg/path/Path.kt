package io.nekohasekai.sfa.bg.path

/**
 * Что мы знаем про каждый выход наружу.
 *
 * До этого файла знание о путях было размазано: обстановку помнил [io.nekohasekai.sfa.bg.AutoMode],
 * состояние комнаты — [io.nekohasekai.sfa.bg.OlcRtcCore], а экран и шторка достраивали
 * недостающее цепочками условий, каждая со своей правдой. Отсюда и брались расхождения:
 * круг писал «Нет сети», шторка в ту же секунду — «Дома, обход на роутере».
 *
 * Здесь заводится одна общая память: по каждому пути — что с ним, когда мы это узнали
 * и откуда знаем. Решений тут нет и не будет: реестр только помнит.
 */

/**
 * Пути наружу.
 *
 * Список открытый: новый путь — это новое значение здесь плюс его [PathDef]. Ранги
 * оставлены с промежутками именно ради этого, см. [PathDef.rank].
 */
enum class PathId {
    /** Дома: обход уже делает роутер, свой туннель не нужен. */
    HOME,

    /** Основной канал до узла. */
    MAIN,

    /** Комната: несущая — чужой видеозвонок. */
    ROOM,
}

/** Чем узнаём, жив ли путь. */
enum class ProbeKind {
    /** Подменные адреса от домашнего резолвера. */
    Dns,

    /** Запрос целиком через локальный вход sing-box. */
    Traffic,

    /** Ответ несущей: меряет присмотр за ядром комнаты. */
    Carrier,
}

/** Откуда знаем то, что записано. */
enum class Evidence {
    /** Мерили сами и дошли до конца пути. */
    Probe,

    /** Косвенно: порт принял соединение, ядро сказало «поднято». Путь целиком не проверяли. */
    Hint,

    /** Не подтверждали ничем. */
    Never,
}

/** Что с путём. */
enum class PathStatus {
    /** Не смотрели. */
    Unknown,

    /** Меряем прямо сейчас. */
    Probing,

    /** Данные ходят. */
    Alive,

    /** Не отвечает. */
    Dead,

    /** Поднимается: комната встаёт не мгновенно, и это не отказ. */
    Raising,

    /** Пути тут нет вообще: нет сети, нет ядра в сборке. Пробовать нечего. */
    Unavailable,
}

/**
 * Постоянное описание пути: то, что не меняется от захода к заходу.
 *
 * @param exitTag имя выхода в селекторе конфига. `null`, пока ядро не отдало раскладку,
 *   а у [PathId.HOME] — всегда: дома никакого выхода не выбирают.
 * @param rank класс качества, а НЕ место в очереди приоритетов. Меньше — приятнее для
 *   телефона и для человека. Промежутки между значениями оставлены нарочно: новый путь
 *   встанет между существующими без пересчёта остальных.
 */
data class PathDef(
    val id: PathId,
    val name: String,
    val exitTag: String? = null,
    val rank: Int,
    val probe: ProbeKind,
) {
    companion object {
        /** Дома лучше всего: ни туннеля, ни батареи, ни лишней обёртки. */
        const val RANK_HOME = 0

        /** Основной канал — обычная жизнь. */
        const val RANK_MAIN = 10

        /** Комната дороже: чужой видеозвонок, поднимается секундами. */
        const val RANK_ROOM = 20

        // Дом узнаётся по DNS, но подтверждается трафиком — и записан именно вторым:
        // подменные адреса без прошедшего наружу запроса домом больше не считаются.
        val HOME = PathDef(PathId.HOME, "Дома", null, RANK_HOME, ProbeKind.Traffic)
        val MAIN = PathDef(PathId.MAIN, "Основной канал", null, RANK_MAIN, ProbeKind.Traffic)
        val ROOM = PathDef(PathId.ROOM, "Комната", null, RANK_ROOM, ProbeKind.Carrier)
    }
}

/**
 * Всё, что известно про путь прямо сейчас.
 *
 * @param latencyMs сколько шёл ответ последней удачной пробы; `null` — не мерили или
 *   мерить нечем (дома пути нет, а порт задержку пути не показывает).
 * @param measuredAt когда узнали то, что записано (стенные часы, миллисекунды).
 *   `0` — не узнавали ни разу.
 * @param failures сколько заходов подряд путь подвёл. Обнуляется первым же успехом.
 * @param reason почему статус такой, словами. Показывается человеку и пишется в лог.
 * @param coolingUntil до этого времени путь не трогаем. Копится, когда комната не встаёт:
 *   долбиться в неё каждым заходом дороже, чем подождать.
 * @param raisingSince когда начали поднимать. Нужен, чтобы отличить «поднимается вторую
 *   секунду» от «поднимается вторую минуту».
 */
data class PathState(
    val def: PathDef,
    val status: PathStatus = PathStatus.Unknown,
    val latencyMs: Long? = null,
    val measuredAt: Long = 0L,
    val failures: Int = 0,
    val reason: String? = null,
    val coolingUntil: Long = 0L,
    val raisingSince: Long = 0L,
    val evidence: Evidence = Evidence.Never,
    val squeezed: Boolean = false,
) {
    /** Путь годится, чтобы им идти. */
    val usable: Boolean get() = status == PathStatus.Alive

    /** Путь отказал: это ответ, а не молчание. */
    val refused: Boolean get() = status == PathStatus.Dead || status == PathStatus.Unavailable
}

/**
 * Неизменяемый срез реестра. Наружу отдаётся только он: читатель не может ни испортить
 * общую память, ни увидеть её на середине правки.
 */
class PathSnapshot internal constructor(
    states: Map<PathId, PathState>,
    /** Когда собран. */
    val at: Long,
) {
    private val states: Map<PathId, PathState> = java.util.Collections.unmodifiableMap(LinkedHashMap(states))

    operator fun get(id: PathId): PathState = states.getValue(id)

    /** Все пути по возрастанию ранга: сначала самые приятные. */
    val all: List<PathState> get() = states.values.sortedBy { it.def.rank }

    fun any(predicate: (PathState) -> Boolean): Boolean = states.values.any(predicate)

    fun anyIs(status: PathStatus): Boolean = states.values.any { it.status == status }

    /** Путь с таким именем выхода. `null` — такого выхода мы не знаем. */
    fun byExit(tag: String?): PathState? {
        val name = tag?.takeIf { it.isNotBlank() } ?: return null
        return states.values.firstOrNull { it.def.exitTag == name }
    }

    override fun toString(): String = all.joinToString(", ") { "${it.def.name}: ${it.status}" }
}
