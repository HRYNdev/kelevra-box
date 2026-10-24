package io.nekohasekai.sfa.bg.pult

import io.nekohasekai.sfa.bg.pult.PultReestr.Klyuch
import io.nekohasekai.sfa.bg.pult.PultReestr.Tip

/**
 * Настроечные константы подсистем, открытые пульту (п. 1 «Главной цели» PULT.md):
 * пороги, таймауты, ритмы, цели проб, списки — и выключатели `vykl.<имя>` (п. 2).
 *
 * Значение по умолчанию — ровно нынешняя константа, и в коде она же передаётся вторым
 * аргументом `Pult.long/int(ключ, КОНСТАНТА)`: без пульта поведение байт в байт прежнее.
 * Границы — от десятой доли до десятикратного значения (время), 1…×4 (счётчики),
 * ⅛…×8 (байты): мозг подкручивает, а не ломает. Таблица — `docs/PULT-KLYUCHI-box.md`.
 */
object PultKonstanty {
    private fun c(imya: String, umolch: Int, min: Long, max: Long, opisanie: String, gde: String) =
        Klyuch(imya, Tip.CELOE, umolch, min, max, opisanie = opisanie, gde = gde)

    private fun vykl(imya: String, opisanie: String, gde: String) =
        Klyuch("vykl.$imya", Tip.LOGICH, false, opisanie = opisanie, gde = gde)

    private fun logich(imya: String, umolch: Boolean, opisanie: String, gde: String) =
        Klyuch(imya, Tip.LOGICH, umolch, opisanie = opisanie, gde = gde)

    /** Имя хоста (домен без схемы). */
    val DOMEN = Regex("""^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$""")

    /** Подсеть IPv4/IPv6 в записи CIDR. */
    val PODSET = Regex("""^[0-9a-f:.]+/\d{1,3}$""")

    /** Адрес IPv4. */
    val IPV4 = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")

    /** Цель пробы: `хост:порт/путь`, простой HTTP. */
    val CEL = Regex("""^[a-z0-9.-]+:\d{1,5}/[!-~]*$""")

    /** Как формат строки называется в таблице ключей. */
    fun slovoShablona(r: Regex): String = when (r) {
        DOMEN -> "домен"
        PODSET -> "подсеть CIDR"
        IPV4 -> "адрес IPv4"
        CEL -> "хост:порт/путь"
        else -> r.pattern
    }

    private val CHISLA: List<Klyuch> = listOf(
        c("dom.home_hits", 2, 1, 10, "Сколько доменов из списка дома должны вернуть подменный адрес", "AutoMode.HOME_HITS"),
        c("dom.dns_budget_millis", 2500, 250, 25000, "Сколько ждём ответы резолвера на вопрос «дома ли мы», мс", "AutoMode.DNS_BUDGET_MILLIS"),
        c("avtomat.tcp_timeout_millis", 4000, 400, 40000, "Потолок ожидания рукопожатия с узлом основного канала, мс", "AutoMode.TCP_TIMEOUT_MILLIS"),
        c("avtomat.connect_slack_millis", 500, 100, 5000, "Запас поверх таймаута соединения: пул должен успеть отдать последний ответ, мс", "AutoMode.CONNECT_SLACK_MILLIS"),
        c("dom.home_traffic_tries", 2, 1, 10, "Сколько раз пробуем подтвердить дом трафиком, прежде чем сказать «не дома»", "AutoMode.HOME_TRAFFIC_TRIES"),
        c("avtomat.hint_ttl_millis", 300000, 30000, 3000000, "Сколько живёт подсказка про режим сети, мс", "AutoMode.HINT_TTL_MILLIS"),
        c("avtomat.probe_timeout_millis", 6000, 600, 60000, "Потолок ожидания честной пробы, мс", "AutoMode.PROBE_TIMEOUT_MILLIS"),
        c("avtomat.round_searching_millis", 12000, 1200, 120000, "Ритм поиска пути (растёт до потолка), мс", "AutoMode.ROUND_SEARCHING_MILLIS"),
        c("avtomat.round_searching_cap_millis", 120000, 12000, 1200000, "Потолок ритма поиска пути, мс", "AutoMode.ROUND_SEARCHING_CAP_MILLIS"),
        c("avtomat.round_no_network_millis", 300000, 30000, 3000000, "Страховочный тик, когда сети нет: чтобы ложный вердикт не усыплял автомат навсегда, мс", "AutoMode.ROUND_NO_NETWORK_MILLIS"),
        c("avtomat.round_home_millis", 300000, 30000, 3000000, "Полный заход дома, мс", "AutoMode.ROUND_HOME_MILLIS"),
        c("avtomat.round_main_millis", 300000, 30000, 3000000, "Полный заход на основном канале, мс", "AutoMode.ROUND_MAIN_MILLIS"),
        c("avtomat.round_room_millis", 180000, 18000, 1800000, "Возврат из комнаты на быстрый канал, мс", "AutoMode.ROUND_ROOM_MILLIS"),
        c("avtomat.round_manual_millis", 60000, 6000, 600000, "Ритм в ручном режиме: решений автомат не принимает, но раз в минуту перемеряет выбранный путь, чтобы экран не показывал протухшую запись, мс", "AutoMode.ROUND_MANUAL_MILLIS"),
        c("avtomat.silent_recheck_min_millis", 20000, 2000, 200000, "Перепроверка после захода, на котором резолверы промолчали: через 20–30 секунд, а не через обычные пять минут, мс", "AutoMode.SILENT_RECHECK_MIN_MILLIS"),
        c("avtomat.silent_recheck_max_millis", 30000, 3000, 300000, "Верхняя граница перепроверки после молчания резолверов, мс", "AutoMode.SILENT_RECHECK_MAX_MILLIS"),
        c("avtomat.silent_recheck_rounds", 10, 1, 40, "Сколько быстрых перепроверок подряд на молчании резолверов", "AutoMode.SILENT_RECHECK_ROUNDS"),
        c("avtomat.room_peek_min_millis", 20000, 2000, 200000, "Приглядка из комнаты: разброс паузы между дешёвыми пробами узла, мс", "AutoMode.ROOM_PEEK_MIN_MILLIS"),
        c("avtomat.room_peek_max_millis", 40000, 4000, 400000, "Верхняя граница паузы приглядки из комнаты, мс", "AutoMode.ROOM_PEEK_MAX_MILLIS"),
        c("avtomat.room_peek_timeout_millis", 2500, 250, 25000, "Потолок ожидания приглядки, мс", "AutoMode.ROOM_PEEK_TIMEOUT_MILLIS"),
        c("avtomat.room_honest_peek_millis", 60000, 6000, 600000, "Как часто из комнаты меряется основной путь ЧЕСТНО, когда узел уже отвечает, мс", "AutoMode.ROOM_HONEST_PEEK_MILLIS"),
        c("avtomat.manual_hold_millis", 3600000, 360000, 36000000, "Сколько живёт выбор, сделанный руками, если сеть под нами так и не сменилась, мс", "AutoMode.MANUAL_HOLD_MILLIS"),
        c("avtomat.node_answer_trust_millis", 90000, 9000, 900000, "Сколько ответ узла считается свежим доводом, мс", "AutoMode.NODE_ANSWER_TRUST_MILLIS"),
        c("avtomat.room_trial_pause_millis", 60000, 6000, 600000, "Начальная пауза между пробными подъёмами комнаты, мс", "AutoMode.ROOM_TRIAL_PAUSE_MILLIS"),
        c("avtomat.room_trial_pause_cap_millis", 600000, 60000, 6000000, "Комната не встаёт — паузу растим, чтобы не жечь батарею попытками в пустоту, мс", "AutoMode.ROOM_TRIAL_PAUSE_CAP_MILLIS"),
        c("avtomat.room_gate_fresh_millis", 15000, 1500, 150000, "Насколько свеж замер, которому верим при запрете комнаты по тишине, мс", "AutoMode.ROOM_GATE_FRESH_MILLIS"),
        c("uzel.uzel_budit_millis", 60000, 6000, 600000, "Не чаще этого отказы узла будят автомат, мс", "AutoMode.UZEL_BUDIT_MILLIS"),
        c("uzel.node_measurement_fresh_millis", 30000, 3000, 300000, "Сколько замер разового опроса путей (nodeMeasured) считается свежим, мс", "AutoMode.NODE_MEASUREMENT_FRESH_MILLIS"),
        c("avtomat.port_silence_gap_millis", 10000, 1000, 100000, "Второй пик молчания порта не раньше чем через столько после первого, мс", "AutoMode.PORT_SILENCE_GAP_MILLIS"),
        c("avtomat.port_silence_stale_millis", 120000, 12000, 1200000, "Давнее молчание порта за первый пик не считаем, мс", "AutoMode.PORT_SILENCE_STALE_MILLIS"),
        c("avtomat.otkaz_mgnovennyy_millis", 1500, 150, 15000, "Отказ (RST) узла основного быстрее этого — мгновенный: так режет белый список у МегаФона, мс", "AutoMode.OTKAZ_MGNOVENNYY_MILLIS"),
        c("avtomat.otkaz_perezamer_millis", 10000, 1000, 100000, "Узел отказывает мгновенно, а прошлый замер режима видел отказ всех неразрешённых без контроля — переспросить не раньше, мс", "AutoMode.OTKAZ_PEREZAMER_MILLIS"),
        c("avtomat.room_trial_after_belyy", 1, 1, 4, "Сколько провалов основного при свежей подсказке «белый список» до пробного подъёма комнаты (сначала WB)", "AutoMode.ROOM_TRIAL_AFTER_WHITELIST"),
        c("corelog.okno_svodki_ms", 600000, 60000, 6000000, "Сводка за окно: сколько соединений, сколько отказов и каких, мс", "CoreLog.OKNO_SVODKI_MS"),
        c("corelog.dolya_trevogi", 15, 1, 100, "С какой доли отказов окно считается бедой и просит отправку, %", "CoreLog.DOLYA_TREVOGI"),
        c("corelog.bystro_okno_ms", 30000, 3000, 300000, "Короткое окно для шторма, рядом с десятиминутным, мс", "CoreLog.BYSTRO_OKNO_MS"),
        c("corelog.bystro_porog", 200, 1, 800, "Сколько отказов в коротком окне считать штормом", "CoreLog.BYSTRO_POROG"),
        c("corelog.bystro_dolya", 50, 1, 100, "И какая при этом доля: сотня отказов на десятках тысяч соединений — не шторм, %", "CoreLog.BYSTRO_DOLYA"),
        c("corelog.trevoga_ne_chashche_ms", 3600000, 360000, 36000000, "Реже одного раза в час по тревоге не шлём: journal и так уедет ночью, мс", "CoreLog.TREVOGA_NE_CHASHCHE_MS"),
        c("uzel.uzel_okno_ms", 30000, 3000, 300000, "Окно, в котором считаются отказы через узлы (uzel.porog_otkazov), мс", "CoreLog.UZEL_OKNO_MS"),
        c("corelog.povod_min_otkazov", 10, 1, 40, "Сколько отказов в окне нужно, чтобы стоило спрашивать режим сети", "CoreLog.POVOD_MIN_OTKAZOV"),
        c("rezhim_seti.tcp_timeout_millis", 2500, 250, 25000, "Ждём ответа на SYN, мс", "NetworkModeDetector.TCP_TIMEOUT_MILLIS"),
        c("rezhim_seti.tls_timeout_millis", 4000, 400, 40000, "Ждём рукопожатия, мс", "NetworkModeDetector.TLS_TIMEOUT_MILLIS"),
        c("rezhim_seti.read_timeout_millis", 4000, 400, 40000, "Ждём очередной порции данных, мс", "NetworkModeDetector.READ_TIMEOUT_MILLIS"),
        c("rezhim_seti.bulk_budget_millis", 6000, 600, 60000, "Сколько всего смотрим на поток, даже если он исправно течёт, мс", "NetworkModeDetector.BULK_BUDGET_MILLIS"),
        c("rezhim_seti.bulk_bytes", 65536, 8192, 524288, "Сколько байт хотим прокачать, байт", "NetworkModeDetector.BULK_BYTES"),
        c("rezhim_seti.cooldown_millis", 60000, 6000, 600000, "Минимум между замерами, мс", "NetworkModeDetector.COOLDOWN_MILLIS"),
        c("rezhim_seti.cooldown_jitter_millis", 30000, 3000, 300000, "Случайная добавка к выдержке — чтобы интервал не был ровным, мс", "NetworkModeDetector.COOLDOWN_JITTER_MILLIS"),
        c("rezhim_seti.max_control_tries", 3, 1, 12, "Сколько разрешённых адресов перебирает контроль режима сети", "NetworkModeDetector.MAX_CONTROL_TRIES"),
        c("rezhim_seti.max_addresses", 2, 1, 10, "Сколько адресов одной точки пробуем", "NetworkModeDetector.MAX_ADDRESSES"),
        c("rezhim_seti.max_unlisted_addresses", 3, 1, 12, "Неразрешённых точек три: вердикт о белом списке не должен зависеть от одной", "NetworkModeDetector.MAX_UNLISTED_ADDRESSES"),
        c("rezhim_seti.freeze_proof_bytes", 24576, 3072, 196608, "Столько байт должна дать передача, чтобы доказать, что окно «16–20 КБ» пройдено: с запасом над верхней границей, рукопожатие входит в счёт у оператора, байт", "NetworkModeDecision.FREEZE_PROOF_BYTES"),
        c("rezhim_seti.control_confident_tries", 2, 1, 10, "Столько разрешённых адресов должен перебрать контроль, чтобы его провал считался уверенным", "NetworkModeDecision.CONTROL_CONFIDENT_TRIES"),
        c("proba.min_timeout_millis", 12000, 1200, 120000, "Нижняя граница ожидания ответа, мс", "HonestProbe.MIN_TIMEOUT_MILLIS"),
        c("proba.entry_timeout_millis", 2000, 200, 20000, "Вход на петле: не ответил за две секунды — его там нет, мс", "HonestProbe.ENTRY_TIMEOUT_MILLIS"),
        c("proba.direct_timeout_millis", 6000, 600, 60000, "Ожидание ответа для прямой пробы — той, что идёт мимо всякого туннеля (measureDirect), мс", "HonestProbe.DIRECT_TIMEOUT_MILLIS"),
        c("proba.direct_connect_timeout_millis", 4000, 400, 40000, "Прямое соединение до цели: столько ждём ответа на SYN, мс", "HonestProbe.DIRECT_CONNECT_TIMEOUT_MILLIS"),
        c("proba.quiet_millis", 2000, 200, 20000, "К одному имени — не чаще раза в это время, мс", "HonestProbe.QUIET_MILLIS"),
        c("proba.name_budget_millis", 1500, 150, 15000, "Сколько ждём адрес цели своим резолвером, прежде чем спросить систему, мс", "HonestProbe.NAME_BUDGET_MILLIS"),
        c("proba.wb_timeout_millis", 4000, 400, 40000, "Ожидание ответа в пробе живости комнаты WB; пол min_timeout_millis на неё не распространяется, мс", "WbChatCore.WB_PROBE_TIMEOUT_MILLIS"),
        c("skorost.entry_timeout_millis", 2000, 200, 20000, "Вход на петле: не ответил за две секунды — его там нет, мс", "SpeedProbe.ENTRY_TIMEOUT_MILLIS"),
        c("skorost.read_timeout_millis", 8000, 800, 80000, "Ждём каждую порцию, мс", "SpeedProbe.READ_TIMEOUT_MILLIS"),
        c("skorost.budget_millis", 15000, 1500, 150000, "Дольше этого замер не тянем: ответ «медленно» уже получен, мс", "SpeedProbe.BUDGET_MILLIS"),
        c("skorost.enough_bytes", 98304, 12288, 786432, "Хватит для вывода о полосе; дальше качать незачем, байт", "SpeedProbe.ENOUGH_BYTES"),
        c("skorost.squeezed_bytes_per_sec", 65536, 8192, 524288, "Ниже этого считаем путь задушенным, байт", "SpeedProbe.SQUEEZED_BYTES_PER_SEC"),
        c("skorost.cooldown_millis", 600000, 60000, 6000000, "Не чаще этого: замер платный по трафику, мс", "SpeedProbe.COOLDOWN_MILLIS"),
        c("dom.memory_millis", 45000, 4500, 450000, "Сколько признак дома считается стоящим после того, как его видели в последний раз, мс", "HomeSign.MEMORY_MILLIS"),
        c("dom.control_budget_millis", 1200, 120, 12000, "Сколько ждём контрольный домен, когда подменных адресов уже набрано достаточно, мс", "HomeSign.CONTROL_BUDGET_MILLIS"),
        c("dom.prismotr.min_millis", 40000, 4000, 400000, "Нижняя граница паузы между пробами, мс", "HomeWatch.MIN_MILLIS"),
        c("dom.prismotr.max_millis", 70000, 7000, 700000, "Верхняя граница паузы между пробами, мс", "HomeWatch.MAX_MILLIS"),
        c("komnata.storozh.check_interval_millis", 5000, 500, 50000, "Как часто спрашиваем канал, мс", "OlcRtcWatchdog.CHECK_INTERVAL_MILLIS"),
        c("komnata.storozh.failures_before_restart", 3, 1, 12, "Столько отказов подряд считаем смертью комнаты, а не её пересборкой", "OlcRtcWatchdog.FAILURES_BEFORE_RESTART"),
        c("komnata.storozh.max_restarts", 5, 1, 20, "Столько подъёмов подряд идут по нарастающей паузе; дальше — редкие попытки", "OlcRtcWatchdog.MAX_RESTARTS"),
        c("komnata.storozh.rare_retry_millis", 120000, 12000, 1200000, "Пауза между редкими попытками, когда частые кончились, мс", "OlcRtcWatchdog.RARE_RETRY_MILLIS"),
        c("komnata.storozh.healthy_reset_millis", 120000, 12000, 1200000, "Сколько канал должен продержаться живым, чтобы забыть прошлые подъёмы, мс", "OlcRtcWatchdog.HEALTHY_RESET_MILLIS"),
        c("komnata.storozh.grace_millis", 180000, 18000, 1800000, "Окно «встаёт» от Ready: пока через комнату не прошла ни одна проба, не перезапускаем и выход не уводим, мс", "OlcRtcWatchdog.GRACE_MILLIS"),
        c("komnata.storozh.failures_unproven", 6, 1, 24, "Столько отказов без единой удачи после окна «встаёт» считаем смертью комнаты", "OlcRtcWatchdog.FAILURES_UNPROVEN"),
        c("komnata.yadro.default_ready_timeout_millis", 25000, 2500, 250000, "Сколько ждём готовности одной попытки, мс", "OlcRtcCore.DEFAULT_READY_TIMEOUT_MILLIS"),
        c("komnata.yadro.default_attempts", 3, 1, 12, "Сколько раз пробуем", "OlcRtcCore.DEFAULT_ATTEMPTS"),
        c("komnata.yadro.default_retry_pause_millis", 5000, 500, 50000, "Пауза между попытками: дать ноге дойти до следующего захода, мс", "OlcRtcCore.DEFAULT_RETRY_PAUSE_MILLIS"),
        c("komnata.yadro.liveness_interval_millis", 10000, 1000, 100000, "Живость управляющего потока, мс", "OlcRtcCore.LIVENESS_INTERVAL_MILLIS"),
        c("komnata.yadro.liveness_timeout_millis", 30000, 3000, 300000, "Сколько ждём ответа управляющего потока ядра комнаты, мс", "OlcRtcCore.LIVENESS_TIMEOUT_MILLIS"),
        c("komnata.yadro.liveness_failures", 6, 1, 24, "Сколько провалов живости управляющего потока подряд — смерть", "OlcRtcCore.LIVENESS_FAILURES"),
        c("wb.storozh.restart_pause_millis", 5000, 500, 50000, "Пауза перед новым подъёмом ядра WB Chat, мс", "WbChatWatchdog.RESTART_PAUSE_MILLIS"),
        c("wb.storozh.slow_pause_millis", 30000, 3000, 300000, "Пауза после серии неудач подъёма WB Chat, мс", "WbChatWatchdog.SLOW_PAUSE_MILLIS"),
        c("wb.storozh.fails_before_slow", 3, 1, 12, "Неудач подряд до медленных подъёмов WB Chat", "WbChatWatchdog.FAILS_BEFORE_SLOW"),
        c("wb.storozh.healthy_millis", 60000, 6000, 600000, "Сколько ядро должно простоять готовым, чтобы его смерть не считалась неудачей, мс", "WbChatWatchdog.HEALTHY_MILLIS"),
        c("zhurnal.max_archive_bytes", 20971520, 2621440, 167772160, "Потолок одной отправки, байт", "LogUploadWork.MAX_ARCHIVE_BYTES"),
        c("zhurnal.povtor_pause_millis", 420000, 42000, 4200000, "Через сколько повторять неудачную отправку, мс", "LogUploadWork.POVTOR_PAUSE_MILLIS"),
        c("zhurnal.retry_give_up_millis", 86400000, 8640000, 86400000, "Сколько повторять неудачную отправку журнала, прежде чем ждать следующего круга, мс", "LogUploadWork.RETRY_GIVE_UP_MILLIS"),
        c("zhurnal.stuck_limit", 3, 1, 12, "После скольких подряд отказов ПО ОДНОМУ И ТОМУ ЖЕ хвосту считаем его безнадёжным", "LogUploadWork.STUCK_LIMIT"),
    )

    private val SPISKI: List<Klyuch> = listOf(
        Klyuch("dom.domeny", Tip.SPISOK, null, opisanie = "домены, по подмене которых узнаём дом", gde = "AutoMode.HOME_DOMAINS", shablon = DOMEN),
        Klyuch("dom.kontrol", Tip.STROKA, null, opisanie = "контрольный ru-домен: дома он отвечает настоящим адресом", gde = "AutoMode.HOME_CONTROL", shablon = DOMEN),
        Klyuch("proba.celi", Tip.SPISOK, null, opisanie = "цели честной пробы по кругу, `хост:порт/путь`", gde = "HonestProbe.TARGETS", shablon = CEL),
        Klyuch("skorost.cel", Tip.STROKA, null, opisanie = "цель замера скорости, `хост:порт/путь`", gde = "SpeedProbe.TARGET", shablon = CEL),
        Klyuch("rezhim_seti.neraz_adresa", Tip.SPISOK, null, opisanie = "адреса вне белого списка оператора (молчат под фильтром)", gde = "NetworkModeDetector.Targets.DEFAULT.unlisted", shablon = IPV4),
        Klyuch("rezhim_seti.raz_adresa", Tip.SPISOK, null, opisanie = "адреса из белого списка (отвечают под фильтром)", gde = "NetworkModeDetector.Targets.DEFAULT.allowed", shablon = IPV4),
        Klyuch("belyj_spisok.domeny", Tip.SPISOK, null, opisanie = "белый список оператора: домены напрямую (заменяет вшитый в APK)", gde = "BelyjSpisok.domeny", shablon = DOMEN),
        Klyuch("belyj_spisok.podseti", Tip.SPISOK, null, opisanie = "белый список оператора: подсети напрямую (заменяет вшитый в APK)", gde = "BelyjSpisok.podseti", shablon = PODSET),
    )

    /**
     * Выключатели подсистем. Нет выключателя (и почему): LifelinePatch — без него под белым
     * списком телефон не достаёт свой сервер, и включить обратно было бы нечем; ClashSecretPatch —
     * открыл бы служебный порт ядра всем приложениям телефона; правки комнаты
     * (OlcRtcConfigPatch: QUIC, стек tun) — без них комната ломается по построению;
     * SocksBreaker — последний предохранитель от шторма отказов.
     */
    private val VYKLYUCHATELI: List<Klyuch> = listOf(
        vykl("nabory_iz_kesha", "наборы правил не подменяются своим кэшем — ядро тянет их само", "BoxService: RuleSetLocalPatch"),
        vykl("kesh_vybora", "выбор выхода снова запоминается в кэше ядра", "BoxService: CacheSelectPatch"),
        vykl("vhody_proby", "без своих входов честной пробы (пути не меряются)", "BoxService: ProbeInboundPatch"),
        vykl("vozvrat_zalipshego", "залипший выбор автомата не возвращается на default", "BoxService: AutoModeSticky.release"),
        vykl("prismotr_doma", "без короткой пробы дома между заходами", "AutoMode.waitAtHome / HomeWatch"),
        vykl("prismotr_komnaty", "без приглядки к основному пути из комнаты", "AutoMode.waitInRoom"),
        vykl("skorost", "без замера скорости основного канала", "AutoMode: SpeedProbe"),
        vykl("storozh_komnaty", "присмотр за комнатой не запускается", "BoxService: OlcRtcWatchdog"),
        vykl("wb_chat", "ядро WB Chat не поднимается", "BoxService: WbChatWatchdog"),
        vykl("otkazy_uzla", "отказы соединений через узел не будят автомат", "CoreLog.otkazUzla"),
        vykl("puls", "без заголовка X-Sostoyanie", "Kelevra.deviceHeaders"),
        vykl("opros_sluzhby", "служба не опрашивает /info сама (остаётся расписание профиля)", "PultSvyaz"),
        vykl("otkaz_kak_belyy", "отказ (RST) узла и всех неразрешённых адресов при живом контроле не считается белым списком (как до 24.10)", "AutoMode.mainWorks / NetworkModeDecision.refusedWhitelist"),
        vykl("belyy_bez_okna", "провал основного в окне смены сети не засчитывается и при подсказке «белый список» (как до 24.10)", "AutoMode.failureCounts"),
        vykl("wb_srazu", "при заведомо мёртвом основном на живую комнату WB — по полным подтверждениям, а не с одной пробы (как до 24.10)", "AutoMode.wbProven"),
        vykl("pustoy_port", "принятое узлом соединение без прошедшего канала по-прежнему считается ответом узла (как до 24.10)", "AutoMode.hollowPort"),
    )

    /**
     * Включатели нового поведения: по умолчанию выключены, мозг включает их по устройствам.
     * `avtomat.wb_kandidat` — решение 24.10.2026: под белыми списками автомат пробует комнату WB
     * раньше Телемоста (порядок дома → основной → комната WB → Телемост → поиск).
     */
    private val VKLYUCHATELI: List<Klyuch> = listOf(
        logich("avtomat.wb_kandidat", false, "автомат пробует комнату WB раньше Телемоста, когда основной канал не поднимается", "AutoMode.trialRaiseWb"),
    )

    val KLYUCHI: List<Klyuch> = CHISLA + SPISKI + VYKLYUCHATELI + VKLYUCHATELI
}
