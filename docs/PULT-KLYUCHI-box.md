# Ключи пульта: Kelevra Box

Генерируется из реестра клиента (`app/src/main/java/io/nekohasekai/sfa/bg/pult/PultReestr.kt`, `PultKonstanty.kt`); правка руками затрётся. Сверяет тест `PultKlyuchiDocTest`.
Значение по умолчанию = константа в коде: без пульта поведение прежнее. Протокол — `PULT.md`.

| Ключ | Тип | Границы | По умолчанию | Что | Где в коде |
|---|---|---|---|---|---|
| `avtomat.connect_slack_millis` | int | 100–5000 | 500 | Запас поверх таймаута соединения: пул должен успеть отдать последний ответ, мс | AutoMode.CONNECT_SLACK_MILLIS |
| `avtomat.hint_ttl_millis` | int | 30000–3000000 | 300000 | Сколько живёт подсказка про режим сети, мс | AutoMode.HINT_TTL_MILLIS |
| `avtomat.manual_hold_millis` | int | 360000–36000000 | 3600000 | Сколько живёт выбор, сделанный руками, если сеть под нами так и не сменилась, мс | AutoMode.MANUAL_HOLD_MILLIS |
| `avtomat.node_answer_trust_millis` | int | 9000–900000 | 90000 | Сколько ответ узла считается свежим доводом, мс | AutoMode.NODE_ANSWER_TRUST_MILLIS |
| `avtomat.podtverzhdeniy` | int | 1–10 | 3 | сколько одинаковых наблюдений подряд меняют обстановку | AutoMode.CONFIRMATIONS |
| `avtomat.port_silence_gap_millis` | int | 1000–100000 | 10000 | Второй пик молчания порта не раньше чем через столько после первого, мс | AutoMode.PORT_SILENCE_GAP_MILLIS |
| `avtomat.port_silence_stale_millis` | int | 12000–1200000 | 120000 | Давнее молчание порта за первый пик не считаем, мс | AutoMode.PORT_SILENCE_STALE_MILLIS |
| `avtomat.probe_timeout_millis` | int | 600–60000 | 6000 | Потолок ожидания честной пробы, мс | AutoMode.PROBE_TIMEOUT_MILLIS |
| `avtomat.room_gate_fresh_millis` | int | 1500–150000 | 15000 | Насколько свеж замер, которому верим при запрете комнаты по тишине, мс | AutoMode.ROOM_GATE_FRESH_MILLIS |
| `avtomat.room_honest_peek_millis` | int | 6000–600000 | 60000 | Как часто из комнаты меряется основной путь ЧЕСТНО, когда узел уже отвечает, мс | AutoMode.ROOM_HONEST_PEEK_MILLIS |
| `avtomat.room_peek_max_millis` | int | 4000–400000 | 40000 | Верхняя граница паузы приглядки из комнаты, мс | AutoMode.ROOM_PEEK_MAX_MILLIS |
| `avtomat.room_peek_min_millis` | int | 2000–200000 | 20000 | Приглядка из комнаты: разброс паузы между дешёвыми пробами узла, мс | AutoMode.ROOM_PEEK_MIN_MILLIS |
| `avtomat.room_peek_timeout_millis` | int | 250–25000 | 2500 | Потолок ожидания приглядки, мс | AutoMode.ROOM_PEEK_TIMEOUT_MILLIS |
| `avtomat.room_trial_pause_cap_millis` | int | 60000–6000000 | 600000 | Комната не встаёт — паузу растим, чтобы не жечь батарею попытками в пустоту, мс | AutoMode.ROOM_TRIAL_PAUSE_CAP_MILLIS |
| `avtomat.room_trial_pause_millis` | int | 6000–600000 | 60000 | Начальная пауза между пробными подъёмами комнаты, мс | AutoMode.ROOM_TRIAL_PAUSE_MILLIS |
| `avtomat.round_home_millis` | int | 30000–3000000 | 300000 | Полный заход дома, мс | AutoMode.ROUND_HOME_MILLIS |
| `avtomat.round_main_millis` | int | 30000–3000000 | 300000 | Полный заход на основном канале, мс | AutoMode.ROUND_MAIN_MILLIS |
| `avtomat.round_manual_millis` | int | 6000–600000 | 60000 | Ритм в ручном режиме: решений автомат не принимает, но раз в минуту перемеряет выбранный путь, чтобы экран не показывал протухшую запись, мс | AutoMode.ROUND_MANUAL_MILLIS |
| `avtomat.round_no_network_millis` | int | 30000–3000000 | 300000 | Страховочный тик, когда сети нет: чтобы ложный вердикт не усыплял автомат навсегда, мс | AutoMode.ROUND_NO_NETWORK_MILLIS |
| `avtomat.round_room_millis` | int | 18000–1800000 | 180000 | Возврат из комнаты на быстрый канал, мс | AutoMode.ROUND_ROOM_MILLIS |
| `avtomat.round_searching_cap_millis` | int | 12000–1200000 | 120000 | Потолок ритма поиска пути, мс | AutoMode.ROUND_SEARCHING_CAP_MILLIS |
| `avtomat.round_searching_millis` | int | 1200–120000 | 12000 | Ритм поиска пути (растёт до потолка), мс | AutoMode.ROUND_SEARCHING_MILLIS |
| `avtomat.silent_recheck_max_millis` | int | 3000–300000 | 30000 | Верхняя граница перепроверки после молчания резолверов, мс | AutoMode.SILENT_RECHECK_MAX_MILLIS |
| `avtomat.silent_recheck_min_millis` | int | 2000–200000 | 20000 | Перепроверка после захода, на котором резолверы промолчали: через 20–30 секунд, а не через обычные пять минут, мс | AutoMode.SILENT_RECHECK_MIN_MILLIS |
| `avtomat.silent_recheck_rounds` | int | 1–40 | 10 | Сколько быстрых перепроверок подряд на молчании резолверов | AutoMode.SILENT_RECHECK_ROUNDS |
| `avtomat.tcp_timeout_millis` | int | 400–40000 | 4000 | Потолок ожидания рукопожатия с узлом основного канала, мс | AutoMode.TCP_TIMEOUT_MILLIS |
| `avtomat.tishina_rezolvera_min` | int | 1–120 | 15 | сколько держим вердикт, пока резолверы молчат, мин | AutoMode.SILENCE_HOLD_MILLIS |
| `belyj_spisok.domeny` | список | ≤ 5000 строк, домен | из кода | белый список оператора: домены напрямую (заменяет вшитый в APK) | BelyjSpisok.domeny |
| `belyj_spisok.podseti` | список | ≤ 5000 строк, подсеть CIDR | из кода | белый список оператора: подсети напрямую (заменяет вшитый в APK) | BelyjSpisok.podseti |
| `corelog.bystro_dolya` | int | 1–100 | 50 | И какая при этом доля: сотня отказов на десятках тысяч соединений — не шторм, % | CoreLog.BYSTRO_DOLYA |
| `corelog.bystro_okno_ms` | int | 3000–300000 | 30000 | Короткое окно для шторма, рядом с десятиминутным, мс | CoreLog.BYSTRO_OKNO_MS |
| `corelog.bystro_porog` | int | 1–800 | 200 | Сколько отказов в коротком окне считать штормом | CoreLog.BYSTRO_POROG |
| `corelog.dolya_trevogi` | int | 1–100 | 15 | С какой доли отказов окно считается бедой и просит отправку, % | CoreLog.DOLYA_TREVOGI |
| `corelog.okno_svodki_ms` | int | 60000–6000000 | 600000 | Сводка за окно: сколько соединений, сколько отказов и каких, мс | CoreLog.OKNO_SVODKI_MS |
| `corelog.povod_min_otkazov` | int | 1–40 | 10 | Сколько отказов в окне нужно, чтобы стоило спрашивать режим сети | CoreLog.POVOD_MIN_OTKAZOV |
| `corelog.trevoga_ne_chashche_ms` | int | 360000–36000000 | 3600000 | Реже одного раза в час по тревоге не шлём: journal и так уедет ночью, мс | CoreLog.TREVOGA_NE_CHASHCHE_MS |
| `dom.control_budget_millis` | int | 120–12000 | 1200 | Сколько ждём контрольный домен, когда подменных адресов уже набрано достаточно, мс | HomeSign.CONTROL_BUDGET_MILLIS |
| `dom.dns_budget_millis` | int | 250–25000 | 2500 | Сколько ждём ответы резолвера на вопрос «дома ли мы», мс | AutoMode.DNS_BUDGET_MILLIS |
| `dom.domeny` | список | ≤ 5000 строк, домен | из кода | домены, по подмене которых узнаём дом | AutoMode.HOME_DOMAINS |
| `dom.home_hits` | int | 1–10 | 2 | Сколько доменов из списка дома должны вернуть подменный адрес | AutoMode.HOME_HITS |
| `dom.home_traffic_tries` | int | 1–10 | 2 | Сколько раз пробуем подтвердить дом трафиком, прежде чем сказать «не дома» | AutoMode.HOME_TRAFFIC_TRIES |
| `dom.kontrol` | строка | домен | из кода | контрольный ru-домен: дома он отвечает настоящим адресом | AutoMode.HOME_CONTROL |
| `dom.memory_millis` | int | 4500–450000 | 45000 | Сколько признак дома считается стоящим после того, как его видели в последний раз, мс | HomeSign.MEMORY_MILLIS |
| `dom.prismotr.max_millis` | int | 7000–700000 | 70000 | Верхняя граница паузы между пробами, мс | HomeWatch.MAX_MILLIS |
| `dom.prismotr.min_millis` | int | 4000–400000 | 40000 | Нижняя граница паузы между пробами, мс | HomeWatch.MIN_MILLIS |
| `funkcii.avtorezhim` | bool | — | true | автомат выбирает выход сам | AutoMode |
| `funkcii.komnata` | bool | — | true | комната (olcRTC) разрешена | OlcRtcParams.hasRoom |
| `kanal.dolgiy_opros` | bool | — | false | долгий опрос …/pult/zhdat из службы (мгновенные команды) | PultSvyaz |
| `komnata.storozh.check_interval_millis` | int | 500–50000 | 5000 | Как часто спрашиваем канал, мс | OlcRtcWatchdog.CHECK_INTERVAL_MILLIS |
| `komnata.storozh.failures_before_restart` | int | 1–12 | 3 | Столько отказов подряд считаем смертью комнаты, а не её пересборкой | OlcRtcWatchdog.FAILURES_BEFORE_RESTART |
| `komnata.storozh.healthy_reset_millis` | int | 12000–1200000 | 120000 | Сколько канал должен продержаться живым, чтобы забыть прошлые подъёмы, мс | OlcRtcWatchdog.HEALTHY_RESET_MILLIS |
| `komnata.storozh.max_restarts` | int | 1–20 | 5 | Столько подъёмов подряд идут по нарастающей паузе; дальше — редкие попытки | OlcRtcWatchdog.MAX_RESTARTS |
| `komnata.storozh.rare_retry_millis` | int | 12000–1200000 | 120000 | Пауза между редкими попытками, когда частые кончились, мс | OlcRtcWatchdog.RARE_RETRY_MILLIS |
| `komnata.yadro.default_attempts` | int | 1–12 | 3 | Сколько раз пробуем | OlcRtcCore.DEFAULT_ATTEMPTS |
| `komnata.yadro.default_ready_timeout_millis` | int | 2500–250000 | 25000 | Сколько ждём готовности одной попытки, мс | OlcRtcCore.DEFAULT_READY_TIMEOUT_MILLIS |
| `komnata.yadro.default_retry_pause_millis` | int | 500–50000 | 5000 | Пауза между попытками: дать ноге дойти до следующего захода, мс | OlcRtcCore.DEFAULT_RETRY_PAUSE_MILLIS |
| `komnata.yadro.liveness_failures` | int | 1–24 | 6 | Сколько провалов живости управляющего потока подряд — смерть | OlcRtcCore.LIVENESS_FAILURES |
| `komnata.yadro.liveness_interval_millis` | int | 1000–100000 | 10000 | Живость управляющего потока, мс | OlcRtcCore.LIVENESS_INTERVAL_MILLIS |
| `komnata.yadro.liveness_timeout_millis` | int | 3000–300000 | 30000 | Сколько ждём ответа управляющего потока ядра комнаты, мс | OlcRtcCore.LIVENESS_TIMEOUT_MILLIS |
| `obnovleniya.kanal` | выбор | obychnyy\|proba\|stop | obychnyy | obychnyy — выпуски GitHub; proba — только блок obnovlenie из пульта; stop — не обновляться | Vendor.checkUpdateAsync |
| `obnovleniya.napominat_chas` | int | 1–48 | 24 | напоминание о неустановленном обновлении, ч | IshodUstanovki.NAPOMINANIE_MINUT |
| `obnovleniya.tiho` | bool | — | false | ставить обновления без подтверждения (поверх настройки в приложении) | Settings.silentInstallEnabled |
| `opros.info_min` | int | 5–240 | 60 | интервал профиля и сводки /info (расписание и опрос из службы), мин | UpdateProfileWork, PultSvyaz |
| `proba.celi` | список | ≤ 5000 строк, хост:порт/путь | из кода | цели честной пробы по кругу, `хост:порт/путь` | HonestProbe.TARGETS |
| `proba.direct_connect_timeout_millis` | int | 400–40000 | 4000 | Прямое соединение до цели: столько ждём ответа на SYN, мс | HonestProbe.DIRECT_CONNECT_TIMEOUT_MILLIS |
| `proba.direct_timeout_millis` | int | 600–60000 | 6000 | Ожидание ответа для прямой пробы — той, что идёт мимо всякого туннеля (measureDirect), мс | HonestProbe.DIRECT_TIMEOUT_MILLIS |
| `proba.entry_timeout_millis` | int | 200–20000 | 2000 | Вход на петле: не ответил за две секунды — его там нет, мс | HonestProbe.ENTRY_TIMEOUT_MILLIS |
| `proba.min_timeout_millis` | int | 1200–120000 | 12000 | Нижняя граница ожидания ответа, мс | HonestProbe.MIN_TIMEOUT_MILLIS |
| `proba.name_budget_millis` | int | 150–15000 | 1500 | Сколько ждём адрес цели своим резолвером, прежде чем спросить систему, мс | HonestProbe.NAME_BUDGET_MILLIS |
| `proba.quiet_millis` | int | 200–20000 | 2000 | К одному имени — не чаще раза в это время, мс | HonestProbe.QUIET_MILLIS |
| `rezhim_seti.bulk_budget_millis` | int | 600–60000 | 6000 | Сколько всего смотрим на поток, даже если он исправно течёт, мс | NetworkModeDetector.BULK_BUDGET_MILLIS |
| `rezhim_seti.bulk_bytes` | int | 8192–524288 | 65536 | Сколько байт хотим прокачать, байт | NetworkModeDetector.BULK_BYTES |
| `rezhim_seti.control_confident_tries` | int | 1–10 | 2 | Столько разрешённых адресов должен перебрать контроль, чтобы его провал считался уверенным | NetworkModeDecision.CONTROL_CONFIDENT_TRIES |
| `rezhim_seti.cooldown_jitter_millis` | int | 3000–300000 | 30000 | Случайная добавка к выдержке — чтобы интервал не был ровным, мс | NetworkModeDetector.COOLDOWN_JITTER_MILLIS |
| `rezhim_seti.cooldown_millis` | int | 6000–600000 | 60000 | Минимум между замерами, мс | NetworkModeDetector.COOLDOWN_MILLIS |
| `rezhim_seti.freeze_proof_bytes` | int | 3072–196608 | 24576 | Столько байт должна дать передача, чтобы доказать, что окно «16–20 КБ» пройдено: с запасом над верхней границей, рукопожатие входит в счёт у оператора, байт | NetworkModeDecision.FREEZE_PROOF_BYTES |
| `rezhim_seti.max_addresses` | int | 1–10 | 2 | Сколько адресов одной точки пробуем | NetworkModeDetector.MAX_ADDRESSES |
| `rezhim_seti.max_control_tries` | int | 1–12 | 3 | Сколько разрешённых адресов перебирает контроль режима сети | NetworkModeDetector.MAX_CONTROL_TRIES |
| `rezhim_seti.max_unlisted_addresses` | int | 1–12 | 3 | Неразрешённых точек три: вердикт о белом списке не должен зависеть от одной | NetworkModeDetector.MAX_UNLISTED_ADDRESSES |
| `rezhim_seti.neraz_adresa` | список | ≤ 5000 строк, адрес IPv4 | из кода | адреса вне белого списка оператора (молчат под фильтром) | NetworkModeDetector.Targets.DEFAULT.unlisted |
| `rezhim_seti.raz_adresa` | список | ≤ 5000 строк, адрес IPv4 | из кода | адреса из белого списка (отвечают под фильтром) | NetworkModeDetector.Targets.DEFAULT.allowed |
| `rezhim_seti.read_timeout_millis` | int | 400–40000 | 4000 | Ждём очередной порции данных, мс | NetworkModeDetector.READ_TIMEOUT_MILLIS |
| `rezhim_seti.tcp_timeout_millis` | int | 250–25000 | 2500 | Ждём ответа на SYN, мс | NetworkModeDetector.TCP_TIMEOUT_MILLIS |
| `rezhim_seti.tls_timeout_millis` | int | 400–40000 | 4000 | Ждём рукопожатия, мс | NetworkModeDetector.TLS_TIMEOUT_MILLIS |
| `skorost.budget_millis` | int | 1500–150000 | 15000 | Дольше этого замер не тянем: ответ «медленно» уже получен, мс | SpeedProbe.BUDGET_MILLIS |
| `skorost.cel` | строка | хост:порт/путь | из кода | цель замера скорости, `хост:порт/путь` | SpeedProbe.TARGET |
| `skorost.cooldown_millis` | int | 60000–6000000 | 600000 | Не чаще этого: замер платный по трафику, мс | SpeedProbe.COOLDOWN_MILLIS |
| `skorost.enough_bytes` | int | 12288–786432 | 98304 | Хватит для вывода о полосе; дальше качать незачем, байт | SpeedProbe.ENOUGH_BYTES |
| `skorost.entry_timeout_millis` | int | 200–20000 | 2000 | Вход на петле: не ответил за две секунды — его там нет, мс | SpeedProbe.ENTRY_TIMEOUT_MILLIS |
| `skorost.read_timeout_millis` | int | 800–80000 | 8000 | Ждём каждую порцию, мс | SpeedProbe.READ_TIMEOUT_MILLIS |
| `skorost.squeezed_bytes_per_sec` | int | 8192–524288 | 65536 | Ниже этого считаем путь задушенным, байт | SpeedProbe.SQUEEZED_BYTES_PER_SEC |
| `uzel.node_measurement_fresh_millis` | int | 3000–300000 | 30000 | Сколько замер разового опроса путей (nodeMeasured) считается свежим, мс | AutoMode.NODE_MEASUREMENT_FRESH_MILLIS |
| `uzel.porog_otkazov` | int | 1–20 | 3 | отказов узла подряд до перехода на следующий | CoreLog.UZEL_POROG |
| `uzel.uzel_budit_millis` | int | 6000–600000 | 60000 | Не чаще этого отказы узла будят автомат, мс | AutoMode.UZEL_BUDIT_MILLIS |
| `uzel.uzel_okno_ms` | int | 3000–300000 | 30000 | Окно, в котором считаются отказы через узлы (uzel.porog_otkazov), мс | CoreLog.UZEL_OKNO_MS |
| `uzel.vozvrat_na_pervyy_min` | int | 1–120 | 4 | через сколько минут на запасном узле пробовать первый | AutoModeNodeWatch.RETURN_AFTER_MILLIS |
| `vykl.kesh_vybora` | bool | — | false | выбор выхода снова запоминается в кэше ядра | BoxService: CacheSelectPatch |
| `vykl.nabory_iz_kesha` | bool | — | false | наборы правил не подменяются своим кэшем — ядро тянет их само | BoxService: RuleSetLocalPatch |
| `vykl.opros_sluzhby` | bool | — | false | служба не опрашивает /info сама (остаётся расписание профиля) | PultSvyaz |
| `vykl.otkazy_uzla` | bool | — | false | отказы соединений через узел не будят автомат | CoreLog.otkazUzla |
| `vykl.prismotr_doma` | bool | — | false | без короткой пробы дома между заходами | AutoMode.waitAtHome / HomeWatch |
| `vykl.prismotr_komnaty` | bool | — | false | без приглядки к основному пути из комнаты | AutoMode.waitInRoom |
| `vykl.puls` | bool | — | false | без заголовка X-Sostoyanie | Kelevra.deviceHeaders |
| `vykl.skorost` | bool | — | false | без замера скорости основного канала | AutoMode: SpeedProbe |
| `vykl.storozh_komnaty` | bool | — | false | присмотр за комнатой не запускается | BoxService: OlcRtcWatchdog |
| `vykl.vhody_proby` | bool | — | false | без своих входов честной пробы (пути не меряются) | BoxService: ProbeInboundPatch |
| `vykl.vozvrat_zalipshego` | bool | — | false | залипший выбор автомата не возвращается на default | BoxService: AutoModeSticky.release |
| `vykl.wb_chat` | bool | — | false | ядро WB Chat не поднимается | BoxService: WbChatWatchdog |
| `wb.storozh.fails_before_slow` | int | 1–12 | 3 | Неудач подряд до медленных подъёмов WB Chat | WbChatWatchdog.FAILS_BEFORE_SLOW |
| `wb.storozh.healthy_millis` | int | 6000–600000 | 60000 | Сколько ядро должно простоять готовым, чтобы его смерть не считалась неудачей, мс | WbChatWatchdog.HEALTHY_MILLIS |
| `wb.storozh.restart_pause_millis` | int | 500–50000 | 5000 | Пауза перед новым подъёмом ядра WB Chat, мс | WbChatWatchdog.RESTART_PAUSE_MILLIS |
| `wb.storozh.slow_pause_millis` | int | 3000–300000 | 30000 | Пауза после серии неудач подъёма WB Chat, мс | WbChatWatchdog.SLOW_PAUSE_MILLIS |
| `zhurnal.chasto_do` | ISO-время | — | нет | до этого времени журнал уходит каждые 10 мин | LogUploadWork |
| `zhurnal.max_archive_bytes` | int | 2621440–167772160 | 20971520 | Потолок одной отправки, байт | LogUploadWork.MAX_ARCHIVE_BYTES |
| `zhurnal.otpravka_min` | int | 10–1440 | 60 | период отправки журнала, мин (WorkManager не чаще 15) | LogUploadWork.schedule0 |
| `zhurnal.povtor_pause_millis` | int | 42000–4200000 | 420000 | Через сколько повторять неудачную отправку, мс | LogUploadWork.POVTOR_PAUSE_MILLIS |
| `zhurnal.retry_give_up_millis` | int | 8640000–86400000 | 86400000 | Сколько повторять неудачную отправку журнала, прежде чем ждать следующего круга, мс | LogUploadWork.RETRY_GIVE_UP_MILLIS |
| `zhurnal.stuck_limit` | int | 1–12 | 3 | После скольких подряд отказов ПО ОДНОМУ И ТОМУ ЖЕ хвосту считаем его безнадёжным | LogUploadWork.STUCK_LIMIT |
