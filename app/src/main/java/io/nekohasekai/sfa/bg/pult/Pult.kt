package io.nekohasekai.sfa.bg.pult

import io.nekohasekai.sfa.bg.KLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/**
 * Пульт в приложении: три слоя значений (код → принятый с диска → свежий из /info),
 * доступ к настройкам, исполнение команд, заголовок `X-Pult`.
 *
 * Сам по себе к Android не обращается: до [zagruzit] (и в JVM-тестах) все значения —
 * из кода, то есть поведение прежнее. Диск и исполнитель команд подключает [Application].
 */
object Pult {
    private const val TAG = "KelevraPult"
    private const val FAYL = "pult-sostoyanie.json"

    @Volatile
    private var s: PultSostoyanie = PultSostoyanie()

    @Volatile
    private var dir: File? = null

    private val zamok = Any()

    /** Исполнитель команд и реакций на смену настроек; ставится при старте приложения. */
    interface Ispolnitel {
        suspend fun vypolnit(z: PultYadro.Zadanie): String
        fun nastroykiIzmenilis() {}
        fun obnovlenieIzmenilos(o: PultYadro.Obnovlenie?) {}
    }

    @Volatile
    var ispolnitel: Ispolnitel? = null

    /**
     * Хранилище пользователя открыто (телефон разблокирован после перезагрузки). Ставит
     * [io.nekohasekai.sfa.Application]; в JVM-тестах — всегда да.
     */
    @Volatile
    var razblokirovano: () -> Boolean = { true }

    /** Причина смерти прошлого процесса по записи системы ([PultYadro.naStarte]); ставит Application. */
    @Volatile
    var proshlyyUpal: () -> Boolean? = { null }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Команды выполняются по одной: «перейди на ключ» и «обнови профиль» не должны толкаться. */
    private val ochered = Mutex()

    val sostoyanie: PultSostoyanie get() = s

    // ---- слои значений ----

    private fun syroe(key: String): Any? = s.nastroyki[key]

    private fun umolch(key: String): Any? = PultReestr.klyuch(key)?.umolch

    /** Целое из пульта; ключа нет — [default]. Значение из пульта уже проверено по границам. */
    fun int(key: String, default: Int): Int = (syroe(key) as? Int) ?: default

    /** Целое с умолчанием из реестра (= константа в коде). */
    fun int(key: String): Int = int(key, (umolch(key) as? Int) ?: error("нет ключа $key в реестре"))

    fun long(key: String, default: Long): Long = (syroe(key) as? Int)?.toLong() ?: default

    fun bool(key: String, default: Boolean): Boolean = (syroe(key) as? Boolean) ?: default

    fun bool(key: String): Boolean = bool(key, (umolch(key) as? Boolean) ?: false)

    /** Есть ли ключ в пульте вообще: «нет ключа» и «ключ = умолчанию» иногда различаются. */
    fun zadan(key: String): Boolean = s.nastroyki.containsKey(key)

    fun vybor(key: String): String = (syroe(key) as? String) ?: (umolch(key) as? String).orEmpty()

    /** Время из пульта в миллисекундах; null — ключа нет. */
    fun vremya(key: String): Long? = (syroe(key) as? String)?.let { PultVremya.razobrat(it) }

    @Suppress("UNCHECKED_CAST")
    fun spisok(key: String): List<String>? = syroe(key) as? List<String>

    /** Строка из пульта; null — ключа нет. */
    fun stroka(key: String): String? = syroe(key) as? String

    /** Подсистема выключена с сервера (`vykl.<imya>`). */
    fun vykl(imya: String): Boolean = bool("vykl.$imya", false)

    // ---- диск ----

    /**
     * Поднять принятый пульт с диска — один раз на процесс. Звать из фона после разблокировки
     * (filesDir — шифрованное хранилище). Повторный вызов ничего не делает: так его можно
     * звать и из фоновых работ, которые могут стартовать раньше [Application].
     *
     * До разблокировки (процесс поднят плиткой, она directBootAware) файл не читается, и
     * раньше это было неотличимо от «файла нет»: пустое состояние запоминалось до конца
     * процесса и первой же записью затирало принятый пульт вместе с испытанием. Теперь до
     * разблокировки и при нечитаемом файле не поднимаем вовсе — поднимет следующий вызов.
     */
    fun zagruzit(papka: File) {
        synchronized(zamok) {
            if (dir != null) return
            if (!runCatching { razblokirovano() }.getOrDefault(true)) {
                KLog.i(TAG, "телефон ещё не разблокирован — пульт с диска подниму позже")
                return
            }
            val fayl = File(papka, FAYL)
            val tekst = if (fayl.exists()) {
                runCatching { fayl.readText() }.getOrElse {
                    KLog.w(TAG, "пульт с диска не прочитался: ${it.javaClass.simpleName}: ${it.message} — подниму позже")
                    return
                }
            } else {
                null
            }
            // Битый текст — не беда хранилища, ждать нечего: начинаем с пустого, как раньше.
            val prochitano = tekst?.let { t -> runCatching { PultSostoyanie.izJson(JSONObject(t)) }.getOrNull() }
            dir = papka
            val itog = PultYadro.naStarte(
                prochitano ?: PultSostoyanie(),
                System.currentTimeMillis(),
                io.nekohasekai.sfa.BuildConfig.VERSION_CODE,
                runCatching { proshlyyUpal() }.getOrNull(),
            )
            s = itog.sostoyanie
            itog.zhurnal.forEach { KLog.w(TAG, it) }
            sohranit()
            KLog.i(
                TAG,
                "пульт с диска: версия ${s.versiya}, настроек ${s.nastroyki.size}" +
                    (s.ispytanie?.let { ", на испытании v${it.versiya}" } ?: ""),
            )
            if (itog.nastroykiIzmenilis) ispolnitel?.nastroykiIzmenilis()
        }
    }

    /** Служба запущена: с этого момента смерть процесса — падение. Прожила [PultYadro.ZHIZN_BEZ_PADENIYA_MS] — счётчик падений с нуля. */
    fun sluzhbaPoshla() {
        zagruzitEsliNado()
        synchronized(zamok) {
            s = s.copy(sluzhbaZhila = true)
            sohranit()
        }
        scope.launch {
            kotlinx.coroutines.delay(PultYadro.ZHIZN_BEZ_PADENIYA_MS)
            synchronized(zamok) {
                if (!s.sluzhbaZhila) return@launch
                val bylo = s
                s = PultYadro.prozhilBezPadeniya(s)
                if (s !== bylo) sohranit()
            }
        }
    }

    /** Служба остановлена штатно (нами): дальнейшая смерть процесса падением не считается. */
    fun sluzhbaOstanovlena() {
        synchronized(zamok) {
            if (!s.sluzhbaZhila) return
            s = s.copy(sluzhbaZhila = false)
            sohranit()
        }
    }

    /** То же для фоновых работ и службы: пульт должен быть поднят до чтения настроек. */
    fun zagruzitEsliNado() {
        if (dir != null) return
        runCatching { zagruzit(io.nekohasekai.sfa.Application.application.filesDir) }
    }

    private fun sohranit() {
        val papka = dir ?: return
        runCatching {
            val tmp = File(papka, "$FAYL.tmp")
            tmp.writeText(s.vJson().toString())
            if (!tmp.renameTo(File(papka, FAYL))) {
                File(papka, FAYL).writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { KLog.w(TAG, "пульт не записался на диск: ${it.message}") }
    }

    private fun primenit(itog: PultYadro.Itog) {
        s = itog.sostoyanie
        sohranit()
        itog.zhurnal.forEach { KLog.i(TAG, it) }
    }

    // ---- приём ----

    /** Блок `pult` из ответа /info. Нет блока — не меняется ничего (держим слой 2). */
    fun prinyatIzSvodki(svodka: JSONObject, deviceId: String) {
        val blok = svodka.optJSONObject("pult") ?: return
        val itog: PultYadro.Itog
        val bylo: PultYadro.Obnovlenie?
        synchronized(zamok) {
            bylo = s.obnovlenie
            itog = PultYadro.prinyat(s, blok, deviceId, System.currentTimeMillis())
            primenit(itog)
        }
        if (itog.nastroykiIzmenilis) runCatching { ispolnitel?.nastroykiIzmenilis() }
        if (itog.sostoyanie.obnovlenie != bylo) runCatching { ispolnitel?.obnovlenieIzmenilos(itog.sostoyanie.obnovlenie) }
        for (z in itog.zadaniya) zapustit(z)
    }

    /**
     * Журнальные команды ([PultYadro.BEZ_OCHEREDI]) идут мимо общей очереди: они ждут ответа
     * сервера до 3 мин, и «перейди на ключ» за ними стоял бы минутами. Друг с другом им
     * толкаться нечего — отправку журнала разводит свой замок.
     */
    internal fun zapustit(z: PultYadro.Zadanie) {
        val isp = ispolnitel
        if (isp == null) {
            zavershit(z.id, "oshibka-net_ispolnitelya")
            return
        }
        scope.launch {
            if (z.chto in PultYadro.BEZ_OCHEREDI) vypolnitOdnu(isp, z) else ochered.withLock { vypolnitOdnu(isp, z) }
        }
    }

    private suspend fun vypolnitOdnu(isp: Ispolnitel, z: PultYadro.Zadanie) {
        KLog.i(TAG, "команда ${PultYadro.chistyyId(z.id)}: ${z.chto} — выполняю")
        val itog = runCatching { isp.vypolnit(z) }.getOrElse {
            KLog.w(TAG, "команда ${z.chto} сорвалась: ${io.nekohasekai.sfa.Kelevra.maskCode(it.toString())}")
            "oshibka-" + (it.javaClass.simpleName.lowercase().take(24))
        }
        KLog.i(TAG, "команда ${PultYadro.chistyyId(z.id)}: ${z.chto} — $itog")
        zavershit(z.id, itog)
    }

    fun zavershit(id: String, itog: String) {
        synchronized(zamok) {
            s = PultYadro.zavershit(s, id, itog, System.currentTimeMillis())
            sohranit()
        }
    }

    // ---- испытание ----

    /** Отметка испытания из службы: см. [PultYadro.otmetit]. */
    fun otmetit(nablyudaem: Boolean, zhivoy: Boolean) {
        if (s.ispytanie == null) return
        val itog: PultYadro.Itog
        synchronized(zamok) {
            itog = PultYadro.otmetit(s, System.currentTimeMillis(), nablyudaem, zhivoy)
            primenit(itog)
        }
        if (itog.nastroykiIzmenilis) runCatching { ispolnitel?.nastroykiIzmenilis() }
    }

    // ---- выкатка ----

    /** Сбой выкатки через пульт — в `X-Pult` (`oshibki=obnovlenie:<prichina>`). */
    fun oshibkaObnovleniya(prichina: String, navsegda: Boolean) {
        synchronized(zamok) {
            val o = s.obnovlenie
            s = s.copy(
                oshibkiObnovleniya = (s.oshibkiObnovleniya + prichina).distinct().takeLast(5),
                plohieObnovleniya = if (navsegda && o != null) s.plohieObnovleniya + "${o.versiyaKod}:${o.sha256}" else s.plohieObnovleniya,
            )
            sohranit()
        }
        KLog.w(TAG, "выкатка через пульт: $prichina${if (navsegda) " — эту сборку больше не беру" else ""}")
    }

    // ---- заголовок ----

    fun zagolovok(): String = PultYadro.zagolovok(s, kod = io.nekohasekai.sfa.BuildConfig.VERSION_CODE)

    /** Только для тестов: настройки в силе без диска и подписи. */
    internal fun dlyaTesta(sostoyanie: PultSostoyanie) {
        s = sostoyanie
        dir = null
    }
}
