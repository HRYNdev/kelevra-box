package io.nekohasekai.sfa.update

import io.nekohasekai.sfa.bg.pult.Pult
import io.nekohasekai.sfa.bg.pult.PultYadro

/**
 * Обновления под пультом (PULT.md): тихая установка, напоминание, канал выкатки и блок
 * `obnovlenie` из подписанного пульта. Логика выбора — без Android, проверяется тестами.
 */
object ObnovleniyaPult {
    /** Тап по уведомлению «Обновление готово» открывает приложение с этим действием. */
    const val ACTION_USTANOVIT = "io.nekohasekai.sfa.USTANOVIT_OBNOVLENIE"

    /** Тихая установка: пульт (`obnovleniya.tiho`) поверх настройки в приложении; ключа нет — настройка. */
    fun tiho(nastroyka: Boolean): Boolean =
        if (Pult.zadan("obnovleniya.tiho")) Pult.bool("obnovleniya.tiho") else nastroyka

    /** Напоминание о неустановленной версии, мин: 24 ч или `obnovleniya.napominat_chas`. */
    fun napominanieMinut(): Long =
        Pult.long("obnovleniya.napominat_chas", IshodUstanovki.NAPOMINANIE_MINUT / 60L) * 60L

    /** Что предлагать установить. */
    sealed class Istochnik {
        /** Сборка из пульта — главнее GitHub. */
        data class IzPulta(val info: UpdateInfo) : Istochnik()

        /** Обычная проверка выпусков (GitHub/F-Droid). */
        data object Vypuski : Istochnik()

        /** Ничего: канал `proba` без блока или `stop`. */
        data object Nichego : Istochnik()
    }

    /**
     * Канал `obychnyy` (или ключа нет) — выпуски, как раньше; блок `obnovlenie` выше
     * установленной версии — он, при любом канале кроме `stop`; `proba` без блока и `stop` —
     * ничего: пробное устройство не перескакивает на выпуск мимо пульта.
     *
     * @param plohie сборки «versiya_kod:sha256», которые уже не сошлись с подписанным хешем.
     */
    fun vybrat(kanal: String, blok: PultYadro.Obnovlenie?, ustanovlenKod: Int, plohie: Set<String>): Istochnik {
        if (kanal == "stop") return Istochnik.Nichego
        if (blok != null && blok.versiyaKod > ustanovlenKod && "${blok.versiyaKod}:${blok.sha256}" !in plohie) {
            return Istochnik.IzPulta(vUpdateInfo(blok))
        }
        return if (kanal == "proba") Istochnik.Nichego else Istochnik.Vypuski
    }

    fun vUpdateInfo(b: PultYadro.Obnovlenie): UpdateInfo = UpdateInfo(
        versionCode = b.versiyaKod,
        versionName = b.versiyaImya ?: "сборка ${b.versiyaKod}",
        downloadUrl = b.url,
        releaseUrl = b.url,
        releaseNotes = b.zametka,
        isPrerelease = true,
        fileSize = b.razmer,
        sha256 = b.sha256,
    )

    /** Сейчас действующий выбор по пульту. */
    fun vybratSeychas(ustanovlenKod: Int): Istochnik = run {
        // Фоновая работа могла подняться раньше, чем приложение прочитало пульт с диска:
        // пробное устройство не должно успеть перескочить на выпуск по умолчанию.
        Pult.zagruzitEsliNado()
        vybratPoPultu(ustanovlenKod)
    }

    private fun vybratPoPultu(ustanovlenKod: Int): Istochnik = vybrat(
        Pult.vybor("obnovleniya.kanal"),
        Pult.sostoyanie.obnovlenie,
        ustanovlenKod,
        Pult.sostoyanie.plohieObnovleniya,
    )

    /** Это сборка из пульта (а не выпуск): по адресу и хешу. */
    fun izPulta(info: UpdateInfo?): Boolean {
        val b = Pult.sostoyanie.obnovlenie ?: return false
        return info != null && info.downloadUrl == b.url && info.sha256.equals(b.sha256, ignoreCase = true)
    }

    /**
     * Сбой скачивания или проверки файла сборки из пульта. Файл не сошёлся с подписанным
     * хешем, размером или версией — эту сборку больше не берём (иначе повтор по кругу) и
     * сообщаем `oshibki=obnovlenie:<prichina>`; сеть — просто сообщаем, повтор будет.
     * @return true — сборка из пульта, сбой учтён.
     */
    fun posleSboya(info: UpdateInfo?, e: Throwable): Boolean {
        if (!izPulta(info)) return false
        if (e is FaylNeGoden) {
            Pult.oshibkaObnovleniya(prichinaFayla(e.prichina), navsegda = true)
        } else {
            Pult.oshibkaObnovleniya("skachat", navsegda = false)
        }
        return true
    }

    /** Причина отказа файла словом для заголовка. */
    fun prichinaFayla(tekst: String): String = when {
        tekst.startsWith("хеш") -> "hesh"
        tekst.startsWith("размер") -> "razmer"
        tekst.startsWith("пустой") -> "pustoy"
        tekst.startsWith("в файле версия") -> "versiya"
        tekst.startsWith("в файле другое") -> "paket"
        else -> "fayl"
    }
}
