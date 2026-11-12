package io.nekohasekai.sfa.compose.screen.home

import io.nekohasekai.sfa.Kelevra
import io.nekohasekai.sfa.ZapasnoyPut
import io.nekohasekai.sfa.bg.KLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Оплата подписки: что сервер говорит про оплату и отправка «Я оплатил».
 *
 * Спрашивается только когда приложение на экране (главный экран зовёт [obnovit] при показе),
 * фоновых работ нет. Ходит тем же путём, что `/info` ([loadSubscription]): прямо к двери
 * (дверь в профиле идёт `direct`, мимо узлов — снятая с узлов учётка запрос не ломает),
 * при обрыве — через локальный вход туннеля и по другим дверям ([ZapasnoyPut.poluchit]).
 *
 * Сбой сети или сервера держит прежнее и ничего больше не трогает: ни сводку, ни экран.
 */
object OplataSostoyanie {
    private const val TAG = "KelevraOplata"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _info = MutableStateFlow<OplataInfo?>(null)

    /** null — не знаем или оплаты нет; на экране тогда ничего нового. */
    val info: StateFlow<OplataInfo?> = _info.asStateFlow()

    /** Отправка «Я оплатил». */
    sealed interface Otpravka {
        data object Net : Otpravka
        data object Idet : Otpravka

        /** 429: заявка уже ушла недавно. */
        data object UzheOtpravleno : Otpravka

        /** Сеть или сервер: кнопка снова доступна. */
        data object Oshibka : Otpravka
    }

    private val _otpravka = MutableStateFlow<Otpravka>(Otpravka.Net)
    val otpravka: StateFlow<Otpravka> = _otpravka.asStateFlow()

    /** Для какого адреса подписки [info] и когда спрашивали в последний раз. */
    @Volatile private var baza: String? = null
    @Volatile private var kogdaMs = 0L

    /** Адрес не умеет оплату (отдал не ответ оплаты): до перезапуска приложения не спрашиваем. */
    @Volatile private var neUmeet: String? = null

    /** Ответ двери. */
    private sealed interface Otvet {
        data class Est(val info: OplataInfo) : Otvet
        data object NeOplata : Otvet
        data object NeNajden : Otvet
        data object Chasto : Otvet
        data object Sboy : Otvet
    }

    /** Спросить `/oplata`, если пора ([shagOprosaOplaty]) или [sila]. */
    fun obnovit(sila: Boolean = false) {
        scope.launch { obnovitSeychas(sila) }
    }

    private suspend fun obnovitSeychas(sila: Boolean) {
        if (!lock.tryLock()) return
        try {
            val b = bazaPodpiski()
            if (b == null) {
                _info.value = null
                baza = null
                return
            }
            if (b != baza) {
                _info.value = null
                _otpravka.value = Otpravka.Net
                baza = b
                kogdaMs = 0L
            }
            if (b == neUmeet) return
            val now = System.currentTimeMillis()
            if (!sila && kogdaMs > 0L && now - kogdaMs < shagOprosaOplaty(_info.value?.zhdet == true)) return
            when (val o = zapros(b, post = false)) {
                is Otvet.Est -> {
                    _info.value = o.info.takeIf { it.platit }
                    kogdaMs = now
                }
                Otvet.NeNajden -> {
                    _info.value = null
                    kogdaMs = now
                }
                Otvet.NeOplata -> {
                    KLog.i(TAG, "дверь не отвечает про оплату — не спрашиваю до перезапуска")
                    _info.value = null
                    neUmeet = b
                }
                // Сбой и 429 подбора кода: держим прежнее, спросим при следующем показе экрана.
                Otvet.Chasto, Otvet.Sboy -> Unit
            }
        } catch (e: Exception) {
            KLog.w(TAG, "оплата не прочитана: ${e.javaClass.simpleName}")
        } finally {
            lock.unlock()
        }
    }

    /** «Я оплатил»: `POST /oplatil`. Итог — в [otpravka] и [info]. */
    fun oplatil() {
        if (_otpravka.value == Otpravka.Idet) return
        _otpravka.value = Otpravka.Idet
        scope.launch {
            val itog = runCatching {
                val b = bazaPodpiski() ?: return@runCatching Otpravka.Oshibka
                when (val o = zapros(b, post = true)) {
                    is Otvet.Est -> {
                        if (b == baza) {
                            _info.value = o.info.takeIf { it.platit }
                            kogdaMs = System.currentTimeMillis()
                        }
                        Otpravka.Net
                    }
                    Otvet.Chasto -> Otpravka.UzheOtpravleno
                    Otvet.NeNajden, Otvet.NeOplata, Otvet.Sboy -> Otpravka.Oshibka
                }
            }.getOrElse {
                KLog.w(TAG, "«Я оплатил» не отправлено: ${it.javaClass.simpleName}")
                Otpravka.Oshibka
            }
            KLog.i(TAG, "«Я оплатил»: ${itog.javaClass.simpleName}")
            _otpravka.value = itog
        }
    }

    /** Сбросить сообщение под кнопкой (шторка закрыта). */
    fun zabytOtpravku() {
        if (_otpravka.value != Otpravka.Idet) _otpravka.value = Otpravka.Net
    }

    /**
     * Заголовки устройства — те же, по которым дверь узнаёт устройство у `/info`; без пульта и
     * пульса: квитанции пульта едут только со сводкой.
     */
    private fun zagolovki(): List<Pair<String, String>> =
        listOf("User-Agent" to "kelevra") +
            Kelevra.deviceHeaders().filter { (n, _) ->
                n == "X-Device-Id" || n == "X-Device-Model" || n == "X-Device-Platform" || n == "X-App-Version"
            }

    private suspend fun zapros(b: String, post: Boolean): Otvet = withContext(Dispatchers.IO) {
        val url = Kelevra.adresSeti("$b/${if (post) "oplatil" else "oplata"}")
        val metod = if (post) "POST" else "GET"
        val zag = zagolovki()
        val (kod, telo) = try {
            ZapasnoyPut.poluchit(
                url,
                ZapasnoyPut.dveriDlyaPerebora(),
                ZapasnoyPut.tunnelIliNull { u ->
                    val o = ZapasnoyPut.zaprosCherezTunnel(
                        u,
                        metod = metod,
                        telo = if (post) ByteArray(0) else null,
                        zagolovkiDop = zag,
                    )
                    o.kod to o.telo
                },
            ) { u -> pryamo(u, metod, zag) }
        } catch (e: Exception) {
            KLog.w(TAG, "$metod оплаты: нет связи (${e.javaClass.simpleName})")
            return@withContext Otvet.Sboy
        }
        when {
            kod == 404 -> Otvet.NeNajden
            kod == 429 -> Otvet.Chasto
            kod !in 200..299 -> {
                KLog.w(TAG, "$metod оплаты: HTTP $kod")
                Otvet.Sboy
            }
            else -> {
                val info = runCatching { razobratOplatu(JSONObject(telo)) }.getOrNull()
                if (info == null) Otvet.NeOplata else Otvet.Est(info)
            }
        }
    }

    private fun pryamo(u: String, metod: String, zag: List<Pair<String, String>>): Pair<Int, String> {
        val conn = URL(u).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = metod
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            zag.forEach { (n, v) -> conn.setRequestProperty(n, v) }
            if (metod == "POST") {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(0)
                conn.outputStream.close()
            }
            val c = conn.responseCode
            val t = (if (c in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            return c to t
        } finally {
            conn.disconnect()
        }
    }
}
