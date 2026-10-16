package io.nekohasekai.sfa.bg

import android.os.SystemClock
import android.util.Log

/**
 * Держит ядро WB Chat ([WbChatCore]) запущенным, пока оно нужно.
 *
 * Простейшая петля, отдельно от [OlcRtcWatchdog] и без связи с автоматом: «нужно» — это
 * поднятая служба с туннелем ([wanted]) и параметры с сервера ([WbChatParams.available]).
 * Выбор в селекторе здесь не учитывается: ядро держится, выбран выход или нет.
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

    /** Пауза перед очередным подъёмом после [fails] неудач подряд. */
    internal fun pauseAfter(fails: Int): Long = if (fails >= FAILS_BEFORE_SLOW) SLOW_PAUSE_MILLIS else RESTART_PAUSE_MILLIS

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
            active = true
            thread = Thread(::loop, "wbchat-watchdog").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "присмотр WB Chat включён")
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
            Log.i(TAG, "присмотр WB Chat выключен (перезапусков за сессию: $restarts)")
        }
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
                    if (WbChatCore.isRunning() || WbChatCore.state !is WbChatCore.State.Idle) {
                        Log.i(TAG, if (need) "параметров WB Chat нет — гашу ядро" else "WB Chat сейчас не нужен — гашу ядро")
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
                        Log.i(TAG, "параметры WB Chat сменились — перезапускаю ядро")
                        WbChatCore.stop()
                        notBefore = 0L
                    } else {
                        Thread.sleep(TICK_MILLIS)
                        continue
                    }
                } else if (WbChatCore.state is WbChatCore.State.Ready) {
                    // Было готово и вышло само.
                    val lived = SystemClock.elapsedRealtime() - WbChatCore.readyAt
                    WbChatCore.noteExited()
                    // Долгий звонок, кончившийся сам, — обычный перезапуск, не «неудача подряд».
                    fails = if (lived >= HEALTHY_MILLIS) 0 else fails + 1
                    notBefore = SystemClock.elapsedRealtime() + pauseAfter(fails)
                    Log.i(TAG, "ядро WB Chat вышло, прожив ${lived / 1000} с — перезапуск через ${pauseAfter(fails) / 1000} с")
                }

                val wait = notBefore - SystemClock.elapsedRealtime()
                if (wait > 0) {
                    Thread.sleep(minOf(wait, TICK_MILLIS))
                    continue
                }

                if (startedOnce) {
                    restarts++
                    Log.i(TAG, "поднимаю WB Chat заново (неудач подряд: $fails)")
                }
                startedOnce = true
                val error = WbChatCore.start(params)
                if (!active) break
                if (error != null) {
                    fails++
                    notBefore = SystemClock.elapsedRealtime() + pauseAfter(fails)
                    Log.w(TAG, "WB Chat не поднят ($error) — следующая попытка через ${pauseAfter(fails) / 1000} с")
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
