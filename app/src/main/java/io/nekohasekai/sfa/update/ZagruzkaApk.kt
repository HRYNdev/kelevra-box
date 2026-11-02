package io.nekohasekai.sfa.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Ответ сервера на запрос файла начиная с байта. [dlinaTela] — длина именно этого ответа
 * (-1, если сервер её не назвал), [nachalo] — первый байт из Content-Range у ответа 206.
 */
class OtvetZagruzki(
    val kod: Int,
    val dlinaTela: Long,
    val nachalo: Long?,
    val telo: InputStream,
    private val zakryt: () -> Unit = {},
) : Closeable {
    override fun close() {
        runCatching { telo.close() }
        zakryt()
    }
}

/** Каждый вызов — новое соединение: повтор не должен уйти в зависшее старое. */
fun interface IstochnikZagruzki {
    fun otkryt(url: String, sBayta: Long): OtvetZagruzki
}

/**
 * Загрузка файла обновления — одна на всё приложение: фоновая проверка, повтор после отказа,
 * сборка из пульта и кнопка «Обновить» идут сюда.
 *
 * Что здесь решено и почему (журналы телефонов, октябрь–ноябрь 2026):
 * - уже скачанный и сверенный `update-<код>.apk` берётся, а не качается заново: одну и ту же
 *   сборку телефон качал по семь раз — на каждую проверку и каждое напоминание;
 * - недокачанное лежит в постоянном `update-<код>.apk.part` и докачивается запросом Range:
 *   55 МБ на сети, которая рвётся через 1,5–4,6 минуты, иначе не скачать никогда — раньше
 *   временный файл был со случайным именем и стирался перед каждой попыткой;
 * - повторы с нарастающей паузой и каждый раз новым соединением;
 * - одна загрузка за раз ([zamok]): вторая ждёт первую и берёт её готовый файл.
 *
 * Логика без Android: сеть, проверка файла и пауза подставляются, поэтому проверяется тестами.
 */
object ZagruzkaApk {
    /** Попыток за один вызов; недокачанное между вызовами не теряется. */
    const val POPYTOK = 4

    /** Пауза перед второй, третьей и четвёртой попыткой. */
    val PAUZY_MS = listOf(5_000L, 20_000L, 60_000L)

    private const val BUFER = 64 * 1024

    private val zamok = Mutex()

    /** Как писать ответ сервера в недокачанный файл длиной [s]. */
    enum class Zapis { DOPISAT, SNACHALA, VSYO_EST, SBROS }

    /**
     * 206 с нужного байта — дописать; 200 — сервер отдал файл целиком, пишем с нуля;
     * 416 при непустом файле — докачивать нечего, решит проверка. Остальное — сбой; если
     * сервер отдал кусок не с того места, недокачанное сбрасывается.
     */
    fun kakPisat(s: Long, kod: Int, nachalo: Long?): Zapis = when {
        kod == 200 -> Zapis.SNACHALA
        kod == 206 && s > 0 && nachalo == s -> Zapis.DOPISAT
        kod == 206 && s > 0 -> Zapis.SBROS
        kod == 416 && s > 0 -> Zapis.VSYO_EST
        else -> throw IOException("сервер ответил $kod")
    }

    /** Первый байт из «bytes 100-999/1000»; иное — null. */
    fun nachaloIzContentRange(zagolovok: String?): Long? =
        zagolovok?.trim()?.let { Regex("""^bytes\s+(\d+)-\d+/(\d+|\*)$""").matchEntire(it) }
            ?.groupValues?.get(1)?.toLongOrNull()

    /** Пауза перед попыткой [popytka] (с 2-й). */
    fun pauzaPered(popytka: Int): Long = PAUZY_MS.getOrElse(popytka - 2) { PAUZY_MS.last() }

    /**
     * Сверенный файл версии [versionCode] в [papka]: готовый — сразу, иначе скачивается.
     *
     * [proverit] — null, если файл годен, иначе причина словами. [dokachka] — можно ли
     * продолжать недокачанное: только когда в конце есть хеш, иначе склейку двух разных
     * файлов одного номера не поймать. При версии 0 (номер неизвестен) готовый файл не
     * берётся и недокачанное не продолжается.
     *
     * Сетевой сбой после всех попыток выбрасывается как есть, недокачанное остаётся для
     * следующего вызова. Файл, не прошедший проверку, стирается; после всех попыток —
     * [FaylNeGoden].
     */
    suspend fun poluchit(
        papka: File,
        versionCode: Int,
        ozhidaemyyRazmer: Long,
        dokachka: Boolean,
        url: String,
        istochnik: IstochnikZagruzki,
        proverit: (File) -> String?,
        progress: (skachano: Long, vsego: Long) -> Unit = { _, _ -> },
        zhurnal: (String) -> Unit = {},
        peredSetyu: suspend () -> Unit = {},
        pauza: suspend (Long) -> Unit = { delay(it) },
    ): File = zamok.withLock {
        papka.mkdirs()
        val gotovyy = File(papka, IshodUstanovki.imyaFayla(versionCode))
        val chast = File(papka, IshodUstanovki.imyaChastichnogoFayla(versionCode))
        IshodUstanovki.kUdaleniyu(papka.listFiles()?.toList() ?: emptyList(), versionCode).forEach { it.delete() }

        if (gotovyy.exists()) {
            val otkaz = if (versionCode > 0) proverit(gotovyy) else "номер версии неизвестен"
            if (otkaz == null) {
                zhurnal("файл обновления уже скачан и сверен, качать не нужно: ${gotovyy.length()} байт")
                return@withLock gotovyy
            }
            zhurnal("скачанный ранее файл обновления не годен ($otkaz), качаю заново")
            gotovyy.delete()
        }
        if (!(dokachka && versionCode > 0)) chast.delete()

        var setUzhe = false
        var sboy: Exception? = null
        var prichina = "не скачан"
        for (popytka in 1..POPYTOK) {
            if (popytka > 1) {
                val ms = pauzaPered(popytka)
                zhurnal("повтор скачивания через ${ms / 1000} с (попытка $popytka из $POPYTOK)")
                pauza(ms)
            }
            try {
                if (!setUzhe) {
                    setUzhe = true
                    peredSetyu()
                }
                dokachat(url, chast, ozhidaemyyRazmer, istochnik, progress, zhurnal)
            } catch (e: CancellationException) {
                // Недокачанное не стирается: работу остановила система, следующая продолжит.
                throw e
            } catch (e: Exception) {
                sboy = e
                zhurnal("скачивание обновления сорвалось (попытка $popytka из $POPYTOK, есть ${chast.length()} байт): ${e.message}")
                continue
            }
            val otkaz = proverit(chast)
            if (otkaz == null) {
                gotovyy.delete()
                if (!chast.renameTo(gotovyy)) {
                    chast.copyTo(gotovyy, overwrite = true)
                    chast.delete()
                }
                zhurnal("файл обновления скачан и сверен: ${gotovyy.length()} байт")
                return@withLock gotovyy
            }
            sboy = null
            prichina = otkaz
            zhurnal("файл обновления не прошёл проверку (попытка $popytka из $POPYTOK): $otkaz — качаю с нуля")
            chast.delete()
        }
        throw sboy ?: FaylNeGoden(prichina)
    }

    private suspend fun dokachat(
        url: String,
        chast: File,
        razmer: Long,
        istochnik: IstochnikZagruzki,
        progress: (Long, Long) -> Unit,
        zhurnal: (String) -> Unit,
    ) {
        var s = if (chast.exists()) chast.length() else 0L
        if (razmer > 0 && s > razmer) {
            chast.delete()
            s = 0L
        }
        if (razmer > 0 && s == razmer) return
        if (s > 0) zhurnal("докачиваю обновление с байта $s")
        istochnik.otkryt(url, s).use { otvet ->
            val zapis = kakPisat(s, otvet.kod, otvet.nachalo)
            when (zapis) {
                Zapis.VSYO_EST -> return
                Zapis.SBROS -> {
                    chast.delete()
                    throw IOException("сервер отдал кусок не с байта $s")
                }
                Zapis.SNACHALA -> if (s > 0) zhurnal("сервер не отдаёт кусками — качаю с нуля")
                Zapis.DOPISAT -> Unit
            }
            val dopisat = zapis == Zapis.DOPISAT
            val nachalo = if (dopisat) s else 0L
            val vsego = when {
                razmer > 0 -> razmer
                otvet.dlinaTela >= 0 -> nachalo + otvet.dlinaTela
                else -> -1L
            }
            var zapisano = 0L
            FileOutputStream(chast, dopisat).use { out ->
                val bufer = ByteArray(BUFER)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = otvet.telo.read(bufer)
                    if (n < 0) break
                    out.write(bufer, 0, n)
                    zapisano += n
                    progress(nachalo + zapisano, vsego)
                }
            }
            if (otvet.dlinaTela >= 0 && zapisano < otvet.dlinaTela) {
                throw EOFException("соединение закрылось на ${nachalo + zapisano} байте из $vsego")
            }
        }
    }
}
