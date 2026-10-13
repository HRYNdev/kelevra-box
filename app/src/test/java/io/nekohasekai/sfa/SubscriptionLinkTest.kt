package io.nekohasekai.sfa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionLinkTest {
    private val sslip = "31-77-203-26.sslip.io"
    private val subkv = "subkv.chickenkiller.com"

    @Test
    fun goliy_kod_idet_na_subkv() {
        assertEquals("https://$subkv/k/abc123", SubscriptionLink.normalize("abc123"))
        assertEquals("https://$subkv/k/abc123", SubscriptionLink.normalize(" abc123/ "))
    }

    @Test
    fun chuzhoy_khost_sokhranyaetsya() {
        assertEquals("https://$sslip/k/abc123", SubscriptionLink.normalize("https://$sslip/k/abc123"))
        assertEquals("https://$sslip/k/abc123", SubscriptionLink.normalize("https://$sslip/s/abc123/"))
        assertEquals("https://$sslip/k/abc123", SubscriptionLink.normalize("https://$sslip/k/abc123/info?device=x"))
        assertEquals("https://$sslip:8443/k/abc123", SubscriptionLink.normalize("https://$sslip:8443/k/abc123"))
    }

    @Test
    fun stranitsa_priglasheniya_i_stanovitsya_k() {
        assertEquals("https://$sslip/k/abc123", SubscriptionLink.normalize("https://$sslip/i/abc123"))
        assertEquals("https://$subkv/k/abc123", SubscriptionLink.normalize("https://$subkv/i/abc123"))
    }

    @Test
    fun starye_ssylki_subkv_rabotayut_kak_ranshe() {
        assertEquals("https://$subkv/k/abc123", SubscriptionLink.normalize("https://$subkv/abc123/singbox"))
        assertEquals("https://$subkv/k/abc123", SubscriptionLink.normalize("http://$subkv/abc123"))
        assertEquals("https://$subkv/k/abc123", SubscriptionLink.normalize("https://$subkv/s/abc123"))
        assertEquals("abc123", SubscriptionLink.parse("https://$subkv/k/abc123/info")!!.code)
    }

    @Test
    fun chuzhie_podpiski_ne_trogaem() {
        assertEquals("https://example.com/sub/token", SubscriptionLink.normalize("https://example.com/sub/token/"))
        assertNull(SubscriptionLink.parse("https://example.com/abc123"))
        assertNull(SubscriptionLink.parse("https://example.com/"))
        assertNull(SubscriptionLink.parse("ftp://example.com/k/abc"))
        assertNull(SubscriptionLink.parse("abc123"))
    }

    @Test
    fun khost_iz_profilya() {
        assertEquals(sslip, SubscriptionLink.hostOf("https://$sslip/k/abc"))
        assertEquals(subkv, SubscriptionLink.hostOf("https://$subkv/abc/singbox"))
        assertEquals(subkv, SubscriptionLink.hostOf("https://example.com/sub/x"))
        assertEquals(subkv, SubscriptionLink.hostOf(""))
    }

    @Test
    fun maskirovka_na_lyubom_khoste() {
        assertEquals(
            "GET https://$sslip/***23 (9 симв.) failed",
            SubscriptionLink.maskCode("GET https://$sslip/k/abcdef123 failed"),
        )
        assertEquals("https://$subkv/***23 (9 симв.)", SubscriptionLink.maskCode("https://$subkv/k/abcdef123"))
        assertFalse(SubscriptionLink.maskCode("https://$subkv/abcdef123/singbox").contains("abcdef123"))
    }

    @Test
    fun glubokaya_ssylka() {
        val enc = java.net.URLEncoder.encode("https://$sslip/k/abc123", "UTF-8")
        assertEquals("https://$sslip/k/abc123", SubscriptionLink.fromDeepLink("kelevra://import?url=$enc"))
        assertEquals("https://$subkv/k/abc123", SubscriptionLink.fromDeepLink("kelevra://import?code=abc123"))
        assertNull(SubscriptionLink.fromDeepLink("kelevra://import"))
        assertNull(SubscriptionLink.fromDeepLink("sing-box://import-remote-profile?url=x"))
    }

    @Test
    fun podmena_bazy() {
        assertEquals("http://10.0.2.2:8765/logs", SubscriptionLink.withBase("https://$sslip/logs", "http://10.0.2.2:8765/"))
    }
}
