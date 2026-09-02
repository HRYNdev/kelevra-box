package io.nekohasekai.sfa

import io.nekohasekai.sfa.compose.screen.home.DEVICE_ONLINE_WINDOW_MS
import io.nekohasekai.sfa.compose.screen.home.deviceSeenWords
import io.nekohasekai.sfa.compose.screen.home.deviceSinceWords
import io.nekohasekai.sfa.compose.screen.home.deviceTrafficWords
import io.nekohasekai.sfa.compose.screen.home.parseDevices
import io.nekohasekai.sfa.compose.screen.home.parseIsoMillis
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Список устройств подписки.
 *
 * Повод. Сервер отдаёт `devices` всем клиентам, десктоп их показывает с 02.09.2026,
 * а телефон получал список и молча выбрасывал. Теперь показывает и он.
 *
 * Проверяется хрупкое — то, что ломается от чужих данных, а не вёрстка:
 *  1. разбор поля, включая его отсутствие (старый сервер) и битые даты;
 *  2. человеческие строки времени вместе с русскими окончаниями;
 *  3. прочерк вместо «0 Б», когда расход не считался;
 *  4. форматирование объёма.
 * Composable-часть не проверяется: вся арифметика ради этого и вынесена в чистые
 * функции, а Compose без Android тут не поднять.
 */
class SubscriptionDevicesTest {

    private fun json(text: String) = JSONObject(text)

    // ---- разбор поля devices ------------------------------------------------

    @Test
    fun `устройство разбирается целиком`() {
        val devices = parseDevices(
            json(
                """
                {"devices": [
                  {"self": true, "name": "Acme AP-1000", "kind": "phone",
                   "platform": "android", "app_version": "1.14.104",
                   "first_seen": "2026-08-31T08:44:12+00:00",
                   "last_seen": "2026-09-02T14:15:44+00:00",
                   "traffic_bytes": 12400000000}
                ]}
                """.trimIndent()
            )
        )
        assertEquals(1, devices.size)
        val one = devices.first()
        assertTrue("своё устройство помечено", one.self)
        assertEquals("Acme AP-1000", one.name)
        assertEquals("phone", one.kind)
        assertEquals("android", one.platform)
        assertEquals("1.14.104", one.appVersion)
        assertEquals(12_400_000_000L, one.trafficBytes)
        assertEquals(
            "дата с зоной разобрана в момент времени",
            1_788_165_852_000L,
            one.firstSeenMillis,
        )
    }

    /**
     * Старый сервер поля не присылает вовсе, и это законно: пустой список означает
     * «список неизвестен», а экран в этом случае просто остаётся прежним.
     */
    @Test
    fun `поля devices нет — список пуст, а не падение`() {
        assertTrue(parseDevices(json("""{"name": "Пользователь", "active": true}""")).isEmpty())
        assertTrue(parseDevices(json("""{"devices": []}""")).isEmpty())
    }

    @Test
    fun `битая дата не роняет устройство, а только своё время`() {
        val one = parseDevices(
            json(
                """
                {"devices": [
                  {"name": "ноут", "kind": "laptop",
                   "first_seen": "вчера", "last_seen": "2026-13-45T99:99:99+00:00"}
                ]}
                """.trimIndent()
            )
        ).single()
        assertEquals("ноут", one.name)
        assertEquals("нечитаемая дата — это ноль, а не мусорный момент", 0L, one.firstSeenMillis)
        assertEquals(0L, one.lastSeenMillis)
        assertNull("времени нет — строки на экране тоже нет", deviceSeenWords(one.lastSeenMillis, NOW))
        assertNull(deviceSinceWords(one.firstSeenMillis))
    }

    /** Устройство без имени показать нечем: пустая строка на экране хуже отсутствия строки. */
    @Test
    fun `безымянное устройство пропускается`() {
        val devices = parseDevices(
            json("""{"devices": [{"name": "", "kind": "phone"}, {"name": "ПК", "kind": "desktop"}]}""")
        )
        assertEquals(1, devices.size)
        assertEquals("ПК", devices.single().name)
    }

    @Test
    fun `пустые поля становятся null, а не строкой null`() {
        val one = parseDevices(
            json("""{"devices": [{"name": "ПК", "kind": "DESKTOP", "platform": "", "app_version": null}]}""")
        ).single()
        assertEquals("вид приводится к нижнему регистру — по нему выбирается значок", "desktop", one.kind)
        assertNull(one.platform)
        assertNull(one.appVersion)
        assertEquals("расхода не прислали — считаем, что не мерили", 0L, one.trafficBytes)
    }

    @Test
    fun `разбор дат в разных видах`() {
        assertEquals(1_788_165_852_000L, parseIsoMillis("2026-08-31T08:44:12+00:00"))
        assertEquals("зона Z — то же самое", 1_788_165_852_000L, parseIsoMillis("2026-08-31T08:44:12Z"))
        assertEquals("смещение учитывается", 1_788_155_052_000L, parseIsoMillis("2026-08-31T08:44:12+03:00"))
        assertEquals("доли секунды не мешают", 1_788_165_852_000L, parseIsoMillis("2026-08-31T08:44:12.000Z"))
        assertEquals("пусто — это ноль", 0L, parseIsoMillis(""))
        assertEquals(0L, parseIsoMillis(null))
    }

    // ---- время словами -------------------------------------------------------

    @Test
    fun `свежее устройство — в сети`() {
        assertEquals("в сети", deviceSeenWords(NOW, NOW))
        assertEquals("в сети", deviceSeenWords(NOW - DEVICE_ONLINE_WINDOW_MS + 1_000L, NOW))
        // Часы устройства могут убежать вперёд: «был через полчаса» читать невозможно.
        assertEquals("в сети", deviceSeenWords(NOW + 30 * MINUTE, NOW))
    }

    @Test
    fun `минуты и часы с русскими окончаниями`() {
        assertEquals("был 21 минуту назад", deviceSeenWords(NOW - 21 * MINUTE, NOW))
        assertEquals("был 22 минуты назад", deviceSeenWords(NOW - 22 * MINUTE, NOW))
        assertEquals("был 25 минут назад", deviceSeenWords(NOW - 25 * MINUTE, NOW))
        assertEquals("был 1 час назад", deviceSeenWords(NOW - 60 * MINUTE, NOW))
        assertEquals("был 2 часа назад", deviceSeenWords(NOW - 2 * HOUR, NOW))
        assertEquals("был 5 часов назад", deviceSeenWords(NOW - 5 * HOUR, NOW))
        assertEquals("был 11 часов назад", deviceSeenWords(NOW - 11 * HOUR, NOW))
    }

    /**
     * Граница четверти часа: ровно на ней устройство уже «был», а не «в сети».
     * Иначе окно молча растягивается на минуту-другую, и разбор жалобы врёт.
     */
    @Test
    fun `на границе четверти часа устройство уже не в сети`() {
        assertEquals("был 15 минут назад", deviceSeenWords(NOW - DEVICE_ONLINE_WINDOW_MS, NOW))
    }

    /** «Вчера» считается по календарю, а не по «минус 24 часа»: человек думает днями. */
    @Test
    fun `вчера и старее — днями, а не часами`() {
        val noon = calendarAt(hour = 12)
        assertEquals("был вчера", deviceSeenWords(noon - 25 * HOUR, noon))
        // Три дня назад календарь уже не помогает — нужна дата словами.
        val then = noon - 3 * DAY
        assertEquals("был ${dayMonthOf(then)}", deviceSeenWords(then, noon))
    }

    @Test
    fun `дата словами берёт месяц в родительном падеже`() {
        // 2 сентября 2026, полдень по месту устройства — берём календарём, чтобы тест
        // не зависел от часового пояса машины, на которой его запустили.
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 2, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals("с 2 сентября", deviceSinceWords(cal.timeInMillis))
        cal.set(2026, Calendar.AUGUST, 31, 12, 0, 0)
        assertEquals("с 31 августа", deviceSinceWords(cal.timeInMillis))
    }

    @Test
    fun `даты нет — строки нет`() {
        assertNull(deviceSeenWords(0L, NOW))
        assertNull(deviceSinceWords(0L))
    }

    // ---- расход --------------------------------------------------------------

    /**
     * Ноль в ответе сервера значит «не считали», а не «не ходил в сеть». Поэтому на
     * экране прочерк, и решается это здесь, а не в вёрстке.
     */
    @Test
    fun `нулевой расход — это прочерк, а не ноль байт`() {
        assertNull(deviceTrafficWords(0L))
        assertNull(deviceTrafficWords(-1L))
    }

    @Test
    fun `объём читается как на десктопе`() {
        assertEquals("12 ГБ", deviceTrafficWords(12_400_000_000L))
        assertEquals("3,0 ГБ", deviceTrafficWords(3L * 1024 * 1024 * 1024))
        assertEquals("1,5 ГБ", deviceTrafficWords(1024L * 1024 * 1024 * 3 / 2))
        assertEquals("870 МБ", deviceTrafficWords(870L * 1024 * 1024))
        assertEquals("512 КБ", deviceTrafficWords(512L * 1024))
        assertEquals("байты дробью не пишем", "900 Б", deviceTrafficWords(900L))
    }

    /** «1024 МБ» — это сломанная единица измерения, а не большое число. */
    @Test
    fun `у самой границы единица переключается`() {
        assertEquals("1,0 ГБ", deviceTrafficWords(1024L * 1024 * 1024 - 1))
        assertEquals("до килобайта дробить нечего", "1023 Б", deviceTrafficWords(1023L))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR

        val MONTHS = listOf(
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря",
        )

        /** Ожидаемая дата словами — считаем календарём, чтобы не зависеть от часового пояса. */
        fun dayMonthOf(millis: Long): String {
            val cal = Calendar.getInstance().apply { timeInMillis = millis }
            return "${cal.get(Calendar.DAY_OF_MONTH)} ${MONTHS[cal.get(Calendar.MONTH)]}"
        }

        /** Полдень нужен там, где сравниваются календарные сутки: край дня их сдвигает. */
        fun calendarAt(hour: Int): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val NOW = calendarAt(hour = 12)
    }
}
