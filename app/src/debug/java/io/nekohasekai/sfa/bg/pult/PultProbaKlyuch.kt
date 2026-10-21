package io.nekohasekai.sfa.bg.pult

/**
 * Проверочный открытый ключ пульта — ТОЛЬКО debug-сборка (этот файл лежит в `src/debug`;
 * в `src/release` такой же объект с `null`). Им подписывается пульт локальной выдачи на стенде
 * и эмуляторе, без боевого ключа мозга. Закрытая половина — только в scratchpad сессии,
 * никуда больше не кладётся. В релизном APK этой строки нет (проверка — grep по dex).
 */
object PultProbaKlyuch {
    val HEX: String? = "2c5d24e0ad1fbbd4130a245c662670c4ad450d2c237babdef5e43c792888365a"
}
