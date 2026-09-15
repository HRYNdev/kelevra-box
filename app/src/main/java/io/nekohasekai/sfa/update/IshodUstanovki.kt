package io.nekohasekai.sfa.update

/**
 * Причина, по которой система не поставила новую версию.
 *
 * Делится по тому, что с отказом можно сделать: перекачать файл, повторить позже,
 * попросить человека поменять настройку телефона или не повторять вовсе.
 */
enum class OtkazUstanovki {
    /** Системная проверка приложений не пропустила файл. Кодом не лечится, нужен человек. */
    PROVERKA,

    /** Система не разобрала файл или файл не той версии. Лечится перекачкой. */
    BITYY_FAYL,

    /** Не хватило места на телефоне. */
    MESTO,

    /** Подпись или пакет не совпали с установленным: повтор ничего не даст. */
    NESOVMESTIMO,

    /** Установку отменили в системном окне. */
    OTMENENO,

    /** Всё остальное, включая сорванное скачивание. */
    DRUGOE,
}

/** Итог проверки скачанного файла до передачи системе. */
enum class ProverkaFayla { OK, PUSTOY, RAZMER, HESH }

/**
 * Скачанный файл не прошёл проверку после всех попыток. Отдельный тип нужен окну: общий
 * текст «скачать не удалось» прятал причину, хотя файл скачался, но оказался не тем.
 */
class FaylNeGoden(val prichina: String) : Exception("update file check failed: $prichina")

/**
 * Исход установки обновления: разбор отказа системы, проверка скачанного файла, лестница
 * повторов и тексты для человека. Логика без Android, поэтому целиком проверяется тестами.
 *
 * Зачем отдельно. Отказ установки клался в состояние, которое не показывал ни один экран,
 * а повтора не было: человек нажимал «Обновить», окно скачивания закрывалось сразу после
 * передачи файла системе, и версия оставалась прежней без единого слова. Окно с
 * предложением обновиться при этом больше не показывалось, потому что считалось
 * показанным. По журналам телефонов в сентябре 2026 так прошли три отказа системной
 * проверки приложений и два отказа разбора файла.
 */
object IshodUstanovki {
    // Коды PackageInstaller.STATUS_*. Числами, а не ссылками на android.*, чтобы разбор
    // жил в обычных тестах без эмулятора.
    private const val STATUS_FAILURE_BLOCKED = 2
    private const val STATUS_FAILURE_ABORTED = 3
    private const val STATUS_FAILURE_INVALID = 4
    private const val STATUS_FAILURE_CONFLICT = 5
    private const val STATUS_FAILURE_STORAGE = 6
    private const val STATUS_FAILURE_INCOMPATIBLE = 7

    /** Сетевой сбой: первые повторы чаще, дальше раз в 6 часов без конца. */
    private val LESTNICA_SETI = listOf(15L, 60L, 360L)
    const val POTOLOK_SETI_MINUT = 360L

    /** Отказ, который снимает только человек у телефона: напоминание раз в сутки, пока версия не встанет. */
    const val NAPOMINANIE_MINUT = 1440L

    private val SHA256_DIGEST = Regex("""^sha256:([0-9a-fA-F]{64})$""")

    fun razobrat(status: Int, soobshchenie: String?): OtkazUstanovki {
        val text = soobshchenie.orEmpty().uppercase()
        // Сообщение точнее кода. Отказ проверки приложений на Android 16 пришёл с кодом
        // ABORTED, хотя человек ничего не отменял, — по одному коду его не отличить.
        return when {
            "VERIFICATION_FAILURE" in text || "VERIFICATION_TIMEOUT" in text -> OtkazUstanovki.PROVERKA
            "PARSE_FAILED" in text || "NOT_APK" in text || "INVALID_APK" in text ||
                "VERSION_DOWNGRADE" in text -> OtkazUstanovki.BITYY_FAYL
            "INSUFFICIENT_STORAGE" in text -> OtkazUstanovki.MESTO
            "UPDATE_INCOMPATIBLE" in text || "SIGNATURE" in text -> OtkazUstanovki.NESOVMESTIMO
            status == STATUS_FAILURE_BLOCKED -> OtkazUstanovki.PROVERKA
            status == STATUS_FAILURE_INVALID -> OtkazUstanovki.BITYY_FAYL
            status == STATUS_FAILURE_STORAGE -> OtkazUstanovki.MESTO
            status == STATUS_FAILURE_CONFLICT || status == STATUS_FAILURE_INCOMPATIBLE -> OtkazUstanovki.NESOVMESTIMO
            status == STATUS_FAILURE_ABORTED -> OtkazUstanovki.OTMENENO
            else -> OtkazUstanovki.DRUGOE
        }
    }

    /**
     * Через сколько минут повторить после [otkazovPodryad]-го отказа этой причины на одну версию.
     * null — только чужая подпись: поверх неё не встанет ни один повтор.
     *
     * Повторы не кончаются: обновление должно дойти всегда. Сорванное скачивание и битый
     * файл — сбой сети, их повторяем по лестнице и дальше раз в 6 часов. Отмена, отказ
     * проверки и нехватка места снимаются только человеком, поэтому это напоминание раз
     * в сутки, а не частые попытки.
     */
    fun zaderzhkaMinut(otkazovPodryad: Int, prichina: OtkazUstanovki): Long? {
        if (otkazovPodryad < 1) return null
        return when (prichina) {
            OtkazUstanovki.NESOVMESTIMO -> null
            OtkazUstanovki.PROVERKA, OtkazUstanovki.OTMENENO, OtkazUstanovki.MESTO -> NAPOMINANIE_MINUT
            OtkazUstanovki.BITYY_FAYL, OtkazUstanovki.DRUGOE ->
                LESTNICA_SETI.getOrNull(otkazovPodryad - 1) ?: POTOLOK_SETI_MINUT
        }
    }

    fun mozhnoPovtorit(prichina: OtkazUstanovki): Boolean = prichina != OtkazUstanovki.NESOVMESTIMO

    /**
     * Счётчики отказов раздельно по причине, одной строкой «номер версии|ПРИЧИНА=n,ПРИЧИНА=n».
     * Запись другой версии читается как пустая: выход новой версии сбрасывает все счётчики.
     */
    private fun razobratSchetchiki(zapis: String, versionCode: Int): Map<OtkazUstanovki, Int> {
        val chasti = zapis.split('|')
        if (chasti.size != 2 || chasti[0].toIntOrNull() != versionCode) return emptyMap()
        return chasti[1].split(',').mapNotNull { para ->
            val kv = para.split('=')
            val prichina = OtkazUstanovki.values().firstOrNull { it.name == kv.getOrNull(0) }
            val n = kv.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0)
            if (kv.size == 2 && prichina != null && n != null) prichina to n else null
        }.toMap()
    }

    fun prochestSchetchik(zapis: String, versionCode: Int, prichina: OtkazUstanovki): Int =
        razobratSchetchiki(zapis, versionCode)[prichina] ?: 0

    /** Новая строка счётчиков: у [prichina] ставится [otkazov], остальные причины той же версии сохраняются. */
    fun zapisatSchetchik(zapis: String, versionCode: Int, prichina: OtkazUstanovki, otkazov: Int): String {
        val schetchiki = razobratSchetchiki(zapis, versionCode) + (prichina to otkazov)
        return "$versionCode|" + OtkazUstanovki.values()
            .mapNotNull { p -> schetchiki[p]?.let { "${p.name}=$it" } }
            .joinToString(",")
    }

    /** Файл привязан к версии: иначе после выхода новой версии ставился старый скачанный. */
    fun imyaFayla(versionCode: Int): String = "update-$versionCode.apk"

    /** Уникальное имя временного файла на один вызов: два воркера одной версии не пишут в один файл. */
    fun imyaChastichnogoFayla(versionCode: Int): String =
        "${imyaFayla(versionCode)}.${java.util.UUID.randomUUID()}.part"

    /**
     * Файлы кэша обновлений, которые уборка перед скачиванием снесёт: всё, кроме готового
     * файла [imyaGotovogo] и чужих свежих `.part` — младше [porogMs] мс с последней записи.
     *
     * Порог 5 минут: заметно дольше одной попытки скачивания на обычной сети (в
     * ApkDownloader их всего 2 подряд), и заметно короче ближайшего повтора в лестнице
     * (15 минут) — «свежий» .part не спутать со следующим циклом того же воркера.
     *
     * До этой правки уборка сносила ЛЮБОЙ файл, кроме готового apk, включая `.part`,
     * который прямо сейчас пишет второй воркер: UpdateWorker и UpdatePovtorWork не
     * сериализованы между собой (#14), скачанные байты терялись, rename падал с
     * NoSuchFileException. Красный тест на старой версии этой функции — ApkKeshTest.
     */
    fun kUdaleniyu(
        files: List<java.io.File>,
        imyaGotovogo: String,
        seychasMs: Long,
        porogMs: Long = 5 * 60_000L,
    ): List<java.io.File> =
        files.filter { file ->
            file.name != imyaGotovogo &&
                !(file.name.endsWith(".part") && seychasMs - file.lastModified() < porogMs)
        }

    /** «sha256:<64 hex>» из поля digest у файла релиза; всё прочее — null. */
    fun sha256IzDigest(digest: String?): String? =
        digest?.trim()?.let { SHA256_DIGEST.matchEntire(it) }?.groupValues?.get(1)?.lowercase()

    /**
     * Размер сверяется, только если известен ожидаемый, хеш — только если он пришёл в
     * списке релизов. Неизвестное не считается провалом: старые релизы хеша не несут.
     */
    fun proveritFayl(razmer: Long, ozhidaemyyRazmer: Long, hesh: String?, ozhidaemyyHesh: String?): ProverkaFayla {
        if (razmer <= 0L) return ProverkaFayla.PUSTOY
        if (ozhidaemyyRazmer > 0L && razmer != ozhidaemyyRazmer) return ProverkaFayla.RAZMER
        if (ozhidaemyyHesh != null && !ozhidaemyyHesh.equals(hesh, ignoreCase = true)) return ProverkaFayla.HESH
        return ProverkaFayla.OK
    }

    /** Строка служебного журнала: по ней разбор идёт по фактам, а не по догадкам. */
    fun strokaZhurnala(versiya: String, prichina: OtkazUstanovki, status: Int, soobshchenie: String?): String =
        "установка обновления не прошла: версия $versiya, причина $prichina, код $status, система: ${soobshchenie ?: "—"}"

    fun tekst(prichina: OtkazUstanovki): String = when (prichina) {
        OtkazUstanovki.PROVERKA ->
            "Телефон не разрешил поставить новую версию: встроенная проверка приложений отклонила установку."
        OtkazUstanovki.BITYY_FAYL ->
            "Файл обновления оказался повреждён. Он скачается заново."
        OtkazUstanovki.MESTO ->
            "На телефоне не хватает места для новой версии. Освободите место и попробуйте ещё раз."
        OtkazUstanovki.NESOVMESTIMO ->
            "Новая версия не встаёт поверх установленной. Сообщите об этом через жалобу в настройках."
        OtkazUstanovki.OTMENENO ->
            "Установка была отменена."
        OtkazUstanovki.DRUGOE ->
            "Телефон не смог поставить новую версию. Попробуйте ещё раз чуть позже."
    }

    /** Короткая инструкция для случаев, где нужен человек у телефона. */
    fun instrukciya(prichina: OtkazUstanovki): String? = when (prichina) {
        OtkazUstanovki.PROVERKA ->
            "Телефон проверяет приложения не из магазина. Нажмите «Попробовать ещё раз», " +
                "в окне проверки откройте «Подробнее» и выберите «Всё равно установить»."
        else -> null
    }
}
