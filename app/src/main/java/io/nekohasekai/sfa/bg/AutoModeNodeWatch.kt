package io.nekohasekai.sfa.bg

/**
 * Когда текущий узел очереди можно признать мёртвым и когда пора вернуться на первый.
 *
 * Решение Вовы 17.10.2026 (п. 6): основной умер (по живым соединениям) → сразу на следующий;
 * ожил → возврат после паузы; «тормозит» — не повод; ручной выбор главнее.
 *
 * Повод для отдельного сторожа — журнал телефона 17.10.2026. Вайфай дома мигнул на пять секунд
 * (22:44:52 → 22:44:57), телефон на эти секунды ушёл на мобильную, и автомат увёл его с
 * «Нидерландов» на «Финляндию»: мобильная ещё не была проверена системой, имя узла не
 * резолвилось (DnsResolver ETIMEDOUT), проба шла в порт ядра, которое пересобиралось параллельно.
 * Один такой провал считался смертью узла, и запасной узел потом пережил весь домашний вечер.
 * Отсюда три правила, и все три здесь:
 *
 *  1. **Окно смены сети не считается.** Пока сеть не проверена системой и не прошло
 *     [settleMillis] с последнего события о ней, провал узла — это провал сети, а не узла.
 *     Сеть без признака проверенности (белый список его обычно снимает) считается устоявшейся
 *     через [settleUnvalidatedMillis].
 *  2. **Смерть подтверждается.** Либо вторым провалом не раньше чем через [confirmMillis]
 *     после первого (без удачи между ними), либо живыми отказами соединений через этот узел
 *     ([CoreLog.otkazUzla]) — если сами отказы случились уже на устоявшейся сети, а не в окне.
 *  3. **Возврат после паузы.** Первый узел должен отвечать [returnAfterMillis] подряд.
 *
 * Время — `elapsedRealtime`, передаётся снаружи. Android внутри нет: поведение проверяется
 * тестом ([AutoModeNodeWatchTest]), а не пересказом.
 */
internal class AutoModeNodeWatch(
    private val settleMillis: Long = SETTLE_MILLIS,
    private val settleUnvalidatedMillis: Long = SETTLE_UNVALIDATED_MILLIS,
    private val confirmMillis: Long = CONFIRM_MILLIS,
    private val staleMillis: Long = STALE_MILLIS,
    private val liveFreshMillis: Long = LIVE_FRESH_MILLIS,
    private val returnAfterMillis: Long = RETURN_AFTER_MILLIS,
) {
    /** Чем кончился провал текущего узла. */
    enum class Death {
        /** Сеть только что сменилась — провал не считается вовсе, на запасной не уходим. */
        Window,

        /** Первый провал на устоявшейся сети: ждём подтверждения. */
        FirstMiss,

        /** Подтверждено вторым провалом через паузу. */
        Confirmed,

        /** Подтверждено живыми отказами соединений через этот узел. */
        ConfirmedByLive,
        ;

        val confirmed: Boolean get() = this == Confirmed || this == ConfirmedByLive
    }

    /** Что делать с первым узлом, пока стоим на запасном. */
    enum class Return {
        /** Первый узел только что ожил — начинаем отсчёт паузы. */
        Started,

        /** Ожил, но пауза ещё не прошла. */
        Waiting,

        /** Отвечает [returnAfterMillis] подряд — возвращаемся. */
        Due,
    }

    /** Последнее событие о сети (смена, проверка, новые резолверы); 0 — не было ни одного. */
    private var netChangedAt = 0L

    /** Первый провал текущего узла на устоявшейся сети; 0 — провала нет. */
    private var firstDeadAt = 0L

    /** С какого момента первый узел снова отвечает, пока стоим на запасном; 0 — не отвечает. */
    private var primaryOkSince = 0L

    /** Последние живые отказы через узел: когда и через какой. */
    private var liveFailAt = 0L
    private var liveFailTag: String? = null

    /** Всё забыть: новый подъём автомата. Окно смены сети при этом начинается заново. */
    @Synchronized
    fun reset(now: Long) {
        netChangedAt = now
        firstDeadAt = 0L
        primaryOkSince = 0L
        liveFailAt = 0L
        liveFailTag = null
    }

    /**
     * Очередь вернулась на первый узел (дома, выключение туннеля, ручной выбор отпущен):
     * счёт провалов и пауза возврата про прежний узел больше ничего не говорят. Окно сети не трогаем.
     */
    @Synchronized
    fun queueReset() {
        firstDeadAt = 0L
        primaryOkSince = 0L
        liveFailAt = 0L
        liveFailTag = null
    }

    /**
     * Система сообщила о сети что-то настоящее: другая сеть, сменилась проверенность или резолверы.
     * Провалы и пауза возврата, набранные на прежней сети, про новую не говорят ничего.
     */
    @Synchronized
    fun networkChanged(now: Long) {
        netChangedAt = now
        firstDeadAt = 0L
        primaryOkSince = 0L
    }

    /** Устоялась ли сеть настолько, чтобы её провалы считались провалами узла. */
    @Synchronized
    fun settled(now: Long, validated: Boolean): Boolean = settledLocked(now, validated)

    private fun settledLocked(now: Long, validated: Boolean): Boolean {
        val since = now - netChangedAt
        return if (validated) since >= settleMillis else since >= settleUnvalidatedMillis
    }

    /** Соединения через узел [tag] массово отказывают ([CoreLog.otkazUzla]). */
    @Synchronized
    fun liveFailures(tag: String, now: Long) {
        liveFailAt = now
        liveFailTag = tag
    }

    /** Текущий узел ответил: счёт провалов сначала. */
    @Synchronized
    fun nodeAlive() {
        firstDeadAt = 0L
    }

    /** Текущий узел [tag] не ответил. Решает, считается ли это смертью. */
    @Synchronized
    fun nodeDead(tag: String, now: Long, validated: Boolean): Death {
        if (!settledLocked(now, validated)) {
            // Провал в окне смены сети в счёт не идёт и следующему заходу не передаётся.
            firstDeadAt = 0L
            return Death.Window
        }
        // Живые отказы засчитываем, только если они свежие, про этот узел и случились уже на
        // устоявшейся сети: на смене сети рвутся все соединения разом, и узел тут ни при чём.
        val liveFresh = liveFailTag == tag && liveFailAt != 0L &&
            now - liveFailAt in 0..liveFreshMillis &&
            liveFailAt - netChangedAt >= settleMillis
        if (liveFresh) return Death.ConfirmedByLive
        if (firstDeadAt == 0L || now - firstDeadAt > staleMillis) {
            firstDeadAt = now
            return Death.FirstMiss
        }
        return if (now - firstDeadAt >= confirmMillis) Death.Confirmed else Death.FirstMiss
    }

    /** Ушли на другой узел: про прежний считать больше нечего. */
    @Synchronized
    fun switched() {
        firstDeadAt = 0L
        primaryOkSince = 0L
        liveFailAt = 0L
        liveFailTag = null
    }

    /** Стоим на запасном, первый узел ответил. */
    @Synchronized
    fun primaryAlive(now: Long): Return {
        if (primaryOkSince == 0L) {
            primaryOkSince = now
            return Return.Started
        }
        return if (now - primaryOkSince >= returnAfterMillis) Return.Due else Return.Waiting
    }

    /** Стоим на запасном, первый узел не ответил: пауза возврата сначала. */
    @Synchronized
    fun primaryDead() {
        primaryOkSince = 0L
    }

    /** Сколько первый узел уже отвечает подряд; 0 — не отвечает. */
    @Synchronized
    fun primaryOkFor(now: Long): Long = if (primaryOkSince == 0L) 0L else now - primaryOkSince

    companion object {
        /** Сколько ждать после последнего события о сети, прежде чем верить провалам узла. */
        const val SETTLE_MILLIS = 10_000L

        /** То же для сети, которую система так и не признала проверенной. */
        const val SETTLE_UNVALIDATED_MILLIS = 30_000L

        /** Второй провал подтверждает смерть не раньше чем через столько после первого. */
        const val CONFIRM_MILLIS = 8_000L

        /** Первый провал давнее этого — уже не «первый из двух», а отдельный случай. */
        const val STALE_MILLIS = 2 * 60_000L

        /** Сколько живые отказы считаются свежими. */
        const val LIVE_FRESH_MILLIS = 60_000L

        /** Вернуться на первый узел, только если он жив столько подряд. */
        const val RETURN_AFTER_MILLIS = 4 * 60_000L
    }
}
