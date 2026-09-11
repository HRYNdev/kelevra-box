package io.nekohasekai.sfa.bg

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
 * Разбор списка соединений ядра: адрес → имя сайта.
 *
 * Повод. В строке отказа ядро печатает только адрес, и ось «сайт» в журнале телефона
 * отсутствовала. Диагноз 10.09.2026 приходилось начинать с обратного запроса руками:
 * сначала выяснять, что 2a02:6b8:a::a — это yandex.ru.
 */
class ImenaSaytovTest {
    @After
    fun posle() = ImenaSaytov.zabyt()

    @Test
    fun imya_beretsya_iz_metadata() {
        ImenaSaytov.razobrat(
            """
            {"connections":[
              {"metadata":{"host":"yandex.ru","destinationIP":"2a02:6b8:a::a"}},
              {"metadata":{"host":"mc.yandex.ru","destinationIP":"77.88.55.60"}}
            ]}
            """.trimIndent(),
        )
        assertEquals("yandex.ru", ImenaSaytov.imya("2a02:6b8:a::a"))
        assertEquals("mc.yandex.ru", ImenaSaytov.imya("77.88.55.60"))
    }

    @Test
    fun bez_imeni_ne_vydumyvaem() {
        // Пустой host — соединение шло по адресу, имени нет. Врать в журнал нельзя:
        // выдуманное имя хуже отсутствующего, по нему поставят неверный диагноз.
        ImenaSaytov.razobrat("""{"connections":[{"metadata":{"host":"","destinationIP":"1.2.3.4"}}]}""")
        assertEquals("", ImenaSaytov.imya("1.2.3.4"))
        assertEquals(0, ImenaSaytov.skolkoPomnim())
    }

    @Test
    fun pustoy_i_bityy_otvet_ne_valyat() {
        ImenaSaytov.razobrat("")
        ImenaSaytov.razobrat("{}")
        ImenaSaytov.razobrat("""{"connections":[]}""")
        assertEquals(0, ImenaSaytov.skolkoPomnim())
    }

    @Test
    fun zapominaetsya_i_podmennyy_adres() {
        // При подмене адресов ядро называет оба: по какому пошли и какой отдал резолвер.
        ImenaSaytov.razobrat(
            """{"connections":[{"metadata":{"host":"ya.ru","destinationIP":"198.18.0.7","remoteDestination":"5.255.255.242"}}]}""",
        )
        assertEquals("ya.ru", ImenaSaytov.imya("198.18.0.7"))
        assertEquals("ya.ru", ImenaSaytov.imya("5.255.255.242"))
    }

    /**
     * Минимальный HTTP/1.0-сервер на голом ServerSocket (com.sun.net.httpserver в
     * android.jar не резолвится — juнит-модуль компилируется против Android SDK
     * stub'ов, а не полного desktop JDK). Слушает 127.0.0.1:9090 — ровно тот адрес,
     * что зашит в ImenaSaytov.ADRES — так что клиент говорит с настоящим TCP
     * собеседником, а не с имитацией внутри процесса.
     */
    private fun podnyatServer(telo: AtomicReference<String>): Pair<ServerSocket, Thread> {
        val socket = ServerSocket(9090)
        val poток = Thread {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: Exception) { break }
                Thread {
                    client.use { c ->
                        runCatching {
                            c.getInputStream().bufferedReader().readLine() // request line, выбрасываем
                            val body = telo.get().toByteArray(StandardCharsets.UTF_8)
                            val out = c.getOutputStream()
                            out.write(
                                (
                                    "HTTP/1.0 200 OK\r\n" +
                                        "Content-Type: application/json\r\n" +
                                        "Content-Length: ${body.size}\r\n" +
                                        "Connection: close\r\n\r\n"
                                    ).toByteArray(StandardCharsets.US_ASCII),
                            )
                            out.write(body)
                            out.flush()
                        }
                    }
                }.start()
            }
        }
        поток.isDaemon = true
        поток.start()
        return socket to поток
    }

    /**
     * ДОКАЗАТЕЛЬСТВО БЕДЫ (разбор 12.09.2026, ветка telemetriya-korobki-razbor).
     *
     * Сценарий из самого же комментария в ImenaSaytov.kt: «отказов бывает триста за
     * семь минут». Поднимаем НАСТОЯЩИЙ TCP-сервер на 127.0.0.1:9090.
     *
     * Первый отказ (адрес A) корректно получает имя — кэш пуст, запрос уходит.
     * Тут же (в пределах тех же 5000 мс, NE_CHASHCHE_MS) второй отказ на ДРУГОЙ адрес
     * (B) — сервер прямо сейчас корректно его знает (список соединений уже обновлён),
     * но клиент к серверу не пойдёт: троттлинг режет ЛЮБОЙ повторный запрос по времени,
     * а не по тому, есть ли новое. Итог: imya("B") = "", хотя сервер отдаёт верный ответ.
     *
     * Через 5100 мс тот же адрес B ДОЛЖЕН начать резолвиться — это отделяет «троттлинг
     * временно скрыл» от «сломано навсегда».
     */
    @Test
    fun burst_vtoroe_imya_v_okne_5s_propadaet_hotya_yadro_ego_uzhe_znaet() {
        val telo = AtomicReference(
            """{"connections":[{"metadata":{"host":"a.example","destinationIP":"10.0.0.1"}}]}""",
        )
        val (socket, thread) = podnyatServer(telo)
        try {
            // Первый отказ: кэш пуст, запрос реально уходит на сервер и получает имя.
            assertEquals("a.example", ImenaSaytov.imya("10.0.0.1"))

            // Тут же (burst) ядро "узнало" про второй адрес — сервер это уже отдаёт.
            telo.set(
                """{"connections":[
                    {"metadata":{"host":"a.example","destinationIP":"10.0.0.1"}},
                    {"metadata":{"host":"b.example","destinationIP":"10.0.0.2"}}
                ]}""",
            )

            // Но клиент не спросит снова 5 секунд — вторая жертва burst'а теряет имя.
            assertEquals("", ImenaSaytov.imya("10.0.0.2"))

            // После окна троттлинга тот же адрес резолвится — подтверждает причину.
            Thread.sleep(5100)
            assertEquals("b.example", ImenaSaytov.imya("10.0.0.2"))
        } finally {
            runCatching { socket.close() }
            runCatching { thread.interrupt() }
        }
    }
}
