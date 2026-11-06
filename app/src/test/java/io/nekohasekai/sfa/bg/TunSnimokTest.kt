package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.TunSnimok.Prefix
import io.nekohasekai.sfa.bg.TunSnimok.Reshenie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Решение «пересоздавать ли tun при пересборке ядра»: прежний интерфейс отдаётся как есть,
 * если собран из того же, и заменяется без паузы, если нет. Android тут нет — одна
 * логика сравнения.
 */
class TunSnimokTest {

    private fun snimok(
        mtu: Int = 9000,
        addresses: List<Prefix> = listOf(Prefix("172.19.0.1", 30)),
        dns: List<String> = listOf("172.19.0.2"),
        routes: List<Prefix> = listOf(Prefix("0.0.0.0", 0)),
        excludeRoutes: List<Prefix> = emptyList(),
        include: Collection<String> = emptyList(),
        exclude: Collection<String> = listOf("ru.gosuslugi.app", "io.nekohasekai.sfa"),
        httpProxy: TunSnimok.HttpProxy? = TunSnimok.HttpProxy("127.0.0.1", 2412, listOf("localhost")),
        allowBypass: Boolean = false,
    ) = TunSnimok.of(mtu, addresses, dns, routes, excludeRoutes, include, exclude, httpProxy, allowBypass)

    @Test
    fun `тот же tun из того же — переиспользуем, establish не зовём`() {
        assertEquals(Reshenie.PEREISPOLZOVAT, TunSnimok.reshenie(snimok(), snimok(), prezhniyZhiv = true))
    }

    @Test
    fun `порядок пакетов и маршрутов ничего не значит`() {
        val a = snimok(exclude = listOf("a.app", "b.app"), routes = listOf(Prefix("10.0.0.0", 8), Prefix("0.0.0.0", 0)))
        val b = snimok(exclude = listOf("b.app", "a.app"), routes = listOf(Prefix("0.0.0.0", 0), Prefix("10.0.0.0", 8)))
        assertEquals(a, b)
        assertEquals(Reshenie.PEREISPOLZOVAT, TunSnimok.reshenie(a, b, prezhniyZhiv = true))
    }

    @Test
    fun `повтор пакета в списке — тот же набор`() {
        assertEquals(snimok(exclude = listOf("a.app", "a.app")), snimok(exclude = listOf("a.app")))
    }

    @Test
    fun `сменился список приложений мимо tun — заменяем, пока прежний открыт`() {
        // Выход приложения из tun ради комнаты: единственное, что меняется на пути комнаты.
        val bez = snimok(exclude = listOf("ru.gosuslugi.app"))
        val s = snimok(exclude = listOf("ru.gosuslugi.app", "io.nekohasekai.sfa"))
        assertNotEquals(bez, s)
        assertEquals(Reshenie.ZAMENIT, TunSnimok.reshenie(bez, s, prezhniyZhiv = true))
        assertTrue(bez.raznica(s).contains("мимо tun 1→2"))
    }

    @Test
    fun `сменились MTU, DNS, адреса или прокси — заменяем`() {
        val base = snimok()
        assertEquals(Reshenie.ZAMENIT, TunSnimok.reshenie(base, snimok(mtu = 1500), true))
        assertEquals(Reshenie.ZAMENIT, TunSnimok.reshenie(base, snimok(dns = listOf("1.1.1.1")), true))
        assertEquals(Reshenie.ZAMENIT, TunSnimok.reshenie(base, snimok(addresses = listOf(Prefix("172.19.0.5", 30))), true))
        assertEquals(Reshenie.ZAMENIT, TunSnimok.reshenie(base, snimok(httpProxy = null), true))
        assertEquals(Reshenie.ZAMENIT, TunSnimok.reshenie(base, snimok(allowBypass = true), true))
    }

    @Test
    fun `прежнего tun нет — обычный первый подъём, даже если снимок помним`() {
        // Подъём из погашенного дома: дескриптор закрыт, снимок прошлой жизни не годится.
        assertEquals(Reshenie.NOVYY, TunSnimok.reshenie(snimok(), snimok(), prezhniyZhiv = false))
        assertEquals(Reshenie.NOVYY, TunSnimok.reshenie(null, snimok(), prezhniyZhiv = false))
    }

    @Test
    fun `снимка не помним, а дескриптор жив — заменяем, не доверяем`() {
        assertEquals(Reshenie.ZAMENIT, TunSnimok.reshenie(null, snimok(), prezhniyZhiv = true))
    }

    @Test
    fun `разница пуста, когда снимки равны`() {
        assertEquals("", snimok().raznica(snimok()))
    }
}
