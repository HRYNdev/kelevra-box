package io.nekohasekai.sfa.bg.path

/**
 * Капсула у камеры: Android 16 поднимает туда «продвинутое» постоянное уведомление
 * (Live Updates, у OnePlus — «Live Alerts») и пишет в ней короткий текст.
 *
 * Места там на семь знаков — длиннее система не обрезает, а показывает одну иконку.
 * Поэтому здесь не пересказ [PathWords.headline], а его сжатие до одного слова: чем
 * идём прямо сейчас.
 *
 * Ни Android, ни ресурсов — чистая таблица, её проверяет тест.
 */
object PathChip {
    /** Больше этого капсула не показывает текстом. */
    const val MAX = 7

    const val ROOM = "Комната"
    const val SEARCH = "Поиск"
    const val DOWN = "Нет"
    const val TUNNEL = "Туннель"

    /**
     * Что делать с капсулой.
     *
     * @param promote просить ли систему поднять уведомление в капсулу.
     * @param text короткий текст капсулы; `null`, когда не продвигаем.
     */
    data class Chip(val promote: Boolean, val text: String?) {
        companion object {
            val NONE = Chip(promote = false, text = null)
        }
    }

    /**
     * Порядок веток тот же, что у [PathWords.headline]: капсула не должна говорить
     * другое, чем шторка под ней.
     *
     * @param tunnelLive поднято ли ядро. Дома автомат его гасит — в капсуле тогда
     *   нечего показывать, и уведомление остаётся обычным.
     * @param manualExit имя выхода, выбранного человеком; `null` — не выбирал.
     * @param codeOf имя выхода → короткий код («Нидерланды» → «NL»); `null` — не знаем.
     *   Та же таблица, что у значка выхода на главном экране.
     */
    fun of(
        snapshot: PathSnapshot,
        chosen: PathId?,
        auto: Boolean,
        tunnelLive: Boolean,
        manualExit: String?,
        codeOf: (String) -> String?,
    ): Chip {
        if (!tunnelLive) return Chip.NONE
        // Сети нет или мы дома: туннель тут ничего не везёт, капсула бы врала.
        if (snapshot[PathId.HOME].status == PathStatus.Unavailable) return Chip.NONE
        if (chosen == PathId.HOME) return Chip.NONE

        val text = when {
            chosen == PathId.ROOM -> when (snapshot[PathId.ROOM].status) {
                PathStatus.Alive -> ROOM
                PathStatus.Dead, PathStatus.Unavailable -> DOWN
                else -> SEARCH
            }

            manualExit != null && !auto -> when (snapshot.byExit(manualExit)?.status) {
                PathStatus.Dead, PathStatus.Unavailable -> DOWN
                else -> codeOf(manualExit) ?: TUNNEL
            }

            chosen == PathId.MAIN -> when (snapshot[PathId.MAIN].status) {
                PathStatus.Alive -> snapshot[PathId.MAIN].def.exitTag?.let(codeOf) ?: TUNNEL
                PathStatus.Dead, PathStatus.Unavailable -> DOWN
                else -> SEARCH
            }

            !auto -> TUNNEL

            else -> SEARCH
        }
        return Chip(promote = true, text = text.take(MAX))
    }
}
