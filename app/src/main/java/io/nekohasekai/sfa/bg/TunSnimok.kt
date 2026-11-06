package io.nekohasekai.sfa.bg

/**
 * Снимок того, из чего собирается интерфейс tun: всё, что уходит в `VpnService.Builder`.
 *
 * Зачем. Каждая пересборка ядра (новый конфиг, комната, WB, подъём из дома) зовёт
 * `openTun` заново, и до сих пор каждый такой вызов заводил новый интерфейс через
 * `establish()`, хотя адреса, маршруты, DNS и списки приложений чаще всего те же:
 * меняются правила и выходы внутри ядра, а не сам tun. Новый интерфейс — это снос
 * VPN-сети Android и подъём новой, то есть разрыв всех соединений и окно без туннеля.
 *
 * Снимок позволяет это сравнить: прежний tun с тем же снимком отдаётся ядру как есть,
 * без `establish()` вовсе ([reshenie] → [Reshenie.PEREISPOLZOVAT]). Если снимок другой,
 * новый интерфейс поднимается, пока прежний ещё открыт ([Reshenie.ZAMENIT]): Android
 * умеет такую замену без паузы (документация `VpnService.Builder.establish`: новый
 * активируется, прежний снимается, при ошибке прежний остаётся нетронутым).
 *
 * Без Android внутри — чтобы сравнение можно было проверить тестом. Списки хранятся
 * отсортированными: порядок пакетов и маршрутов в `TunOptions` ядра ничего не значит.
 */
internal data class TunSnimok(
    val mtu: Int,
    val addresses: List<Prefix>,
    val dns: List<String>,
    val routes: List<Prefix>,
    val excludeRoutes: List<Prefix>,
    val includePackages: List<String>,
    val excludePackages: List<String>,
    /** `host:port` системного HTTP-прокси и домены мимо него; `null` — прокси не ставим. */
    val httpProxy: HttpProxy?,
    val allowBypass: Boolean,
) {
    /** Адрес с длиной префикса; порядок полей как у `RoutePrefix` ядра. */
    data class Prefix(val address: String, val prefix: Int) : Comparable<Prefix> {
        override fun compareTo(other: Prefix): Int =
            compareValuesBy(this, other, { it.address }, { it.prefix })

        override fun toString(): String = "$address/$prefix"
    }

    data class HttpProxy(val host: String, val port: Int, val bypass: List<String>)

    /** Что делать с tun при очередном `openTun`. */
    enum class Reshenie {
        /** Прежний tun жив и собран из того же — отдаём его, `establish()` не зовём. */
        PEREISPOLZOVAT,

        /** Прежний жив, но нужен другой — поднимаем новый, пока прежний открыт, потом закрываем прежний. */
        ZAMENIT,

        /** Прежнего нет — обычный первый подъём. */
        NOVYY,
        ;
    }

    /** Коротко, чем снимки различаются — для журнала. Пусто, если ничем. */
    fun raznica(other: TunSnimok): String = buildList {
        if (mtu != other.mtu) add("mtu $mtu→${other.mtu}")
        if (addresses != other.addresses) add("адреса")
        if (dns != other.dns) add("DNS")
        if (routes != other.routes) add("маршруты")
        if (excludeRoutes != other.excludeRoutes) add("исключённые маршруты")
        if (includePackages != other.includePackages) add("приложения в tun ${includePackages.size}→${other.includePackages.size}")
        if (excludePackages != other.excludePackages) add("приложения мимо tun ${excludePackages.size}→${other.excludePackages.size}")
        if (httpProxy != other.httpProxy) add("HTTP-прокси")
        if (allowBypass != other.allowBypass) add("allowBypass")
    }.joinToString(", ")

    companion object {
        /**
         * Собрать снимок из уже прочитанных полей; списки приводятся к сортированному виду,
         * чтобы одинаковые наборы в разном порядке сравнивались как одинаковые.
         */
        fun of(
            mtu: Int,
            addresses: List<Prefix>,
            dns: List<String>,
            routes: List<Prefix>,
            excludeRoutes: List<Prefix>,
            includePackages: Collection<String>,
            excludePackages: Collection<String>,
            httpProxy: HttpProxy?,
            allowBypass: Boolean,
        ): TunSnimok = TunSnimok(
            mtu = mtu,
            addresses = addresses.sorted(),
            dns = dns.sorted(),
            routes = routes.sorted(),
            excludeRoutes = excludeRoutes.sorted(),
            includePackages = includePackages.toSortedSet().toList(),
            excludePackages = excludePackages.toSortedSet().toList(),
            httpProxy = httpProxy?.copy(bypass = httpProxy.bypass.sorted()),
            allowBypass = allowBypass,
        )

        /**
         * @param prezhniy снимок, с которым поднят прежний tun; `null` — не помним.
         * @param novyy снимок, который просит ядро сейчас.
         * @param prezhniyZhiv наш дескриптор прежнего tun открыт и годен.
         */
        fun reshenie(prezhniy: TunSnimok?, novyy: TunSnimok, prezhniyZhiv: Boolean): Reshenie = when {
            !prezhniyZhiv -> Reshenie.NOVYY
            prezhniy == novyy -> Reshenie.PEREISPOLZOVAT
            else -> Reshenie.ZAMENIT
        }
    }
}
