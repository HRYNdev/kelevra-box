package io.nekohasekai.sfa.bg

import android.util.Log
import io.nekohasekai.sfa.Application
import org.json.JSONObject

/**
 * Записи с полями: то же, что журнал, но не текстом, а данными.
 *
 * Зачем отдельным файлом, а не переделкой журнала. Журнал ядра возится и разбирается
 * с 05.09.2026, на нём держится вся диагностика; менять его вид значит на день-другой
 * остаться без разбора вообще, пока обновление доезжает до людей. Здесь новый файл
 * рядом: приезжает той же отправкой (она забирает весь каталог), разбирается отдельно,
 * а если в нём что-то не так — текстовый журнал продолжает работать как раньше.
 *
 * Что даёт. Разбор перестаёт зависеть от того, как ядро написало фразу: сегодня сервер
 * достаёт из строк отказы регулярными выражениями, и любая правка формулировки в ядре
 * тихо ломает ось разбора. Поля не ломаются.
 *
 * Состав записи. `v` — версия схемы (менять формат можно, не угадывая, что на сервере),
 * `t` — тип, `ts` — время в миллисекундах, дальше поля по типу. Одна строка — одна
 * запись, файл читается построчно и переживает обрыв на середине: битой окажется
 * последняя строка, а не всё.
 */
object Zapisi {
    private const val TAG = "KelevraZapisi"

    const val BASE_NAME = "kelevra-zapisi.jsonl"

    /** Записи весят копейки против потока ядра, поэтому и места им меньше. */
    private const val MAX_FILE_BYTES = 512L * 1024
    private const val MAX_FILES = 3

    /** Версия схемы. Растёт, когда меняется СМЫСЛ полей, а не когда добавилось новое. */
    private const val SHEMA = 1

    @Volatile
    private var rotator: LogRotator? = null

    @Synchronized
    fun start() {
        if (rotator != null) return
        runCatching {
            val folder = AppLog.dir(Application.application)
            if (!folder.isDirectory && !folder.mkdirs()) error("каталог записей не создан: $folder")
            rotator = LogRotator(folder, BASE_NAME, MAX_FILE_BYTES, MAX_FILES)
        }.onFailure { Log.w(TAG, "записи не открылись: ${it.message}") }
    }

    @Synchronized
    fun stop() {
        runCatching { rotator?.close() }
        rotator = null
    }

    /**
     * Записать одно событие. Тип и поля решает вызывающий: этот объект ничего не знает
     * про смысл, он только складывает.
     *
     * Ошибка записи гасится: запись — сведения о работе, а не работа. Уронить туннель
     * из-за журнала было бы обменом наоборот.
     */
    fun zapisat(tip: String, polya: Map<String, Any?>) {
        val target = rotator ?: return
        runCatching {
            val o = JSONObject()
            o.put("v", SHEMA)
            o.put("t", tip)
            o.put("ts", System.currentTimeMillis())
            for ((k, v) in polya) {
                if (v != null) o.put(k, v)
            }
            target.append(o.toString() + "\n")
        }.onFailure { Log.w(TAG, "запись $tip не легла: ${it.message}") }
    }

    /** Отказ соединения: главная запись, по которой ставится диагноз. */
    fun otkaz(imya: String, adres: String, port: Int, kod: String, vyhod: String, ms: Long) {
        zapisat(
            "otkaz",
            mapOf(
                "imya" to imya.ifEmpty { null },
                "adres" to adres,
                "sem" to if (adres.contains(':')) 6 else 4,
                "port" to port,
                "kod" to kod,
                "vyhod" to vyhod.ifEmpty { null },
                "ms" to if (ms > 0) ms else null,
            ),
        )
    }

    /** Сводка за окно: переживает ротацию потока и потому единственный след прошлого часа. */
    fun svodka(
        okno: Int,
        soed: Int,
        otkazy: Int,
        kody: Map<String, Int>,
        imena: Map<String, Int>,
    ) {
        zapisat(
            "svodka",
            mapOf(
                "okno" to okno,
                "soed" to soed,
                "otkazy" to otkazy,
                "kody" to JSONObject(kody as Map<*, *>),
                "imena" to JSONObject(imena as Map<*, *>),
            ),
        )
    }

    /** Переход состояния: туннель, сеть, разрешение, профиль. */
    fun perehod(kod: String, podrobno: String? = null) {
        zapisat("perehod", mapOf("kod" to kod, "podrobno" to podrobno))
    }

    /** Обстановка: что за сеть и как настроено. Пишется на старте и при смене сети. */
    fun sreda(polya: Map<String, Any?>) {
        zapisat("sreda", polya)
    }
}
