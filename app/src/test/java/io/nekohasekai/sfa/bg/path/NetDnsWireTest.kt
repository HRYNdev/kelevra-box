package io.nekohasekai.sfa.bg.path

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор DNS-ответа: на нём держится всё опознание дома, а приходит он из UDP, куда
 * прилетает что угодно. Поэтому проверяем не только «понимает правильный ответ», но и
 * «не падает и не врёт на мусоре».
 */
class NetDnsWireTest {

    // ------------------------------------------------------------------------- вопрос

    @Test
    fun `вопрос собран по формату`() {
        val query = NetDnsWire.query(id = 0x1234, host = "gosuslugi.ru")
        // id
        assertEquals(0x12, query[0].toInt() and 0xFF)
        assertEquals(0x34, query[1].toInt() and 0xFF)
        // recursion desired: без него домашний роутер ответит отказом, он не авторитетный
        assertEquals(0x01, query[2].toInt() and 0xFF)
        // вопросов ровно один
        assertEquals(1, query[5].toInt() and 0xFF)
        // 12 заголовка + «gosuslugi» (9+1) + «ru» (2+1) + конец имени + тип и класс
        assertEquals(12 + 10 + 3 + 1 + 4, query.size)
        assertEquals(9, query[12].toInt() and 0xFF)
    }

    @Test
    fun `точка на конце имени не ломает вопрос`() {
        val query = NetDnsWire.query(id = 1, host = "youtube.com.")
        assertEquals(12 + 8 + 4 + 1 + 4, query.size)
    }

    // ------------------------------------------------------------------------- ответ

    @Test
    fun `адрес из ответа достаётся`() {
        val packet = answer(
            id = 0x2222,
            host = "youtube.com",
            addresses = listOf(byteArrayOf(198.toByte(), 18, 3, 9)),
        )
        val reply = NetDnsWire.parse(packet, packet.size, 0x2222)
        assertTrue(reply is NetDnsWire.Reply.Answered)
        val addresses = (reply as NetDnsWire.Reply.Answered).addresses
        assertEquals(1, addresses.size)
        assertEquals("198.18.3.9", NetDnsWire.text(addresses[0]))
    }

    @Test
    fun `несколько адресов и сжатые имена разбираются`() {
        val packet = answer(
            id = 7,
            host = "discord.com",
            addresses = listOf(byteArrayOf(1, 2, 3, 4), byteArrayOf(198.toByte(), 19, 0, 1)),
            compressName = true,
        )
        val reply = NetDnsWire.parse(packet, packet.size, 7) as NetDnsWire.Reply.Answered
        assertEquals(listOf("1.2.3.4", "198.19.0.1"), reply.addresses.map { NetDnsWire.text(it) })
    }

    @Test
    fun `запись не-A пропускается, а не ломает разбор`() {
        // Настоящий ответ почти всегда начинается с CNAME: сперва цепочка имён, потом
        // адреса. Пропускать её обязаны молча.
        val packet = answer(
            id = 9,
            host = "www.gstatic.com",
            addresses = listOf(byteArrayOf(8, 8, 8, 8)),
            leadingCname = true,
        )
        val reply = NetDnsWire.parse(packet, packet.size, 9) as NetDnsWire.Reply.Answered
        assertEquals(listOf("8.8.8.8"), reply.addresses.map { NetDnsWire.text(it) })
    }

    @Test
    fun `пустой ответ — это ответ, а не молчание`() {
        // «Такого имени нет» тоже говорит нам главное: резолвер живой и подмены нет.
        val packet = answer(id = 3, host = "example.com", addresses = emptyList())
        val reply = NetDnsWire.parse(packet, packet.size, 3)
        assertTrue(reply is NetDnsWire.Reply.Answered)
        assertEquals(0, (reply as NetDnsWire.Reply.Answered).addresses.size)
    }

    // -------------------------------------------------------------------------- мусор

    @Test
    fun `чужой id ответом не считается`() {
        val packet = answer(id = 100, host = "youtube.com", addresses = listOf(byteArrayOf(1, 1, 1, 1)))
        assertTrue(NetDnsWire.parse(packet, packet.size, 101) is NetDnsWire.Reply.NotOurs)
    }

    @Test
    fun `вопрос, прилетевший вместо ответа, ответом не считается`() {
        val query = NetDnsWire.query(id = 5, host = "youtube.com")
        assertTrue(NetDnsWire.parse(query, query.size, 5) is NetDnsWire.Reply.NotOurs)
    }

    @Test
    fun `обрезанный пакет разбор не роняет`() {
        val packet = answer(id = 11, host = "youtube.com", addresses = listOf(byteArrayOf(1, 1, 1, 1)))
        // Режем по-разному: на заголовке, на имени, на середине записи.
        for (size in intArrayOf(4, 12, 20, packet.size - 3)) {
            val reply = NetDnsWire.parse(packet, size, 11)
            assertTrue(
                "на обрезке $size байт разбор обязан честно сказать, а не выдумать адрес",
                reply is NetDnsWire.Reply.Broken || reply is NetDnsWire.Reply.Answered,
            )
            if (reply is NetDnsWire.Reply.Answered) assertEquals(0, reply.addresses.size)
        }
    }

    @Test
    fun `указатель на самого себя разбор не зацикливает`() {
        // Пакет, где имя записи ссылается на своё же начало. Без потолка прыжков разбор
        // крутился бы вечно прямо в потоке автомата.
        val packet = ByteArray(64)
        packet[0] = 0
        packet[1] = 12
        packet[2] = 0x80.toByte() // это ответ
        packet[5] = 0 // вопросов нет
        packet[7] = 1 // одна запись
        packet[12] = 0xC0.toByte()
        packet[13] = 12 // указатель сам на себя
        val reply = NetDnsWire.parse(packet, packet.size, 12)
        assertTrue(reply is NetDnsWire.Reply.Broken || reply is NetDnsWire.Reply.Answered)
    }

    @Test
    fun `запись, обещающая больше данных чем есть, отвергается`() {
        val packet = answer(id = 13, host = "youtube.com", addresses = listOf(byteArrayOf(1, 1, 1, 1)))
        // Ломаем длину последней записи: пусть обещает 200 байт.
        packet[packet.size - 5] = 0
        packet[packet.size - 6] = 200.toByte()
        val reply = NetDnsWire.parse(packet, packet.size, 13)
        assertTrue(reply is NetDnsWire.Reply.Broken)
    }

    // ------------------------------------------------------------------ сборка пакетов

    /** Собирает ответ так, как его собрал бы резолвер. */
    private fun answer(
        id: Int,
        host: String,
        addresses: List<ByteArray>,
        compressName: Boolean = false,
        leadingCname: Boolean = false,
    ): ByteArray {
        val out = ArrayList<Byte>(128)
        fun short(value: Int) {
            out.add((value shr 8).toByte())
            out.add(value.toByte())
        }
        short(id)
        short(0x8180) // ответ, рекурсия доступна
        short(1) // вопросов
        short(addresses.size + if (leadingCname) 1 else 0) // ответов
        short(0)
        short(0)
        val nameAt = out.size
        for (label in host.split('.')) {
            out.add(label.length.toByte())
            label.forEach { out.add(it.code.toByte()) }
        }
        out.add(0)
        short(1) // тип A
        short(1) // класс IN

        fun name() {
            if (compressName) {
                out.add(0xC0.toByte())
                out.add(nameAt.toByte())
            } else {
                for (label in host.split('.')) {
                    out.add(label.length.toByte())
                    label.forEach { out.add(it.code.toByte()) }
                }
                out.add(0)
            }
        }

        if (leadingCname) {
            name()
            short(5) // CNAME
            short(1)
            short(0)
            short(60)
            val target = byteArrayOf(3, 'c'.code.toByte(), 'd'.code.toByte(), 'n'.code.toByte(), 0)
            short(target.size)
            target.forEach { out.add(it) }
        }

        for (address in addresses) {
            name()
            short(1) // A
            short(1) // IN
            short(0)
            short(60) // ttl
            short(address.size)
            address.forEach { out.add(it) }
        }
        return out.toByteArray()
    }
}
