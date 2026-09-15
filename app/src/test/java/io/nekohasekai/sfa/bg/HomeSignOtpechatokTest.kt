package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.AutoMode.Situation
import io.nekohasekai.sfa.bg.HomeSign.Sign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дом по отпечатку сети, когда резолверы молчат.
 *
 * Беда: после переподключения к тому же вайфаю резолверы первые минуты могут молчать, а
 * память о доме привязана к смене сети и перезапуску — и автомат уходил в туннель у себя
 * дома. Отпечаток (транспорт + набор резолверов) у домашней сети особенный: среди
 * резолверов есть уникальный адрес IPv6 ULA, который выдаёт только свой роутер.
 */
class HomeSignOtpechatokTest {

    private val home = "wifi|192.168.1.1,fd4e:12ab:9c01::1"

    // ------------------------------------------------------------ годится ли отпечаток

    @Test
    fun `отпечаток с резолвером ULA годится`() {
        assertTrue(HomeSign.distinctKey(home))
        assertTrue(HomeSign.distinctKey("wifi|fc00::53"))
        assertTrue(HomeSign.distinctKey("other|10.0.0.1,fd00:1::1"))
    }

    @Test
    fun `без ULA отпечаток не годится — такой адрес роутера стоит у половины сетей`() {
        assertFalse(HomeSign.distinctKey("wifi|192.168.1.1"))
        // Link-local и глобальные адреса уникальной приметой не являются.
        assertFalse(HomeSign.distinctKey("wifi|192.168.1.1,fe80::1%wlan0"))
        assertFalse(HomeSign.distinctKey("wifi|2001:4860:4860::8888"))
        // «fd::1» — это 00fd::1, а не ULA.
        assertFalse(HomeSign.distinctKey("wifi|fd::1"))
    }

    @Test
    fun `сота и непонятная сеть домом по отпечатку не бывают`() {
        assertFalse(HomeSign.distinctKey("cell|fd4e:12ab:9c01::1"))
        assertFalse(HomeSign.distinctKey("?|fd4e:12ab:9c01::1"))
        assertFalse(HomeSign.distinctKey("network-12345"))
        assertFalse(HomeSign.distinctKey(null))
    }

    @Test
    fun `адрес ULA узнаётся по первому слову`() {
        assertTrue(HomeSign.isUla("fd4e:12ab:9c01::1"))
        assertTrue(HomeSign.isUla("FC00::1"))
        assertFalse(HomeSign.isUla("fe80::1"))
        assertFalse(HomeSign.isUla("::1"))
        assertFalse(HomeSign.isUla("192.168.1.1"))
    }

    // ------------------------------------------------------------ когда признак стоит

    @Test
    fun `резолверы молчат, отпечаток совпал с последним домом — признак стоит`() {
        assertTrue(HomeSign.byFingerprint(Sign.Unknown, home, home))
    }

    @Test
    fun `та же SSID, другой набор резолверов — не дома`() {
        // Имени сети в отпечатке нет вовсе: одноимённый вайфай в гостях даёт другой ULA.
        val sameNameElsewhere = "wifi|192.168.1.1,fd77:3300:aa01::1"
        assertFalse(HomeSign.byFingerprint(Sign.Unknown, sameNameElsewhere, home))
        assertFalse(HomeSign.byFingerprint(Sign.Unknown, "wifi|192.168.1.1", home))
    }

    @Test
    fun `резолвер ответил — отпечаток не нужен и ничего не решает`() {
        assertFalse(HomeSign.byFingerprint(Sign.No, home, home))
        assertFalse(HomeSign.byFingerprint(Sign.Yes, home, home))
    }

    @Test
    fun `последнего дома не было — отпечатку не с чем совпадать`() {
        assertFalse(HomeSign.byFingerprint(Sign.Unknown, home, null))
    }

    // ------------------------------------------------------------ что помнить

    @Test
    fun `дом подтверждён резолвером и трафиком — отпечаток запоминаем`() {
        assertEquals(home, HomeSign.nextHomeKey(saved = null, key = home, seenNow = Sign.Yes, home = true))
    }

    @Test
    fun `дом подтверждён, но по отпечатку его не узнать — прежний не трогаем`() {
        assertEquals(home, HomeSign.nextHomeKey(saved = home, key = "wifi|192.168.0.1", seenNow = Sign.Yes, home = true))
    }

    @Test
    fun `дом по самому отпечатку себя не продлевает и не переписывает`() {
        assertEquals(home, HomeSign.nextHomeKey(saved = home, key = home, seenNow = Sign.Unknown, home = true))
    }

    @Test
    fun `на этой же сети резолвер ответил без подмен — обход сняли, отпечаток забываем`() {
        assertNull(HomeSign.nextHomeKey(saved = home, key = home, seenNow = Sign.No, home = false))
    }

    @Test
    fun `в чужой сети ответ без подмен домашний отпечаток не стирает`() {
        assertEquals(home, HomeSign.nextHomeKey(saved = home, key = "wifi|8.8.8.8", seenNow = Sign.No, home = false))
    }

    // ------------------------------------------------------------ сценарий целиком

    /**
     * Заход, собранный из тех же чистых функций, что стоят в живом коде: признак DNS
     * ([HomeSign.stands] или отпечаток), вердикт [AutoMode.homeVerdict], решение
     * [AutoMode.decide], броня [AutoMode.holdsVerdict] и задвижка [AutoModeGate].
     */
    private class Zahod(start: Situation, private var lastHome: String?) {
        val gate = AutoModeGate(AutoMode.CONFIRMATIONS).apply { reset(start) }
        var situationNetwork: String? = null
        var toggles = 0
        private var tunnelUp = start != Situation.Home

        fun round(key: String, dns: Sign, traffic: Boolean?, networkChanged: Boolean) {
            val fingerprint = HomeSign.byFingerprint(dns, key, lastHome)
            val dnsHome = HomeSign.stands(dns, ageMillis = null, refuted = traffic == false) || fingerprint
            val home = AutoMode.homeVerdict(dnsHome, traffic, NetworkMode.Normal)
            val observed = AutoMode.decide(hasNetwork = true, home = home, main = true, room = false)
            val blind = dns == Sign.Unknown && !(fingerprint && home)
            lastHome = HomeSign.nextHomeKey(lastHome, key, dns, home)
            if (AutoMode.holdsVerdict(blind, observed, gate.current, key == situationNetwork, networkChanged, 0L)) return
            val changed = gate.offer(observed, trust = networkChanged, blind = blind)
            if (changed || observed == gate.current) situationNetwork = key
            val up = gate.current != Situation.Home
            if (up != tunnelUp) {
                tunnelUp = up
                toggles++
            }
        }
    }

    @Test
    fun `переподключили домашний вайфай, резолверы молчат, трафик идёт — остаёмся дома`() {
        val zahod = Zahod(Situation.Home, lastHome = home)
        zahod.round(home, Sign.Yes, traffic = true, networkChanged = false)
        // Переподключение: новая сеть с тем же отпечатком, резолверы молчат.
        zahod.round(home, Sign.Unknown, traffic = true, networkChanged = true)
        repeat(10) { zahod.round(home, Sign.Unknown, traffic = true, networkChanged = false) }
        assertEquals(Situation.Home, zahod.gate.current)
        assertEquals("туннель не должен дёрнуться", 0, zahod.toggles)
    }

    @Test
    fun `после перезапуска стартуем не дома, отпечаток из настроек возвращает домой без DNS`() {
        // Память процесса пуста, обстановка неизвестна; отпечаток дожил в настройках.
        val zahod = Zahod(Situation.Main, lastHome = home)
        zahod.round(home, Sign.Unknown, traffic = true, networkChanged = true)
        assertEquals(Situation.Home, zahod.gate.current)
    }

    @Test
    fun `тот же набор резолверов, но трафик наружу не идёт — не дома`() {
        val zahod = Zahod(Situation.Main, lastHome = home)
        repeat(5) { zahod.round(home, Sign.Unknown, traffic = false, networkChanged = it == 0) }
        assertEquals(Situation.Main, zahod.gate.current)
    }

    @Test
    fun `чужая сеть без совпадения отпечатка — как раньше, молчание домом не считается`() {
        val zahod = Zahod(Situation.Main, lastHome = home)
        repeat(5) { zahod.round("wifi|192.168.1.1,fd77:3300:aa01::1", Sign.Unknown, traffic = true, networkChanged = it == 0) }
        assertEquals(Situation.Main, zahod.gate.current)
    }
}
