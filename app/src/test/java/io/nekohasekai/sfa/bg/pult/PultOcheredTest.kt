package io.nekohasekai.sfa.bg.pult

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Журнальные команды не держат общую очередь; остальные идут по одной, как раньше. */
class PultOcheredTest {
    private val otpustit = mutableMapOf<String, CompletableDeferred<String>>()
    private val nachaty = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun sbros() {
        Pult.dlyaTesta(PultSostoyanie())
        Pult.ispolnitel = object : Pult.Ispolnitel {
            override suspend fun vypolnit(z: PultYadro.Zadanie): String {
                nachaty += z.id
                return otpustit[z.id]?.await() ?: "ok"
            }
        }
    }

    @After
    fun posle() {
        otpustit.values.forEach { it.complete("ok") }
        Pult.ispolnitel = null
        Pult.dlyaTesta(PultSostoyanie())
    }

    private fun itog(id: String): String? = Pult.sostoyanie.komandy[id]?.itog

    private suspend fun dozhdatsya(usl: () -> Boolean) = withTimeout(5_000) { while (!usl()) delay(10) }

    @Test
    fun klyuch_ne_zhdyot_zhurnal() = runBlocking {
        otpustit["d1"] = CompletableDeferred()
        otpustit["z1"] = CompletableDeferred()
        Pult.zapustit(PultYadro.Zadanie("d1", "diagnostika", JSONObject()))
        Pult.zapustit(PultYadro.Zadanie("z1", "prishli_zhurnal", JSONObject()))
        Pult.zapustit(PultYadro.Zadanie("k1", "pereydi_na_klyuch", JSONObject()))
        dozhdatsya { itog("k1") == "ok" }
        assertEquals(null, itog("d1"))
        assertEquals(null, itog("z1"))
        otpustit["z1"]!!.complete("oshibka-set")
        otpustit["d1"]!!.complete("ok")
        dozhdatsya { itog("z1") == "oshibka-set" && itog("d1") == "ok" }
    }

    @Test
    fun obychnye_po_odnoy() = runBlocking {
        otpustit["p1"] = CompletableDeferred()
        Pult.zapustit(PultYadro.Zadanie("p1", "obnovi_profil", JSONObject()))
        dozhdatsya { "p1" in nachaty }
        Pult.zapustit(PultYadro.Zadanie("s1", "sbros_avtomata", JSONObject()))
        delay(200)
        assertFalse("s1" in nachaty)
        otpustit["p1"]!!.complete("ok")
        dozhdatsya { itog("s1") == "ok" }
        assertTrue(itog("p1") == "ok")
    }

    @Test
    fun bez_ocheredi_tolko_zhurnalnye() {
        assertEquals(setOf("prishli_zhurnal", "diagnostika"), PultYadro.BEZ_OCHEREDI)
        assertTrue(PultYadro.KOMANDY.containsAll(PultYadro.BEZ_OCHEREDI))
    }
}
