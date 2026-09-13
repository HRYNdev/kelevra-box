package io.nekohasekai.sfa.bg

import android.util.Log
import io.nekohasekai.sfa.Application

/**
 * Домены белого списка оператора, вшитые в APK.
 *
 * Под белым списком сервер подписки недоступен, поэтому скачать список в нужный момент
 * нельзя — он едет внутри приложения (`assets/belyj-spisok/domeny.txt`, собирается
 * `tools/belyj-spisok/sobrat.py`). Читается один раз и живёт в памяти: это локальный
 * файл на полтора десятка килобайт, сети здесь нет, и путь нажатой кнопки он не держит.
 *
 * Не прочитался — пустой список: правка маршрутов тогда ведёт напрямую только приложения
 * разрешённых сервисов и вшитые добавки, а не роняет старт.
 */
object BelyjSpisok {
    private const val TAG = "BelyjSpisok"
    private const val ASSET = "belyj-spisok/domeny.txt"

    @Volatile
    private var cache: List<String>? = null

    fun domeny(): List<String> = cache ?: synchronized(this) {
        cache ?: runCatching {
            Application.application.assets.open(ASSET).bufferedReader().use { razobrat(it.readText()) }
        }.onFailure {
            Log.w(TAG, "список доменов не прочитан (${it.javaClass.simpleName}), напрямую пойдут только приложения и добавки")
        }.getOrDefault(emptyList()).also { cache = it }
    }

    private const val ASSET_PODSETI = "belyj-spisok/podseti.txt"

    @Volatile
    private var podsetiCache: List<String>? = null

    /**
     * Живые подсети белого списка (`assets/belyj-spisok/podseti.txt`, собирается
     * `tools/belyj-spisok/podseti.py` из замеров через симку). Не прочитались — пусто,
     * и правила по адресам просто не будет.
     */
    fun podseti(): List<String> = podsetiCache ?: synchronized(this) {
        podsetiCache ?: runCatching {
            Application.application.assets.open(ASSET_PODSETI).bufferedReader().use { razobrat(it.readText()) }
        }.onFailure {
            Log.w(TAG, "подсети не прочитаны (${it.javaClass.simpleName}), безымянные соединения пойдут в комнату")
        }.getOrDefault(emptyList()).also { podsetiCache = it }
    }

    /** Одно имя (или подсеть) на строку, `#` — комментарий. */
    fun razobrat(text: String): List<String> = text.lineSequence()
        .map { it.substringBefore('#').trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toList()
}
