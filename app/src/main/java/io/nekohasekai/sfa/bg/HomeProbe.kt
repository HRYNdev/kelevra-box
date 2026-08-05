package io.nekohasekai.sfa.bg

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Один вопрос к сети: «какие адреса у этого домена **прямо сейчас**».
 *
 * Зачем отдельно от [Network.getAllByName]. Тот отвечает из кеша резолвера, и это
 * ровно та ошибка, из-за которой возвращение домой не замечалось минуту: на мобильной
 * сети проксируемые домены отрезолвились в настоящие адреса, ответ лёг в кеш, и первые
 * секунды после вайфая система отдавала его же. Проба видела «не дома», решение уходило
 * на пять минут вперёд — а на самом деле роутер уже подменял адреса.
 *
 * Поэтому спрашиваем именно сеть, а не кеш: [DnsResolver] с [DnsResolver.FLAG_NO_CACHE_LOOKUP]
 * (есть с API 29). Ответ при этом в кеш кладётся как обычно — нам мешает только чтение
 * из него, а не запись.
 *
 * На старых версиях остаётся прежний путь через [Network.getAllByName]. Туда же уходим,
 * если резолвер ответил ошибкой: выдумывать «не дома» из-за сбоя пробы хуже, чем
 * посмотреть в кеш.
 */
internal object HomeProbe {

    /** Колбэк резолвера зовём на том потоке, который его отдал: работы там на одну строчку. */
    private val sameThread = Executor { it.run() }

    /**
     * @param network сеть, у которой спрашиваем. Обязана быть физической: у VPN-сети
     *   свой резолвер, и он отвечает не про обстановку вокруг, а про наш же туннель.
     */
    fun addresses(network: Network, host: String, timeoutMillis: Long): List<InetAddress> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            fresh(network, host, timeoutMillis)?.let { return it }
        }
        return runCatching { network.getAllByName(host).toList() }.getOrElse { emptyList() }
    }

    /** @return ответ сети или null, если резолвер не ответил (ошибка, отказ, не уложился). */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun fresh(network: Network, host: String, timeoutMillis: Long): List<InetAddress>? {
        val answer = AtomicReference<List<InetAddress>?>(null)
        val done = CountDownLatch(1)
        val signal = CancellationSignal()
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
