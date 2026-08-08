package io.nekohasekai.sfa.bg.path

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Проверки общей памяти о путях: переходы статусов и неизменяемость снимка.
 *
 * Реестр нарочно не знает ни про Android, ни про сеть, поэтому проверяется на JVM целиком,
 * а не глазами по логу. Часы подменяются: «не отвечает с 12:31» и «40 секунд назад» —
 * это про время, и проверять их по настоящим часам нельзя.
 */
class PathRegistryTest {

    private var now = 1_000_000L

    @Before
    fun setUp() {
        now = 1_000_000L
        PathRegistry.clock = { now }
        // Имена выходов [reset] нарочно переживает — они из конфига, а не из наблюдений.
        // Значит соседний тест их бы и унаследовал: снимаем явно.
        PathRegistry.bindExits(main = null, room = null)
        PathRegistry.reset()
    }

    private fun path(id: PathId): PathState = PathRegistry.snapshot.value[id]

    // ------------------------------------------------------------ переходы статусов

    @Test
    fun `новый реестр не знает ничего и не выдумывает`() {
        val snapshot = PathRegistry.snapshot.value
        PathId.values().forEach { id ->
            assertEquals("про $id знать нечего", PathStatus.Unknown, snapshot[id].status)
            assertEquals(Evidence.Never, snapshot[id].evidence)
            assertNull(snapshot[id].latencyMs)
            assertEquals("не мерили — значит и времени замера нет", 0L, snapshot[id].measuredAt)
        }
    }

    @Test
    fun `начали мерить — так и записано`() {
        PathRegistry.probing(PathId.MAIN)
        assertEquals(PathStatus.Probing, path(PathId.MAIN).status)
        // Соседей проба не трогает: реестр помнит пути по отдельности.
        assertEquals(PathStatus.Unknown, path(PathId.ROOM).status)
    }

    @Test
    fun `путь ожил — задержка и время замера записаны, подтверждено пробой`() {
        PathRegistry.probing(PathId.MAIN)
        now = 1_200_000L
        PathRegistry.alive(PathId.MAIN, latencyMs = 210)

        val main = path(PathId.MAIN)
        assertEquals(PathStatus.Alive, main.status)
        assertEquals(210L, main.latencyMs)
        assertEquals(1_200_000L, main.measuredAt)
        assertEquals(Evidence.Probe, main.evidence)
        assertTrue(main.usable)
        assertFalse(main.refused)
    }

    @Test
    fun `подсказка живого пути задержку не выдумывает`() {
        // Открытый порт говорит «рукопожатие прошло» и ровно ничего — про время ответа
        // всего пути. Показать тут число значило бы выдать догадку за замер.
        PathRegistry.alive(PathId.MAIN, evidence = Evidence.Hint)
        assertEquals(PathStatus.Alive, path(PathId.MAIN).status)
        assertNull(path(PathId.MAIN).latencyMs)
        assertEquals(Evidence.Hint, path(PathId.MAIN).evidence)
    }

    @Test
    fun `прошлая задержка не переезжает в новый успех без замера`() {
        PathRegistry.alive(PathId.MAIN, latencyMs = 210)
        PathRegistry.alive(PathId.MAIN, evidence = Evidence.Hint)
        assertNull("210 мс мерили в прошлый раз и про этот не говорят", path(PathId.MAIN).latencyMs)
    }

    @Test
    fun `отказы считаются подряд, а время первого отказа не переписывается`() {
        PathRegistry.dead(PathId.MAIN, "ответа нет")
        val firstFailure = path(PathId.MAIN).measuredAt

        now += 60_000L
        PathRegistry.dead(PathId.MAIN, "ответа нет")
        now += 60_000L
        PathRegistry.dead(PathId.MAIN, "ответа нет")

        val main = path(PathId.MAIN)
        assertEquals(3, main.failures)
        // Человеку нужно «не отвечает с 12:31», а не «не отвечает с этой секунды»:
        // иначе отказ вечно выглядит свежим, и понять, лежит канал минуту или час, нельзя.
        assertEquals(firstFailure, main.measuredAt)
        assertTrue(main.refused)
        assertFalse(main.usable)
        assertNull("мёртвый путь задержки не имеет", main.latencyMs)
    }

    @Test
    fun `первый же успех обнуляет счёт отказов`() {
        repeat(4) { PathRegistry.dead(PathId.MAIN, "ответа нет") }
        assertEquals(4, path(PathId.MAIN).failures)

        PathRegistry.alive(PathId.MAIN, latencyMs = 180)
        assertEquals(0, path(PathId.MAIN).failures)
        assertNull("причина отказа больше не про этот путь", path(PathId.MAIN).reason)
    }

    @Test
    fun `подъём помнит, когда начался, и повторный вызов его не сбрасывает`() {
        PathRegistry.raising(PathId.ROOM)
        val startedAt = path(PathId.ROOM).raisingSince
        assertEquals(1_000_000L, startedAt)

        now += 30_000L
        PathRegistry.raising(PathId.ROOM)
        assertEquals(
            "иначе «поднимается вторую минуту» не отличить от «поднимается вторую секунду»",
            startedAt,
            path(PathId.ROOM).raisingSince,
        )
        assertEquals(PathStatus.Raising, path(PathId.ROOM).status)
        assertFalse("подъём — это не отказ", path(PathId.ROOM).refused)
        assertFalse("и не работа", path(PathId.ROOM).usable)
    }

    @Test
    fun `успех после подъёма забывает, что поднимались`() {
        PathRegistry.raising(PathId.ROOM)
        PathRegistry.alive(PathId.ROOM, latencyMs = 640)
        assertEquals(0L, path(PathId.ROOM).raisingSince)
    }

    @Test
    fun `сети нет — недоступны все пути сразу и по одной причине`() {
        PathRegistry.alive(PathId.MAIN, latencyMs = 210)
        PathRegistry.allUnavailable("сети нет")

        PathId.values().forEach { id ->
            assertEquals(PathStatus.Unavailable, path(id).status)
            assertEquals("сети нет", path(id).reason)
            assertTrue("недоступен — это ответ, а не молчание", path(id).refused)
            assertNull(path(id).latencyMs)
        }
    }

    @Test
    fun `не проверяли — это не отказ`() {
        PathRegistry.dead(PathId.MAIN, "ответа нет")
        PathRegistry.unchecked(PathId.MAIN, "дома обход делает роутер")

        val main = path(PathId.MAIN)
        assertEquals(PathStatus.Unknown, main.status)
        assertFalse("иначе экран напишет «не отвечает» про то, что никто не спрашивал", main.refused)
        assertEquals(0, main.failures)
        assertEquals(0L, main.measuredAt)
        assertEquals("дома обход делает роутер", main.reason)
    }

    @Test
    fun `остывание помнит срок и не трогает статус`() {
        PathRegistry.dead(PathId.ROOM, "комната не встала")
        PathRegistry.coolDown(PathId.ROOM, now + 600_000L)

        assertEquals(now + 600_000L, path(PathId.ROOM).coolingUntil)
        assertEquals("реестр помнит срок, но никого никуда не пускает", PathStatus.Dead, path(PathId.ROOM).status)
    }

    @Test
    fun `забыть всё — значит вернуться к незнанию, а не к последнему хорошему`() {
        PathRegistry.alive(PathId.MAIN, latencyMs = 210)
        PathRegistry.dead(PathId.ROOM, "комната не встала")
        PathRegistry.reset()

        PathId.values().forEach { assertEquals(PathStatus.Unknown, path(it).status) }
    }

    // ------------------------------------------------------------ раскладка выходов

    @Test
    fun `имена выходов приходят позже путей и находят свои`() {
        // Раскладку отдаёт работающее ядро, а помнить про пути надо и до него.
        assertNull(path(PathId.MAIN).def.exitTag)

        PathRegistry.bindExits(main = "Нидерланды", room = "Комната")

        assertEquals("Нидерланды", path(PathId.MAIN).def.exitTag)
        assertEquals(PathId.MAIN, PathRegistry.snapshot.value.byExit("Нидерланды")?.def?.id)
        assertEquals(PathId.ROOM, PathRegistry.snapshot.value.byExit("Комната")?.def?.id)
        assertNull("чужой выход — не наш путь", PathRegistry.snapshot.value.byExit("Германия"))
        assertNull(PathRegistry.snapshot.value.byExit(null))
        assertNull(PathRegistry.snapshot.value.byExit(""))
    }

    @Test
    fun `забыть намеренное — не значит разучиться узнавать комнату`() {
        // Имя выхода приходит из конфига, а не из наблюдений. Если стирать его вместе
        // с замерами, экран перестанет узнавать комнату ровно в тот момент, когда её
        // выбрали руками: [reset] зовётся именно там.
        PathRegistry.bindExits(main = "Нидерланды", room = "Комната")
        PathRegistry.alive(PathId.ROOM, latencyMs = 640)

        PathRegistry.reset()

        assertEquals(PathStatus.Unknown, path(PathId.ROOM).status)
        assertEquals("Комната", path(PathId.ROOM).def.exitTag)
        assertEquals(PathId.ROOM, PathRegistry.snapshot.value.byExit("Комната")?.def?.id)
    }

    @Test
    fun `раскладка не стирает того, что уже знали про путь`() {
        PathRegistry.alive(PathId.MAIN, latencyMs = 210)
        PathRegistry.bindExits(main = "Нидерланды", room = "Комната")

        assertEquals(PathStatus.Alive, path(PathId.MAIN).status)
        assertEquals(210L, path(PathId.MAIN).latencyMs)
    }

    // ------------------------------------------------------------ неизменяемость снимка

    @Test
    fun `снимок на руках не меняется под читателем`() {
        val before = PathRegistry.snapshot.value
        assertEquals(PathStatus.Unknown, before[PathId.MAIN].status)

        PathRegistry.dead(PathId.MAIN, "ответа нет")

        assertEquals("снимок — это срез, а не окно", PathStatus.Unknown, before[PathId.MAIN].status)
        assertEquals(PathStatus.Dead, PathRegistry.snapshot.value[PathId.MAIN].status)
        assertNotSame(before, PathRegistry.snapshot.value)
    }

    @Test
    fun `без правок снимок не пересобирается`() {
        assertSame(PathRegistry.snapshot.value, PathRegistry.snapshot.value)
    }

    @Test
    fun `испортить снимок снаружи нечем`() {
        PathRegistry.alive(PathId.MAIN, latencyMs = 210)
        val snapshot = PathRegistry.snapshot.value

        // Список путей отдаётся копией: что бы с ним ни сделали, память не пострадает.
        val all = snapshot.all.toMutableList()
        all.clear()

        assertEquals(PathId.values().size, snapshot.all.size)
        assertEquals(PathStatus.Alive, snapshot[PathId.MAIN].status)
    }

    @Test
    fun `пути в снимке идут от самого приятного к самому дорогому`() {
        val order = PathRegistry.snapshot.value.all.map { it.def.id }
        assertEquals(listOf(PathId.HOME, PathId.MAIN, PathId.ROOM), order)
        // Ранг — класс качества, а не место в очереди: промежутки оставлены под новые пути.
        assertTrue(PathDef.RANK_HOME < PathDef.RANK_MAIN)
        assertTrue(PathDef.RANK_MAIN < PathDef.RANK_ROOM)
        assertTrue("между рангами должно быть место", PathDef.RANK_MAIN - PathDef.RANK_HOME > 1)
    }

    @Test
    fun `снимок отвечает про всё сразу, не заставляя перебирать пути руками`() {
        PathRegistry.probing(PathId.HOME)
        assertTrue(PathRegistry.snapshot.value.anyIs(PathStatus.Probing))
        assertFalse(PathRegistry.snapshot.value.any { it.usable })

        PathRegistry.alive(PathId.HOME)
        assertFalse(PathRegistry.snapshot.value.anyIs(PathStatus.Probing))
        assertTrue(PathRegistry.snapshot.value.any { it.usable })
    }
}
