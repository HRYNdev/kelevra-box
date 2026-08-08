package io.nekohasekai.sfa.bg.path

import io.nekohasekai.sfa.bg.OlcRtcCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Таблица «что говорит ядро комнаты → что помнит реестр».
 *
 * Проверяется на JVM, потому что перевод нарочно вынесен из автомата: писать в реестр
 * теперь может и присмотр, который меряет комнату раз в пять секунд, и сам сервис, когда
 * досчитал подъём. Если бы таблица жила в трёх местах, они бы и разошлись — так уже было.
 */
class RoomNoteTest {

    private var now = 5_000_000L

    @Before
    fun setUp() {
        now = 5_000_000L
        PathRegistry.clock = { now }
        PathRegistry.bindExits(main = null, room = null)
        PathRegistry.reset()
    }

    private fun room(): PathState = PathRegistry.snapshot.value[PathId.ROOM]

    @Test
    fun `ядро поднимается — комната поднимается`() {
        RoomNote.note(OlcRtcCore.State.Starting, OlcRtcCore.Health.Unknown)
        assertEquals(PathStatus.Raising, room().status)
    }

    @Test
    fun `ядро встало, а присмотр ещё не мерил — это не отказ`() {
        RoomNote.note(OlcRtcCore.State.Ready, OlcRtcCore.Health.Unknown)
        assertEquals(
            "«поднят» без прошедших байтов — ещё не канал, но и не мертвец",
            PathStatus.Raising,
            room().status,
        )
    }

    @Test
    fun `присмотр намерил живой канал — задержка попала в реестр`() {
        RoomNote.note(OlcRtcCore.State.Ready, OlcRtcCore.Health.Live(latencyMs = 210))
        assertEquals(PathStatus.Alive, room().status)
        assertEquals(210L, room().latencyMs)
        assertEquals(now, room().measuredAt)
    }

    @Test
    fun `присмотр намерил обрыв — реестр говорит про отказ той же секундой`() {
        RoomNote.note(OlcRtcCore.State.Ready, OlcRtcCore.Health.Live(latencyMs = 210))
        now = 5_010_000L
        RoomNote.note(OlcRtcCore.State.Ready, OlcRtcCore.Health.Dead("данных нет"))
        assertEquals(PathStatus.Dead, room().status)
        assertEquals("данных нет", room().reason)
        assertNull("у мёртвого пути задержки нет", room().latencyMs)
    }

    @Test
    fun `обрыв держит время первого отказа, а не последней проверки`() {
        RoomNote.note(OlcRtcCore.State.Ready, OlcRtcCore.Health.Dead("данных нет"))
        val first = room().measuredAt
        now = 5_030_000L
        RoomNote.note(OlcRtcCore.State.Ready, OlcRtcCore.Health.Dead("данных нет"))
        assertEquals("человеку нужно «не отвечает с 12:31», а не «с этой секунды»", first, room().measuredAt)
        assertEquals(2, room().failures)
    }

    @Test
    fun `подъём не удался — это отказ с причиной`() {
        RoomNote.note(OlcRtcCore.State.Failed("нога не отвечает"), OlcRtcCore.Health.Unknown)
        assertEquals(PathStatus.Dead, room().status)
        assertEquals("нога не отвечает", room().reason)
    }

    @Test
    fun `ядра в сборке нет — путь недоступен, а не мёртв`() {
        RoomNote.note(OlcRtcCore.State.Unavailable, OlcRtcCore.Health.Unknown)
        assertEquals(PathStatus.Unavailable, room().status)
    }

    @Test
    fun `комнату не поднимали — так и записано, без вранья про отказ`() {
        RoomNote.note(OlcRtcCore.State.Idle, OlcRtcCore.Health.Unknown)
        assertEquals(PathStatus.Unknown, room().status)
        assertEquals(Evidence.Never, room().evidence)
    }

    @Test
    fun `здоровье прошлой сессии не воскрешает погашенную комнату`() {
        RoomNote.note(OlcRtcCore.State.Ready, OlcRtcCore.Health.Live(latencyMs = 100))
        // Ядро погасили, а последнее здоровье так и лежит «живым» — читать его нельзя.
        RoomNote.note(OlcRtcCore.State.Idle, OlcRtcCore.Health.Live(latencyMs = 100))
        assertEquals(PathStatus.Unknown, room().status)
        assertNull(room().latencyMs)
    }

    @Test
    fun `присмотр поднимает заново — на круге это подъём, а не «не проверяли»`() {
        RoomNote.note(OlcRtcCore.State.Ready, OlcRtcCore.Health.Dead("данных нет"))
        RoomNote.raising()
        assertEquals(PathStatus.Raising, room().status)
        assertEquals(
            "круг обязан сказать «Поднимаю комнату»",
            "Поднимаю комнату",
            PathWords.headline(
                snapshot = PathRegistry.snapshot.value,
                chosen = PathId.ROOM,
                auto = true,
                manualExit = null,
            ),
        )
    }

    @Test
    fun `оборванная комната на круге не выглядит подключённой`() {
        RoomNote.note(OlcRtcCore.State.Ready, OlcRtcCore.Health.Live(latencyMs = 120))
        assertEquals(
            "Комната",
            PathWords.headline(PathRegistry.snapshot.value, PathId.ROOM, auto = true, manualExit = null),
        )
        RoomNote.note(OlcRtcCore.State.Ready, OlcRtcCore.Health.Dead("данных нет"))
        assertEquals(
            "живой прогон 08.08.2026: комната лежала 83 секунды, а круг писал «Подключено»",
            "Комната не отвечает",
            PathWords.headline(PathRegistry.snapshot.value, PathId.ROOM, auto = true, manualExit = null),
        )
    }
}
