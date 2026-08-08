package io.nekohasekai.sfa.update

import android.content.Context
import io.nekohasekai.sfa.R

/**
 * Причина словами вместо сырого (часто английского) текста исключения.
 *
 * Живёт здесь, а не рядом с экраном: проверку обновления запускают из разных мест,
 * и «не удалось проверить» должно звучать одинаково в каждом. Раньше живая кнопка
 * в настройках глотала ошибку через `runCatching { … }.getOrNull()` и писала
 * «установлена свежая версия» — то есть врала, когда проверка вообще не состоялась.
 */
fun humanUpdateError(
    e: Throwable,
    context: Context,
    fallback: Int = R.string.update_check_failed,
): String {
    val text = ((e.message ?: "") + " " + (e.cause?.message ?: "")).lowercase()
    return when {
        "no such host" in text || "unable to resolve host" in text ||
            "unable to resolve" in text || "no address associated" in text ||
            "network is unreachable" in text || "network is down" in text ||
            "connection refused" in text || "econnrefused" in text ||
            "failed to connect" in text || "no route to host" in text ||
            "unknownhost" in text || "server misbehaving" in text ->
            context.getString(R.string.update_check_no_network)

        "timeout" in text || "timed out" in text || "etimedout" in text ||
            "deadline exceeded" in text || "connection reset" in text ||
            "eof" == text.trim() || "unexpected eof" in text ||
            "500" in text || "502" in text || "503" in text || "504" in text ||
            "bad gateway" in text || "unavailable" in text ->
            context.getString(R.string.update_check_server_unavailable)

        else -> context.getString(fallback)
    }
}
