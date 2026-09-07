package io.nekohasekai.sfa.compose.screen.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * До правки экран «Настройки обновлены» рапортовал сразу после вызова updateProfile —
 * функция не suspend, а реальный итог (успех/ошибка) приходил позже отдельным
 * событием. На профиле не по ссылке подпись врала о успехе, хотя обновление не
 * запускалось вовсе (ранний return в updateProfile).
 *
 * updateProfile теперь не решает сам за экран: он лишь сообщает один из трёх
 * итогов (ProfileUpdateOutcome) через колбэк, когда дело действительно случилось.
 * Проверить сам updateProfile тут нельзя — внутри Android Log, файловый ввод-вывод,
 * сетевой HTTPClient и Room-хранилище ProfileManager, которых на JVM без Android
 * и без сети нет. Поэтому проверяется чистая функция profileUpdateResultText —
 * она и есть то единственное место, которое решает, что покажет подпись для
 * каждого из трёх итогов.
 */
class ProfileUpdateOutcomeTest {

    @Test
    fun `успех подписывается только словом успеха`() {
        val text = profileUpdateResultText(ProfileUpdateOutcome.SUCCESS)
        assertEquals("Настройки обновлены", text)
    }

    @Test
    fun `ошибка не показывает текст успеха`() {
        val text = profileUpdateResultText(ProfileUpdateOutcome.FAILED, "нет связи")
        assertNotEquals("Настройки обновлены", text)
        assertEquals("Не получилось: нет связи", text)
    }

    @Test
    fun `ошибка без текста получает запасную формулировку`() {
        val text = profileUpdateResultText(ProfileUpdateOutcome.FAILED, null)
        assertEquals("Не получилось: нет связи", text)
    }

    @Test
    fun `профиль не по ссылке не показывает текст успеха`() {
        val text = profileUpdateResultText(ProfileUpdateOutcome.NOT_APPLICABLE)
        assertNotEquals("Настройки обновлены", text)
        assertEquals("Обновлять нечего: профиль задан не ссылкой", text)
    }
}
