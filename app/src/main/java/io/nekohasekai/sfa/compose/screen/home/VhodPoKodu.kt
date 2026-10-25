package io.nekohasekai.sfa.compose.screen.home

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Aktivatsiya
import io.nekohasekai.sfa.Kelevra
import io.nekohasekai.sfa.Priglashenie
import io.nekohasekai.sfa.SubscriptionLink
import io.nekohasekai.sfa.bg.KLog
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

/**
 * Вход по коду, один на всё приложение и живущий дольше экрана.
 *
 * Активация заводит устройство на сервере (слот, ключ) раньше, чем профиль ляжет в телефон.
 * Пока вход жил в экране, уход с экрана обрывал его посередине: устройство на сервере есть,
 * профиля нет, повторный ввод кода заводит ещё одно устройство. Здесь вход доводится до
 * сохранения профиля всегда, а экран только показывает [sostoyanie].
 *
 * Вход делится на две части:
 * - [sohranit] — активация, загрузка настроек и запись профиля. Её результат — код человека
 *   на сервере, поэтому она доводится до конца, даже если экрана уже нет;
 * - [primenit] — сделать профиль выбранным и попросить запущенное ядро перечитать настройки.
 *   Это меняет то, через что человек сейчас в сети, поэтому делается при нём: если экран
 *   открыт в момент готовности — сразу, как раньше; если человек ушёл — когда он снова откроет
 *   экран входа. Исключение — профиль, который и так выбран ([Sohraneno.uzheVybran]): повторная
 *   активация уже сменила его ключ, старый умер, и без перечитывания запущенный туннель остаётся
 *   на мёртвом ключе. Тут перечитывание ничего не переключает, а чинит — делается сразу.
 *
 * Запуск службы с нуля здесь не делается никогда: его по-прежнему делает только кнопка экрана.
 */
class VhodPoKodu(
    private val scope: CoroutineScope,
    private val sohranit: suspend (vvod: String) -> Sohraneno,
    private val primenit: suspend (Sohraneno) -> Unit,
) {
    /** Что записано: какой профиль и выбран ли он уже (тогда применение ничего не переключает). */
    data class Sohraneno(val profilId: Long, val uzheVybran: Boolean)

    sealed interface Sostoyanie {
        data object Pusto : Sostoyanie

        /** Вход идёт по коду [vvod]. */
        data class Idet(val vvod: String) : Sostoyanie

        /** Профиль записан; [primeneno] — выбран и ядро попрошено перечитать настройки. */
        data class Gotovo(val sohraneno: Sohraneno, val primeneno: Boolean) : Sostoyanie

        data class Oshibka(val prichina: Throwable) : Sostoyanie
    }

    private val lock = Any()
    private val _sostoyanie = MutableStateFlow<Sostoyanie>(Sostoyanie.Pusto)
    val sostoyanie: StateFlow<Sostoyanie> = _sostoyanie.asStateFlow()

    /** Сколько экранов входа открыто сейчас. */
    private var ekrany = 0

    /** Итог (готово или ошибка) видел хоть один экран: уход с экрана его сбрасывает. */
    private var uvideno = false

    /**
     * Начать вход. Пока идёт прежний, новый не стартует: вызывающий получает false и видит
     * прежний через [sostoyanie] — второе нажатие не заводит второе устройство.
     */
    fun nachat(vvod: String): Boolean {
        synchronized(lock) {
            if (_sostoyanie.value is Sostoyanie.Idet) return false
            uvideno = false
            _sostoyanie.value = Sostoyanie.Idet(vvod)
        }
        scope.launch {
            val itog = runCatching { sohranit(vvod) }
            val primenitSeychas = synchronized(lock) {
                itog.fold(
                    onSuccess = {
                        val seychas = ekrany > 0 || it.uzheVybran
                        // Применение идёт ниже; до него экран уже может показывать «готово».
                        _sostoyanie.value = Sostoyanie.Gotovo(it, primeneno = seychas)
                        uvideno = ekrany > 0
                        seychas
                    },
                    onFailure = {
                        _sostoyanie.value = Sostoyanie.Oshibka(it)
                        uvideno = ekrany > 0
                        false
                    },
                )
            }
            if (primenitSeychas) runCatching { primenit(itog.getOrThrow()) }
        }
        return true
    }

    /** Экран входа открылся. Вход, завершённый без человека, применяется сейчас, при нём. */
    fun ekranOtkryt(): Job? {
        val primenitSeychas = synchronized(lock) {
            ekrany++
            when (val s = _sostoyanie.value) {
                is Sostoyanie.Gotovo -> {
                    uvideno = true
                    if (s.primeneno) {
                        null
                    } else {
                        _sostoyanie.value = s.copy(primeneno = true)
                        s.sohraneno
                    }
                }
                is Sostoyanie.Oshibka -> {
                    uvideno = true
                    null
                }
                else -> null
            }
        }
        return primenitSeychas?.let { s -> scope.launch { runCatching { primenit(s) } } }
    }

    /** Экран входа закрылся. Итог, который человек видел, сбрасывается: следующий вход — с нуля. */
    fun ekranZakryt() {
        synchronized(lock) {
            if (ekrany > 0) ekrany--
            if (ekrany == 0 && uvideno) {
                val s = _sostoyanie.value
                if (s is Sostoyanie.Oshibka || (s is Sostoyanie.Gotovo && s.primeneno)) {
                    _sostoyanie.value = Sostoyanie.Pusto
                    uvideno = false
                }
            }
        }
    }

    /** Человек увидел ошибку и правит код: ошибка больше не показывается. */
    fun sbrositOshibku() {
        synchronized(lock) {
            if (_sostoyanie.value is Sostoyanie.Oshibka) {
                _sostoyanie.value = Sostoyanie.Pusto
                uvideno = false
            }
        }
    }

    /** Человек прошёл дальше по готовому входу: итог больше не нужен. */
    fun zabratGotovo() {
        synchronized(lock) {
            val s = _sostoyanie.value
            if (s is Sostoyanie.Gotovo && s.primeneno) {
                _sostoyanie.value = Sostoyanie.Pusto
                uvideno = false
            }
        }
    }
}

/** Вход по коду всего приложения: экран подключения только показывает его [VhodPoKodu.sostoyanie]. */
object Vhod {
    private const val TAG = "KelevraConnect"

    val vhod = VhodPoKodu(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        sohranit = { vvod ->
            try {
                importByCode(vvod)
            } catch (e: Throwable) {
                // Адрес, который запрашивали, содержит код доступа целиком —
                // в журнал он идёт маскированным, не как есть.
                if (e !is LimitUstroystv) KLog.w(TAG, "подключение по коду не удалось", Kelevra.maskThrowable(e))
                throw e
            }
        },
        primenit = { s ->
            Settings.selectedProfile = s.profilId
            perezagruzitServis()
        },
    )
}

class LimitUstroystv(val tekst: String) : Exception(tekst)

class KodNeNajden : Exception("код не найден")

/**
 * Вход по коду: ищет дверь, активирует устройство и записывает профиль. Ссылка и QR идут
 * прежним путём. Повторный ввод обновляет уже существующий профиль, а не заводит второй.
 * Выбор профиля и перечитывание ядра — отдельно, в [VhodPoKodu].
 */
private suspend fun importByCode(vvod: String): VhodPoKodu.Sohraneno {
    val varianty = Priglashenie.varianty(vvod)
    if (varianty.isEmpty()) {
        return zagruzitProfil(Kelevra.normalizeSubscription(vvod), emptyList())
    }
    val (itog, kod) = Aktivatsiya.voyti(vvod)
    return when (itog) {
        is Priglashenie.Itog.Novyj ->
            zagruzitProfil(SubscriptionLink.baseUrl(itog.profil) ?: itog.profil, varianty)
        is Priglashenie.Itog.Staryj ->
            zagruzitProfil(SubscriptionLink.configUrl(kod, itog.dver), varianty)
        is Priglashenie.Itog.Limit -> throw LimitUstroystv(itog.tekst)
        Priglashenie.Itog.NeNajden -> throw KodNeNajden()
        Priglashenie.Itog.NetSvyazi -> throw java.io.IOException("двери не ответили")
    }
}

/** Скачивает настройки по адресу профиля и записывает; профиль этого кода уже есть: обновляется он. */
private suspend fun zagruzitProfil(url: String, vvedennyeKody: List<String>): VhodPoKodu.Sohraneno {
    val content = HTTPClient().use { it.getString(Kelevra.adresSeti(url)) }
    io.nekohasekai.sfa.bg.MlkemPriznak.proverit(content)

    val imeyushijsya = ProfileManager.list().firstOrNull {
        it.typed.type == TypedProfile.Type.Remote && Priglashenie.totZhe(url, it.typed.remoteURL, vvedennyeKody)
    }
    if (imeyushijsya != null && imeyushijsya.typed.path.isNotBlank()) {
        File(imeyushijsya.typed.path).apply { parentFile?.mkdirs() }.writeText(content)
        imeyushijsya.typed.remoteURL = url
        imeyushijsya.typed.lastUpdated = Date()
        ProfileManager.update(imeyushijsya)
        io.nekohasekai.sfa.bg.UpdateProfileWork.reconfigureUpdater()
        return sohranenoDlya(imeyushijsya.id)
    }

    val typed =
        TypedProfile().apply {
            type = TypedProfile.Type.Remote
            remoteURL = url
            autoUpdate = true
            autoUpdateInterval = 60
            lastUpdated = Date()
        }
    // Имя отличает новый профиль от уже имеющихся: при втором профиле к нему добавляется сервер.
    val imya = Priglashenie.imyaProfilya(
        url,
        ProfileManager.list().map { it.name },
        java.text.SimpleDateFormat("dd.MM", java.util.Locale.ROOT).format(Date()),
    )
    val profile = Profile(name = imya, typed = typed).apply { userOrder = ProfileManager.nextOrder() }

    val app = io.nekohasekai.sfa.Application.application
    val dir = File(app.filesDir, "configs").also { it.mkdirs() }
    val file = File(dir, "${ProfileManager.nextFileID()}.json")
    typed.path = file.path
    file.writeText(content)

    ProfileManager.create(profile, andSelect = false)
    io.nekohasekai.sfa.bg.UpdateProfileWork.reconfigureUpdater()
    return sohranenoDlya(profile.id)
}

/**
 * Выбран ли записанный профиль. Если не выбрано ничего (или выбранного профиля уже нет),
 * записанный выбирается сразу: переключать человека не с чего.
 */
private suspend fun sohranenoDlya(id: Long): VhodPoKodu.Sohraneno {
    val vybran = Settings.selectedProfile
    if (vybran == id) return VhodPoKodu.Sohraneno(id, uzheVybran = true)
    if (vybran == -1L || ProfileManager.get(vybran) == null) {
        Settings.selectedProfile = id
        return VhodPoKodu.Sohraneno(id, uzheVybran = true)
    }
    return VhodPoKodu.Sohraneno(id, uzheVybran = false)
}

/**
 * Повторная активация выдаёт новый ключ устройства, старый умирает: запущенное ядро надо
 * пересобрать с новым профилем (как после UpdateProfileWork). Не запущено — команда молча не уйдёт:
 * служба отсюда не запускается никогда.
 */
private suspend fun perezagruzitServis() {
    withContext(Dispatchers.IO) {
        runCatching {
            io.nekohasekai.sfa.bg.BoxService.markReloadByHuman()
            Libbox.newStandaloneCommandClient().serviceReload()
        }
    }
}
