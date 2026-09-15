package io.nekohasekai.sfa.bg

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import io.nekohasekai.sfa.bg.path.NetDns
import io.nekohasekai.sfa.bg.path.NetDnsRace
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Один вопрос к сети: «какие адреса у этого домена **прямо сейчас**».
 *
 * Спрашиваем сами, своим сокетом мимо своего же tun ([NetDns]), а не через систему.
 * Причина не в скорости, а в том, что системный ответ на наш вопрос не отвечает:
 *
 *  - при поднятом туннеле правило per-uid уводит запрос в собственное ядро, и «дома ли
 *    мы» превращается в «что написано у нас в конфиге». В чужой сети это опаснее всего:
 *    домены опознания лежат в наших же наборах подмены, и признак дома собирается там,
 *    где дома нет;
 *  - [Network.getAllByName] на сорвавшемся запросе молча отдаёт кеш, а на свежем вайфае
 *    в кеше лежат адреса прошлой сети. Ровно так возвращение домой не замечалось минуту;
 *  - молчание резолвера приходит тем же пустым списком, что и честный настоящий адрес,
 *    то есть «не узнали» неотличимо от «не дома».
 *
 * Поэтому ответ здесь трёхзначный по построению: адреса, либо честное [Answer.Silent].
 * Системные пути остались запасными — на случай, когда своим сокетом спросить не вышло
 * (резолверы сети не видны, порт 53 закрыт, версия Android старше [Build.VERSION_CODES.Q]).
 */
internal object HomeProbe {

    /** Колбэк резолвера зовём на том потоке, который его отдал: работы там на одну строчку. */
    private val sameThread = Executor { it.run() }

    /** Что ответила сеть. */
    sealed interface Answer {

        /**
         * Резолвер ответил. Пустой список — законный ответ «нет такой A-записи», он тоже
         * означает, что резолвер живой.
         */
        data class Addresses(val addresses: List<InetAddress>, val from: String) : Answer

        /** Ответа нет. Это не «настоящий адрес» и не «не дома», это отсутствие ответа. */
        data class Silent(val reason: String) : Answer
    }

    /**
     * @param network сеть, у которой спрашиваем. Обязана быть физической: у VPN-сети
     *   свой резолвер, и он отвечает не про обстановку вокруг, а про наш же туннель.
     */
    fun ask(network: Network, host: String, timeoutMillis: Long): Answer {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return askWithSystem(network, host, timeoutMillis)
        return when (val own = NetDns.resolve(network, host, timeoutMillis)) {
            is NetDns.Outcome.Answered -> Answer.Addresses(own.addresses, "резолвер сети ${own.resolver}")
            // Кеш системы отвечает сразу, ждать его незачем — спрашиваем после своего пути.
            is NetDns.Outcome.Silent -> runCatching { network.getAllByName(host).toList() }.getOrNull()
                ?.let { Answer.Addresses(it, "системный кеш (Android до 10)") }
                ?: Answer.Silent(own.reason)
        }
    }

    /**
     * Свой путь и системный резолвер — разом ([NetDnsRace.withBackup]).
     *
     * Системный хуже (может ответить за наше ядро или из кеша), поэтому решает свой путь:
     * ответил — берём его, системный снимаем. Но без системного сеть, где обычный UDP на
     * 53-й порт закрыт, вообще перестала бы опознаваться, а раньше он запускался только
     * после того, как свой путь промолчал весь бюджет, — молчание стоило два бюджета подряд.
     * Теперь, когда свой путь промолчал, системный уже отработал рядом тот же срок.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun askWithSystem(network: Network, host: String, timeoutMillis: Long): Answer {
        val signal = CancellationSignal()
        val done = CountDownLatch(1)
        val (own, system) = NetDnsRace.withBackup(
            budgetMillis = timeoutMillis,
            primary = { NetDns.resolve(network, host, timeoutMillis) },
            primaryAnswered = { it is NetDns.Outcome.Answered },
            backup = { budget -> fresh(network, host, budget, signal, done) },
            // Снятый запрос колбэка уже не позовёт, поэтому отпускаем и ожидание: иначе поток
            // запасного пути досиживал бы весь бюджет впустую. Слушатель отмены на сам сигнал
            // не повесить — его занимает DnsResolver, а слушатель у сигнала один.
            cancelBackup = {
                signal.cancel()
                done.countDown()
            },
        )
        return when (own) {
            is NetDns.Outcome.Answered -> Answer.Addresses(own.addresses, "резолвер сети ${own.resolver}")
            is NetDns.Outcome.Silent -> system
                ?.let { Answer.Addresses(it, "системный резолвер (свой путь молчит: ${own.reason})") }
                ?: Answer.Silent(own.reason)
        }
    }

    /** @return ответ сети или null, если резолвер не ответил (ошибка, отказ, не уложился). */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun fresh(
        network: Network,
        host: String,
        timeoutMillis: Long,
        signal: CancellationSignal,
        done: CountDownLatch,
    ): List<InetAddress>? {
        val answer = AtomicReference<List<InetAddress>?>(null)
        val asked = runCatching {
            DnsResolver.getInstance().query(
                network,
                host,
                DnsResolver.FLAG_NO_CACHE_LOOKUP,
                sameThread,
                signal,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(addresses: List<InetAddress>, rcode: Int) {
                        answer.set(addresses)
                        done.countDown()
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        done.countDown()
                    }
                },
            )
        }.isSuccess
        if (!asked) return null
        if (!done.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            runCatching { signal.cancel() }
            return null
        }
        return answer.get()
    }
}
