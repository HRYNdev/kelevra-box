package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.pult.Ed25519
import io.nekohasekai.sfa.bg.pult.PultBase64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Вход домой на телефоне: ключ X25519, блок dom с подписью мозга, правка конфига ядра.
 * Образцы подписи сделаны питоном так же, как мозг (`mozg/dom_vhod.py`: json.dumps sort_keys,
 * separators, ensure_ascii=False; Ed25519 cryptography), ключ подписи — байты 0..31.
 */
class VhodDomoyTest {
    private val klyuchPodpisi = listOf(Ed25519.izHex("03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8"))
    private val pub = "hSDwCYkwp1R0i33ctD73Wg2/Og0mOBr066SpjqqbTmo="
    private val zakrytyy = "dwdtCnMYpX08FsFyUbJmRd9ML4frwJkqsXf7pR25LCo="
    private val blok = """{"adres": "100.77.0.3/32", "uzel_pub": "2P6Il8HNCVB68E5DdTWTYK4KOqrq2/1G9wjuI3oYCVQ=", "most": "100.76.0.1:51820", "seti": ["100.77.0.0/16"], "dns": "100.77.0.1", "zona": "dom", "mtu": 1280, "versiya": 6, "komu": "01-2", "pub": "hSDwCYkwp1R0i33ctD73Wg2/Og0mOBr066SpjqqbTmo=", "portaly": [{"uzel": "fi-1", "server": "node2kv.example.org", "port": 2053}, {"uzel": "vu-1", "server": "nodekv.example.org", "port": 2083}], "podpis": "IIujae63NnRpRzwznQs9dijUOwFog/nxcMKtDXEXXUPMjDZvUKR5C9wyyDEl3aQXdiIrRkFXMYvLzJUmHfegCg=="}"""
    private val otzyv = """{"otozvan": true, "pub": "hSDwCYkwp1R0i33ctD73Wg2/Og0mOBr066SpjqqbTmo=", "komu": "01-2", "versiya": 7, "podpis": "doofoBCZtLaD6ypWGMj9HYTEotTp8zyxhSY4S8MNrZT809dCVlLGfjqzhmufpTlCCH9N11A8HPkMVLNtevUpBQ=="}"""

    private fun hex(s: String) = Ed25519.izHex(s)
    private fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `X25519 по векторам RFC 7748`() {
        assertEquals(
            "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
            hexOf(X25519.otkrytyy(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))),
        )
        assertEquals(
            "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f",
            hexOf(X25519.otkrytyy(hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"))),
        )
        // общий секрет сходится с обеих сторон (§6.1)
        val a = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val b = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val s1 = X25519.umnozhit(a, X25519.otkrytyy(b))
        assertEquals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742", hexOf(s1))
        assertEquals(hexOf(s1), hexOf(X25519.umnozhit(b, X25519.otkrytyy(a))))
    }

    @Test
    fun `новый ключ прижат и даёт свой открытый`() {
        val k = X25519.novyy()
        assertEquals(0, k[0].toInt() and 7)
        assertEquals(64, k[31].toInt() and 0xC0)
        assertEquals(32, X25519.otkrytyy(k).size)
        assertEquals(pub, PultBase64.zakodirovat(X25519.otkrytyy(PultBase64.razobrat(zakrytyy)!!)))
    }

    @Test
    fun `канонический JSON как у мозга`() {
        assertEquals(
            """{"a":[1,true,null],"b":"x\"y\\z\n","c":"Дом"}""",
            VhodDomoyBlok.kanonicheskiy(JSONObject("""{"b":"x\"y\\z\n","a":[1,true,null],"c":"Дом"}""")),
        )
        assertNull(VhodDomoyBlok.kanonicheskiy(JSONObject("""{"a":1.5}""")))
    }

    @Test
    fun `подписанный блок принимается своему слоту`() {
        val r = VhodDomoyBlok.razobrat(blok, "01-2", klyuchPodpisi) as VhodDomoyBlok.Razbor.Ok
        val b = r.blok
        assertEquals("100.77.0.3/32", b.adres)
        assertEquals("100.76.0.1", b.mostHost)
        assertEquals(51820, b.mostPort)
        assertEquals(listOf("100.77.0.0/16"), b.seti)
        assertEquals(pub, b.pub)
        assertEquals(6L, b.versiya)
        assertEquals(2, b.portaly.size)
        assertFalse(b.otozvan)
    }

    @Test
    fun `чужой слот, чужая подпись, правка поля — отказ`() {
        assertEquals("komu", (VhodDomoyBlok.razobrat(blok, "01-3", klyuchPodpisi) as VhodDomoyBlok.Razbor.Otkaz).prichina)
        assertEquals("komu", (VhodDomoyBlok.razobrat(blok, "", klyuchPodpisi) as VhodDomoyBlok.Razbor.Otkaz).prichina)
        val chuzhoy = listOf(Ed25519.izHex("89f9504b7dfa4bad83b8f781e8fb81225e5883739ac502804669429b1a6ab552"))
        assertEquals("podpis", (VhodDomoyBlok.razobrat(blok, "01-2", chuzhoy) as VhodDomoyBlok.Razbor.Otkaz).prichina)
        val podmena = blok.replace("100.76.0.1:51820", "203.0.113.9:51820")
        assertEquals("podpis", (VhodDomoyBlok.razobrat(podmena, "01-2", klyuchPodpisi) as VhodDomoyBlok.Razbor.Otkaz).prichina)
        val bezPodpisi = JSONObject(blok).apply { remove("podpis") }.toString()
        assertEquals("podpis", (VhodDomoyBlok.razobrat(bezPodpisi, "01-2", klyuchPodpisi) as VhodDomoyBlok.Razbor.Otkaz).prichina)
    }

    @Test
    fun `подписанный отзыв разбирается`() {
        val b = (VhodDomoyBlok.razobrat(otzyv, "01-2", klyuchPodpisi) as VhodDomoyBlok.Razbor.Ok).blok
        assertTrue(b.otozvan)
        assertEquals(7L, b.versiya)
        assertEquals(pub, b.pub)
    }

    private val profil = """
        {
          "dns": {"servers": [{"type": "fakeip", "tag": "fakeip"}, {"type": "local", "tag": "local"}],
                  "rules": [{"action": "reject", "rule_set": ["ads"]}], "final": "local"},
          "inbounds": [{"type": "tun", "tag": "tun-in"}, {"type": "mixed", "tag": "mixed-in"}],
          "route": {"rules": [
            {"action": "sniff"},
            {"type": "logical", "mode": "or", "rules": [{"protocol": "dns"}, {"port": 53}], "action": "hijack-dns"},
            {"ip_is_private": true, "outbound": "direct"}
          ], "final": "direct"},
          "outbounds": [
            {"type": "selector", "tag": "Соединение", "outbounds": ["Финляндия", "Нидерланды"]},
            {"type": "urltest", "tag": "Финляндия", "outbounds": ["Финляндия · XHTTP", "Финляндия · Vision"]},
            {"type": "vless", "tag": "Финляндия · XHTTP", "server": "Node2kv.example.org", "server_port": 2053},
            {"type": "vless", "tag": "Финляндия · Vision", "server": "node2kv.example.org", "server_port": 443},
            {"type": "vless", "tag": "Нидерланды", "server": "nodekv.example.org", "server_port": 2083},
            {"type": "direct", "tag": "direct"}
          ]
        }
    """.trimIndent()

    private fun vhod() = VhodDomoyPatch.Vhod(zakrytyy, (VhodDomoyBlok.razobrat(blok, "01-2", klyuchPodpisi) as VhodDomoyBlok.Razbor.Ok).blok)

    @Test
    fun `правка вписывает эндпоинт, правила и DNS зоны`() {
        val r = VhodDomoyPatch.apply(profil, vhod())
        assertTrue(r.note, r.patched)
        val d = JSONObject(r.content)
        val e = d.getJSONArray("endpoints").getJSONObject(0)
        assertEquals("wireguard", e.getString("type"))
        assertEquals("Дом", e.getString("tag"))
        assertEquals(zakrytyy, e.getString("private_key"))
        assertEquals("100.77.0.3/32", e.getJSONArray("address").getString(0))
        // мост — первый выход ровно на вход с порталом (имя без учёта регистра), без группы и без Vision :443
        assertEquals("Финляндия · XHTTP", e.getString("detour"))
        val peer = e.getJSONArray("peers").getJSONObject(0)
        assertEquals("100.76.0.1", peer.getString("address"))
        assertEquals(51820, peer.getInt("port"))
        assertEquals(25, peer.getInt("persistent_keepalive_interval"))
        val tegi = (0 until d.getJSONArray("outbounds").length()).map { d.getJSONArray("outbounds").getJSONObject(it).getString("tag") }
        assertFalse("Дом · мост" in tegi)
        val rules = d.getJSONObject("route").getJSONArray("rules")
        assertEquals("sniff", rules.getJSONObject(0).getString("action"))
        assertEquals("Дом", rules.getJSONObject(1).getString("outbound"))
        assertEquals("tun-in", rules.getJSONObject(1).getJSONArray("inbound").getString(0))
        assertEquals("reject", rules.getJSONObject(2).getString("action"))
        assertEquals("hijack-dns", rules.getJSONObject(3).getString("action"))
        val dns = d.getJSONObject("dns")
        assertEquals("dns-dom", dns.getJSONArray("rules").getJSONObject(0).getString("server"))
        assertEquals("dom", dns.getJSONArray("rules").getJSONObject(0).getJSONArray("domain_suffix").getString(0))
        val srv = (0 until dns.getJSONArray("servers").length()).map { dns.getJSONArray("servers").getJSONObject(it) }.last()
        assertEquals("100.77.0.1", srv.getString("server"))
        assertEquals("Дом", srv.getString("detour"))
    }

    @Test
    fun `один вход с порталом — мост прямо в него, ни одного — через Соединение`() {
        val odin = profil.replace("\"server\": \"nodekv.example.org\", \"server_port\": 2083", "\"server\": \"drugoy.example.org\", \"server_port\": 2083")
        val e1 = JSONObject(VhodDomoyPatch.apply(odin, vhod()).content).getJSONArray("endpoints").getJSONObject(0)
        assertEquals("Финляндия · XHTTP", e1.getString("detour"))
        val nikto = odin.replace("2053", "2054")
        val e2 = JSONObject(VhodDomoyPatch.apply(nikto, vhod()).content).getJSONArray("endpoints").getJSONObject(0)
        assertEquals("Соединение", e2.getString("detour"))
    }

    @Test
    fun `занятый тег и отзыв — конфиг как был`() {
        val zanyat = profil.replace("\"tag\": \"direct\"}", "\"tag\": \"Дом\"}")
        assertFalse(VhodDomoyPatch.apply(zanyat, vhod()).patched)
        assertEquals(zanyat, VhodDomoyPatch.apply(zanyat, vhod()).content)
        val otz = VhodDomoyPatch.Vhod(zakrytyy, (VhodDomoyBlok.razobrat(otzyv, "01-2", klyuchPodpisi) as VhodDomoyBlok.Razbor.Ok).blok)
        assertFalse(VhodDomoyPatch.apply(profil, otz).patched)
    }
}
