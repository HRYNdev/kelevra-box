package io.nekohasekai.sfa.bg.path

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.DatagramSocket
import java.net.Socket

/**
 * Проверки сборки пробного сокета. Сам `VpnService.protect` без телефона не позвать,
 * поэтому крючок подставной — но проверяется здесь ровно то, из-за чего баг и жил:
 * защита обязана сработать ДО привязки к сети и до соединения, иначе она бесполезна.
 */
class ProbeSocketTest {

    /** Подставной крючок: записывает, что защищал, и отвечает то, что велено. */
    private class Fake(private val answer: Boolean = true, private val boom: Throwable? = null) :
        ProbeSocket.Protector {
        val protected = mutableListOf<Any>()

        override fun protect(socket: Socket): Boolean = record(socket)

        override fun protect(socket: DatagramSocket): Boolean = record(socket)

        private fun record(socket: Any): Boolean {
            protected += socket
            boom?.let { throw it }
            return answer
        }
    }

    @After
    fun tearDown() {
        // Крючок общий на весь процесс: оставить его — значит подсунуть соседнему тесту.
        ProbeSocket.useProtector(null)
    }

    @Test
    fun `защита ставится раньше привязки к сети`() {
        val order = mutableListOf<String>()
        ProbeSocket.useProtector(
            object : ProbeSocket.Protector {
                override fun protect(socket: Socket): Boolean {
                    order += "защита"
                    return true
                }

                override fun protect(socket: DatagramSocket): Boolean = true
            },
        )

        ProbeSocket.open { order += "привязка" }.use { socket ->
            assertEquals(listOf("защита", "привязка"), order)
            // Соединённый сокет уже не защитить и не привязать — поэтому connect остаётся
            // вызывающему, а сюда сокет приходит несоединённым.
            assertFalse("сокет обязан приехать несоединённым", socket.isConnected)
        }
    }

    @Test
    fun `защита и привязка достаются одному и тому же сокету`() {
        val fake = Fake()
        ProbeSocket.useProtector(fake)
        var bound: Socket? = null

        ProbeSocket.open { bound = it }.use { socket ->
            assertEquals(listOf<Any>(socket), fake.protected)
            assertSame(socket, bound)
        }
    }

    @Test
    fun `датаграммный сокет защищается тем же порядком`() {
        // UDP заворачивается в VPN тем же правилом per-uid, что и TCP: без защиты
        // проба внешнего резолвера спрашивала бы наш собственный туннель.
        val order = mutableListOf<String>()
        val fake = Fake()
        ProbeSocket.useProtector(
            object : ProbeSocket.Protector {
                override fun protect(socket: Socket): Boolean = fake.protect(socket)

                override fun protect(socket: DatagramSocket): Boolean {
                    order += "защита"
                    return fake.protect(socket)
                }
            },
        )

        ProbeSocket.openDatagram { order += "привязка" }.use { socket ->
            assertEquals(listOf("защита", "привязка"), order)
            assertEquals(listOf<Any>(socket), fake.protected)
        }
    }

    @Test
    fun `крючка нет — проба идёт как раньше, одной привязкой`() {
        ProbeSocket.useProtector(null)
        assertFalse(ProbeSocket.protecting)

        var bound = false
        ProbeSocket.open { bound = true }.use {
            assertTrue("без защиты проба обязана состояться, а не упасть", bound)
        }
        var boundUdp = false
        ProbeSocket.openDatagram { boundUdp = true }.use {
            assertTrue(boundUdp)
        }
    }

    @Test
    fun `крючок стоит — пробе есть что сказать про защиту`() {
        ProbeSocket.useProtector(Fake())
        assertTrue(ProbeSocket.protecting)

        ProbeSocket.useProtector(null)
        assertFalse("снятый крючок обязан перестать считаться защитой", ProbeSocket.protecting)
    }

    @Test
    fun `защита отказала — замер всё равно делаем`() {
        // Оба вида отказа: сервис ответил «нет» и сервис умер прямо в вызове.
        for (fake in listOf(Fake(answer = false), Fake(boom = IOException("сервис уже мёртв")))) {
            ProbeSocket.useProtector(fake)
            var bound = false
            ProbeSocket.open { bound = true }.use {
                assertTrue("осечка защиты пробу не отменяет", bound)
            }
            var boundUdp = false
            ProbeSocket.openDatagram { boundUdp = true }.use {
                assertTrue(boundUdp)
            }
        }
    }

    @Test
    fun `привязка сорвалась — ошибка идёт наверх, а сокет закрыт`() {
        val fake = Fake()
        ProbeSocket.useProtector(fake)
        val boom = IOException("сеть отвалилась")

        val thrown = runCatching { ProbeSocket.open { throw boom } }.exceptionOrNull()

        assertSame("проба обязана узнать, что сокет не готов", boom, thrown)
        assertTrue("брошенный сокет не должен течь", (fake.protected.single() as Socket).isClosed)
    }

    @Test
    fun `привязка датаграммы сорвалась — сокет тоже закрыт`() {
        val fake = Fake()
        ProbeSocket.useProtector(fake)
        val boom = IOException("сеть отвалилась")

        val thrown = runCatching { ProbeSocket.openDatagram { throw boom } }.exceptionOrNull()

        assertSame(boom, thrown)
        assertTrue((fake.protected.single() as DatagramSocket).isClosed)
    }
}
