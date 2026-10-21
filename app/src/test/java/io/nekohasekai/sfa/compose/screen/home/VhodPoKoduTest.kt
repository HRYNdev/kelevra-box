package io.nekohasekai.sfa.compose.screen.home

import io.nekohasekai.sfa.compose.screen.home.VhodPoKodu.Sohraneno
import io.nekohasekai.sfa.compose.screen.home.VhodPoKodu.Sostoyanie
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class VhodPoKoduTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Сохранение ждёт [vorota]: так тест держит вход «идущим», сколько нужно. */
    private val vorota = CompletableDeferred<Unit>()
    private val sohraneniya = Collections.synchronizedList(mutableListOf<String>())
    private val primeneniya = Collections.synchronizedList(mutableListOf<Long>())
    private val nachatoSohraneniy = AtomicInteger()
    private var otvet: () -> Sohraneno = { Sohraneno(7, uzheVybran = false) }
    private var primenenoSignal = CompletableDeferred<Unit>()

    private val vhod = VhodPoKodu(
        scope = scope,
        sohranit = { vvod ->
            nachatoSohraneniy.incrementAndGet()
            vorota.await()
            val s = otvet()
            sohraneniya += vvod
            s
        },
        primenit = { s ->
            primeneniya += s.profilId
            primenenoSignal.complete(Unit)
        },
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun zhdat(uslovie: (Sostoyanie) -> Boolean): Sostoyanie = runBlocking {
        withTimeout(5000) { vhod.sostoyanie.first(uslovie) }
    }

    private fun zhdatPrimeneniya() = runBlocking { withTimeout(5000) { primenenoSignal.await() } }

    @Test
    fun vtorojVhodVoVremyaPervogoNeStartuet() {
        vhod.ekranOtkryt()
        assertTrue(vhod.nachat("AAAA-BBBB"))
        assertFalse(vhod.nachat("AAAA-BBBB"))
        assertFalse(vhod.nachat("CCCC-DDDD"))
        assertEquals(Sostoyanie.Idet("AAAA-BBBB"), vhod.sostoyanie.value)

        vorota.complete(Unit)
        val gotovo = zhdat { it is Sostoyanie.Gotovo } as Sostoyanie.Gotovo
        assertTrue(gotovo.primeneno)
        zhdatPrimeneniya()
        assertEquals(1, nachatoSohraneniy.get())
        assertEquals(listOf("AAAA-BBBB"), sohraneniya.toList())
        assertEquals(listOf(7L), primeneniya.toList())
    }

    @Test
    fun ekranUshelProfilSohranenNoNePrimenenDoVozvrata() {
        vhod.ekranOtkryt()
        vhod.nachat("AAAA-BBBB")
        vhod.ekranZakryt()
        assertTrue(vhod.sostoyanie.value is Sostoyanie.Idet)

        vorota.complete(Unit)
        val gotovo = zhdat { it is Sostoyanie.Gotovo } as Sostoyanie.Gotovo
        assertEquals(listOf("AAAA-BBBB"), sohraneniya.toList())
        assertFalse(gotovo.primeneno)
        assertTrue(primeneniya.isEmpty())

        // Человек вернулся на экран: вход применяется при нём, один раз.
        vhod.ekranOtkryt()
        zhdatPrimeneniya()
        assertEquals(Sostoyanie.Gotovo(Sohraneno(7, false), primeneno = true), vhod.sostoyanie.value)
        vhod.ekranZakryt()
        vhod.ekranOtkryt()
        assertEquals(listOf(7L), primeneniya.toList())
        assertEquals(Sostoyanie.Pusto, vhod.sostoyanie.value)
    }

    @Test
    fun ekranUshelVybrannyjProfilPerechityvaetsyaSrazu() {
        otvet = { Sohraneno(3, uzheVybran = true) }
        vhod.nachat("AAAA-BBBB")
        vorota.complete(Unit)
        zhdatPrimeneniya()
        assertEquals(listOf(3L), primeneniya.toList())
        assertEquals(Sostoyanie.Gotovo(Sohraneno(3, true), primeneno = true), vhod.sostoyanie.value)
    }

    @Test
    fun oshibkaDohoditDoSostoyaniya() {
        val prichina = LimitUstroystv("лимит")
        otvet = { throw prichina }
        vhod.ekranOtkryt()
        vhod.nachat("AAAA-BBBB")
        vorota.complete(Unit)
        val oshibka = zhdat { it is Sostoyanie.Oshibka } as Sostoyanie.Oshibka
        assertSame(prichina, oshibka.prichina)
        assertTrue(primeneniya.isEmpty())

        vhod.sbrositOshibku()
        assertEquals(Sostoyanie.Pusto, vhod.sostoyanie.value)
        // После ошибки новый вход снова стартует.
        otvet = { Sohraneno(9, uzheVybran = false) }
        assertTrue(vhod.nachat("CCCC-DDDD"))
        zhdatPrimeneniya()
        assertEquals(listOf(9L), primeneniya.toList())
    }

    @Test
    fun oshibkaBezEkranaZhdyotEgo() {
        otvet = { throw java.io.IOException("двери не ответили") }
        vhod.nachat("AAAA-BBBB")
        vorota.complete(Unit)
        zhdat { it is Sostoyanie.Oshibka }

        // Экрана не было — ошибка ждёт его; увидел и ушёл — сброшена.
        vhod.ekranOtkryt()
        assertTrue(vhod.sostoyanie.value is Sostoyanie.Oshibka)
        vhod.ekranZakryt()
        assertEquals(Sostoyanie.Pusto, vhod.sostoyanie.value)
    }

    @Test
    fun gotovoPriEkraneSbrasyvaetsyaPriUhode() {
        vhod.ekranOtkryt()
        vhod.nachat("AAAA-BBBB")
        vorota.complete(Unit)
        zhdatPrimeneniya()
        zhdat { it is Sostoyanie.Gotovo }
        vhod.ekranZakryt()
        assertEquals(Sostoyanie.Pusto, vhod.sostoyanie.value)
    }
}
