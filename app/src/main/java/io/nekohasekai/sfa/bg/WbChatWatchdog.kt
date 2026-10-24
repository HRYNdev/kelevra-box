package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.pult.Pult

import android.os.SystemClock
import io.nekohasekai.sfa.bg.path.HonestProbe
import io.nekohasekai.sfa.bg.path.PathId
import io.nekohasekai.sfa.bg.path.PathRegistry

/**
 * Держит ядро WB Chat ([WbChatCore]) запущенным, пока оно нужно.
 *
 * Простейшая петля, отдельно от [OlcRtcWatchdog]: «нужно» — это поднятая служба с туннелем
 * ([wanted]: выход выбран руками или нужен автомату) и параметры с сервера
 * ([WbChatParams.available]). Выбор в селекторе здесь не учитывается.
 *
 * Пока комната WB нужна автомату ([AutoMode.wbWanted]), присмотр ещё и меряет её сокс раз в
 * [PROBE_INTERVAL_MILLIS] ([WbChatCore.probe]) и пишет итог в реестр ([PathId.WB]): автомат
 * идёт ей, только когда через неё прошёл запрос. Владелец пробы один — этот присмотр.
 *
 * Ядро умерло (звонок кончился — код 3 — или оборвался) — перезапуск через
 * [RESTART_PAUSE_MILLIS]; после [FAILS_BEFORE_SLOW] неудач подряд — через [SLOW_PAUSE_MILLIS].
 * Неудача — это отказ подъёма или ядро, прожившее готовым меньше [HEALTHY_MILLIS]; ядро,
 * простоявшее дольше, счёт неудач обнуляет. Сменились параметры на сервере (токен, порт) —
 * перезапуск сразу.
 */
object WbChatWatchdog {
    private const val TAG = "WbChatWatchdog"

    const val RESTART_PAUSE_MILLIS = 5_000L
    const val SLOW_PAUSE_MILLIS = 30_000L
    const val FAILS_BEFORE_SLOW = 3

    /** Сколько ядро должно простоять готовым, чтобы его смерть не считалась неудачей. */
    const val HEALTHY_MILLIS = 60_000L

    private const val TICK_MILLIS = 1_000L

    /** Как часто меряем сокс комнаты WB, пока она нужна автомату (как присмотр за комнатой). */
    const val PROBE_INTERVAL_MILLIS = 5_000L

    /** Когда мерили последний раз (elapsedRealtime); 0 — в этом подъёме не мерили. */
    @Volatile
    private var probedAt = 0L

    /** В реестр про WB писали по просьбе автомата — когда она ему не нужна, стираем один раз. */
    @Volatile
    private var noted = false

    /** Пауза перед очередным подъёмом после [fails] неудач подряд. */
    internal fun pauseAfter(fails: Int): Long = if (fails >= Pult.int("wb.storozh.fails_before_slow", FAILS_BEFORE_SLOW)) Pult.long("wb.storozh.slow_pause_millis", SLOW_PAUSE_MILLIS) else Pult.long("wb.storozh.restart_pause_millis", RESTART_PAUSE_MILLIS)

    private val lock = Any()

    @Volatile
    private var active = false

    private var thread: Thread? = null

    @Volatile
    private var wanted: () -> Boolean = { false }

    /** Неудач подряд. */
    @Volatile
    var fails = 0
        private set

    /** Перезапусков за сессию службы — для экрана. */
    @Volatile
    var restarts = 0
        private set

    fun start(wanted: () -> Boolean) {
        synchronized(lock) {
            stop()
            this.wanted = wanted
            fails = 0
            restarts = 0
            probedAt = 0L
            noted = false
            active = true
            thread = Thread(::loop, "wbchat-watchdog").apply {
                isDaemon = true
                start()
            }
            KLog.i(TAG, "присмотр WB Chat включён")
        }
    }

    /** Гасит петлю и само ядро (блокирует до ~4 с: ядру даётся положить трубку). */
    fun stop() {
        synchronized(lock) {
            if (!active && thread == null) return
            active = false
            val t = thread
            thread = null
            t?.interrupt()
            t?.join(1_000)
            runCatching { WbChatCore.stop() }
            WbChatCore.markIdle()
            KLog.i(TAG, "присмотр WB Chat выключен (перезапусков за сессию: $restarts)")
        }
    }

    private fun autoWants(): Boolean = runCatching { AutoMode.wbWanted() }.getOrDefault(false)

    /**
     * Проба сокса WB, если она нужна автомату и подошёл срок. Итог — в реестр; сменился —
     * автомат узнаёт сразу ([AutoMode.onWbProbe]), а не через свой трёхминутный ритм.
     */
    private fun probeIfDue() {
        if (!autoWants()) {
            forgetNote()
            return
        }
        if (WbChatCore.state !is WbChatCore.State.Ready) return
        if (probedAt != 0L && SystemClock.elapsedRealtime() - probedAt < PROBE_INTERVAL_MILLIS) return
        val m = WbChatCore.probe()
        probedAt = SystemClock.elapsedRealtime()
        if (!active || !autoWants()) return
        noted = true
        when (m.verdict) {
            HonestProbe.Verdict.Live -> PathRegistry.alive(PathId.WB, m.latencyMs)
            HonestProbe.Verdict.Dead -> PathRegistry.dead(PathId.WB, m.reason)
            // Мерить нечем: ядро вышло между вопросом и пробой — поднимается заново.
            HonestProbe.Verdict.Unmeasurable -> PathRegistry.raising(PathId.WB)
        }
        if (m.measured) runCatching { AutoMode.onWbProbe(m.live) }
    }

    /** Комната WB автомату не нужна: запись о ней в реестре — из прошлой пробы, стираем. */
    private fun forgetNote() {
        probedAt = 0L
        if (!noted) return
        noted = false
        PathRegistry.unchecked(PathId.WB, "комната WB автомату не нужна")
    }

    private fun loop() {
        // Когда можно поднимать в следующий раз; 0 — сразу.
        var notBefore = 0L
        var startedOnce = false
        try {
            while (active) {
                val need = runCatching { wanted() }.getOrDefault(false)
                val params = if (need) WbChatParams.resolve() else null
                if (params == null) {
                    forgetNote()
                    if (WbChatCore.isRunning() || WbChatCore.state !is WbChatCore.State.Idle) {
                        KLog.i(TAG, if (need) "параметров WB Chat нет — гашу ядро" else "WB Chat сейчас не нужен — гашу ядро")
                        WbChatCore.stop()
                        WbChatCore.markIdle()
                    }
                    fails = 0
                    notBefore = 0L
                    Thread.sleep(TICK_MILLIS)
                    continue
                }

                if (WbChatCore.isRunning()) {
                    if (WbChatCore.runningParams != params) {
                        KLog.i(TAG, "параметры WB Chat сменились — перезапускаю ядро")
                        WbChatCore.stop()
                        notBefore = 0L
                    } else {
                        probeIfDue()
                        Thread.sleep(TICK_MILLIS)
                        continue
                    }
                } else if (WbChatCore.state is WbChatCore.State.Ready) {
                    // Было готово и вышло само.
                    val lived = SystemClock.elapsedRealtime() - WbChatCore.readyAt
                    WbChatCore.noteExited()
                    // Долгий звонок, кончившийся сам, — обычный перезапуск, не «неудача подряд».
                    fails = if (lived >= Pult.long("wb.storozh.healthy_millis", HEALTHY_MILLIS)) 0 else fails + 1
                    notBefore = SystemClock.elapsedRealtime() + pauseAfter(fails)
                    KLog.i(TAG, "ядро WB Chat вышло, прожив ${lived / 1000} с — перезапуск через ${pauseAfter(fails) / 1000} с")
                    // Автомат стоит на ней или пробует: сокс больше не слушает — так и пишем.
                    if (autoWants()) {
                        noted = true
                        PathRegistry.raising(PathId.WB)
                    }
                    probedAt = 0L
                }

                val wait = notBefore - SystemClock.elapsedRealtime()
                if (wait > 0) {
                    Thread.sleep(minOf(wait, TICK_MILLIS))
                    continue
                }

                if (startedOnce) {
                    restarts++
                    KLog.i(TAG, "поднимаю WB Chat заново (неудач подряд: $fails)")
                }
                startedOnce = true
                probedAt = 0L
                if (autoWants()) {
                    noted = true
                    PathRegistry.raising(PathId.WB)
                }
                val error = WbChatCore.start(params)
                if (!active) break
                if (error != null) {
                    if (autoWants()) PathRegistry.dead(PathId.WB, error)
                    fails++
                    notBefore = SystemClock.elapsedRealtime() + pauseAfter(fails)
                    KLog.w(TAG, "WB Chat не поднят ($error) — следующая попытка через ${pauseAfter(fails) / 1000} с")
                } else {
                    notBefore = 0L
                    // Выбор «Комнаты WB» ждал готовности ядра — пусть автомат применит его сейчас.
                    runCatching { AutoMode.wbReady() }
                }
            }
        } catch (_: InterruptedException) {
            // остановка
        }
    }
}
