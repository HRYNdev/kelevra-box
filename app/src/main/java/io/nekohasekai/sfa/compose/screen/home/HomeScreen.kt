package io.nekohasekai.sfa.compose.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.bg.AutoMode
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.bg.path.PathId
import io.nekohasekai.sfa.bg.path.PathRegistry
import io.nekohasekai.sfa.bg.path.PathStatus
import io.nekohasekai.sfa.bg.path.PathWords
import io.nekohasekai.sfa.compose.theme.DialState
import io.nekohasekai.sfa.compose.theme.K
import io.nekohasekai.sfa.compose.theme.KBadge
import io.nekohasekai.sfa.compose.theme.KButton
import io.nekohasekai.sfa.compose.theme.KCard
import io.nekohasekai.sfa.compose.theme.KDial
import io.nekohasekai.sfa.compose.theme.KDim
import io.nekohasekai.sfa.compose.theme.KDivider
import io.nekohasekai.sfa.compose.theme.KGroupTitle
import io.nekohasekai.sfa.compose.theme.KHint
import io.nekohasekai.sfa.compose.theme.KRowItem
import io.nekohasekai.sfa.compose.theme.Montserrat
import io.nekohasekai.sfa.compose.theme.RobotoMono
import io.nekohasekai.sfa.constant.Status
import kotlinx.coroutines.delay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow

/** Канал в списке: имя, задержка, выбран ли сейчас. */
data class ChannelRow(val name: String, val delayMs: Int, val selected: Boolean)

/**
 * Код выхода в бейдже. Флаги-эмодзи не используем: на части устройств и в
 * браузере они не рисуются, а двухбуквенный код читается везде одинаково.
 */
/**
 * Выход через комнату.
 *
 * Спрашиваем реестр: имя комнаты приходит с сервера, и знает его раскладка конфига —
 * та самая, которую автомат отдаёт в общую память. Угадывание по корню слова осталось
 * запасным путём и работает ровно там, где раскладки ещё нет: до первого включения сети
 * ядро её не отдавало, а выход человек выбирает и в этот момент тоже.
 */
fun isRoomExit(name: String?): Boolean {
    val tag = name?.takeIf { it.isNotBlank() } ?: return false
    PathRegistry.snapshot.value[PathId.ROOM].def.exitTag?.let { return tag == it }
    val n = tag.lowercase()
    return n.contains("комнат") || n.contains("room")
}

fun badgeOf(name: String?): String? {
    val n = name?.lowercase() ?: return null
    return when {
        // У комнаты страны нет: несущая — чужой видеозвонок. Двухбуквенный огрызок
        // имени («КО») читался бы как код страны и врал бы.
        isRoomExit(n) -> null
        n.contains("нидерланд") || n.contains("netherl") || n.contains("nl") -> "NL"
        n.contains("герман") || n.contains("german") || n.contains("de") -> "DE"
        n.contains("екатеринб") || n.contains("дом") || n.contains("home") || n.contains("ru") -> "RU"
        n.contains("авто") || n.contains("auto") -> "AUTO"
        else -> name.take(2).uppercase()
    }
}

/**
 * Главный экран: круг-состояние по центру, под ним выход и подписка.
 *
 * Круг — единственное действие, попадать по нему нужно не глядя. Всё остальное
 * на экране только рассказывает, что происходит.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    serviceStatus: Status,
    activeOutbound: String?,
    channels: List<ChannelRow>,
    hasProfile: Boolean,
    transport: String? = null,
    activeChannel: String? = null,
    onToggle: () -> Unit,
    onSelectChannel: (String) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = K
    val running = serviceStatus == Status.Started
    val busy = serviceStatus == Status.Starting || serviceStatus == Status.Stopping
    var showExits by remember { mutableStateOf(false) }
    var showSubscription by remember { mutableStateOf(false) }
    // «40 секунд назад» обязано стареть на глазах: замер, который вечно выглядит свежим,
    // хуже отсутствия замера. Тикаем, пока открыт список выходов или шторка подписки —
    // в обеих время показано словами; на закрытом экране считать секунды незачем.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(showExits, showSubscription) {
        while (showExits || showSubscription) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    // Сводку держит общий объект: её же обновляет кнопка в расширенных, и карточка
    // должна показать новое сразу, без перезахода в приложение.
    val subscription by SubscriptionRefresh.info.collectAsState()
    // Какой ответ на жалобу уже прочитан. В памяти экрана — чтобы карточка исчезла по
    // нажатию сразу, на диске — чтобы не появилась снова после перезахода.
    var replySeen by remember { mutableStateOf(Settings.complaintReplySeen) }
    val auto by AutoMode.state.collectAsState()
    // Что известно про каждый путь. Экран больше ничего не достраивает сам: и круг,
    // и список выходов пересказывают этот снимок, а не собственную версию правды.
    val paths by PathRegistry.snapshot.collectAsState()
    // Что с наборами правил. Одна и та же строка идёт сюда и в шторку — через одну
    // таблицу [PathWords], чтобы экран и шторка не разошлись.
    val ruleSets by io.nekohasekai.sfa.bg.RuleSetCache.state.collectAsState()
    val rulesNote = PathWords.rulesNote(total = ruleSets.total, ready = ruleSets.ready)
    // Что человек выбрал руками. Читаем при каждой смене состояния автомата и запуска:
    // ядро может молчать, а показать надо правду.
    val manualExit = io.nekohasekai.sfa.database.Settings.manualExitName

    LaunchedEffect(hasProfile, running) {
        if (hasProfile) {
            SubscriptionRefresh.loadInfo()
            SubscriptionRefresh.loadLocal()
        }
    }

    val homePath = paths[PathId.HOME]
    val roomPath = paths[PathId.ROOM]

    val home = running && auto.situation == AutoMode.Situation.Home
    val noNetwork = running && homePath.status == PathStatus.Unavailable
    // Комнату узнаём по выбранному выходу, а не только по решению автомата: выбранная
    // руками комната — та же комната, и подпись под кругом должна быть та же.
    val roomChosen = running && (AutoMode.standingOn() == PathId.ROOM || isRoomExit(activeOutbound))
    // Выбранная комната и поднятая комната — разные вещи. Круг писал «Подключено. Комната»,
    // когда ядро olcRTC не запускалось вовсе: на ноге в этот момент ноль участников и ноль
    // трафика (поймано в эмуляторе 07.08.2026). Зелёным теперь только по живой комнате.
    val room = roomChosen && roomPath.status == PathStatus.Alive
    // «Не отвечает» пишем только когда комната действительно отказалась. Пока она ещё
    // не поднималась или поднимается — это «поднимаю»: иначе в первые же секунды
    // после включения человек читает «Комната не отвечает», хотя вход только начался.
    val roomDead = roomChosen && roomPath.refused
    val roomRising = roomChosen && !room && !roomDead
    // Выход человек выбрал сам — автомат отошёл. Это не «поломка», но и не молчание:
    // человек должен видеть, что подбором пути сейчас никто не занимается.
    val manualHold = running && !auto.auto
    // «Подключено» — это утверждение про канал, а не про сервис. Пока проба не прошла,
    // сказать про канал нечего; когда она провалилась — тем более (06.08.2026 экран
    // писал «Подключено» на мёртвом канале).
    // Сами правила «меряем» и «связи нет» живут в [PathWords] и проверяются тестами:
    // экран их пересказывал своей цепочкой условий, и цепочка разошлась — «Связи нет»
    // объявлялось прямо посреди замера основного канала (жалоба владельца 10.08.2026).
    val measuring = running && !manualHold && !noNetwork && PathWords.measuring(paths)
    val linkDead = running && !manualHold && !noNetwork && PathWords.linkDead(paths)
    // Путь, который человек выбрал руками. Раньше в ручном режиме круг показывал
    // состояние ядра, а не то, что реально намерили: проба секундами раньше называла
    // тот же путь мёртвым, а круг писал «Подключено» (поймано на стенде 08.08.2026).
    // Автомат отошёл, но мерить пришпиленный путь никто не запрещал — состояние берём
    // из того же реестра, что и для автоматического выбора.
    val manualPath = if (manualHold) paths.byExit(manualExit) else null
    val manualDead = manualHold && manualPath?.refused == true
    // Задушенный канал отвечает — просто им нельзя пользоваться. Писать про него
    // «не отвечает» значит врать человеку в глаза: он видит, что связь есть.
    val squeezed = running && !noNetwork && (manualPath?.squeezed == true || paths[PathId.MAIN].squeezed)
    // Ничего не поднимается, сети нет, или выбранный руками путь не отвечает —
    // круг не должен врать зелёным.
    val broken = running && (noNetwork || roomDead || linkDead || manualDead)

    // Задержку комнаты меряет её собственный присмотр, а помнит — реестр. Своего опроса
    // экран больше не ведёт: цикл раз в три секунды жил только потому, что состояние
    // комнаты нельзя было получить иначе как спросив ядро напрямую.
    val roomLatency = roomPath.latencyMs ?: 0L

    val state = when {
        busy -> DialState.Busy
        broken -> DialState.Broken
        // Задушенный канал работает, но плохо. Это «внимание», не «ошибка» и не
        // «норма»: зелёным кругом на нём врать нельзя, красным — тоже.
        squeezed -> DialState.Degraded
        running -> DialState.On
        else -> DialState.Off
    }

    // «Ищу путь» — это решение автомата, а не факт про отдельный выход: пути уже
    // отказали, но менять обстановку он вправе только после своих подтверждений.
    // Реестр про такое не знает и знать не должен — он помнит выходы, а не решения.
    val searching = running && auto.situation == AutoMode.Situation.Searching

    val activeDelay = channels.firstOrNull { it.selected }?.delayMs ?: 0
    val place = when {
        busy || !running -> null
        // Дома защита уже есть, её делает роутер. Это и написано, без «подключено».
        home -> "обход на роутере"
        noNetwork || searching -> null
        else -> activeOutbound
    }
    // транспорт активного выхода знает только сервер: ядро наверх его не отдаёт
    val activeTransport = transport ?: subscription?.transports?.get(activeChannel)
    val meta = buildString {
        when {
            !running || home || broken -> Unit
            room -> {
                append("комната")
                if (roomLatency > 0) append(" · $roomLatency мс")
            }

            else -> {
                if (!activeTransport.isNullOrBlank()) append(activeTransport)
                if (activeDelay > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("$activeDelay мс")
                }
            }
        }
    }.ifBlank { null }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.Bg)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))

        KDial(
            state = state,
            title = when {
                !hasProfile -> "Подключить"
                busy && serviceStatus == Status.Starting -> "Подключаюсь"
                busy -> "Отключаюсь"
                home -> "Дома"
                noNetwork -> "Нет сети"
                searching -> "Ищу путь"
                roomRising -> "Поднимаю комнату"
                roomDead -> "Комната не отвечает"
                squeezed -> "Медленно"
                linkDead -> "Связи нет"
                manualDead -> "Не отвечает"
                measuring -> "Проверяю связь"
                running -> "Подключено"
                else -> "Отключено"
            },
            badge = if (running && !home && !broken) badgeOf(activeOutbound) else null,
            place = place,
            meta = meta,
            onClick = { if (hasProfile) onToggle() else onConnect() },
        )

        Spacer(Modifier.height(16.dp))
        KHint(
            when {
                // Правила ещё не приехали: туннель поднялся, но маршруты бедные и почти
                // всё идёт напрямую. Это первое, что человеку надо знать про такой сеанс, —
                // иначе он видит рабочий круг и не понимает, почему работает не всё.
                running && rulesNote != null -> rulesNote
                !hasProfile -> "нажмите, чтобы подключить"
                busy -> "подождите"
                home -> "дома интернет и так открыт, туннель выключен"
                noNetwork -> "включится, когда появится сеть"
                searching -> "подбираю рабочий выход"
                roomRising -> "поднимаю канал через комнату"
                roomDead -> "канал через комнату не поднялся, выберите другой выход"
                squeezed -> "связь есть, но очень медленная"
                linkDead -> "выбранный путь не отвечает, ищу другой"
                manualDead -> "выбран вами, не отвечает"
                measuring -> "проверяю, идут ли данные"
                manualHold -> "выход выбран вами, автомат вернётся при смене сети"
                running -> "нажмите, чтобы выключить"
                else -> "нажмите, чтобы включить"
            },
        )

        Spacer(Modifier.height(26.dp))

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = KDim.Pad)) {
            // карточка выхода видна всегда, когда подписка есть: до запуска сети
            // ядро ещё не отдало список, но человек должен понимать, куда пойдёт трафик
            if (hasProfile) {
                KCard(onClick = { showExits = true }) {
                    KRowItem(
                        label = "Выход",
                        title = when {
                            home -> "Не нужен"
                            // Имя выбранного выхода знает только работающее ядро, поэтому
                            // при выключенной сети карточка писала «Автоматически» даже
                            // после ручного выбора. Пока ядро молчит, показываем то, что
                            // человек выбрал сам, и оно же поедет в ядро при включении.
                            else -> activeOutbound
                                ?: manualExit.takeIf { it.isNotBlank() && !auto.auto }
                                // Ручной режим без выбранного выхода: писать
                                // «Автоматически» здесь значит врать — автомат выключен.
                                ?: if (auto.auto) "Автоматически" else "Не выбран"
                        },
                        subtitle = when {
                            home -> "дома работает роутер"
                            channels.isEmpty() && !running -> "определится при подключении"
                            auto.auto -> "выбирается сам"
                            // Ручной выбор больше не выключает автомат навсегда: он
                            // держится, пока не сменится сеть. Так и пишем, иначе
                            // «выбран вручную» читается как «автомат сломался».
                            activeOutbound == null && manualExit.isBlank() -> "нажмите, чтобы выбрать"
                            else -> "выбран вами · автомат вернётся при смене сети"
                        },
                        badge = if (home) null else badgeOf(activeOutbound),
                        chevron = true,
                    )
                }
                Spacer(Modifier.height(KDim.Gap))
            }

            val sub = subscription
            // Ответ на жалобу. Раньше жалоба уходила в пустоту: человек писал и больше
            // ничего не узнавал — а половина жалоб это вопрос, а не сообщение.
            val answer = sub?.reply
            if (answer != null && replySeen != answer.id.toString()) {
                KCard {
                    KRowItem(
                        label = "Ответ на вашу жалобу",
                        title = answer.about.take(64).ifBlank { "Жалоба №${answer.id}" },
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = answer.text,
                        fontFamily = Montserrat,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = colors.Text,
                    )
                    Spacer(Modifier.height(14.dp))
                    KButton(
                        text = "Понятно",
                        ghost = true,
                        onClick = {
                            Settings.complaintReplySeen = answer.id.toString()
                            replySeen = answer.id.toString()
                        },
                    )
                }
                Spacer(Modifier.height(KDim.Gap))
            }
            if (sub != null) {
                // Карточка говорит ровно одно: подписка жива и до каких пор. Кто ею
                // пользуется и с какого телефона — подробность, и живёт она за нажатием:
                // имя и прочие подробности показываем только при заходе во вкладку
                // (решение 31.08.2026). На главном экране имя человека не торчит.
                KCard(onClick = { showSubscription = true }) {
                    // имя аккаунта из панели человеку ничего не говорит: показываем состояние
                    KRowItem(
                        label = "Подписка",
                        title = if (sub.active) "Активна" else "Приостановлена",
                        subtitle = sub.note,
                        chevron = true,
                    )
                }
            }
        }

        Spacer(Modifier.height(40.dp))
    }

    if (showExits) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showExits = false },
            sheetState = sheetState,
            containerColor = colors.Surface,
            contentColor = colors.Text,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = KDim.Pad, vertical = 4.dp)) {
                Text(
                    text = "Выбор выхода",
                    fontFamily = Montserrat,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = colors.Text,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                // Режим — переключателем на две стороны, а не пунктом в списке выходов.
                // Прежняя «Автоматически» первой строкой читалась как ещё один выход,
                // хотя это не выход, а способ его выбирать. Требование от 10.08.2026:
                // переключатель на две стороны — ручной режим и авто; в ручном список
                // выходов показан полностью, а авторежим работает сам.
                ModeSwitch(
                    auto = auto.auto,
                    onAuto = { AutoMode.setEnabled(true) },
                    onManual = { AutoMode.setEnabled(false) },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (auto.auto) {
                        "выход подбирается под обстановку"
                    } else {
                        "выход держится тот, что выбран"
                    },
                    fontFamily = Montserrat,
                    fontSize = 12.sp,
                    color = colors.Dim,
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                )
                channels.forEach { ch ->
                    // Что мы про этот выход знаем.
                    //
                    // В списке лежат узлы, а не пути: сервер отдаёт «Нидерланды · прямой»
                    // и «Нидерланды · запасной», тогда как путь у них один — основной
                    // канал, и меряется он целиком. Поэтому комнату узнаём отдельно,
                    // а всё остальное — это основной канал и его состояние.
                    val known = paths.byExit(ch.name)
                        ?: if (isRoomExit(ch.name)) paths[PathId.ROOM] else paths[PathId.MAIN]
                    ExitRow(
                        name = ch.name,
                        delayMs = ch.delayMs,
                        selected = !auto.auto && ch.selected,
                        subtitle = known?.let { PathWords.state(it, now) },
                        onClick = {
                            // Выбор руками выключает автомат: иначе он через минуту
                            // передумает, и человек увидит, что его выбор не держится.
                            // Он же поднимает комнату, если выбрали именно её: ядро
                            // olcRTC больше не висит всегда.
                            // Выбор комнаты применяется в ядре не сразу, а когда она встанет,
                            // иначе трафик уходит в неслушающий socks — см. chooseManually.
                            if (AutoMode.chooseManually(ch.name)) onSelectChannel(ch.name)
                            showExits = false
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // Подробности подписки. Сделаны шторкой, как выбор выхода: на главном экране
    // остаётся только состояние, а имя человека и название телефона человек видит,
    // когда сам зашёл посмотреть. Пустых строк здесь не бывает — чего сервер не
    // прислал, того и нет на экране.
    if (showSubscription) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val sub = subscription
        ModalBottomSheet(
            onDismissRequest = { showSubscription = false },
            sheetState = sheetState,
            containerColor = colors.Surface,
            contentColor = colors.Text,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = KDim.Pad, vertical = 4.dp)) {
                Text(
                    text = "Подписка",
                    fontFamily = Montserrat,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = colors.Text,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                KCard {
                    KRowItem(
                        label = "Состояние",
                        title = if (sub?.active == true) "Активна" else "Приостановлена",
                        subtitle = sub?.note,
                    )
                }
                val person = sub?.personName
                val device = sub?.deviceName
                val devices = sub?.devices.orEmpty()
                // Пришёл список устройств — отдельная строка «Устройство» уходит: она
                // про это же устройство, только вслепую, а в списке оно стоит первым и
                // с подробностями. Списка нет (старый сервер) — всё остаётся как было.
                val showDeviceRow = device != null && devices.isEmpty()
                if (person != null || showDeviceRow) {
                    Spacer(Modifier.height(KDim.Gap))
                    KCard {
                        if (person != null) {
                            KRowItem(label = "Кто пользуется", title = person)
                        }
                        if (person != null && showDeviceRow) KDivider()
                        if (showDeviceRow) {
                            KRowItem(label = "Устройство", title = device)
                        }
                    }
                    if (showDeviceRow) {
                        // Подсказка объясняет ровно эту строку, поэтому и живёт с ней.
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "так это устройство подписано на сервере",
                            fontFamily = Montserrat,
                            fontSize = 12.sp,
                            color = colors.Dim,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                if (devices.isNotEmpty()) {
                    KGroupTitle("Устройства")
                    KCard {
                        devices.forEachIndexed { index, item ->
                            if (index > 0) KDivider()
                            DeviceRow(item, now)
                        }
                    }
                    // Прочерк объясняем только когда он у всех: рядом с честными цифрами
                    // одинокий прочерк читается сам, а лишняя строка под списком — шум.
                    if (devices.all { it.trafficBytes <= 0L }) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "прочерк — расход по устройствам сервер не считал",
                            fontFamily = Montserrat,
                            fontSize = 12.sp,
                            color = colors.Dim,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/**
 * Переключатель режима: автомат или ручной выбор.
 *
 * Две половины одной полосы, а не две карточки: половины показывают, что это один
 * выбор из двух, и что третьего положения нет. Подсвечена та, что действует сейчас.
 */
@Composable
private fun ModeSwitch(auto: Boolean, onAuto: () -> Unit, onManual: () -> Unit) {
    val colors = K
    val shape = RoundedCornerShape(KDim.RadiusM)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.Surface)
            .border(BorderStroke(1.dp, colors.Border), shape)
            .padding(4.dp),
    ) {
        ModeHalf("Автоматически", selected = auto, modifier = Modifier.weight(1f), onClick = onAuto)
        ModeHalf("Вручную", selected = !auto, modifier = Modifier.weight(1f), onClick = onManual)
    }
}

@Composable
private fun ModeHalf(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = K
    val shape = RoundedCornerShape(KDim.RadiusS)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) colors.Accent else Color.Transparent)
            .clickable(enabled = !selected, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontFamily = Montserrat,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 14.sp,
            color = if (selected) colors.Bg else colors.Dim,
        )
    }
}

@Composable
private fun ExitRow(
    name: String,
    delayMs: Int,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    val colors = K
    KCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            badgeOf(name)?.let { KBadge(it, accent = selected) }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = name,
                    fontFamily = Montserrat,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 15.sp,
                    color = colors.Text,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontFamily = Montserrat,
                        fontSize = 12.sp,
                        color = colors.Dim,
                    )
                }
            }
            if (selected) {
                Text(text = "✓", fontSize = 15.sp, color = colors.Accent)
            } else {
                Text(
                    text = if (delayMs > 0) "$delayMs мс" else "—",
                    fontFamily = RobotoMono,
                    fontSize = 13.sp,
                    color = colors.Dim,
                )
            }
        }
    }
}

/**
 * Одно устройство подписки в списке.
 *
 * Собрана вручную, а не из KRowItem: там нет ни значка слева, ни третьей, мелкой
 * строки. Размеры и цвета взяты у KRowItem один в один, чтобы список не выпадал
 * из карточек остального экрана.
 */
@Composable
private fun DeviceRow(device: SubscriptionDevice, now: Long) {
    val colors = K
    val seen = deviceSeenWords(device.lastSeenMillis, now)
    // Версия и «с 31 августа» — одна мелкая строка: порознь они занимают полкарточки,
    // а вместе читаются как одна справка о том, что это за устройство.
    val details = listOfNotNull(device.appVersion, deviceSinceWords(device.firstSeenMillis))
        .joinToString(" · ")
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = deviceIcon(device.kind),
            contentDescription = null,
            tint = if (device.self) colors.Accent else colors.Dim,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = device.name,
                    fontFamily = Montserrat,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = colors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (device.self) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "это устройство",
                        fontFamily = Montserrat,
                        fontSize = 11.sp,
                        color = colors.Accent,
                    )
                }
            }
            if (seen != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = seen,
                    fontFamily = Montserrat,
                    fontSize = 13.sp,
                    // «В сети» — единственное состояние, которое стоит подсветить:
                    // остальные это просто когда, а не хорошо или плохо.
                    color = if (seen == "в сети") colors.Ok else colors.Dim,
                )
            }
            if (details.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = details,
                    fontFamily = Montserrat,
                    fontSize = 11.sp,
                    color = colors.Dim2,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            // Прочерк ровно тот же, что у выхода без замера: и там, и тут он значит
            // «не мерили», а не «ноль».
            text = deviceTrafficWords(device.trafficBytes) ?: "—",
            fontFamily = RobotoMono,
            fontSize = 13.sp,
            color = colors.Dim,
        )
    }
}

/** Значок по виду устройства. Незнакомый вид — общий значок, а не пустое место. */
private fun deviceIcon(kind: String): ImageVector = when (kind) {
    "phone" -> Icons.Outlined.Smartphone
    "laptop" -> Icons.Outlined.Laptop
    "desktop" -> Icons.Outlined.DesktopWindows
    else -> Icons.Outlined.DevicesOther
}

/** Пустое место под будущую карточку подписки, чтобы экран не прыгал. */
@Composable
private fun Placeholder() {
    Box(Modifier.fillMaxWidth().height(0.dp))
}
