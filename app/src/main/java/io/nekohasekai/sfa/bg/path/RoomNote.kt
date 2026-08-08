package io.nekohasekai.sfa.bg.path

import io.nekohasekai.sfa.bg.OlcRtcCore

/**
 * Перевод состояния ядра комнаты в запись реестра. Одна штука на всё приложение.
 *
 * Зачем отдельно. Реестр — единственный источник правды о путях, но про комнату в него
 * писал только автомат, своим кругом. А меряет комнату не автомат, а присмотр
 * ([io.nekohasekai.sfa.bg.OlcRtcWatchdog]), раз в пять секунд. Пока круг автомата шёл раз
 * в три минуты, реестр про комнату отставал ровно на столько же: живой прогон 08.08.2026
 * показал обрыв комнаты на 83 секунды, который присмотр вылечил сам, — и все эти 83 секунды
 * человек читал на экране «Подключено». Теперь тот, кто померил, тот и записал.
 *
 * Своего мнения тут нет ни на грош: только таблица «что говорит ядро → что помнит реестр».
 * Поэтому она и вынесена сюда — таблицу можно проверить тестом, а не пересказать.
 */
object RoomNote {
    /** Записать то, что ядро комнаты говорит о себе прямо сейчас. */
    fun note() = note(OlcRtcCore.state, OlcRtcCore.health)

    /**
     * Та же запись, но состояние передают снаружи.
     *
     * @param state что ядро отвечает про факт старта.
     * @param health прошли ли через комнату реальные байты. Смотрится только у поднятого
     *   ядра: у непóднятого здоровье — эхо прошлой сессии и врёт.
     */
    fun note(state: OlcRtcCore.State, health: OlcRtcCore.Health) {
        when (state) {
            is OlcRtcCore.State.Starting -> PathRegistry.raising(PathId.ROOM)

            is OlcRtcCore.State.Ready -> when (health) {
                is OlcRtcCore.Health.Live -> PathRegistry.alive(PathId.ROOM, health.latencyMs)
                is OlcRtcCore.Health.Dead -> PathRegistry.dead(PathId.ROOM, health.reason)
                // Ядро встало, а присмотр ещё не мерил: это не отказ, комната поднимается.
                OlcRtcCore.Health.Unknown -> PathRegistry.raising(PathId.ROOM)
            }

            is OlcRtcCore.State.Failed -> PathRegistry.dead(PathId.ROOM, state.reason)
            OlcRtcCore.State.Unavailable -> PathRegistry.unavailable(PathId.ROOM, "ядра комнаты в сборке нет")
            OlcRtcCore.State.Idle -> PathRegistry.unchecked(PathId.ROOM, "комнату не поднимали")
        }
    }

    /**
     * Комнату поднимают заново: ядро сейчас погашено, но это не «её не поднимали».
     *
     * Отдельно от [note], потому что по одному лишь [OlcRtcCore.State.Idle] отличить
     * «ещё не начинали» от «гасим, чтобы поднять» нельзя, а человеку это разные вещи:
     * первое читается как «не проверяли», второе — как «поднимаю комнату».
     */
    fun raising() = PathRegistry.raising(PathId.ROOM)
}
