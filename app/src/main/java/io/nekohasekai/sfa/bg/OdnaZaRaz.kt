package io.nekohasekai.sfa.bg

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Одно дело за раз: кто пришёл, пока оно идёт, не начинает второе, а ждёт итога идущего.
 *
 * Дело живёт в [scope], а не у того, кто его позвал: отменили одного из ждущих (WorkManager
 * снял работу при смене сети) — дело доходит до конца, и остальные получают его итог.
 * Закончилось — следующий вызов начинает новое.
 */
internal class OdnaZaRaz<T>(private val scope: CoroutineScope) {
    private var idet: Deferred<T>? = null

    /** @param novoe true — дело начал этот вызов, false — присоединился к идущему. */
    suspend fun vypolnit(delo: suspend () -> T, novoe: (Boolean) -> Unit = {}): T {
        val (zadacha, svoya) = synchronized(this) {
            idet?.takeIf { it.isActive }?.let { it to false }
                ?: scope.async { delo() }.also { idet = it }.let { it to true }
        }
        novoe(svoya)
        return zadacha.await()
    }
}
