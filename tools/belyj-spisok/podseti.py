#!/usr/bin/env python3
"""
Собирает вшитый в APK короткий список живых подсетей белого списка оператора.

Зачем. Снимок подсетей из открытого списка (hxehex/russia-mobile-internet-whitelist,
`cidrwhitelist.txt`) раздут: ~30 тыс. записей на десятки миллионов адресов, из которых
оператор на деле пропускает несколько десятков тысяч. С таким набором безымянные
соединения уходили бы напрямую к адресам, которых оператор не пускает. Здесь — только
подсети /24, в которых живые адреса нашлись замером через симку под белым списком.

Источник живых адресов — открытые замеры проекта openlibrecommunity (статья
habr.com/ru/articles/1027276):
  - twl  `verified` 10.07.2026: masscan 443 через симку, подтверждение nmap/httpx;
  - rewl `verified` 05-06.08.2026: мобильный Мегафон, Иваново, порты 80/443, nmap.
Формат входа — один IPv4 на строку.

Правило отбора: подсеть /24 берётся, если в КАЖДОМ замере в ней не меньше `--min`
живых адресов. Два замера с разницей в месяц отсекают то, что живо было разово
(между прогонами автор терял ~15 тыс. адресов). Плюс добавки (`--dobavki`): подсети
сервисов, без которых разрешённое не работает (WB, Ozon, Яндекс, VK), — каждая с
адресом-источником в комментарии; добавка без единого живого адреса в свежем замере
отбрасывается с предупреждением.

Только стандартная библиотека. Запуск:
    python tools/belyj-spisok/podseti.py --zhivye twl.txt --zhivye rewl.txt \
        --dobavki tools/belyj-spisok/dobavki-podseti.txt
"""

from __future__ import annotations

import argparse
import collections
import ipaddress
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_OUT = ROOT / "app" / "src" / "main" / "assets" / "belyj-spisok" / "podseti.txt"
HERE = pathlib.Path(__file__).resolve().parent


def po_24(path: pathlib.Path) -> collections.Counter:
    """Сколько живых адресов в каждой /24 (ключ — адрес, сдвинутый на 8 бит)."""
    counter: collections.Counter = collections.Counter()
    for line in path.read_text(encoding="utf-8").splitlines():
        value = line.split("#", 1)[0].strip()
        if not value:
            continue
        try:
            counter[int(ipaddress.IPv4Address(value)) >> 8] += 1
        except ValueError:
            continue
    return counter


def chitat_dobavki(path: pathlib.Path) -> list[ipaddress.IPv4Network]:
    nets = []
    for line in path.read_text(encoding="utf-8").splitlines():
        value = line.split("#", 1)[0].strip()
        if value:
            nets.append(ipaddress.IPv4Network(value, strict=False))
    return nets


def sobrat(zamery: list[collections.Counter], minimum: int, dobavki: list[ipaddress.IPv4Network]):
    obshchie = set.intersection(*(set(k for k, v in z.items() if v >= minimum) for z in zamery))
    nets = [ipaddress.IPv4Network((k << 8, 24)) for k in obshchie]
    svezhiy = zamery[-1]
    prinyato, otbrosheno = [], []
    for net in dobavki:
        first = int(net.network_address) >> 8
        last = int(net.broadcast_address) >> 8
        if any(svezhiy.get(k, 0) for k in range(first, last + 1)):
            prinyato.append(net)
        else:
            otbrosheno.append(net)
    result = sorted(ipaddress.collapse_addresses(nets + prinyato))
    return result, len(obshchie), prinyato, otbrosheno


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--zhivye", type=pathlib.Path, action="append", required=True,
                        help="замер живых адресов, по одному IPv4 на строку; последний — самый свежий")
    parser.add_argument("--min", type=int, default=8, help="живых адресов в /24 в каждом замере (по умолчанию 8)")
    parser.add_argument("--dobavki", type=pathlib.Path, default=HERE / "dobavki-podseti.txt")
    parser.add_argument("--out", type=pathlib.Path, default=DEFAULT_OUT)
    args = parser.parse_args()

    zamery = [po_24(p) for p in args.zhivye]
    dobavki = chitat_dobavki(args.dobavki) if args.dobavki.exists() else []
    result, obshchih, prinyato, otbrosheno = sobrat(zamery, args.min, dobavki)
    for net in otbrosheno:
        print(f"добавка {net} отброшена: в свежем замере в ней нет живых адресов", file=sys.stderr)

    header = [
        "# Живые подсети белого списка оператора для правила ip_cidr (только соединения без имени).",
        "# Источник: " + ", ".join(f"{p.name} ({sum(z.values())} адресов)" for p, z in zip(args.zhivye, zamery)) + ".",
        f"# /24 с не меньше {args.min} живыми адресами в каждом замере: {obshchih}; "
        f"добавок {len(prinyato)}; после свёртки {len(result)}.",
        "# Файл собирается tools/belyj-spisok/podseti.py, руками не править.",
    ]
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("\n".join(header + [str(n) for n in result]) + "\n", encoding="utf-8", newline="\n")
    print(f"{args.out}: {len(result)} подсетей (общих /24 {obshchih}, добавок {len(prinyato)}, отброшено {len(otbrosheno)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
