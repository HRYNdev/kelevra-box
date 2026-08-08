package io.nekohasekai.sfa.bg.path

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Проверки таблицы «снимок → слова».
 *
 * Смысл этих тестов не в красоте формулировок, а в том, что формулировка теперь одна.
 * Пока экран и шторка собирали текст каждый своей цепочкой условий, они расходились на
 * ровном месте: круг писал «Нет сети», шторка в ту же секунду — «Дома, обход на роутере».
 * Таблица общая, значит расхождение можно проверить, а не ловить глазами.
 */
class PathWordsTest {

    private var now = 1_000_000L

    @Before
    fun setUp() {
        now = 1_000_000L
        PathRegistry.clock = { now }
        PathRegistry.reset()
    }

    private fun snapshot(): PathSnapshot = PathRegistry.snapshot.value

    private fun headline(chosen: PathId?, auto: Boolean = true, manualExit: String? = null): String =
        PathWords.headline(snapshot(), chosen, auto, manualExit)

    // ------------------------------------------------------------ состояние одного пути

    @Test
    fun `работает — с задержкой и с тем, когда мерили`() {
        PathRegistry.alive(PathId.MAIN, latencyMs = 210)
        now += 40_000L
        assertEquals("работает, 210 мс, 40 секунд назад", PathWords.state(snapshot()[PathId.MAIN], now))
    }

    @Test
    fun `свежий замер не притворяется старым`() {
        PathRegistry.alive(PathId.MAIN, latencyMs = 210)
        now += 1_000L
        assertEquals("работает, 210 мс, только что", PathWords.state(snapshot()[PathId.MAIN], now))
    }

    @Test
    fun `работает без замера задержки — про задержку молчим`() {
        PathRegistry.alive(PathId.MAIN, evidence = Evidence.Hint)
        now += 10_000L
        assertEquals("работает, 10 секунд назад", PathWords.state(snapshot()[PathId.MAIN], now))
    }

    @Test
    fun `отказ показывает, с какого времени он длится`() {
        PathRegistry.dead(PathId.MAIN, "ответа нет")
        val first = PathWords.state(snapshot()[PathId.MAIN], now)
        // Часовой пояс тут системный, поэтому проверяем форму, а не цифры.
        assertTrue("«$first» должно называть время", first.startsWith("не отвечает с "))
        assertEquals("не отвечает с HH:MM".length, first.length)

        // Через час отказ по-прежнему датируется своим началом, а не последней проверкой:
        // иначе «лежит минуту» и «лежит час» на экране выглядят одинаково.
        now += 3_600_000L
        PathRegistry.dead(PathId.MAIN, "ответа нет")
        assertEquals(first, PathWords.state(snapshot()[PathId.MAIN], now))
    }

    @Test
    fun `поднимается — это не отказ и не работа`() {
        PathRegistry.raising(PathId.ROOM)
        assertEquals("поднимается", PathWords.state(snapshot()[PathId.ROOM], now))
    }

    @Test
    fun `не проверяли — так и говорим`() {
        assertEquals("не проверяли", PathWords.state(snapshot()[PathId.MAIN], now))
        PathRegistry.unchecked(PathId.MAIN, "дома обход делает роутер")
        assertEquals("не проверяли", PathWords.state(snapshot()[PathId.MAIN], now))
    }

    @Test
    fun `меряем прямо сейчас`() {
        PathRegistry.probing(PathId.MAIN)
        assertEquals("проверяю", PathWords.state(snapshot()[PathId.MAIN], now))
    }

    @Test
    fun `недоступный путь объясняет себя причиной`() {
        PathRegistry.unavailable(PathId.ROOM, "ядра комнаты в сборке нет")
        assertEquals("ядра комнаты в сборке нет", PathWords.state(snapshot()[PathId.ROOM], now))
    }

    @Test
    fun `русский счёт не сбивается на двузначных`() {
        PathRegistry.alive(PathId.MAIN)
        fun after(seconds: Long): String {
            now = 1_000_000L + seconds * 1000
            return PathWords.state(snapshot()[PathId.MAIN], now).substringAfter("работает, ")
        }
        assertEquals("21 секунду назад", after(21))
        assertEquals("22 секунды назад", after(22))
        assertEquals("25 секунд назад", after(25))
        assertEquals("11 секунд назад", after(11))
        assertEquals("2 минуты назад", after(125))
        assertEquals("5 минут назад", after(300))
        assertEquals("давно", after(7200))
    }

    // ------------------------------------------------------------ одна строка про всё

    @Test
    fun `сети нет — это важнее любого выхода`() {
        PathRegistry.allUnavailable("сети нет")
        assertEquals("Нет сети", headline(chosen = PathId.MAIN))
        assertEquals("Нет сети", headline(chosen = null, auto = false, manualExit = "Нидерланды"))
    }

    @Test
    fun `дома про выходы не говорим вовсе`() {
        PathRegistry.alive(PathId.HOME)
        PathRegistry.unchecked(PathId.MAIN, "дома обход делает роутер")
        assertEquals(PathWords.HOME, headline(chosen = PathId.HOME))
    }

    @Test
    fun `комната говорит своим состоянием, а не фактом выбора`() {
        PathRegistry.dead(PathId.HOME, "подменных адресов нет")

        PathRegistry.raising(PathId.ROOM)
        assertEquals("Поднимаю комнату", headline(chosen = PathId.ROOM))

        PathRegistry.alive(PathId.ROOM, latencyMs = 640)
        assertEquals("Комната", headline(chosen = PathId.ROOM))

        PathRegistry.dead(PathId.ROOM, "данных нет")
        assertEquals("Комната не отвечает", headline(chosen = PathId.ROOM))
    }

    @Test
    fun `комнату ещё не поднимали — это «поднимаю», а не «не отвечает»`() {
        // Иначе в первые же секунды после выбора человек читает отказ, хотя вход
        // только начался.
        PathRegistry.dead(PathId.HOME, "подменных адресов нет")
        assertEquals("Поднимаю комнату", headline(chosen = PathId.ROOM))
    }

    @Test
    fun `выход выбран человеком — показываем его имя`() {
        PathRegistry.dead(PathId.HOME, "подменных адресов нет")
        assertEquals("Нидерланды", headline(chosen = null, auto = false, manualExit = "Нидерланды"))
    }

    @Test
    fun `основной канал отвечает за себя сам`() {
        PathRegistry.dead(PathId.HOME, "подменных адресов нет")

        PathRegistry.probing(PathId.MAIN)
        assertEquals("Проверяю связь", headline(chosen = PathId.MAIN))

        PathRegistry.alive(PathId.MAIN, latencyMs = 210)
        assertEquals("Выход выбирается сам", headline(chosen = PathId.MAIN))

        // Ровно та обстановка, ради которой всё писалось: автомат ещё стоит на основном
        // канале (подтверждений на смену не набрал), а канал уже мёртв. «Подключено»
        // тут говорить нельзя.
        PathRegistry.dead(PathId.MAIN, "ответа не дождались")
        assertEquals("Связи нет", headline(chosen = PathId.MAIN))
    }

    @Test
    fun `стоять не на чем — ищем путь`() {
        PathRegistry.dead(PathId.HOME, "подменных адресов нет")
        PathRegistry.dead(PathId.MAIN, "ответа не дождались")
        assertEquals("Ищу путь", headline(chosen = null))
    }

    @Test
    fun `ещё ничего не мерили — не обещаем работу`() {
        assertEquals("Проверяю связь", headline(chosen = null))
        PathRegistry.probing(PathId.HOME)
        assertEquals("Проверяю связь", headline(chosen = null))
    }

    @Test
    fun `автомат выключен, а выбора нет — говорим только про сервис`() {
        assertEquals(PathWords.STARTED, headline(chosen = null, auto = false, manualExit = null))
    }

    @Test
    fun `на мёртвом канале «Подключено» не появляется ни в одной строке таблицы`() {
        // Главное требование ко всей затее, поэтому проверяется отдельно и целиком.
        PathRegistry.dead(PathId.HOME, "подменных адресов нет")
        PathRegistry.dead(PathId.MAIN, "ответа не дождались")
        PathRegistry.dead(PathId.ROOM, "данных нет")

        val everything = listOf(null, PathId.HOME, PathId.MAIN, PathId.ROOM).flatMap { chosen ->
            listOf(
                headline(chosen, auto = true),
                headline(chosen, auto = false, manualExit = "Нидерланды"),
            )
        }
        everything.forEach { words ->
            assertFalse("«$words» обещает связь, которой нет", words.contains("Подключено"))
        }
    }
}
