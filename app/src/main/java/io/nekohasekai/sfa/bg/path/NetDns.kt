package io.nekohasekai.sfa.bg.path

import android.net.Network
import android.os.SystemClock
import android.util.Log
import io.nekohasekai.sfa.Application
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.random.Random

/**
 * Спрашивает имена **у резолверов конкретной сети**, минуя и системный кеш, и наш
 * собственный туннель.
 *
 * Это источник правды для опознания дома. Раньше им был системный резолвер, и он не
 * годится по трём причинам сразу, каждая из которых уже стоила боевого бага:
 *
 *  1. **Наш же туннель.** Правило per-uid, которым Android заворачивает приложение в
 *     свой VPN, стоит выше выбора сети. При поднятом туннеле ответы приходили от нашего
 *     ядра, а не от роутера, — и вопрос «дома ли мы» превращался в вопрос «что написано
 *     у нас в конфиге». Опаснее всего это в **чужой** сети: три домена опознания лежат в
 *     наших же наборах подмены, диапазон подменных адресов совпадает с домашним, и полный
 *     набор признаков дома собирается там, где дома нет.
 *  2. **Кеш.** `Network.getAllByName` при сорвавшемся запросе молча отдаёт прошлый ответ,
 *     а на свежем вайфае прошлый ответ — с мобильной сети.
 *  3. **Молчание неотличимо от «нет».** Пустой список приходит и когда резолвер не
 *     ответил, и когда имя честно резолвится в настоящий адрес.
 *
 * Здесь всё три закрыто по построению: пакет уходит своим сокетом (защищённым от нашего
 * tun через [ProbeSocket], привязанным к нужной сети), кеша нет вовсе, а «резолвер
 * молчит» и «резолвер ответил» — разные исходы.
 */
internal object NetDns {

    private const val TAG = "NetDns"

    private const val DNS_PORT = 53

    /** Ответ на один A-вопрос в 512 байт помещается всегда; больше по UDP и не придёт. */
    private const val BUFFER_BYTES = 512

    /** Что вышло из вопроса. */
    sealed interface Outcome {

        /**
         * Резолвер ответил. Пустой список адресов — законный ответ, он тоже говорит,
         * что резолвер живой и подмены на этом имени нет.
         */
        data class Answered(
            val addresses: List<InetAddress>,
            val resolver: String,
            val tookMillis: Long,
        ) : Outcome

        /**
         * Ответа нет: резолверы этой сети молчат, их не видно вовсе или спросить нечем.
         *
         * Это **не** «настоящий адрес». Наверху такой исход обязан оставаться
         * неизвестностью, иначе получится ровно та ошибка, ради которой класс написан.
         */
        data class Silent(val reason: String) : Outcome
    }

    /**
     * @param network физическая сеть. Передавать сюда VPN-сеть бессмысленно: у неё свои
     *   резолверы, и ответ будет про наш туннель.
     * @param budgetMillis общий бюджет на все резолверы сети, а не на каждый.
     */
    fun resolve(network: Network, host: String, budgetMillis: Long): Outcome {
        val resolvers = resolversOf(network)
        if (resolvers.isEmpty()) {
            Log.d(TAG, "$host: у сети нет своих резолверов")
            return Outcome.Silent("у сети нет своих резолверов")
        }
        val startedAt = SystemClock.elapsedRealtime()
        var lastReason = "резолверы молчат"
        for (resolver in resolvers) {
            val left = budgetMillis - (SystemClock.elapsedRealtime() - startedAt)
            // Меньше четверти секунды — это не попытка, а способ соврать «молчит».
            if (left < 250) break
            when (val reply = ask(network, resolver, host, left)) {
                is Outcome.Answered -> return reply
                is Outcome.Silent -> lastReason = reply.reason
            }
        }
        // Причина молчания до сих пор возвращалась наверх и там терялась: в журнале
        // оставалось «промолчали 3 из 3» без единого слова о том, почему. Разница между
        // «резолвер не ответил за 2500 мс» и «сеть недостижима» — это разница между
        // протухшим путём до резолвера и оборванной связью.
        Log.d(TAG, "$host: $lastReason (за ${SystemClock.elapsedRealtime() - startedAt} мс)")
        return Outcome.Silent(lastReason)
    }

    /**
     * Резолверы сети: сперва IPv4.
     *
     * IPv6-резолверы не выбрасываем — у некоторых сетей других и нет, — но ставим после:
     * домашний роутер отвечает по обоим, а лишний круг ожидания на link-local адресе
     * там, где рядом есть обычный, оплачивать незачем.
     */
    private fun resolversOf(network: Network): List<InetAddress> = runCatching {
        val link = Application.connectivity.getLinkProperties(network) ?: return emptyList()
        link.dnsServers.sortedBy { it !is Inet4Address }
    }.getOrElse { emptyList() }

    private fun ask(
        network: Network,
        resolver: InetAddress,
        host: String,
        budgetMillis: Long,
    ): Outcome {
        val id = Random.nextInt(0, 0x1_0000)
        val question = runCatching { NetDnsWire.query(id, host) }.getOrElse {
            return Outcome.Silent("вопрос не собрался: ${it.message}")
        }
        val startedAt = SystemClock.elapsedRealtime()
        var socket: DatagramSocket? = null
        return try {
            socket = ProbeSocket.openDatagram { network.bindSocket(it) }
            socket.soTimeout = budgetMillis.toInt()
            socket.send(DatagramPacket(question, question.size, resolver, DNS_PORT))
            // Читаем, пока не придёт ответ на НАШ вопрос: на открытый порт может прилететь
            // чужой пакет, и принять его за ответ значит принять решение по чужим данным.
            while (true) {
                val left = budgetMillis - (SystemClock.elapsedRealtime() - startedAt)
                if (left <= 0) return Outcome.Silent("резолвер ${text(resolver)} не ответил за $budgetMillis мс")
                socket.soTimeout = left.toInt()
                val buffer = ByteArray(BUFFER_BYTES)
                val answer = DatagramPacket(buffer, buffer.size)
                socket.receive(answer)
                when (val reply = NetDnsWire.parse(buffer, answer.length, id)) {
                    is NetDnsWire.Reply.Answered -> {
                        val took = SystemClock.elapsedRealtime() - startedAt
                        val addresses = reply.addresses.mapNotNull { bytes ->
                            // getByAddress по готовым байтам в сеть не ходит — имя не спрашивается.
                            runCatching { InetAddress.getByAddress(bytes) }.getOrNull()
                        }
                        Log.d(TAG, "$host у ${text(resolver)}: ${addresses.joinToString { it.hostAddress.orEmpty() }} (за $took мс)")
                        return Outcome.Answered(addresses, text(resolver), took)
                    }
                    // Чужой или битый пакет ответом не считаем и ждём дальше, пока есть бюджет.
                    is NetDnsWire.Reply.NotOurs -> Log.d(TAG, "чужой пакет от ${text(resolver)}: ${reply.reason}")
                    is NetDnsWire.Reply.Broken -> Log.d(TAG, "битый пакет от ${text(resolver)}: ${reply.reason}")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Outcome.Silent("недостижимо")
        } catch (t: Throwable) {
            Outcome.Silent("резолвер ${text(resolver)}: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun text(address: InetAddress): String = address.hostAddress ?: address.toString()
}
