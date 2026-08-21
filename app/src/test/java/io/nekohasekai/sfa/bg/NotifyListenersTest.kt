package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Беда (краш-репорты хозяина, 15-16.08.2026, 13:18): рассылка слушателям смены сети шла
 * голым `forEach` — исключение в одном слушателе рвало обход остальных, а на
 * ConnectivityThread ловить его было некому: падал весь процесс, а с ним и
 * [DefaultNetworkListener] актор, который после такого перестаёт слышать сеть вовсе.
 *
 * [notifyListeners] — чистая функция без единого `android.*` в сигнатуре, поэтому
 * проверяется на JVM без стенда с реальным `Network`.
 */
class NotifyListenersTest {
    @Test
    fun `упавший слушатель не мешает остальным, onError зовётся один раз`() {
        val seen = mutableListOf<Int?>()
        val errors = mutableListOf<Throwable>()
        val listeners = listOf<(Int?) -> Unit>(
            { value -> seen += value },
            { throw RuntimeException("второй слушатель упал") },
            { value -> seen += value },
        )

        notifyListeners(listeners, 42) { errors += it }

        assertEquals(listOf(42, 42), seen)
        assertEquals(1, errors.size)
        assertEquals("второй слушатель упал", errors.single().message)
    }
}
