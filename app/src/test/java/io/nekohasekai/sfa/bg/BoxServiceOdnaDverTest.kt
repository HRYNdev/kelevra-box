package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Автомат узнаёт о КАЖДОЙ пересборке ядра — сторож устройства, а не поведения.
 *
 * Повод. 20.10.2026: перечитывание профиля ([BoxService.serviceReload0]) пересобирало ядро и
 * не говорило автомату, а входы пробы при каждой сборке получают новые порты. Честная проба
 * сутки стучала в порт прошлой жизни ядра («мёртв — локальный вход не отвечает», 261 раз у
 * одного телефона). До этого ту же ошибку чинили по одному пути за раз (11.08, 16.10).
 *
 * Класс закрыт устройством: ядро стартует только через `startCore`, и автомату говорит он сам,
 * после удачного старта. Сервис живёт на Android и в юнит-тесте не поднимается, поэтому сторожим
 * исходник: второй вызов `startOrReloadService` мимо `startCore` или уведомление до старта
 * вернули бы прежний класс бага.
 */
class BoxServiceOdnaDverTest {
    private val source: String by lazy {
        val candidates = listOf(
            File("src/main/java/io/nekohasekai/sfa/bg/BoxService.kt"),
            File("app/src/main/java/io/nekohasekai/sfa/bg/BoxService.kt"),
        )
        candidates.first { it.isFile }.readText()
    }

    private fun body(name: String): String {
        val start = source.indexOf("private fun $name(")
        assertTrue("нет функции $name", start >= 0)
        val next = source.indexOf("\n    private fun ", start + 1).takeIf { it > 0 } ?: source.length
        return source.substring(start, next)
    }

    @Test
    fun `ядро стартует ровно в одном месте`() {
        assertEquals(1, Regex("""\.startOrReloadService\(""").findAll(source).count())
        assertTrue(body("startCore").contains(".startOrReloadService("))
    }

    @Test
    fun `startCore говорит автомату после удачного старта и публикует конфиг тоже после`() {
        val core = body("startCore")
        val start = core.indexOf(".startOrReloadService(")
        val config = core.indexOf("runningConfig = config")
        val notify = core.indexOf("AutoMode.onCoreRebuilt(")
        assertTrue("конфиг публикуется после старта", config > start)
        assertTrue("автомат узнаёт после публикации конфига", notify > config)
    }

    @Test
    fun `конфиг не объявляется работающим до старта`() {
        assertEquals(1, Regex("""runningConfig = """).findAll(source).count())
        assertTrue(!body("effectiveConfig").contains("runningConfig = "))
    }

    @Test
    fun `вне startCore о пересборке не говорят — только о поднятой комнате`() {
        val calls = Regex("""AutoMode\.onCoreRebuilt\("([^"]*)""").findAll(source).map { it.groupValues[1] }.toList()
        assertEquals(listOf("ядро собрано: \$reason", "комната поднята (\$reason)"), calls)
    }
}
