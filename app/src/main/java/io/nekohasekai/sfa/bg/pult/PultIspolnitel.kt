package io.nekohasekai.sfa.bg.pult

import android.content.pm.PackageManager
import android.os.Build
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Aktivatsiya
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.Kelevra
import io.nekohasekai.sfa.Priglashenie
import io.nekohasekai.sfa.bg.AutoMode
import io.nekohasekai.sfa.bg.DefaultNetworkMonitor
import io.nekohasekai.sfa.bg.KLog
import io.nekohasekai.sfa.bg.LogUploadWork
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.bg.path.HonestProbe
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.vendor.Vendor
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.Date

/**
 * Исполнитель команд пульта на телефоне. Каждая команда — только из списка в коде
 * ([PultYadro.KOMANDY]); кода по сети не исполняет ничего.
 */
object PultIspolnitel : Pult.Ispolnitel {
    private const val TAG = "KelevraPult"
    private const val TAG_DIAG = "KelevraDiagnostika"

    override suspend fun vypolnit(z: PultYadro.Zadanie): String = when (z.chto) {
        // `ok` — журнал принят сервером, как у Desktop (не «поставлен в очередь»).
        "prishli_zhurnal" -> LogUploadWork.otpravitDlyaPulta("по команде пульта")
        "obnovi_profil" -> {
            UpdateProfileWork.seychas()
            "ok"
        }
        "pereydi_na_klyuch" -> pereydiNaKlyuch()
        "sbros_avtomata" -> {
            AutoMode.sbrosPamyati("команда пульта")
            "ok"
        }
        "proverit_obnovlenie" -> {
            Vendor.proveritObnovlenieSeychas()
            "ok"
        }
        "diagnostika" -> {
            diagnostika().forEach { KLog.i(TAG_DIAG, it) }
            LogUploadWork.otpravitDlyaPulta("по команде пульта: диагностика")
        }
        "proba" -> proba(z.param.optString("cel"))
        else -> "ne_umeyu"
    }

    override fun nastroykiIzmenilis() {
        // Границы: период журнала и расписание профиля переставляются сразу, автомат и пороги
        // берут новое на своём следующем заходе сами.
        runCatching { LogUploadWork.posleSmenyPulta() }
        runCatching { AutoMode.posleSmenyPulta() }
        @Suppress("OPT_IN_USAGE")
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { UpdateProfileWork.reconfigureUpdater() }
        }
    }

    override fun obnovlenieIzmenilos(o: PultYadro.Obnovlenie?) {
        if (o == null) return
        KLog.i(TAG, "пульт выкатывает сборку ${o.versiyaKod} — проверяю обновление")
        runCatching { Vendor.proveritObnovlenieSeychas() }
    }

    // ---- pereydi_na_klyuch ----

    private suspend fun pereydiNaKlyuch(): String {
        val id = Settings.selectedProfile
        val profile = if (id == -1L) null else ProfileManager.get(id)
        if (profile == null || profile.typed.type != TypedProfile.Type.Remote) return "oshibka-net_profilya"
        val itog = PereydiNaKlyuch.vypolnit(
            profile.typed.remoteURL,
            object : PereydiNaKlyuch.Okruzhenie {
                override suspend fun dveri(): List<String> = Aktivatsiya.spisokDverej()

                override suspend fun aktivirovat(dver: String, kod: String): Priglashenie.Otvet? =
                    Aktivatsiya.aktivirovat(dver, kod)

                override suspend fun znaetKod(dver: String, kod: String): Boolean = Aktivatsiya.znaetKod(dver, kod)

                override suspend fun skachatKonfig(url: String): String =
                    HTTPClient().use { it.getString(Kelevra.adresSeti(url)) }

                override fun proveritKonfig(konfig: String) = Libbox.checkConfig(konfig)

                override suspend fun perepisat(novyyUrl: String, konfig: String) {
                    File(profile.typed.path).writeText(konfig)
                    profile.typed.remoteURL = novyyUrl
                    profile.typed.lastUpdated = Date()
                    ProfileManager.update(profile)
                    runCatching { Settings.profilObnovlenV = System.currentTimeMillis() }
                    KLog.i(TAG, "профиль «${profile.name}» переведён на ключ устройства: ${Kelevra.maskCode(novyyUrl)}")
                    if (profile.id == Settings.selectedProfile) {
                        runCatching { Libbox.newStandaloneCommandClient().serviceReload() }
                    }
                }
            },
        )
        return itog
    }

    // ---- diagnostika ----

    /** Сжатый отчёт строками «ключ=значение»; ни кода, ни ключа в нём нет. */
    fun diagnostika(): List<String> {
        val ctx = Application.application
        val chasti = mutableListOf<String>()
        chasti += "versiya=${BuildConfig.VERSION_NAME} kod=${BuildConfig.VERSION_CODE} yadro=${runCatching { Libbox.version() }.getOrDefault("?")}"
        chasti += "pasport ${runCatching { Kelevra.pasportStroka() }.getOrDefault("?")}"
        chasti += "avtomat ${runCatching { AutoMode.puls() }.getOrDefault("?")}"
        chasti += "pult ${Pult.zagolovok()}"
        val razresheniya = mutableListOf<String>()
        runCatching { razresheniya += "vpn=" + if (android.net.VpnService.prepare(ctx) == null) "1" else "0" }
        runCatching {
            razresheniya += "uvedomleniya=" + if (androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()) "1" else "0"
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                razresheniya += "ustanovka=" + if (ctx.packageManager.canRequestPackageInstalls()) "1" else "0"
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                razresheniya += "bat_iskl=" + if (Application.powerManager.isIgnoringBatteryOptimizations(ctx.packageName)) "1" else "0"
            }
        }
        chasti += "razresheniya " + razresheniya.joinToString(" ")
        chasti += "ustanovshik " + ustanovshik(ctx.packageManager, ctx.packageName)
        chasti += "tiho nastroyka=${runCatching { Settings.silentInstallEnabled }.getOrDefault(false)} " +
            "itog=${io.nekohasekai.sfa.update.ObnovleniyaPult.tiho(runCatching { Settings.silentInstallEnabled }.getOrDefault(false))}"
        chasti += "set=" + if (DefaultNetworkMonitor.defaultNetwork != null) "est" else "net"
        chasti += "konfig " + heshKonfiga()
        chasti += "komnata ${io.nekohasekai.sfa.bg.OlcRtcCore.state.javaClass.simpleName} wb=${runCatching { io.nekohasekai.sfa.bg.WbChatParams.available }.getOrDefault(false)}"
        return chasti.map { "диагностика: $it" }
    }

    private fun ustanovshik(pm: PackageManager, paket: String): String = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = pm.getInstallSourceInfo(paket)
            "installing=${i.installingPackageName} initiating=${i.initiatingPackageName} originating=${i.originatingPackageName}"
        } else {
            @Suppress("DEPRECATION")
            "installer=${pm.getInstallerPackageName(paket)}"
        }
    }.getOrElse { "?(${it.javaClass.simpleName})" }

    private fun heshKonfiga(): String = runCatching {
        val id = Settings.selectedProfile
        if (id == -1L) return@runCatching "net_profilya"
        val profile = kotlinx.coroutines.runBlocking { ProfileManager.get(id) } ?: return@runCatching "net_profilya"
        val f = File(profile.typed.path)
        val h = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).take(8).joinToString("") { "%02x".format(it) }
        "sha256:$h razmer=${f.length()} vozrast_min=${(System.currentTimeMillis() - f.lastModified()) / 60000}"
    }.getOrElse { "?(${it.javaClass.simpleName})" }

    // ---- proba ----

    private val CEL = Regex("""^([a-z0-9]([a-z0-9.-]*[a-z0-9])?)(:(\d{1,5}))?(/[\x21-\x7e]*)?$""")

    /** `param.cel` → цель пробы: `host[:port][/path]`, только простой HTTP. null — цель негодна. */
    internal fun razobratCel(cel: String): HonestProbe.Target? {
        val m = CEL.matchEntire(cel.trim().lowercase().removePrefix("http://")) ?: return null
        val port = m.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 80
        if (port !in 1..65535) return null
        return HonestProbe.Target(m.groupValues[1], port, m.groupValues[5].ifEmpty { "/" })
    }

    /** Итог пробы одним словом для квитанции: `ok-tun.<мс|net|nepr>-mimo.<мс|net|nepr>`. */
    internal fun slovoProby(m: HonestProbe.Measurement?): String = when {
        m == null || !m.measured -> "nepr"
        m.live -> (m.latencyMs ?: 0L).toString()
        else -> "net"
    }

    private fun proba(cel: String): String {
        val target = razobratCel(cel) ?: return "oshibka-cel"
        val vhod = AutoMode.vhodOsnovnogo()
        val tun = runCatching { HonestProbe.measure(vhod, "проба пульта через туннель", target = target) }.getOrNull()
        val network = DefaultNetworkMonitor.defaultNetwork
        val mimo = network?.let { n -> runCatching { HonestProbe.measureDirect(n, "проба пульта мимо туннеля", target = target) }.getOrNull() }
        KLog.i(TAG, "проба $target: через туннель ${tun ?: "не мерили"}, мимо ${mimo ?: "не мерили"}")
        return "ok-tun.${slovoProby(tun)}-mimo.${slovoProby(mimo)}"
    }
}
