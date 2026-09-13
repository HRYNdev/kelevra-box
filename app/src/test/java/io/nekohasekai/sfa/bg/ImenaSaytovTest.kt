package io.nekohasekai.sfa.bg

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
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
    private fun podnyatServer(
        telo: AtomicReference<String>,
        zaprosov: AtomicInteger = AtomicInteger(0),
    ): Pair<ServerSocket, Thread> {
        val socket = ServerSocket(9090)
        val поток = Thread {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: Exception) { break }
                Thread {
                    client.use { c ->
                        runCatching {
                            zaprosov.incrementAndGet()
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
     * (B) — сервер прямо сейчас корректно его знает (список соединений уже обновлён).
     * Правильное поведение: клиент обязан спросить снова, потому что B — НОВЫЙ адрес,
     * которого троттлинг ещё не видел. На сломанном коде глобальная метка времени режет
     * этот запрос так же, как повтор по A, и imya("B") возвращает "", хотя сервер прямо
     * сейчас отдаёт верный ответ — тест по этой строке падает до правки.
     *
     * Через 5100 мс тот же адрес B по-прежнему резолвится — троттлинг не сломан насовсем.
     */
    @Test
    fun burst_vtoroe_imya_v_okne_5s_dolzhno_resolvitsya_a_ne_propast() {
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

            // Новый адрес в том же окне ДОЛЖЕН резолвиться — троттлинг не про него.
            assertEquals("b.example", ImenaSaytov.imya("10.0.0.2"))

            // И спустя окно тот же адрес по-прежнему резолвится (уже из кэша или заново).
            Thread.sleep(5100)
            assertEquals("b.example", ImenaSaytov.imya("10.0.0.2"))
        } finally {
            runCatching { socket.close() }
            runCatching { thread.interrupt() }
        }
    }

    /**
     * КОНТРОЛЬ ПУСТОТОЙ: троттлинг не должен исчезнуть вместе с багом.
     *
     * Повторный промах по ОДНОМУ И ТОМУ ЖЕ адресу в пределах окна 5с не должен уходить
     * к ядру второй раз — иначе «починка» — это просто снятая защита, а не адресный
     * троттлинг. Считаем реальные TCP-запросы к серверу счётчиком zaprosov.
     */
    @Test
    fun povtor_po_tomu_zhe_adresu_v_okne_ne_dolbit_yadro() {
        val telo = AtomicReference(
            """{"connections":[{"metadata":{"host":"a.example","destinationIP":"10.0.0.1"}}]}""",
        )
        val zaprosov = AtomicInteger(0)
        val (socket, thread) = podnyatServer(telo, zaprosov)
        try {
            assertEquals("a.example", ImenaSaytov.imya("10.0.0.1"))
            assertEquals(1, zaprosov.get())

            // Тот же адрес ещё раз, тут же — кэш уже есть, к ядру идти не должны вовсе.
            assertEquals("a.example", ImenaSaytov.imya("10.0.0.1"))
            assertEquals(1, zaprosov.get())

            // Моделируем обычное LRU-вытеснение: адрес выпал из kesh, а троттлинг про
            // него ещё помнит — второй промах на ТОТ ЖЕ адрес не должен дойти до ядра.
            ImenaSaytov.zabytKeshAdresa("10.0.0.1")
            assertEquals("", ImenaSaytov.imya("10.0.0.1"))
            assertEquals(1, zaprosov.get())
        } finally {
            runCatching { socket.close() }
            runCatching { thread.interrupt() }
        }
    }

    /**
     * Немой сервер: принимает TCP-соединение и НИКОГДА не отвечает — держит его до
     * readTimeout клиента (1500 мс из ImenaSaytov.prochitat). Моделирует «ядро не
     * отвечает», а не «ядро упало сразу» (мгновенный connection refused не покажет
     * беды — залипание именно на самом чтении ответа).
     */
    private fun podnyatNemoyServer(zaprosov: AtomicInteger): Pair<ServerSocket, Thread> {
        val socket = ServerSocket(9090)
        val поток = Thread {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: Exception) { break }
                zaprosov.incrementAndGet()
                Thread {
                    client.use { c ->
                        runCatching {
                            c.getInputStream().bufferedReader().readLine() // забрали запрос
                            Thread.sleep(3000) // и просто молчим дольше клиентского readTimeout
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
     * ДОКАЗАТЕЛЬСТВО РЕГРЕССА (приёмка PR #10, 12.09.2026, ветка telemetriya-korobki-razbor).
     *
     * До правки адресный троттлинг был и предохранителем: одна метка на всё приложение
     * резала обращения к ядру не чаще раза в NE_CHASHCHE_MS, что бы ни случилось. После
     * переезда на карту `sprashivaliPoAdresu` предохранитель исчез — каждый НОВЫЙ адрес
     * идёт в prochitat() всегда, потолка на их число нет. Если ядро при этом молчит
     * (`connectTimeout`/`readTimeout` = 1500 мс), пачка из 20 разных адресов даёт до
     * 20 реальных синхронных попыток и ~20×1.5с блокировки на @Synchronized-мониторе —
     * вместо одного промаха в 5с, как было раньше.
     *
     * До починки: zaprosov ~20, elapsed ~20×1.5с (десятки секунд).
     * После починки: первая попытка ставит ГЛОБАЛЬНЫЙ отбой на неудачу — остальные 19
     * новых адресов в то же окно к ядру не идут вовсе.
     */
    @Test
    fun nemoe_yadro_ne_dolbim_na_kazhdyy_novyy_adres() {
        val zaprosov = AtomicInteger(0)
        val (socket, thread) = podnyatNemoyServer(zaprosov)
        try {
            val start = System.currentTimeMillis()
            for (i in 0 until 20) {
                ImenaSaytov.imya("10.9.0.$i")
            }
            val elapsed = System.currentTimeMillis() - start
            assertTrue(
                "ожидали не больше 2 реальных попыток к немому ядру, было ${zaprosov.get()}",
                zaprosov.get() <= 2,
            )
            assertTrue(
                "ожидали меньше 3000 мс на 20 новых адресов, было $elapsed",
                elapsed < 3000,
            )
        } finally {
            runCatching { socket.close() }
            runCatching { thread.interrupt() }
        }
    }
}
