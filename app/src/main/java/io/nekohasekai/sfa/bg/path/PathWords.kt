package io.nekohasekai.sfa.bg.path

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Снимок словами.
 *
 * Один и тот же вопрос — «что человеку показать» — раньше решался дважды: своей цепочкой
 * условий на экране и своей в шторке. Две цепочки расходились. Здесь они сведены в одну
 * таблицу, и обе стороны читают её.
 *
 * Ни Android, ни ресурсов: чистые строки, которые можно проверить тестом, а не глазами.
 * Тексты, уже живущие в ресурсах, повторены дословно — шторка не должна начать говорить
 * иначе только потому, что строку собрали в другом месте.
 */
object PathWords {
    /** Дословно `R.string.status_home`. */
    const val HOME = "Дома, обход на роутере"

    /** Дословно `R.string.status_started`. */
    const val STARTED = "Работает"

    private val TIME = SimpleDateFormat("HH:mm", Locale.getDefault())

    /**
     * Что с этим путём, словами: «работает, 210 мс, 40 секунд назад», «не отвечает с 12:31»,
     * «поднимается», «не проверяли».
     *
     * @param now стенное время прямо сейчас — чтобы «40 секунд назад» считалось от него,
     *   а не от момента сборки снимка.
     */
    fun state(state: PathState, now: Long): String = when (state.status) {
        PathStatus.Alive -> buildString {
            append("работает")
            state.latencyMs?.let { append(", $it мс") }
            if (state.measuredAt != 0L) append(", ${ago(now - state.measuredAt)}")
        }

        PathStatus.Dead -> if (state.measuredAt != 0L) {
            "не отвечает с ${at(state.measuredAt)}"
        } else {
            "не отвечает"
        }

        PathStatus.Raising -> "поднимается"
        PathStatus.Probing -> "проверяю"
        PathStatus.Unavailable -> state.reason ?: "недоступен"
        PathStatus.Unknown -> "не проверяли"
    }

    /**
     * Одна строка про всё сразу: для шторки и для круга.
     *
     * Порядок строк таблицы — это и есть ответ на «что важнее сказать». Он повторяет тот,
     * что жил в шторке цепочкой `when`, только теперь его видно целиком.
     *
     * @param chosen путь, на котором стоим. Выбирает его по-прежнему автомат: реестр помнит
     *   состояние путей, но не то, каким из них мы идём.
     * @param auto подбирает ли выход автомат сам.
     * @param manualExit имя выхода, выбранного человеком; `null` — не выбирал.
     */
    fun headline(snapshot: PathSnapshot, chosen: PathId?, auto: Boolean, manualExit: String?): String = when {
        // Сети нет вообще: без неё мертвы все пути, и говорить про выходы нечего.
        // Узнаём по дому: его проба идёт по физической сети, и «недоступен» у неё
        // означает ровно одно — сети под нами нет.
        snapshot[PathId.HOME].status == PathStatus.Unavailable -> "Нет сети"

        chosen == PathId.HOME -> HOME

        chosen == PathId.ROOM -> when (snapshot[PathId.ROOM].status) {
            PathStatus.Alive -> "Комната"
            PathStatus.Raising, PathStatus.Probing -> "Поднимаю комнату"
            PathStatus.Unknown -> "Поднимаю комнату"
            else -> "Комната не отвечает"
        }

        // Выход выбрал человек: автомат отошёл до смены сети, но мерить пришпиленный
        // путь никто не запрещал. Раньше здесь просто повторяли выбранное имя — и
        // получалось, что шторка радостно писала «Нидерланды», хотя честная проба
        // секундами раньше уже назвала тот же путь мёртвым (поймано на стенде 08.08.2026).
        // Состояние берём из реестра путей, как и для автоматического выбора.
        manualExit != null && !auto -> when (snapshot.byExit(manualExit)?.status) {
            PathStatus.Dead, PathStatus.Unavailable -> "$manualExit не отвечает"
            else -> manualExit
        }

        chosen == PathId.MAIN -> when (snapshot[PathId.MAIN].status) {
            PathStatus.Alive -> "Выход выбирается сам"
            PathStatus.Dead, PathStatus.Unavailable -> "Связи нет"
            else -> "Проверяю связь"
        }

        !auto -> STARTED

        // Стоять не на чем: пути отказали, автомат подбирает следующий.
        snapshot[PathId.HOME].refused && snapshot[PathId.MAIN].refused -> "Ищу путь"

        else -> "Проверяю связь"
    }

    /** «в 12:31» приходит как «12:31»: время без слова, чтобы вставлялось в любую фразу. */
    private fun at(millis: Long): String = synchronized(TIME) { TIME.format(Date(millis)) }

    /**
     * Сколько прошло. Свежесть важнее точности: «210 мс, 40 секунд назад» человек читает
     * как «только что мерили», а «210 мс, 12:31» — как «мерили когда-то».
     */
    private fun ago(elapsed: Long): String {
        val seconds = elapsed / 1000
        return when {
            seconds < 0 -> "только что"
            seconds < 5 -> "только что"
            seconds < 60 -> "${plural(seconds, "секунду", "секунды", "секунд")} назад"
            seconds < 3600 -> "${plural(seconds / 60, "минуту", "минуты", "минут")} назад"
            else -> "давно"
        }
    }

    /** Русский счёт: 1 секунду, 2 секунды, 5 секунд, 21 секунду. */
    private fun plural(value: Long, one: String, few: String, many: String): String {
        val tail = value % 100
        val last = value % 10
        val word = when {
            tail in 11..14 -> many
            last == 1L -> one
            last in 2..4 -> few
            else -> many
        }
        return "$value $word"
    }
}
