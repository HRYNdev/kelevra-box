package io.nekohasekai.sfa.bg

import io.nekohasekai.libbox.Libbox
import org.json.JSONObject

/**
 * Признак нового рукопожатия Reality (`tls.reality.support_x25519mlkem768`) — только тому ядру,
 * которое его знает.
 *
 * Сервер пишет признак по номеру сборки из User-Agent (`KELEVRA_MLKEM_BOX_MIN`, на 25.10.2026 —
 * 159) и по ключу пульта `set.mlkem`. Ядро с правками ML-KEM живёт в отдельной сборке; сборка
 * с бо́льшим номером без этих правок получила бы признак и не приняла бы конфиг вовсе
 * («json: unknown field "support_x25519mlkem768"», проверено на эмуляторе 25.10.2026) — ни
 * обновления профиля, ни туннеля. Поэтому ядро, не знающее признака, получает конфиг без него:
 * ровно тот конфиг, что был до ML-KEM. Знает ли ядро — спрашиваем у него самого один раз.
 */
object MlkemPriznak {
    private const val TAG = "MlkemPriznak"
    const val POLE = "support_x25519mlkem768"

    private val PROBA = """
        {"outbounds":[{"type":"vless","tag":"p","server":"127.0.0.1","server_port":1,
        "uuid":"00000000-0000-0000-0000-000000000000","tls":{"enabled":true,"server_name":"a.invalid",
        "utls":{"enabled":true,"fingerprint":"chrome"},"reality":{"enabled":true,
        "public_key":"jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0","short_id":"01","$POLE":true}}}]}
    """.trimIndent()

    /** Ядро этой сборки знает признак. Не вышло спросить — считаем, что знает (поведение как раньше). */
    val yadroZnaet: Boolean by lazy {
        val znaet = runCatching { Libbox.checkConfig(PROBA); true }
            .getOrElse { !neZnaet(it.message) }
        KLog.i(TAG, if (znaet) "ядро знает признак ML-KEM" else "ядро не знает признак ML-KEM — убираю его из конфига")
        znaet
    }

    internal fun neZnaet(oshibka: String?): Boolean =
        oshibka != null && oshibka.contains("unknown field") && oshibka.contains(POLE)

    /** Конфиг, который примет ядро этой сборки. */
    fun dlyaYadra(content: String): String = dlyaYadra(content) { yadroZnaet }

    internal fun dlyaYadra(content: String, znaet: () -> Boolean): String {
        if (!content.contains(POLE) || znaet()) return content
        return runCatching {
            val root = JSONObject(content)
            val outbounds = root.optJSONArray("outbounds") ?: return content
            var ubrano = 0
            for (i in 0 until outbounds.length()) {
                val reality = outbounds.optJSONObject(i)?.optJSONObject("tls")?.optJSONObject("reality") ?: continue
                if (reality.remove(POLE) != null) ubrano++
            }
            if (ubrano == 0) content else root.toString()
        }.getOrDefault(content)
    }

    /** [Libbox.checkConfig] для конфига с сервера: признак, которого ядро не знает, отказом не считается. */
    fun proverit(content: String) = Libbox.checkConfig(dlyaYadra(content))
}
