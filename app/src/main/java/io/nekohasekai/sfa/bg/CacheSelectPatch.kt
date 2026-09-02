package io.nekohasekai.sfa.bg

import android.util.Log
import org.json.JSONObject

/**
 * Выбор выхода не переживает перезапуск ядра.
 *
 * Зачем. В конфиге с сервера включён `experimental.cache_file`, и sing-box пишет в него
 * бакет `selected` с тем выходом, который стоит в селекторе прямо сейчас. Этот выбор
 * восстанавливается при каждом старте и **перекрывает `default`, который раздаёт сервер**.
 *
 * Чем это ударило 15.08.2026. Пока шёл BGP-блэкхол до VPS, автомат увёл телефон в комнату.
 * Комната выходит через дом, дом упирался в тот же мёртвый VPS, и наружу работало только
 * российское. Канал давно починился, а телефон продолжал сидеть в комнате: выбор залип
 * в кэше и вставал обратно при каждом запуске. Отпустило лишь тогда, когда стёрлись данные
 * приложения и кэш ушёл вместе с ними — после этого связь сразу заработала целиком.
 *
 * Почему выключить безопасно. Ручной выбор человека хранит само приложение
 * ([AutoMode.chooseManually] пишет `Settings.manualExitName`), живёт он час и отпускается
 * при смене сети. То есть кэш ядра тут ничего не помнит сверх того, что помним мы, зато
 * умеет пережить то, что мы намеренно забыли. Само хранилище остаётся на месте: в нём
 * лежат и другие вещи (наборы правил), поэтому гасится ровно `store_selected`.
 */
object CacheSelectPatch {
    private const val TAG = "BoxService"

    data class Result(val content: String, val note: String, val patched: Boolean)

    fun dontStoreSelected(content: String): Result = runCatching { patch(content) }.getOrElse {
        // Конфиг приходит с сервера и меняется. Уронить старт из-за неудачной правки
        // хуже, чем оставить кэш как есть.
        Result(content, "выбор выхода не отвязан от кэша (${it.javaClass.simpleName}), конфиг как есть", false)
    }

    private fun patch(content: String): Result {
        val root = JSONObject(content)
        val experimental = root.optJSONObject("experimental")
            ?: return Result(content, "в конфиге нет experimental, кэш выбора не используется", false)
        val cacheFile = experimental.optJSONObject("cache_file")
            ?: return Result(content, "в конфиге нет cache_file, выбор и так не сохраняется", false)

        if (!cacheFile.optBoolean("enabled", false)) {
            return Result(content, "cache_file выключен, выбор и так не сохраняется", false)
        }
        if (!cacheFile.optBoolean("store_selected", false)) {
            return Result(content, "store_selected уже выключен", false)
        }

        cacheFile.put("store_selected", false)
        return Result(
            root.toString(),
            "store_selected выключен: старт идёт с default сервера, а не с прошлого выбора",
            true,
        )
    }

    fun log(result: Result) {
        if (result.patched) Log.i(TAG, result.note) else Log.i(TAG, result.note)
    }
}
