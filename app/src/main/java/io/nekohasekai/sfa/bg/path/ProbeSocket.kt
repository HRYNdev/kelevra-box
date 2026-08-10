package io.nekohasekai.sfa.bg.path

import java.net.DatagramSocket
import java.net.Socket

/**
 * Сокет для пробы, который не попадает в наш собственный туннель.
 *
 * Привязки к физической сети для этого не хватает, и это не мелочь, а причина боевого
 * бага. Правило per-uid, которым Android заворачивает наше приложение в свой VPN, стоит
 * ВЫШЕ привязки сокета к сети: `network.socketFactory` выбирает интерфейс, но таблица
 * маршрутов для нашего uid всё равно уводит пакеты в tun. Поэтому проба, которой
 * подтверждается «мы дома», при поднятом туннеле мерила сам туннель — и дом подтверждал
 * себя сам собой. Дальше система запиралась: осечка вердикта поднимала туннель, а
 * поднятый туннель не давал вердикту разойтись.
 *
 * Спасает то же, чем спасается ядро на своих исходящих: `VpnService.protect` помечает
 * сокет как «мимо VPN» на уровне ядра, и правило per-uid его больше не касается.
 * Порядок обязателен и потому вынесен сюда: защита ставится на **несоединённый** сокет,
 * привязка к сети идёт после неё, connect — последним. Ни защитить, ни привязать уже
 * соединённый сокет нельзя.
 *
 * Крючок ставит [io.nekohasekai.sfa.bg.BoxService]: только у него есть живой `VpnService`.
 * Не поставлен (режим без tun, сервис ещё не стартовал) — проба идёт как раньше, одной
 * привязкой к сети. Это хуже, но так было всегда, и падать тут не на чем.
 */
object ProbeSocket {

    /**
     * Чем защищать сокеты от нашего же tun.
     *
     * Два метода, а не один, потому что их два и у самого `VpnService`: у TCP и UDP
     * дескрипторы разные, и общего «защити что угодно» в Android нет.
     */
    interface Protector {
        fun protect(socket: Socket): Boolean

        fun protect(socket: DatagramSocket): Boolean
    }

    @Volatile
    private var protector: Protector? = null

    /**
     * Ставит защиту или снимает её (`null`).
     *
     * Снимать обязательно: `protect` умершего сервиса уже ничего не делает, а проба,
     * считающая себя защищённой, врёт ровно так же, как врала до правки.
     */
    fun useProtector(protect: Protector?) {
        protector = protect
    }

    /** Есть ли чем защищать — чтобы проба не выдавала незащищённый замер за защищённый. */
    val protecting: Boolean get() = protector != null

    /**
     * Готовит несоединённый сокет: сперва защита, потом привязка к сети. Соединяет
     * вызывающий — куда и с каким ожиданием, знает только он.
     *
     * @param bindToNetwork привязка к физической сети (`network.bindSocket`).
     * @throws Throwable то, что бросит привязка; сокет при этом закрывается.
     */
    fun open(bindToNetwork: (Socket) -> Unit): Socket {
        val socket = Socket()
        try {
            // Опция трогается не ради самой опции: пока к сокету не обратились, у него
            // нет файлового дескриптора — защищать и привязывать было бы нечего.
            socket.tcpNoDelay = true
            // Осечка защиты пробу не отменяет: замер по физической сети без защиты хуже
            // защищённого, но лучше отсутствующего.
            runCatching { protector?.protect(socket) }
            bindToNetwork(socket)
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
        return socket
    }

    /**
     * То же для UDP. Дескриптор у датаграммного сокета появляется сразу при создании,
     * поэтому трогать опции незачем — порядок «защита, потом привязка» остаётся тот же.
     */
    fun openDatagram(bindToNetwork: (DatagramSocket) -> Unit): DatagramSocket {
        val socket = DatagramSocket()
        try {
            runCatching { protector?.protect(socket) }
            bindToNetwork(socket)
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
        return socket
    }
}
