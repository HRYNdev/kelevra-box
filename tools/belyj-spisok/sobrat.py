#!/usr/bin/env python3
"""
Собирает вшитый в APK список доменов белого списка оператора.

Под белым списком сервер подписки недоступен, поэтому список не скачивается, а едет
внутри приложения: `app/src/main/assets/belyj-spisok/domeny.txt`. Клиент кладёт его
в конфиг sing-box правилом `domain_suffix` прямо в памяти, без .srs и без сети.

Вход — снимок доменов из открытого списка (формат: одно имя на строку, `#` — комментарий),
например `whitelist.txt` из репозитория hxehex/russia-mobile-internet-whitelist.

Что делает:
  - приводит имена к нижнему регистру, снимает `*.` и точки по краям;
  - отбрасывает мусор (не похожее на имя хоста) и повторы;
  - сворачивает поддомены: если в списке есть `ozon.ru`, строка `adv.ozon.ru` лишняя,
    `domain_suffix` и так её накрывает.

Только стандартная библиотека. Запуск:
    python tools/belyj-spisok/sobrat.py путь/к/whitelist.txt
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

HOST = re.compile(r"^(?=.{1,253}$)([a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?\.)+[a-z0-9-]{2,63}$")

ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_OUT = ROOT / "app" / "src" / "main" / "assets" / "belyj-spisok" / "domeny.txt"


def normalize(line: str) -> str | None:
    value = line.split("#", 1)[0].strip().lower()
    if value.startswith("*."):
        value = value[2:]
    value = value.strip(".")
    return value if HOST.match(value) else None


def collapse(hosts: set[str]) -> list[str]:
    """Оставляет только имена, ни один родитель которых сам не лежит в списке."""
    kept = []
    for host in hosts:
        labels = host.split(".")
        covered = any(".".join(labels[i:]) in hosts for i in range(1, len(labels) - 1))
        if not covered:
            kept.append(host)
    return sorted(kept, key=lambda h: (list(reversed(h.split("."))), h))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("source", type=pathlib.Path, help="снимок доменов, одно имя на строку")
    parser.add_argument("--out", type=pathlib.Path, default=DEFAULT_OUT, help="куда писать (по умолчанию asset приложения)")
    args = parser.parse_args()

    raw = args.source.read_text(encoding="utf-8").splitlines()
    hosts = {h for h in (normalize(line) for line in raw) if h}
    rejected = sum(1 for line in raw if line.strip() and not line.strip().startswith("#") and not normalize(line))
    result = collapse(hosts)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    header = [
        "# Домены белого списка оператора для правила domain_suffix.",
        f"# Источник: {args.source.name}; строк {len(raw)}, имён {len(hosts)}, после свёртки {len(result)}.",
        "# Файл собирается tools/belyj-spisok/sobrat.py, руками не править.",
    ]
    args.out.write_text("\n".join(header + result) + "\n", encoding="utf-8", newline="\n")
    print(f"{args.out}: {len(result)} имён (было {len(hosts)}, отброшено строк {rejected})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
