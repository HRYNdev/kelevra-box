package io.nekohasekai.sfa.bg

import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/**
 * Обстановка вокруг: не «жив ли эндпоинт», а **как именно** сеть нас ограничивает.
 *
 * Все, кто меряет сеть до нас (sing-box, Xray, Hiddify), отвечают на один вопрос:
 * поднялось соединение или нет. Этого мало. «Не поднялось» под белым списком и
 * «не поднялось» под DPI — две разные беды, и лечатся они разным путём. Поэтому
 * здесь описана не доступность, а **режим**.
 *
 * Этот файл целиком без Android: только модель и решение. Вся возня с сокетами —
 * в [NetworkModeDetector]. Разделение не косметическое: логика вердикта проверяется
 * юнит-тестами на JVM, а не пересказывается в комментарии.
 */
enum class NetworkMode {

    /** Наружу ходит всё: TCP доходит куда угодно, TLS встаёт, трафик льётся. */
    Normal,

    /**
     * Белый список: наружу пускают только разрешённые адреса. Два вида:
     *  - drop-all — остальное умирает молча, SYN уходит, ответа нет вообще;
     *  - TCP пропускают куда угодно, а TLS ко всему вне списка рвут.
     */
    Whitelist,

    /**
     * Чёрный список с DPI: TCP жив и доходит куда угодно, ломается именно TLS —
     * либо разрыв, либо подвисание после первых десятков килобайт.
     */
    DpiBlacklist,

    /** Физической сети под нами нет — мерить нечего. */
    NoNetwork,

    /**
     * Данных не хватает на вердикт.
     *
     * Это полноценный ответ, а не отговорка. Догадка «наверное белый список»
     * дороже честного «не знаю»: на вердикте этого органа дальше строится выбор
     * пути, и увести канал в комнату по выдумке хуже, чем не увести вообще.
     */
    Unknown,
}

/**
 * Чем кончилась одна проба. Важно различать не «получилось/нет», а **как** не получилось:
 * тишина и отказ приходят из разных миров, и именно этим белый список отличается
 * от закрытого порта.
 */
enum class ProbeOutcome {

    /** Проба прошла целиком. */
    Ok,

    /**
     * Собеседник ответил, но проба не доиграла до конца по причине, к сети отношения
     * не имеющей (типично — сертификат не из нашего хранилища доверия).
     *
     * Для нас это **положительный** признак, и вот почему. Чтобы дело дошло до разбора
     * сертификата, по сети уже должны были пройти ClientHello, ServerHello и сам
     * Certificate. DPI, который ломает TLS, рубит соединение раньше — на ClientHello.
     * Значит «не понравился сертификат» доказывает ровно то, что нам нужно: TLS
     * прошёл через сеть целым. Проверять чужой сертификат мы и не собирались —
     * мы измеряем сеть, а не аутентифицируем сервер.
     */
    Answered,

    /**
     * Тишина: пакет ушёл, не вернулось ничего, вышел таймаут.
     * Подпись drop-all — фильтр не отвечает, он молчит.
     */
    Silence,

    /** Явный отказ (RST): на том конце ответили «нет». Это НЕ подпись белого списка. */
    Refused,

    /** Сеть/хост недостижимы (ICMP unreachable, нет маршрута). Тоже ответ, тоже не тишина. */
    Unreachable,

    /** Соединение стояло и было разорвано на ходу. */
    Reset,

    /** Соединение стоит, но данные встали: не разрыв, а подвисание. */
    Stalled,

    /** Проба сорвалась по причине, которая ничего не говорит об обстановке. */
    Failed,

    /** Пробу не запускали — до неё не дошло из-за раннего выхода. */
    Skipped,
}

/**
 * Факты, на которых стоит вердикт. Отдаются наружу целиком: тот, кто получит режим,
 * должен иметь возможность не поверить и посмотреть сам.
 */
data class NetworkSignals(

    /** Нашлась ли физическая (не наш туннель) сеть, через которую вообще можно мерить. */
    val physicalNetwork: Boolean = false,

    /** TCP к заведомо неразрешённому адресу. Одна эта проба отделяет белый список. */
    val tcpUnlisted: ProbeOutcome = ProbeOutcome.Skipped,

    /** TCP к заведомо разрешённому адресу — контроль: отделяет белый список от мёртвой сети. */
    val tcpAllowed: ProbeOutcome = ProbeOutcome.Skipped,

    /** TLS-рукопожатие к канарейке — имени, по которому DPI и работает. */
    val tlsCanary: ProbeOutcome = ProbeOutcome.Skipped,

    /** Короткая передача по этому же соединению — ловит подвисание после первых килобайт. */
    val bulkCanary: ProbeOutcome = ProbeOutcome.Skipped,

    /** Сколько байт успели прочитать в передаче. Признак для человека, в решении НЕ участвует. */
    val bulkBytes: Int = 0,

    /**
     * TLS к неразрешённому, но нигде не заблокированному адресу (публичный резолвер).
     *
     * Снимается только когда рукопожатие с канарейкой уже сломалось. Нужен, чтобы отличить
     * DPI от белого списка, который пускает TCP куда угодно, а рвёт TLS ко всему вне списка:
     * DPI рвёт TLS к запрещённому имени, а к публичному резолверу пропускает.
     */
    val tlsUnlisted: ProbeOutcome = ProbeOutcome.Skipped,

    /** TLS к разрешённому адресу — контроль к [tlsUnlisted]: без него сломан любой TLS. */
    val tlsAllowed: ProbeOutcome = ProbeOutcome.Skipped,

    /**
     * Сколько разрешённых адресов перебрал контроль [tlsAllowed]. Провал контроля считается
     * уверенным только на [NetworkModeDecision.CONTROL_CONFIDENT_TRIES] адресах: одна осечка
     * к одному адресу (потеря пакета, медленный сервер) не должна переворачивать вердикт.
     */
    val tlsAllowedTries: Int = 0,

    /**
     * Передача больше окна «16–20 КБ» с неразрешённого незаблокированного адреса (резолвер).
     * Снимается, только когда передача с канарейкой уже встала после рукопожатия: так
     * выглядит и DPI, и белый список, который пропускает первые килобайты и замолкает.
     */
    val bulkUnlisted: ProbeOutcome = ProbeOutcome.Skipped,
    val bulkUnlistedBytes: Int = 0,

    /** Та же передача с разрешённого адреса — контроль к [bulkUnlisted]. */
    val bulkAllowed: ProbeOutcome = ProbeOutcome.Skipped,
    val bulkAllowedBytes: Int = 0,

    /**
     * Отвечает ли внешний резолвер.
     *
     * В решении НЕ участвует и участвовать не должен: данные по операторам расходятся —
     * часть не трогает DNS вовсе, часть пускает только свой резолвер. Признак отдаётся
     * отдельно, чтобы его было видно, а не чтобы на нём что-то строить.
     */
    val externalDns: ProbeOutcome = ProbeOutcome.Skipped,
)

/** Вердикт вместе с тем, на чём он стоит, и когда снят. */
data class NetworkModeReport(
    val mode: NetworkMode,
    val signals: NetworkSignals,
    /** Стенные часы момента замера, мс — чтобы было видно, насколько вердикт протух. */
    val atMillis: Long,
    /** Сколько замер занял, мс. */
    val tookMillis: Long,
    /** На чём стоит вердикт, человеческими словами. */
    val note: String,
)

/**
 * Решение по признакам. Чистая функция: те же признаки — тот же режим, без сети,
 * без времени, без настроек.
 *
 * Порядок разбора повторяет порядок проб — от дешёвого к дорогому:
 *  1. нет физической сети → мерить нечего;
 *  2. тишина к неразрешённому + живой контроль → белый список (дальше не идём);
 *  3. неразрешённый адрес достижим → смотрим TLS и передачу; TLS к канарейке сломан —
 *     два контрольных рукопожатия отделяют белый список по TLS от DPI;
 *  4. всё остальное → честное «не знаю».
 */
object NetworkModeDecision {

    fun decide(signals: NetworkSignals): NetworkMode {
        if (!signals.physicalNetwork) return NetworkMode.NoNetwork
        return when (signals.tcpUnlisted) {
            ProbeOutcome.Silence -> silence(signals)
            ProbeOutcome.Ok -> reachable(signals)
            // Отказ и недостижимость — это ОТВЕТ, а под белым списком ответа не бывает.
            // Значит точно не он. Но и TLS проверять негде: порт нам не отдали.
            // Честный итог — «не знаю», а не «норма»: мишень пробы сломана, не сеть.
            ProbeOutcome.Refused, ProbeOutcome.Unreachable -> NetworkMode.Unknown
            else -> NetworkMode.Unknown
        }
    }

    /**
     * К неразрешённому адресу тишина. Сам по себе этот факт ничего не стоит: так же
     * молчит выдернутый кабель. Решает контроль — разрешённый адрес.
     */
    private fun silence(signals: NetworkSignals): NetworkMode = when (signals.tcpAllowed) {
        // Неразрешённый молчит, разрешённый отвечает — это и есть drop-all.
        ProbeOutcome.Ok -> NetworkMode.Whitelist
        // Молчит и контроль: сеть числится, но наружу не ходит ничего. Под это
        // подходит и белый список, куда наш «разрешённый» не попал, и мёртвый вайфай.
        // Различить нечем — значит не различаем.
        else -> NetworkMode.Unknown
    }

    /**
     * TCP доходит куда угодно — белого списка нет. Остаётся отличить норму от DPI,
     * а ломается у DPI именно TLS.
     */
    private fun reachable(signals: NetworkSignals): NetworkMode {
        when (signals.tlsCanary) {
            // TCP жив, а рукопожатие рвут или подвешивают. Обычно это DPI, но так же
            // выглядит белый список, который пропускает TCP куда угодно и рвёт TLS ко всему
            // вне списка (мобильная сеть: соединение до VPS встаёт, рукопожатие
            // рвётся). Различает их TLS к незаблокированному неразрешённому адресу при живом
            // TLS к разрешённому — см. [tlsWhitelist].
            ProbeOutcome.Reset, ProbeOutcome.Stalled -> return when {
                tlsWhitelist(signals) -> NetworkMode.Whitelist
                // TLS рвут и незаблокированному резолверу, а контроль не состоялся или
                // сорвался на одном адресе. Это может быть и белый список с осечкой контроля
                // (стенд 13.09: одна потеря SYN к ya.ru — и вердикт DpiBlacklist), и сеть, где
                // сломан любой TLS. DPI здесь объявлять нельзя: он и отличается тем, что
                // рвёт не всё. Честный итог — «не знаю», следующий замер перемеряет.
                brokenTls(signals.tlsUnlisted) && !controlSurelyBroken(signals) -> NetworkMode.Unknown
                else -> NetworkMode.DpiBlacklist
            }

            // Непонятная ошибка на канарейке сама ничего не доказывает, но пара контрольных
            // рукопожатий может доказать белый список и без неё.
            ProbeOutcome.Failed -> return if (tlsWhitelist(signals)) NetworkMode.Whitelist else NetworkMode.Unknown

            ProbeOutcome.Ok -> Unit

            // Собеседник ответил сам — предупреждением TLS или неподходящим сертификатом.
            // И то и другое значит, что ClientHello дошёл, а ответ вернулся целым:
            // сеть рукопожатие не трогала. Сессии при этом нет, поэтому передачу не
            // проверить, и подвисание «после десятков килобайт» остаётся непроверенным.
            // Признак [NetworkSignals.bulkCanary] покажет Skipped — тот, кто читает
            // вердикт, увидит, чего мы не мерили.
            ProbeOutcome.Answered -> return NetworkMode.Normal

            else -> return NetworkMode.Unknown
        }
        return when (signals.bulkCanary) {
            ProbeOutcome.Ok -> NetworkMode.Normal
            // Рукопожатие прошло, а поток встал или его оборвали. Обычно DPI, сработавший
            // позже (свидетельство про ~16 КБ), но так же выглядит белый список, который
            // к неразрешённому пропускает первые 16–20 КБ и замолкает: рукопожатие в это
            // окно помещается, и признак по TLS молчит (стенд sni/freeze 13.09:
            // «рукопожатие Ok, передача Stalled, 8668 Б» → DpiBlacklist). Различает их та
            // же пара, что и для TLS, но по передаче больше окна — см. [bulkWhitelist].
            ProbeOutcome.Reset, ProbeOutcome.Stalled -> when {
                bulkWhitelist(signals) -> NetworkMode.Whitelist
                // Резолвер тоже встал, а контроль не доказал, что большая передача вообще
                // возможна: это может быть и сеть, где встаёт всё крупное. Не DPI.
                brokenTls(signals.bulkUnlisted) -> NetworkMode.Unknown
                else -> NetworkMode.DpiBlacklist
            }
            else -> NetworkMode.Unknown
        }
    }

    /**
     * Столько байт должна дать передача, чтобы доказать, что окно «16–20 КБ» пройдено:
     * с запасом над верхней границей, рукопожатие входит в счёт у оператора.
     */
    const val FREEZE_PROOF_BYTES = 24 * 1024

    /**
     * Белый список по передаче: с неразрешённого незаблокированного адреса поток встал или
     * оборван, не набрав [FREEZE_PROOF_BYTES], а с разрешённого набрал их целиком.
     * Обе половины обязательны, как в [tlsWhitelist]; «короткий, но дочитанный» ответ
     * контроля не доказывает ничего — окно он не переходит.
     */
    private fun bulkWhitelist(signals: NetworkSignals): Boolean =
        brokenTls(signals.bulkUnlisted) && signals.bulkUnlistedBytes < FREEZE_PROOF_BYTES &&
            signals.bulkAllowed == ProbeOutcome.Ok && signals.bulkAllowedBytes >= FREEZE_PROOF_BYTES

    /**
     * Белый список по TLS: к неразрешённому, но нигде не заблокированному адресу
     * рукопожатие рвут или подвешивают, а к разрешённому оно проходит.
     *
     * Обе половины обязательны. Без контроля так же выглядит сеть, где сломан любой TLS;
     * без неразрешённого резолвера — обычный DPI, который рвёт только запрещённые имена.
     */
    /** Столько разрешённых адресов должен перебрать контроль, чтобы его провал считался уверенным. */
    const val CONTROL_CONFIDENT_TRIES = 2

    private fun brokenTls(outcome: ProbeOutcome): Boolean =
        outcome == ProbeOutcome.Reset || outcome == ProbeOutcome.Stalled

    /**
     * Контроль провален уверенно: рукопожатие к разрешённому сломалось на уровне TLS
     * (не TCP, не непонятной ошибкой) на нескольких адресах подряд.
     */
    private fun controlSurelyBroken(signals: NetworkSignals): Boolean =
        brokenTls(signals.tlsAllowed) && signals.tlsAllowedTries >= CONTROL_CONFIDENT_TRIES

    /**
     * Итог контроля по попыткам к разным разрешённым адресам, по порядку.
     *
     * Первая прошедшая (`Ok`/`Answered`) решает сразу. Иначе провал TLS (`Reset`/`Stalled`)
     * засчитывается, только если так кончились ВСЕ попытки: смесь с тишиной или непонятной
     * ошибкой — это не «TLS сломан у разрешённого», а несостоявшийся контроль (`Failed`).
     */
    fun controlOutcome(attempts: List<ProbeOutcome>): ProbeOutcome {
        if (attempts.isEmpty()) return ProbeOutcome.Skipped
        attempts.firstOrNull { it == ProbeOutcome.Ok || it == ProbeOutcome.Answered }?.let { return it }
        return if (attempts.all(::brokenTls)) attempts.last() else ProbeOutcome.Failed
    }

    private fun tlsWhitelist(signals: NetworkSignals): Boolean =
        (signals.tlsUnlisted == ProbeOutcome.Reset || signals.tlsUnlisted == ProbeOutcome.Stalled) &&
            (signals.tlsAllowed == ProbeOutcome.Ok || signals.tlsAllowed == ProbeOutcome.Answered)

    /**
     * Переживает ли вердикт то, что узел принял TCP-соединение.
     *
     * Белый список, найденный по тишине, принятым соединением опровергается: под ним SYN
     * пропадает. Белый список, найденный по TLS, — нет: там TCP принимают куда угодно,
     * и открытый порт узла ровно ничего не говорит. Если стирать такой вердикт каждым
     * открытым портом, он не доживёт и до следующей приглядки.
     */
    fun survivesOpenPort(report: NetworkModeReport?): Boolean =
        report != null && report.mode == NetworkMode.Whitelist && report.signals.tcpUnlisted == ProbeOutcome.Ok

    /** Короткое «почему так» для отчёта и лога. */
    fun explain(mode: NetworkMode, signals: NetworkSignals): String = when (mode) {
        NetworkMode.NoNetwork -> "физической сети под нами нет"
        NetworkMode.Whitelist -> if (signals.tcpUnlisted == ProbeOutcome.Ok && signals.bulkUnlisted != ProbeOutcome.Skipped) {
            "TCP и рукопожатие к неразрешённому проходят, но поток встаёт на ${signals.bulkUnlistedBytes} Б " +
                "(${signals.bulkUnlisted}), с разрешённого идёт (${signals.bulkAllowedBytes} Б)"
        } else if (signals.tcpUnlisted == ProbeOutcome.Ok) {
            "TCP к неразрешённому проходит, но TLS к нему рвётся (${signals.tlsUnlisted}), " +
                "к разрешённому TLS встаёт (${signals.tlsAllowed})"
        } else {
            "к неразрешённому адресу тишина, разрешённый отвечает"
        }
        NetworkMode.DpiBlacklist ->
            "TCP доходит, но TLS ломается (рукопожатие ${signals.tlsCanary}, " +
                "передача ${signals.bulkCanary}, ${signals.bulkBytes} Б)"
        NetworkMode.Normal -> if (signals.bulkCanary == ProbeOutcome.Ok) {
            "TCP доходит, TLS встал, передано ${signals.bulkBytes} Б без обрыва"
        } else {
            "TCP доходит, TLS прошёл по сети целым (${signals.tlsCanary}); " +
                "передачу не мерили"
        }
        NetworkMode.Unknown ->
            "признаков не хватает: неразрешённый ${signals.tcpUnlisted}, " +
                "контроль ${signals.tcpAllowed}, TLS ${signals.tlsCanary}, " +
                "передача ${signals.bulkCanary}"
    }
}

/**
 * Разбор ошибок сокета в признаки. Вынесен сюда и сделан чистым не для красоты:
 * именно тут живёт различие «тишина против отказа», на котором держится весь
 * белый список, — и проверять его надо тестом, а не в поле.
 */
object ProbeFailure {

    /** Чем кончилась попытка установить TCP. */
    fun onConnect(error: Throwable): ProbeOutcome = when {
        // Вышел таймаут и не пришло ничего — ровно подпись drop-all.
        error is SocketTimeoutException -> ProbeOutcome.Silence
        error is NoRouteToHostException -> ProbeOutcome.Unreachable
        error is PortUnreachableException -> ProbeOutcome.Unreachable
        error is ConnectException -> connectException(error)
        else -> ProbeOutcome.Failed
    }

    private fun connectException(error: ConnectException): ProbeOutcome {
        val text = error.message.orEmpty().lowercase()
        return when {
            // ECONNREFUSED: нам ответили RST. Это ответ, значит путь открыт.
            text.contains("refused") -> ProbeOutcome.Refused
            text.contains("unreachable") -> ProbeOutcome.Unreachable
            text.contains("timed out") || text.contains("timeout") -> ProbeOutcome.Silence
            else -> ProbeOutcome.Failed
        }
    }

    /** Чем кончилось TLS-рукопожатие. */
    fun onHandshake(error: Throwable): ProbeOutcome = when {
        // Рукопожатие повисло: ClientHello ушёл, ServerHello не пришёл.
        error is SocketTimeoutException -> ProbeOutcome.Stalled
        error is EOFException -> ProbeOutcome.Reset
        error is SSLException -> sslException(error)
        error is SocketException -> socketException(error)
        else -> ProbeOutcome.Failed
    }

    private fun sslException(error: SSLException): ProbeOutcome {
        val text = (error.message.orEmpty() + " " + error.cause?.message.orEmpty()).lowercase()
        return when {
            // Предупреждение (alert) прислал сам собеседник — например
            // TLSV1_ALERT_UNRECOGNIZED_NAME, «не знаю такого имени», обычный ответ
            // сервера на чужой SNI. Чтобы его прислать, надо было получить и разобрать
            // наш ClientHello, а ответ должен был вернуться целым. DPI так себя не
            // ведёт: он рвёт соединение, а не пишет корректных предупреждений.
            // Значит по сети TLS прошёл — это признак В НАШУ пользу.
            text.contains("unrecognized_name") ||
                text.contains("tlsv1_alert") -> ProbeOutcome.Answered

            // Проблема доверия — про сертификаты, а не про DPI. И это хорошая новость
            // по той же причине: до сертификата рукопожатие доехало.
            text.contains("trust anchor") ||
                text.contains("certpath") ||
                text.contains("certificate") ||
                text.contains("hostname") -> ProbeOutcome.Answered

            // «connection closed» — так на телефоне выглядел разрыв рукопожатия
            // оператором (`SSLHandshakeException: connection closed`); раньше он уходил
            // в невнятное Failed.
            text.contains("reset") ||
                text.contains("closed by peer") ||
                text.contains("connection closed") ||
                text.contains("end of file") ||
                text.contains("eof") ||
                text.contains("broken pipe") -> ProbeOutcome.Reset

            text.contains("timed out") || text.contains("timeout") -> ProbeOutcome.Stalled
            else -> ProbeOutcome.Failed
        }
    }

    private fun socketException(error: SocketException): ProbeOutcome {
        val text = error.message.orEmpty().lowercase()
        return when {
            text.contains("reset") || text.contains("broken pipe") -> ProbeOutcome.Reset
            else -> ProbeOutcome.Failed
        }
    }

    /**
     * Чем кончилась передача.
     *
     * @param bytes сколько успели прочитать.
     * @param wanted сколько хотели прочитать.
     * @param complete ответ дочитан до конца по-честному (EOF), а не оборван.
     * @param error что прилетело, если прилетело.
     */
    fun onTransfer(bytes: Int, wanted: Int, complete: Boolean, error: Throwable?): ProbeOutcome {
        if (error != null) {
            return when {
                // Данные шли и встали — подвисание, а не разрыв. Это и есть то,
                // что ловится «десятками килобайт».
                error is SocketTimeoutException -> ProbeOutcome.Stalled
                error is EOFException -> if (bytes > 0) ProbeOutcome.Reset else ProbeOutcome.Failed
                error is SSLException -> sslException(error)
                error is SocketException -> socketException(error)
                else -> ProbeOutcome.Failed
            }
        }
        // Набрали объём — этого достаточно, дочитывать до конца незачем.
        if (bytes >= wanted) return ProbeOutcome.Ok
        // Короткий, но дочитанный до конца ответ — тоже норма: сервер столько и отдал.
        // Подвисание от короткого ответа отличается наличием честного EOF.
        if (complete && bytes > 0) return ProbeOutcome.Ok
        if (bytes == 0) return ProbeOutcome.Stalled
        return ProbeOutcome.Stalled
    }
}
