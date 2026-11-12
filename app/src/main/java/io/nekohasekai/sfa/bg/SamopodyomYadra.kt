package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.bg.pult.Pult

/**
 * Самоподъём ядра: сеть есть, а туннель мёртв — перезапуск ядра sing-box по лестнице.
 *
 * Зачем. Ядро бывает живо как процесс, а наружу не ведёт: после смены сети оно не может
 * разрешить имя узла, держит мёртвые соединения, застряло после сна. Автомат это видит
 * (адрес узла напрямую принимает соединение, а запрос через канал не проходит — «пустой
 * порт»), но лечить ему было нечем: он переключает выходы и комнату, а ядро до сих пор
 * перезапускал только человек кнопкой «Отключить → Подключить». У Desktop такая ступень
 * есть (`internal/sluzhba/samopodyom.go`); здесь — то же правило под телефон.
 *
 * Новых проб нет. Диагноз собирается из того, что полный заход и так меряет
 * ([AutoMode.observe]): рукопожатие с адресом узла по физической сети (мимо туннеля, через
 * `protect`) и честная проба через закреплённый вход. Своих таймеров тоже нет: решение
 * принимается только на заходе, поэтому в дозе и в покое телефона ничего не будится.
 *
 * Диагнозы (коды — общие с Desktop, кроме `belyy_spisok`):
 *  - [Diagnoz.Ok] — канал жив;
 *  - [Diagnoz.TunnelMertv] — узел напрямую принимает соединение, а канал пуст: лечится
 *    перезапуском ядра, если тот вообще может помочь;
 *  - [Diagnoz.UzlyNedostupny] — адрес узла напрямую не отвечает: узел лежит или режут адрес,
 *    перезапуск ядра этого не лечит — не трогаем;
 *  - [Diagnoz.NetSeti] — физической сети нет — ждём сеть;
 *  - [Diagnoz.BelyySpisok] — свежая подсказка «белый список»: «пустой порт» под ним — режим
 *    оператора (МегаФон 24.10.2026), а не ядро — не трогаем.
 *
 * В последних трёх случаях время «мёртв» не копится: иначе после пяти минут без сети ядро
 * перезапускалось бы в первую же секунду после её возврата, не дав ему ожить самому.
 *
 * Лестница: первый перезапуск — через 45 с подтверждённой смерти, а сразу после смены сети —
 * через 30 с от самой смены (если туннель с тех пор так и не ожил); дальше промежутки между
 * перезапусками 2 → 5 → 15 → 15 мин… Смерть подтверждается двумя заходами, а не одним: одна
 * проба на неустоявшейся сети — слабый довод. Сброс лестницы — после 10 мин живого канала,
 * по действию человека или перезапуску автомата.
 *
 * Без Android внутри и со своими часами — чтобы правило проверялось тестом.
 */
internal class SamopodyomYadra(
    private val nastroyki: Nastroyki = Nastroyki.IZ_PULTA,
) {
    enum class Diagnoz(val kod: String) {
        Ok("ok"),
        TunnelMertv("tunnel_mertv"),
        UzlyNedostupny("uzly_nedostupny"),
        NetSeti("net_seti"),
        BelyySpisok("belyy_spisok"),
    }

    /** Пороги лестницы. Умолчания — как у Desktop; пульт подкручивает. */
    interface Nastroyki {
        val pervyyMillis: Long
        val posleSmenySetiMillis: Long
        val oknoSmenySetiMillis: Long
        val lestnicaMillis: List<Long>
        val sbrosMillis: Long
        val podtverzhdeniy: Int

        companion object {
            val IZ_PULTA: Nastroyki = object : Nastroyki {
                override val pervyyMillis get() = Pult.long("samopodyom.pervyy_millis", PERVYY_MILLIS)
                override val posleSmenySetiMillis get() = Pult.long("samopodyom.posle_smeny_seti_millis", POSLE_SMENY_SETI_MILLIS)
                override val oknoSmenySetiMillis get() = OKNO_SMENY_SETI_MILLIS
                override val lestnicaMillis get() = listOf(
                    Pult.long("samopodyom.stupen2_millis", STUPEN2_MILLIS),
                    Pult.long("samopodyom.stupen3_millis", STUPEN3_MILLIS),
                    Pult.long("samopodyom.stupen4_millis", STUPEN4_MILLIS),
                )
                override val sbrosMillis get() = Pult.long("samopodyom.sbros_millis", SBROS_MILLIS)
                override val podtverzhdeniy get() = Pult.int("samopodyom.podtverzhdeniy", PODTVERZHDENIY).coerceAtLeast(1)
            }
        }
    }

    /** Что делать по итогу наблюдения. */
    sealed class Reshenie {
        object Nichego : Reshenie()

        /** Перезапустить ядро сейчас; [stupen] — какой по счёту перезапуск в лестнице (с 1). */
        data class Perezapusk(val stupen: Int, val mertvMillis: Long, val posleSmenySeti: Boolean) : Reshenie()
    }

    @Volatile
    var diagnoz: Diagnoz? = null
        private set

    /** Сколько перезапусков сделано в этой лестнице. */
    @Volatile
    var stupen: Int = 0
        private set

    private var mertvS: Long? = null
    private var mertvNablyudeniy = 0
    private var zhivS: Long? = null
    private var poslednijPerezapusk: Long? = null
    private var smenaSetiV: Long? = null

    /** Новые строки журнала — одна на смену состояния, а не на каждый заход. */
    private var zapisannyy: Diagnoz? = null

    /**
     * Наблюдение захода. `null` — судить не по чему (туннель погашен, стоим в комнате,
     * человек выбрал выход сам, замер не состоялся): счёт мёртвого времени сбрасывается.
     *
     * @return решение и строка журнала, если состояние сменилось (или пусто).
     */
    @Synchronized
    fun nablyudenie(d: Diagnoz?, now: Long): Pair<Reshenie, String?> {
        val bylo = diagnoz
        diagnoz = d
        when (d) {
            null -> {
                mertvS = null
                mertvNablyudeniy = 0
                zhivS = null
                zapisannyy = null
                return Reshenie.Nichego to null
            }

            Diagnoz.Ok -> {
                mertvS = null
                mertvNablyudeniy = 0
                // Туннель ожил после смены сети — событие больше не в счёт.
                smenaSetiV = null
                val zhiv = zhivS ?: now.also { zhivS = it }
                var stroka: String? = if (bylo == Diagnoz.TunnelMertv) "самоподъём: туннель ожил" else null
                if (stupen > 0 && now - zhiv >= nastroyki.sbrosMillis) {
                    stupen = 0
                    poslednijPerezapusk = null
                    stroka = "самоподъём: канал жив ${nastroyki.sbrosMillis / 60_000} мин — лестница перезапусков сначала"
                }
                zapisannyy = d
                return Reshenie.Nichego to stroka
            }

            Diagnoz.NetSeti, Diagnoz.UzlyNedostupny, Diagnoz.BelyySpisok -> {
                mertvS = null
                mertvNablyudeniy = 0
                zhivS = null
                val stroka = if (zapisannyy != d) {
                    when (d) {
                        Diagnoz.NetSeti -> "самоподъём: физической сети нет — ядро не трогаю, жду сеть"
                        Diagnoz.UzlyNedostupny -> "самоподъём: канал молчит, но и адрес узла напрямую не отвечает — перезапуск не поможет, ядро не трогаю"
                        else -> "самоподъём: канал пуст, но подсказка «белый список» — это режим оператора, ядро не трогаю"
                    }
                } else {
                    null
                }
                zapisannyy = d
                return Reshenie.Nichego to stroka
            }

            Diagnoz.TunnelMertv -> {
                zhivS = null
                if (mertvS == null) mertvS = now
                mertvNablyudeniy++
                val stroka = if (zapisannyy != d) {
                    "самоподъём: сеть есть (адрес узла принимает соединение напрямую), а канал пуст — " +
                        "перезапущу ядро, если не оживёт за ${porogMillis(now) / 1000} с"
                } else {
                    null
                }
                zapisannyy = d
                val pora = pora(now) ?: return Reshenie.Nichego to stroka
                return pora to stroka
            }
        }
    }

    /** Сеть сменилась: прошлое мёртвое время — про прежнюю сеть, отсчёт после смены — свой. */
    @Synchronized
    fun smenaSeti(now: Long) {
        smenaSetiV = now
        mertvS = null
        mertvNablyudeniy = 0
    }

    /**
     * Перезапуск состоялся (или упал — ступень всё равно потрачена, иначе неудачный подъём
     * долбил бы ядро каждым заходом). Не состоялся вовсе (замок занят, служба уходит) — не
     * звать: ступень не тратится, решит следующий заход.
     */
    @Synchronized
    fun perezapushcheno(now: Long) {
        stupen++
        poslednijPerezapusk = now
        mertvS = null
        mertvNablyudeniy = 0
        smenaSetiV = null
        zapisannyy = null
    }

    /** Человек или автомат сказали своё (включение, ручной выбор, сброс памяти) — всё сначала. */
    @Synchronized
    fun sbros() {
        diagnoz = null
        stupen = 0
        mertvS = null
        mertvNablyudeniy = 0
        zhivS = null
        poslednijPerezapusk = null
        smenaSetiV = null
        zapisannyy = null
    }

    /** Для диагностики пульта: `diagnoz=… stupen=…`. */
    fun stroka(): String = "diagnoz=${diagnoz?.kod ?: "-"} stupen=$stupen"

    /** Смена сети, которая ещё в счёт первой ступени; `null` — такой нет. */
    private fun smenaVSchet(now: Long): Long? =
        smenaSetiV?.takeIf { stupen == 0 && now - it in 0..nastroyki.oknoSmenySetiMillis }

    /** Через сколько от начала смерти первый перезапуск — для строки журнала. */
    private fun porogMillis(now: Long): Long = smenaVSchet(now)?.let { smena ->
        (smena + nastroyki.posleSmenySetiMillis - (mertvS ?: now)).coerceAtLeast(0L)
    } ?: nastroyki.pervyyMillis

    private fun pora(now: Long): Reshenie.Perezapusk? {
        if (mertvNablyudeniy < nastroyki.podtverzhdeniy) return null
        val mertv = now - (mertvS ?: now)
        val smena = smenaVSchet(now)
        val dozhdalis = if (smena != null) {
            now - smena >= nastroyki.posleSmenySetiMillis
        } else {
            mertv >= nastroyki.pervyyMillis
        }
        if (!dozhdalis) return null
        val posleSmeny = smena != null
        val byl = poslednijPerezapusk
        if (stupen > 0 && byl != null) {
            val lestnica = nastroyki.lestnicaMillis
            val pauza = lestnica.getOrElse(stupen - 1) { lestnica.last() }
            if (now - byl < pauza) return null
        }
        return Reshenie.Perezapusk(stupen = stupen + 1, mertvMillis = mertv, posleSmenySeti = posleSmeny)
    }

    companion object {
        /** Первый перезапуск: столько подтверждённой смерти. */
        const val PERVYY_MILLIS = 45_000L

        /** Сразу после смены сети: столько от самой смены. */
        const val POSLE_SMENY_SETI_MILLIS = 30_000L

        /** Смена сети в счёт первой ступени — не дольше этого. */
        const val OKNO_SMENY_SETI_MILLIS = 300_000L

        /** Промежутки между перезапусками: после первого, второго, третьего и дальше. */
        const val STUPEN2_MILLIS = 120_000L
        const val STUPEN3_MILLIS = 300_000L
        const val STUPEN4_MILLIS = 900_000L

        /** Столько живого канала — и лестница сначала. */
        const val SBROS_MILLIS = 600_000L

        /** Заходов с диагнозом «канал пуст» до первого перезапуска. */
        const val PODTVERZHDENIY = 2

        /**
         * Диагноз по заходу. Чистая функция.
         *
         * @param kanalZhiv итог полного захода по основному (с очередью узлов).
         * @param portOpen рукопожатие с адресом узла по физической сети; `null` — адресов нет.
         * @param trafficFlows честная проба через закреплённый вход; `null` — не мерили.
         * @param belyy свежая подсказка «белый список» про эту сеть.
         * @return `null` — судить не по чему.
         */
        fun diagnozZahoda(kanalZhiv: Boolean, portOpen: Boolean?, trafficFlows: Boolean?, belyy: Boolean): Diagnoz? = when {
            kanalZhiv -> Diagnoz.Ok
            // Трафик шёл, но полосу задушили: перезапуск ядра троттлинг не лечит.
            trafficFlows == true -> Diagnoz.Ok
            belyy -> Diagnoz.BelyySpisok
            portOpen == false -> Diagnoz.UzlyNedostupny
            portOpen == true && trafficFlows == false -> Diagnoz.TunnelMertv
            else -> null
        }
    }
}
