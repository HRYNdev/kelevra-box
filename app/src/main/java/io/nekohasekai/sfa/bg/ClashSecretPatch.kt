package io.nekohasekai.sfa.bg

import android.util.Log
import org.json.JSONObject

/**
 * Служебный порт ядра (Clash API, `experimental.clash_api`) закрывается паролем устройства.
 *
 * Зачем. Шаблон профиля с сервера открывает порт на 127.0.0.1:9090 без пароля. Любое
 * приложение телефона, которому доступен интернет, ходит на loopback: читает список
 * живых соединений (домены и приложения человека) и переключает выход.
 *
 * Как устроено. Пароль создаётся на телефоне при первом обращении и лежит в приватной
 * базе приложения ([io.nekohasekai.sfa.database.Settings.clashSecret]); сервер его не знает
 * и не присылает. Перед запуском ядра пароль вписывается в `clash_api.secret`, а тот, кто
 * ходит к порту ([ImenaSaytov]), шлёт его заголовком `Authorization: Bearer`.
 *
 * Совместимость. Профиль со своим паролем остаётся как есть, и этот пароль и возвращается
 * (старые и новые шаблоны работают в любом сочетании). Секции `clash_api` нет — порта нет,
 * ничего не создаём. Конфиг приходит с сервера и меняется: правка не должна ронять старт,
 * при любой неудаче конфиг остаётся как был.
 */
object ClashSecretPatch {
    private const val TAG = "BoxService"

    /** [secret] — пароль, который действует в итоговом конфиге; пусто — порт без пароля. */
    data class Result(val content: String, val note: String, val patched: Boolean, val secret: String)

    /** [deviceSecret] берётся лениво: если профиль уже со своим паролем, свой не создаём. */
    fun addSecret(content: String, deviceSecret: () -> String): Result = runCatching {
        patch(content, deviceSecret)
    }.getOrElse {
        Result(content, "пароль Clash API не вписан (${it.javaClass.simpleName}), конфиг как есть", false, "")
    }

    private fun patch(content: String, deviceSecret: () -> String): Result {
        val root = JSONObject(content)
        val clash = root.optJSONObject("experimental")?.optJSONObject("clash_api")
            ?: return Result(content, "в конфиге нет clash_api, закрывать нечего", false, "")
        val fromProfile = clash.optString("secret", "")
        if (fromProfile.isNotEmpty()) {
            return Result(content, "пароль Clash API задан профилем", false, fromProfile)
        }
        val own = deviceSecret()
        if (own.isEmpty()) {
            return Result(content, "пароль устройства не создан, Clash API без пароля", false, "")
        }
        clash.put("secret", own)
        return Result(root.toString(), "Clash API закрыт паролем устройства", true, own)
    }

    fun log(result: Result) {
        Log.i(TAG, result.note)
    }
}
