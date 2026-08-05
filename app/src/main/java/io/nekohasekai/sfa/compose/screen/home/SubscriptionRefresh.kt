package io.nekohasekai.sfa.compose.screen.home

import android.util.Log
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.bg.OlcRtcParams
import io.nekohasekai.sfa.compose.theme.plural
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.HTTPClient
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
import java.io.File
import java.util.Date

/**
 * Обновление подписки по требованию человека.
 *
 * Раньше подписка обновлялась только сама, по расписанию ([io.nekohasekai.sfa.bg.UpdateProfileWork]),
 * и когда сеть начинала раздавать новые параметры, в телефоне они появлялись
 * непонятно когда. Здесь то же самое, но по нажатию и с честным ответом.
 *
 * Порядок важен. Сначала забираем конфиг (список выходов), затем сводку /info
 * (состояние подписки, исключения, параметры комнаты), кладём параметры в настройки
 * и только после этого просим ядро перечитать конфиг: к моменту перезагрузки
 * комната уже описана новыми значениями, а не прошлыми.
 *
 * Состояние живёт в объекте, а не в экране: обновление не должно обрываться
 * из-за того, что человек ушёл с экрана, а результат нужен сразу двум экранам.
 */
object SubscriptionRefresh {
    private const val TAG = "KelevraSubscription"

    /**
     * Служебные направления и группы: в списке выходов человек их не видит,
     * значит и в счёт «сколько выходов приехало» они не идут.
     */
    private val SERVICE_TYPES = setOf("direct", "block", "dns", "selector", "urltest")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Два нажатия подряд не должны качать подписку дважды. */
    private val lock = Mutex()

    /** Что происходит прямо сейчас: показывается кнопкой и строкой под ней. */
    sealed interface State {
        /** Ничего не делали с момента открытия. */
        data object Idle : State

        data object Running : State

        /** Получилось. [note] говорит, действуют ли изменения прямо сейчас. */
        data class Ok(val at: Date, val note: String) : State

        /** Не получилось. [reason] уже человеческий, наружу идёт как есть. */
        data class Failed(val reason: String) : State
    }

    /** Что приехало прошлый раз — словами, без технических полей. */
    data class Summary(
        /** Состояние подписки; null — сводка не пришла, врать про неё не будем. */
        val active: Boolean?,
        /** Сколько выходов в конфиге. */
        val exits: Int,
        /** Раздаёт ли сеть комнату. */
        val room: Boolean,
    ) {
        /** Одной строкой: «Активна · 4 выхода · комната есть». */
        val words: String
            get() = buildList {
                when (active) {
                    true -> add("Активна")
                    false -> add("Приостановлена")
                    null -> Unit
                }
                if (exits > 0) add("$exits ${plural(exits, "выход", "выхода", "выходов")}")
                add(if (room) "комната есть" else "комнаты нет")
            }.joinToString(" · ")
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _summary = MutableStateFlow<Summary?>(null)
    val summary: StateFlow<Summary?> = _summary.asStateFlow()

    /** Когда подписка обновлялась в последний раз — хоть сама, хоть руками. */
    private val _lastUpdated = MutableStateFlow<Date?>(null)
    val lastUpdated: StateFlow<Date?> = _lastUpdated.asStateFlow()

    /** Сводка с сервера: её же показывает карточка на главном экране. */
    private val _info = MutableStateFlow<SubscriptionInfo?>(null)
    val info: StateFlow<SubscriptionInfo?> = _info.asStateFlow()

    /** Кнопка нажата. Ход обновления виден через [state]. */
    fun request() {
        if (_state.value == State.Running) return
        _state.value = State.Running
        scope.launch { refresh() }
    }

    /**
     * Читает то, что уже лежит в телефоне: время прошлого обновления и число выходов.
     * Сеть не трогает — нужно, чтобы экран сразу показал правду, а не пустоту.
     */
    suspend fun loadLocal() = withContext(Dispatchers.IO) {
        val profile = selectedRemote() ?: return@withContext
        _lastUpdated.value = profile.typed.lastUpdated.takeIf { it.time > 0 }
        if (_summary.value == null) {
            _summary.value = Summary(
                active = _info.value?.active,
                exits = countExits(runCatching { File(profile.typed.path).readText() }.getOrNull()),
                room = OlcRtcParams.hasRoom,
            )
        }
    }

    /**
     * Тянет сводку /info и раскладывает её по настройкам: исключения приложений и
     * параметры комнаты. То же самое делает главный экран при открытии — здесь оно
     * вынесено, чтобы обновление и открытие экрана не расходились.
     */
    suspend fun loadInfo(): SubscriptionInfo? {
        val loaded = loadSubscription()
        if (loaded != null) {
            loaded.bypassPackages.let { applyBypassPackages(it) }
            // Сеть не ответила — старые параметры комнаты остаются; ответила без блока
            // olcrtc — стираем, чтобы не держать протухшую комнату.
            OlcRtcParams.applyServer(loaded.olcrtc)
            _info.value = loaded
        }
        return loaded
    }

    private suspend fun refresh() {
        // Уже качаем — второе нажатие ничего не меняет, состояние и так «идёт обновление».
        if (!lock.tryLock()) return
        try {
            _state.value = State.Running
            val profile = selectedRemote()
            if (profile == null) {
                Log.i(TAG, "обновление подписки: подписка не подключена")
                _state.value = State.Failed("Подписка не подключена.")
                return
            }

            val content = try {
                Log.i(TAG, "обновление подписки: запрашиваю настройки у сервера")
                val text = HTTPClient().use { it.getString(profile.typed.remoteURL) }
                Libbox.checkConfig(text)
                text
            } catch (e: Exception) {
                Log.w(TAG, "обновление подписки: настройки не получены", e)
                _state.value = State.Failed(humanError(e))
                return
            }

            // Сводка отдельным запросом: конфиг про состояние подписки и комнату молчит.
            Log.i(TAG, "обновление подписки: запрашиваю сводку")
            val loaded = runCatching { loadInfo() }.getOrNull()
            Log.i(
                TAG,
                "обновление подписки: сводка ${if (loaded == null) "не пришла" else "получена"}, " +
                    "комната ${if (OlcRtcParams.serverOffers) "раздаётся сетью" else "сетью не раздаётся"}",
            )

            val file = File(profile.typed.path)
            val changed = !file.exists() || file.readText() != content
            if (changed) file.writeText(content)
            profile.typed.lastUpdated = Date()
            ProfileManager.update(profile)
            _lastUpdated.value = profile.typed.lastUpdated

            val exits = countExits(content)
            _summary.value = Summary(
                active = loaded?.active,
                exits = exits,
                room = OlcRtcParams.hasRoom,
            )
            Log.i(TAG, "обновление подписки: выходов $exits, настройки ${if (changed) "изменились" else "прежние"}")

            // Ядро перечитывает конфиг само; выключенное ядро об этом просто не узнает.
            var reloaded = false
            if (changed && profile.id == Settings.selectedProfile) {
                runCatching { Libbox.newStandaloneCommandClient().serviceReload() }
                    .onSuccess {
                        reloaded = true
                        Log.i(TAG, "обновление подписки: ядро перечитало настройки")
                    }
                    .onFailure { Log.i(TAG, "обновление подписки: ядро не запущено, применится при включении") }
            }

            // Говорим ровно то, что произошло: обещать «уже работает» выключенному ядру нельзя.
            val note = when {
                !changed -> "Готово. Настройки не менялись."
                reloaded -> "Готово. Изменения уже действуют."
                else -> "Готово. Изменения применятся при подключении."
            }
            _state.value = State.Ok(profile.typed.lastUpdated, note)
        } catch (e: Exception) {
            Log.w(TAG, "обновление подписки: сорвалось", e)
            _state.value = State.Failed(humanError(e))
        } finally {
            lock.unlock()
        }
    }

    private suspend fun selectedRemote(): Profile? {
        val id = Settings.selectedProfile
        if (id == -1L) return null
        val profile = ProfileManager.get(id) ?: return null
        return profile.takeIf { it.typed.type == TypedProfile.Type.Remote }
    }

    /** Сколько в конфиге настоящих выходов. */
    private fun countExits(content: String?): Int {
        if (content.isNullOrBlank()) return 0
        return runCatching {
            val outbounds = JSONObject(content).optJSONArray("outbounds") ?: return 0
            (0 until outbounds.length()).count { i ->
                outbounds.optJSONObject(i)?.optString("type") !in SERVICE_TYPES
            }
        }.getOrDefault(0)
    }

    /**
     * Причина словами. Технические коды и стектрейсы остаются в журнале.
     *
     * Сеть тут ходит через ядро на Go, поэтому и текст ошибки приходит его —
     * `lookup … no such host`, `dial tcp … connection refused`. Java-формулировки
     * («unable to resolve host») ловим заодно: часть запросов идёт обычным клиентом.
     */
    private fun humanError(e: Throwable): String {
        val text = ((e.message ?: "") + " " + (e.cause?.message ?: "")).lowercase()
        return when {
            "no such host" in text || "unable to resolve host" in text ||
                "unable to resolve" in text || "no address associated" in text ||
                "network is unreachable" in text || "network is down" in text ||
                "connection refused" in text || "econnrefused" in text ||
                "failed to connect" in text || "no route to host" in text ||
                "unknownhost" in text || "server misbehaving" in text ->
                "Нет связи. Проверьте интернет и повторите."

            "timeout" in text || "timed out" in text || "etimedout" in text ||
                "deadline exceeded" in text || "connection reset" in text ||
                "eof" == text.trim() || "unexpected eof" in text ->
                "Сервер не ответил. Попробуйте позже."

            "404" in text || "not found" in text ->
                "Код не найден. Проверьте подписку."

            "403" in text || "401" in text || "forbidden" in text || "unauthorized" in text ->
                "Сервер отказал в доступе. Проверьте подписку."

            "500" in text || "502" in text || "503" in text || "504" in text ||
                "bad gateway" in text || "unavailable" in text ->
                "Сервер не ответил. Попробуйте позже."

            else -> "Обновить не удалось. Попробуйте позже."
        }
    }
}

/**
 * Время словами: «Обновлено 5 минут назад». Точная дата человеку тут не нужна —
 * ему нужно понять, свежее это или лежит со вчера.
 */
fun humanAgo(date: Date?): String {
    if (date == null || date.time <= 0) return "Ещё не обновлялась"
    val seconds = (System.currentTimeMillis() - date.time) / 1000L
    val ago = when {
        seconds < 60 -> "только что"
        seconds < 3600 -> {
            val minutes = (seconds / 60).toInt()
            "$minutes ${plural(minutes, "минуту", "минуты", "минут")} назад"
        }

        seconds < 86400 -> {
            val hours = (seconds / 3600).toInt()
            "$hours ${plural(hours, "час", "часа", "часов")} назад"
        }

        else -> {
            val days = (seconds / 86400).toInt()
            "$days ${plural(days, "день", "дня", "дней")} назад"
        }
    }
    return "Обновлено $ago"
}
