package io.nekohasekai.sfa.bg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Посылка журнала — одна за раз: сошедшиеся работы получают итог идущей, а не шлют свою.
 */
class OdnaZaRazTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `пришедшие во время дела ждут его итог, дело идёт один раз`() = runBlocking {
        val odna = OdnaZaRaz<String>(scope)
        val zapuskov = AtomicInteger()
        val pusk = CompletableDeferred<Unit>()
        val nachalos = CompletableDeferred<Unit>()
        val delo: suspend () -> String = {
            zapuskov.incrementAndGet()
            nachalos.complete(Unit)
            pusk.await()
            "итог"
        }
        val prisoedinilis = AtomicInteger()
        val pervyy = async(Dispatchers.Default) { odna.vypolnit(delo) }
        nachalos.await()
        val ostalnye = (1..2).map {
            async(Dispatchers.Default) { odna.vypolnit(delo) { svoya -> if (!svoya) prisoedinilis.incrementAndGet() } }
        }
        withTimeout(5_000) { while (prisoedinilis.get() < 2) kotlinx.coroutines.delay(10) }
        pusk.complete(Unit)
        assertEquals(listOf("итог", "итог", "итог"), (listOf(pervyy) + ostalnye).map { it.await() })
        assertEquals(1, zapuskov.get())
    }

    @Test
    fun `закончилось — следующий вызов начинает новое`() = runBlocking {
        val odna = OdnaZaRaz<Int>(scope)
        val zapuskov = AtomicInteger()
        assertEquals(1, odna.vypolnit({ zapuskov.incrementAndGet() }))
        assertEquals(2, odna.vypolnit({ zapuskov.incrementAndGet() }))
    }

    @Test
    fun `ошибка дела достаётся ждущему, следующий вызов идёт заново`() = runBlocking {
        val odna = OdnaZaRaz<Int>(scope)
        val upalo = runCatching { odna.vypolnit({ error("сеть") }) }
        assertTrue(upalo.isFailure)
        assertEquals(7, odna.vypolnit({ 7 }))
    }

    @Test
    fun `отмена позвавшего не обрывает дело`() = runBlocking {
        val odna = OdnaZaRaz<Int>(scope)
        val pusk = CompletableDeferred<Unit>()
        val nachalos = CompletableDeferred<Unit>()
        val doshlo = CompletableDeferred<Int>()
        val delo: suspend () -> Int = {
            nachalos.complete(Unit)
            pusk.await()
            doshlo.complete(1)
            1
        }
        val pozvavshiy = launch(Dispatchers.Default) { odna.vypolnit(delo) }
        nachalos.await()
        pozvavshiy.cancelAndJoin()
        pusk.complete(Unit)
        assertEquals(1, withTimeout(5_000) { doshlo.await() })
    }
}
