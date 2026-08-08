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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import io.nekohasekai.sfa.bg.path.PathId
import io.nekohasekai.sfa.bg.path.PathRegistry
import io.nekohasekai.sfa.bg.path.PathStatus
import io.nekohasekai.sfa.bg.path.PathWords
import io.nekohasekai.sfa.compose.theme.DialState
import io.nekohasekai.sfa.compose.theme.K
import io.nekohasekai.sfa.compose.theme.KBadge
import io.nekohasekai.sfa.compose.theme.KCard
import io.nekohasekai.sfa.compose.theme.KDial
import io.nekohasekai.sfa.compose.theme.KDim
import io.nekohasekai.sfa.compose.theme.KHint
import io.nekohasekai.sfa.compose.theme.KRowItem
import io.nekohasekai.sfa.compose.theme.Montserrat
import io.nekohasekai.sfa.compose.theme.RobotoMono
import io.nekohasekai.sfa.constant.Status
import kotlinx.coroutines.delay

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
    // «40 секунд назад» обязано стареть на глазах: замер, который вечно выглядит свежим,
    // хуже отсутствия замера. Тикаем только пока открыт список выходов — больше эту
    // фразу нигде не показываем, а считать секунды на закрытом экране незачем.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(showExits) {
        while (showExits) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    // Сводку держит общий объект: её же обновляет кнопка в расширенных, и карточка
    // должна показать новое сразу, без перезахода в приложение.
    val subscription by SubscriptionRefresh.info.collectAsState()
    val auto by AutoMode.state.collectAsState()
    // Что известно про каждый путь. Экран больше ничего не достраивает сам: и круг,
    // и список выходов пересказывают этот снимок, а не собственную версию правды.
    val paths by PathRegistry.snapshot.collectAsState()
    // Что человек выбрал руками. Читаем при каждой смене состояния автомата и запуска:
    // ядро может молчать, а показать надо правду.
    val manualExit by remember(auto.auto, running) {
        mutableStateOf(io.nekohasekai.sfa.database.Settings.manualExitName)
    }

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
    val measuring = running && !manualHold && !noNetwork &&
        (paths.anyIs(PathStatus.Probing) || !paths.any { it.usable || it.refused })
    val linkDead = running && !manualHold && !noNetwork &&
        !paths.any { it.usable } && paths.any { it.refused }
    // Путь, который человек выбрал руками. Раньше в ручном режиме круг показывал
    // состояние ядра, а не то, что реально намерили: проба секундами раньше называла
    // тот же путь мёртвым, а круг писал «Подключено» (поймано на стенде 08.08.2026).
    // Автомат отошёл, но мерить пришпиленный путь никто не запрещал — состояние берём
    // из того же реестра, что и для автоматического выбора.
    val manualPath = if (manualHold) paths.byExit(manualExit) else null
    val manualDead = manualHold && manualPath?.refused == true
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
                !hasProfile -> "нажмите, чтобы подключить"
                busy -> "подождите"
                home -> "дома интернет и так открыт, туннель выключен"
                noNetwork -> "включится, когда появится сеть"
                searching -> "подбираю рабочий выход"
                roomRising -> "поднимаю канал через комнату"
                roomDead -> "канал через комнату не поднялся, выберите другой выход"
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
                KCard(onClick = { if (channels.isNotEmpty()) showExits = true }) {
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
                                ?: "Автоматически"
                        },
                        subtitle = when {
                            home -> "дома работает роутер"
                            channels.isEmpty() && !running -> "определится при подключении"
                            auto.auto -> "выбирается сам"
                            // Ручной выбор больше не выключает автомат навсегда: он
                            // держится, пока не сменится сеть. Так и пишем, иначе
                            // «выбран вручную» читается как «автомат сломался».
                            else -> "выбран вами · автомат вернётся при смене сети"
                        },
                        badge = if (home) null else badgeOf(activeOutbound),
                        chevron = channels.isNotEmpty(),
                    )
                }
                Spacer(Modifier.height(KDim.Gap))
            }

            val sub = subscription
            if (sub != null) {
                KCard {
                    // имя аккаунта из панели человеку ничего не говорит: показываем состояние
                    KRowItem(
                        label = "Подписка",
                        title = if (sub.active) "Активна" else "Приостановлена",
                        subtitle = sub.note,
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
            containerColor = colors.Bg2,
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
                // Первым — сам автомат. Он же и есть способ вернуться к нему после того,
                // как человек один раз выбрал выход руками.
                ExitRow(
                    name = "Автоматически",
                    delayMs = 0,
                    selected = auto.auto,
                    subtitle = if (auto.auto) {
                        "подбирает выход под обстановку"
                    } else {
                        "вернётся сам при смене сети"
                    },
                    onClick = {
                        AutoMode.setEnabled(true)
                        showExits = false
                    },
                )
                Spacer(Modifier.height(8.dp))
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
                            AutoMode.chooseManually(ch.name)
                            onSelectChannel(ch.name)
                            showExits = false
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(20.dp))
            }
        }
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

/** Пустое место под будущую карточку подписки, чтобы экран не прыгал. */
@Composable
private fun Placeholder() {
    Box(Modifier.fillMaxWidth().height(0.dp))
}
