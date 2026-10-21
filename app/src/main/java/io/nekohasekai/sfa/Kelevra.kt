package io.nekohasekai.sfa

/**
 * Настройки своей сети.
 *
 * Подписка раздаётся панелью по адресу вида https://<host>/<код>, а конфиг sing-box
 * лежит по тому же адресу с суффиксом /singbox. Пользователю достаточно кода:
 * остальное достраиваем сами.
 */
object Kelevra {
    const val SUBSCRIPTION_HOST = SubscriptionLink.DEFAULT_HOST

    private const val CONFIG_SUFFIX = "/singbox"

    /**
     * Постоянный идентификатор устройства для учёта в панели.
     * Генерируется один раз и переживает перезапуски: иначе каждое обновление подписки
     * выглядело бы как новое устройство.
     */
    val deviceId: String by lazy {
        val prefs = Application.application.getSharedPreferences("kelevra", android.content.Context.MODE_PRIVATE)
        // Уже сохранённый id остаётся: иначе обновление размножит устройства. Новая установка берёт
        // id от ANDROID_ID, который переживает переустановку приложения.
        prefs.getString("device_id", null) ?: novyjDeviceId().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    private fun novyjDeviceId(): String {
        val android = runCatching {
            android.provider.Settings.Secure.getString(
                Application.application.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID,
            )
        }.getOrNull()
        return if (android.isNullOrBlank()) {
            java.util.UUID.randomUUID().toString()
        } else {
            Priglashenie.idIzAndroidId(android)
        }
    }

    /**
     * Как устройство называет себя серверу.
     *
     * Ровно то же, что уходит с жалобой ([io.nekohasekai.sfa.compose.screen.home.ComplaintScreen]):
     * модель железа и версия приложения. Раньше это собиралось только там, теперь
     * одно место на всех — жалобу, запрос конфига, сводку и отправку логов.
     */
    const val PLATFORM = "android"

    /** «Xiaomi 23021RAAEG» — производитель и модель, как их отдаёт само железо. */
    val deviceModel: String
        get() = header("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")

    val appVersion: String
        get() = header(BuildConfig.VERSION_NAME)

    /** Версия самой системы — «12». Тем же значением зовётся заголовок `x-ver-os`. */
    val osVersion: String
        get() = header(android.os.Build.VERSION.RELEASE ?: "")

    /**
     * Значение заголовка обязано быть однострочным ASCII: имена моделей у части
     * прошивок содержат кириллицу и переводы строк, а такой заголовок HTTP-клиент
     * либо режет, либо роняет весь запрос.
     */
    private fun header(value: String): String =
        value.map { if (it.code in 32..126) it else '?' }.joinToString("").trim().take(128)

    /**
     * Заголовки, по которым сервер сам заводит устройство и потом отдаёт в сводке
     * человеческие имена (`person.name`, `device.name`).
     *
     * Пятым идёт свой расход трафика ([io.nekohasekai.sfa.bg.DeviceTraffic]) — его
     * может и не быть: пока ничего не насчитали, заголовок не шлём вовсе.
     */
    fun deviceHeaders(): List<Pair<String, String>> = listOfNotNull(
        "X-Device-Id" to deviceId,
        "X-Device-Model" to deviceModel,
        "X-Device-Platform" to PLATFORM,
        "X-App-Version" to appVersion,
        io.nekohasekai.sfa.bg.DeviceTraffic.header(io.nekohasekai.sfa.bg.DeviceTraffic.total()),
        pasportHeader(),
        pultHeader(),
        sostoyanieHeader(),
    )

    /**
     * `X-Pult` — что клиент умеет, какую версию пульта принял, квитанции команд и ошибки
     * (KelevraServer/docs/PULT.md). Без этого заголовка сервер пульт не шлёт вовсе.
     */
    internal fun pultHeader(): Pair<String, String>? =
        runCatching {
            // Не через [header]: тот режет до 128 знаков, а квитанции с ошибками длиннее.
            "X-Pult" to io.nekohasekai.sfa.bg.pult.Pult.zagolovok().filter { it.code in 32..126 }.take(1024)
        }.getOrNull()

    /** `X-Sostoyanie` — пульс устройства: режим автомата, узел, жив ли канал, сеть. */
    private fun sostoyanieHeader(): Pair<String, String>? =
        if (io.nekohasekai.sfa.bg.pult.Pult.vykl("puls")) {
            null
        } else {
            // Не через [header]: тот режет до 128 знаков, а имя узла в процент-кодировке длинное.
            runCatching {
                "X-Sostoyanie" to io.nekohasekai.sfa.bg.AutoMode.puls().filter { it.code in 33..126 }.take(512)
            }.getOrNull()
        }

    /**
     * Паспорт устройства одной строкой: чем оно описывает СЕБЯ сверх имени и версии.
     *
     * Зачем. Разбор 10.09.2026: сервер знал про устройство пять полей и не мог ответить
     * ни на «система отобрала разрешение на туннель», ни на «какой возраст профиля», ни
     * на «сколько живёт процесс» — а из этого складывается половина диагнозов, и каждый
     * такой разбор стоил по полсессии. На одном телефоне туннель гасила сама система,
     * и увидеть это можно было только внутри архива журнала, вручную.
     *
     * Одной строкой, а не десятком заголовков: они едут в КАЖДОМ запросе, а полей будет
     * больше. Формат «ключ=значение» через точку с запятой — тот же, что у десктопа,
     * сервер разбирает обе платформы одним куском кода и незнакомые ключи кладёт рядом.
     */
    private fun pasportHeader(): Pair<String, String>? = runCatching { "X-Pasport" to pasportStroka() }.getOrNull()

    /**
     * Сам паспорт — та же строка пишется и в журнал приложения ([io.nekohasekai.sfa.bg.AppLog]):
     * журнал читается на сервере без заголовков запросов.
     *
     * С 20.10.2026 сверх прежнего: снято ли с приложения ограничение батареи (`bat_iskl`),
     * режет ли прошивка logcat (`logcat`, см. [io.nekohasekai.sfa.bg.KLog]), тип сети
     * (`set`), оператор (`operator`) и пояс часов (`tz`, `tz_id`) — у телефонов на
     * московском времени разбор журналов сдвигал записи на два часа. Новых разрешений
     * ни одно поле не требует; каждое добывается отдельно, и сбой одного не съедает паспорт.
     */
    fun pasportStroka(): String {
        val chasti = mutableListOf<String>()
        chasti += "os=" + osVersion.replace(';', ' ').replace('=', ' ').take(40)
        chasti += "yadro=" + io.nekohasekai.libbox.Libbox.version().take(24)

        val obnovlen = io.nekohasekai.sfa.database.Settings.profilObnovlenV
        if (obnovlen > 0) {
            val minut = (System.currentTimeMillis() - obnovlen) / 60000L
            if (minut >= 0) chasti += "profil=$minut"
        }
        val otozvano = io.nekohasekai.sfa.database.Settings.vpnRazreshenieOtozvanoV
        if (otozvano > 0) {
            val chasov = (System.currentTimeMillis() - otozvano) / 3600000L
            chasti += "vpn_otozvano_ch=$chasov"
        }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val pm = Application.powerManager
                val iskl = pm.isIgnoringBatteryOptimizations(Application.application.packageName)
                chasti += "bat_iskl=" + if (iskl) "1" else "0"
            }
        }
        chasti += "logcat=" + io.nekohasekai.sfa.bg.AppLog.priznakLogcat()
        runCatching { chasti += "set=" + tipSeti() }
        runCatching {
            val tm = Application.application.getSystemService(android.content.Context.TELEPHONY_SERVICE)
                as? android.telephony.TelephonyManager
            val imya = tm?.networkOperatorName.orEmpty()
            if (imya.isNotBlank()) chasti += "operator=" + polePasporta(header(latinica(imya))).take(32)
        }
        runCatching {
            val tz = java.util.TimeZone.getDefault()
            chasti += "tz=" + io.nekohasekai.sfa.bg.AppLog.smeshchenie(tz.getOffset(System.currentTimeMillis()))
            chasti += "tz_id=" + polePasporta(header(tz.id)).take(40)
        }
        return chasti.joinToString(";")
    }

    /** Значение поля паспорта не должно ломать разбор «ключ=значение;…». */
    private fun polePasporta(value: String): String = value.replace(';', ' ').replace('=', ' ').trim()

    /**
     * Тип сети, которую система считает основной: wifi / mobile / ethernet / vpn / other / none.
     * Сначала спрашиваем физическую сеть ([io.nekohasekai.sfa.bg.DefaultNetworkMonitor]): при
     * поднятом туннеле `activeNetwork` — это он сам.
     */
    private fun tipSeti(): String {
        val cm = Application.connectivity
        val network = io.nekohasekai.sfa.bg.DefaultNetworkMonitor.defaultNetwork
            ?: (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) cm.activeNetwork else null)
            ?: return "none"
        val caps = cm.getNetworkCapabilities(network) ?: return "none"
        return when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }

    /**
     * Кириллицу — латиницей: заголовок обязан быть ASCII ([header]), а «МТС» иначе
     * превратился бы в «???». Остальное не-ASCII остаётся на совести [header].
     */
    internal fun latinica(value: String): String = buildString {
        for (ch in value) {
            val lower = ch.lowercaseChar()
            val t = TRANSLIT[lower]
            when {
                t == null -> append(ch)
                ch.isUpperCase() -> append(t.replaceFirstChar { it.uppercaseChar() })
                else -> append(t)
            }
        }
    }

    private val TRANSLIT: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "",
        'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
    )

    /**
     * Чем устройство подписывается в первой строке своего журнала.
     *
     * Повод. В андроидном журнале не было ни модели, ни версии приложения, ни версии
     * системы: кто прислал файл — узнавалось только из реестра на сервере, а по самому
     * журналу никак. У десктопа такая шапка есть с самого начала («запуск Kelevra 0.6.44
     * (windows/amd64)»), и разбор там начинается с неё.
     *
     * Источник ровно тот же, что у заголовков `X-Device-*` ([deviceHeaders]) — второго
     * не заводим: шапка журнала и то, чем устройство назвалось серверу, обязаны
     * совпадать, иначе при разборе жалобы они начинают спорить друг с другом.
     */
    fun deviceSummary(): String = describeDevice(deviceModel, osVersion, appVersion)

    /**
     * Та же шапка из готовых значений — отдельно, чтобы её можно было проверить без телефона.
     *
     * Пустое поле не выкидываем, а называем словами: «модель неизвестна» в шапке — это
     * факт об устройстве, а молча съеденное поле не отличить от того, что его забыли
     * записать вовсе.
     */
    internal fun describeDevice(model: String, osVersion: String, appVersion: String): String {
        val железо = model.ifBlank { "модель неизвестна" }
        val система = if (osVersion.isBlank()) PLATFORM else "$PLATFORM $osVersion"
        val версия = appVersion.ifBlank { "версия неизвестна" }
        return "Kelevra $версия, $железо, $система"
    }

    /**
     * Код подписки из любого нашего адреса: и нового (/k/<код>), и старого, который
     * отдавала панель (/<код>/singbox). Для чужих ссылок — null.
     *
     * Нужен, потому что профили, заведённые до своего сборщика, ведут на панель:
     * такой конфиг приезжает без комнаты, а сводка /info по нему не читается вовсе.
     */
    fun subscriptionCode(url: String): String? = SubscriptionLink.parse(url)?.code

    /** Наш ли адрес: тогда к запросу добавляются заголовки устройства. */
    fun fromDeepLink(link: String): String? = SubscriptionLink.fromDeepLink(link)

    fun isOwnUrl(url: String): Boolean = SubscriptionLink.isOwn(url)

    /** Ключ устройства выбранного профиля (профиль на /u/<ключ>); у профиля по общему коду его нет. */
    suspend fun currentDeviceKey(): String? = runCatching {
        val id = io.nekohasekai.sfa.database.Settings.selectedProfile
        if (id == -1L) return@runCatching null
        val profile = io.nekohasekai.sfa.database.ProfileManager.get(id) ?: return@runCatching null
        SubscriptionLink.parse(profile.typed.remoteURL)?.takeIf { it.kind == 'u' }?.code
    }.getOrNull()

    /** Хост сервера из ссылки профиля (у каждого профиля свой); не наша ссылка — subkv. */
    fun subscriptionHost(url: String): String = SubscriptionLink.hostOf(url)

    /**
     * Хост сервера выбранного профиля. Профиля нет (жалоба до подключения) — subkv.
     */
    suspend fun currentHost(): String = runCatching {
        val id = io.nekohasekai.sfa.database.Settings.selectedProfile
        if (id == -1L) return@runCatching SUBSCRIPTION_HOST
        val profile = io.nekohasekai.sfa.database.ProfileManager.get(id) ?: return@runCatching SUBSCRIPTION_HOST
        SubscriptionLink.hostOf(profile.typed.remoteURL)
    }.getOrDefault(SUBSCRIPTION_HOST)

    /**
     * Куда на самом деле уходит запрос к своему серверу. В выпускной сборке — ровно [url].
     *
     * Только в debug адрес сервера можно подменить файлом `files/debug-server-url.txt`
     * (кладётся через `run-as`, например `http://10.0.2.2:8765`): так то, что раздаёт
     * сервер, проверяется на локальном стенде, не трогая боевой. Путь и код остаются
     * прежними, в профиле хранится настоящий адрес.
     */
    fun adresSeti(url: String): String {
        if (!BuildConfig.DEBUG) return url
        val podmena = runCatching {
            java.io.File(Application.application.filesDir, "debug-server-url.txt").readText().trim().trimEnd('/')
        }.getOrNull()
        if (podmena.isNullOrEmpty()) return url
        return SubscriptionLink.withBase(url, podmena)
    }

    /** Адрес конфига у своего сборщика. */
    fun configUrl(code: String, host: String = SUBSCRIPTION_HOST): String = SubscriptionLink.configUrl(code, host)

    /**
     * Маскирует код доступа в произвольном тексте: адрес вида
     * `https://<host>/k/<код>` несёт код целиком, и текст ошибки от HTTP-клиента
     * или ядра на Go часто повторяет запрошенный URL внутри себя. В журнал и в
     * исключения код должен попадать не целиком — домен и длина остаются,
     * чтобы диагностика не потерялась.
     */
    fun maskCode(text: String): String = SubscriptionLink.maskCode(text)

    /**
     * Копия исключения с замаскированным сообщением, но с тем же местом падения:
     * стектрейс диагностировать помогает, а код доступа в нём не нужен.
     */
    fun maskThrowable(e: Throwable): Throwable {
        val masked = RuntimeException(maskCode(e.toString()))
        masked.stackTrace = e.stackTrace
        return masked
    }

    /**
     * Приводит к рабочей ссылке на конфиг:
     *  - короткий код               -> https://<host>/k/<код>
     *  - любая наша ссылка          -> тоже /k/<код>, в какой бы форме её ни дали
     *  - всё остальное              -> оставляем как есть (чужие подписки не трогаем)
     *
     * Старую форму (`/<код>/singbox`, конфиг от панели) не оставляем даже когда её
     * вводят руками: панель отдаёт формат старого ядра, и sing-box 1.14 его не принимает
     * вовсе. Проверено в эмуляторе 07.08.2026: подключение по такой ссылке падало с
     * «legacy DNS fakeip options are deprecated ... removed in sing-box 1.14.0»,
     * профиль не создавался.
     */
    fun normalizeSubscription(input: String): String = SubscriptionLink.normalize(input)
}
