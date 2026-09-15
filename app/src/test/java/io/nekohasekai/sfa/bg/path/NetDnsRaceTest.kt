package io.nekohasekai.sfa.bg.path

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Опрос резолверов разом. Беда, ради которой это написано: резолверы сети спрашивались по
 * очереди, первый молчал и забирал весь бюджет, а второй, который ответил бы сразу, не
 * спрашивался вовсе.
 *
 * Резолверы здесь игрушечные: строка-ответ через заданное время или молчание до конца
 * своего бюджета, ровно как сокет с `soTimeout`.
 */
class NetDnsRaceTest {

    private fun answers(after: Long, value: String): (Long) -> String = {
        Thread.sleep(after)
        value
    }

    /** Молчит, пока не выйдет весь отданный ему бюджет, — как резолвер, до которого не доходит запрос. */
    private val mute: (Long) -> String = { budget ->
        runCatching { Thread.sleep(budget) }
        "молчит"
    }

    private fun silentAfter(after: Long): (Long) -> String = {
        Thread.sleep(after)
        "молчит"
    }

    private fun isAnswer(value: String) = value != "молчит"

    private fun timed(block: () -> Unit): Long {
        val startedAt = System.nanoTime()
        block()
        return (System.nanoTime() - startedAt) / 1_000_000
    }

    // ------------------------------------------------------------ опрос резолверов

    @Test
    fun `первый молчит весь бюджет — второй всё равно спрошен и его ответ побеждает`() {
        lateinit var result: NetDnsRace.Result<String>
        val took = timed {
            result = NetDnsRace.first(2_500, listOf(mute, answers(50, "198.18.3.9")), ::isAnswer)
        }
        assertEquals("198.18.3.9", result.winner)
        assertTrue("ответ второго не должен ждать молчание первого: $took мс", took < 1_500)
    }

    @Test
    fun `молчание одного не закрывает опрос`() {
        val result = NetDnsRace.first(2_000, listOf(silentAfter(10), answers(150, "ответ")), ::isAnswer)
        assertEquals("ответ", result.winner)
        assertEquals(listOf("молчит"), result.silences)
    }

    @Test
    fun `побеждает первый ответ, а не последний`() {
        lateinit var result: NetDnsRace.Result<String>
        val took = timed {
            result = NetDnsRace.first(3_000, listOf(answers(1_000, "поздний"), answers(30, "ранний")), ::isAnswer)
        }
        assertEquals("ранний", result.winner)
        assertTrue("опрос обязан закрыться первым ответом: $took мс", took < 800)
    }

    @Test
    fun `промолчали все раньше срока — возвращаемся раньше срока`() {
        lateinit var result: NetDnsRace.Result<String>
        val took = timed {
            result = NetDnsRace.first(5_000, listOf(silentAfter(20), silentAfter(40)), ::isAnswer)
        }
        assertNull(result.winner)
        assertEquals(2, result.silences.size)
        assertEquals(0, result.unfinished)
        assertTrue("досиживать бюджет незачем: $took мс", took < 2_000)
    }

    @Test
    fun `никто не ответил за бюджет — молчание, и бюджет общий, а не на каждого`() {
        lateinit var result: NetDnsRace.Result<String>
        val took = timed {
            result = NetDnsRace.first(300, listOf(mute, mute, mute), ::isAnswer)
        }
        assertNull(result.winner)
        assertTrue("три молчуна по очереди стоили бы 900 мс: $took мс", took < 800)
    }

    @Test
    fun `каждый получает весь бюджет`() {
        val seen = Collections.synchronizedList(mutableListOf<Long>())
        val ask: (Long) -> String = { budget ->
            seen += budget
            "молчит"
        }
        NetDnsRace.first(2_500, listOf(ask, ask), ::isAnswer)
        assertEquals(listOf(2_500L, 2_500L), seen.toList())
    }

    @Test
    fun `источник бросил — это молчание, ответ другого всё равно берём`() {
        val broken: (Long) -> String = { throw IllegalStateException("сеть недостижима") }
        val result = NetDnsRace.first(2_000, listOf(broken, answers(50, "ответ")), ::isAnswer)
        assertEquals("ответ", result.winner)
    }

    @Test
    fun `спрашивать некого — сразу молчание`() {
        val result = NetDnsRace.first(2_000, emptyList<(Long) -> String>(), ::isAnswer)
        assertNull(result.winner)
    }

    // ------------------------------------------------------------ основной и запасной путь

    @Test
    fun `свой путь ответил — запасной не берём и снимаем`() {
        val cancelled = AtomicBoolean(false)
        val (own, backup) = NetDnsRace.withBackup(
            budgetMillis = 2_000,
            primary = { "свой" },
            primaryAnswered = ::isAnswer,
            backup = answers(0, "системный"),
            cancelBackup = { cancelled.set(true) },
        )
        assertEquals("свой", own)
        assertNull(backup)
        assertTrue(cancelled.get())
    }

    @Test
    fun `свой путь промолчал — запасной уже отработал рядом, второго бюджета не ждём`() {
        lateinit var outcome: Pair<String, String?>
        val took = timed {
            outcome = NetDnsRace.withBackup(
                budgetMillis = 1_000,
                primary = { Thread.sleep(900); "молчит" },
                primaryAnswered = ::isAnswer,
                backup = answers(800, "системный"),
            )
        }
        assertEquals("молчит", outcome.first)
        assertEquals("системный", outcome.second)
        // По очереди это стоило бы 900 + 800 мс.
        assertTrue("запасной должен идти параллельно: $took мс", took < 1_500)
    }

    @Test
    fun `быстрый запасной не перебивает свой путь`() {
        val (own, backup) = NetDnsRace.withBackup(
            budgetMillis = 2_000,
            primary = { Thread.sleep(200); "свой" },
            primaryAnswered = ::isAnswer,
            backup = answers(0, "системный"),
        )
        assertEquals("свой", own)
        assertNull(backup)
    }

    @Test
    fun `оба промолчали — запасной ждём не дольше общего бюджета`() {
        lateinit var outcome: Pair<String, String?>
        val took = timed {
            outcome = NetDnsRace.withBackup(
                budgetMillis = 400,
                primary = { "молчит" },
                primaryAnswered = ::isAnswer,
                backup = { budget -> Thread.sleep(budget * 5); "поздно" },
            )
        }
        assertNull(outcome.second)
        assertTrue("общий бюджет 400 мс: $took мс", took < 1_200)
    }
}
