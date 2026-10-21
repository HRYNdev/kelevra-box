package io.nekohasekai.sfa.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class BootReceiver : BroadcastReceiver() {
    private companion object {
        /** Весь заход укладывается сюда: фоновую рассылку система ждёт до минуты. */
        const val VES_ZAKHOD_MS = 45_000L
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
            }

            else -> return
        }
        val obnovilos = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        // Рассылку держим открытой, пока служба не подтвердит запуск. Раньше onReceive
        // возвращался сразу, работа шла в корутине уже без рассылки: процесс считался
        // фоновым и пустым, система вправе была заморозить его посреди дороги, а окно,
        // в котором Android 12+ разрешает поднять службу из фона, к тому времени закрыто.
        val pending = goAsync()
        GlobalScope.launch(Dispatchers.IO) {
            try {
                withTimeoutOrNull(VES_ZAKHOD_MS) { obrabotat(obnovilos) }
                    ?: KLog.w("KelevraVozvrat", "заход после ${if (obnovilos) "обновления" else "перезагрузки"} не уложился в ${VES_ZAKHOD_MS / 1000} с")
            } catch (e: Throwable) {
                KLog.e("KelevraVozvrat", "сбой после ${if (obnovilos) "обновления" else "перезагрузки"}: ${e.javaClass.name}: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun obrabotat(obnovilos: Boolean) {
        val vklyuchena = Settings.startedByUser
        if (obnovilos) {
            // Успех установки своего обновления система часто не присылает: процесс
            // завершается раньше ответа. Замена пакета — надёжная отметка исхода.
            KLog.i(
                "KelevraObnovlenie",
                "приложение обновилось: версия ${BuildConfig.VERSION_NAME}, " +
                    if (vklyuchena) "служба поднимается" else "служба не была запущена",
            )
            Settings.updateInstallFailures = ""
        }
        if (!vklyuchena) return
        // Непрочитанный отчёт о падении раньше молча отменял подъём и вдобавок стирал
        // отметку «служба включена» — без единой строки в журнале. Отчёт лежит до тех пор,
        // пока человек сам не откроет его в «Инструментах», то есть одно давнее падение
        // выключало службу после каждого следующего обновления. Теперь только пишем.
        runCatching {
            CrashReportManager.refresh()
            val n = CrashReportManager.unreadCount.value
            if (n > 0) {
                val svezhiy = CrashReportManager.reports.value.firstOrNull { !it.isRead }?.id
                KLog.w("KelevraVozvrat", "непрочитанных отчётов о падении: $n (свежий $svezhiy) — подъём службы не отменяю")
            }
        }.onFailure { KLog.w("KelevraVozvrat", "отчёты о падении не прочитались: ${it.message}") }
        AvtoVozvrat.vernut(if (obnovilos) AvtoVozvrat.Povod.OBNOVLENIE else AvtoVozvrat.Povod.ZAGRUZKA)
    }
}
