#!/usr/bin/env python3
"""
Добавляет транспорт xhttp в ядро sing-box.

Зачем: наш боевой транспорт дома — xhttp, но в апстриме sing-box его нет
(`unknown transport type: xhttp`), а форк с поддержкой сделан на старой базе
и с нашим приложением не собирается. Поэтому переносим транспорт в свежее ядро сами.

Запуск: python3 apply.py <путь к дереву sing-box>
"""

import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def fail(msg):
    print(f"ОШИБКА: {msg}", file=sys.stderr)
    sys.exit(1)


def patch(path, replacements, must_exist=True):
    if not os.path.exists(path):
        fail(f"нет файла {path}")
    src = open(path, encoding="utf-8").read()
    for old, new in replacements:
        if new.strip() and new in src:
            continue  # уже применено
        if old not in src:
            if must_exist:
                fail(f"в {os.path.basename(path)} не найдено место для правки:\n{old[:120]}")
            continue
        src = src.replace(old, new, 1)
    open(path, "w", encoding="utf-8").write(src)


def main():
    if len(sys.argv) < 2:
        fail("укажите путь к дереву sing-box")
    core = os.path.abspath(sys.argv[1])
    if not os.path.isdir(os.path.join(core, "transport", "v2ray")):
        fail(f"{core} не похож на дерево sing-box")

    # 1. файлы транспорта и его зависимостей
    shutil.copytree(
        os.path.join(HERE, "v2rayxhttp"),
        os.path.join(core, "transport", "v2rayxhttp"),
        dirs_exist_ok=True,
    )
    for name in ("xray", "congestion", "kmutex"):
        shutil.copytree(
            os.path.join(HERE, "common", name),
            os.path.join(core, "common", name),
            dirs_exist_ok=True,
        )
    for name in ("xhttp.go", "range.go"):
        shutil.copy2(os.path.join(HERE, name), os.path.join(core, "option", name))

    # 2. константа типа транспорта
    patch(
        os.path.join(core, "constant", "v2ray.go"),
        [(
            'V2RayTransportTypeHTTPUpgrade = "httpupgrade"',
            'V2RayTransportTypeHTTPUpgrade = "httpupgrade"\n\tV2RayTransportTypeXHTTP       = "xhttp"',
        )],
    )

    # 3. опции транспорта
    patch(
        os.path.join(core, "option", "v2ray_transport.go"),
        [
            (
                '\tHTTPUpgradeOptions V2RayHTTPUpgradeOptions `json:"-"`\n}',
                '\tHTTPUpgradeOptions V2RayHTTPUpgradeOptions `json:"-"`\n'
                '\tXHTTPOptions       V2RayXHTTPOptions       `json:"-"`\n}',
            ),
            (
                'enum:"http,ws,quic,grpc,httpupgrade"',
                'enum:"http,ws,quic,grpc,httpupgrade,xhttp"',
            ),
            (
                "\tcase C.V2RayTransportTypeHTTPUpgrade:\n\t\tv = o.HTTPUpgradeOptions",
                "\tcase C.V2RayTransportTypeHTTPUpgrade:\n\t\tv = o.HTTPUpgradeOptions\n"
                "\tcase C.V2RayTransportTypeXHTTP:\n\t\tv = o.XHTTPOptions",
            ),
            (
                "\tcase C.V2RayTransportTypeHTTPUpgrade:\n\t\tv = &o.HTTPUpgradeOptions",
                "\tcase C.V2RayTransportTypeHTTPUpgrade:\n\t\tv = &o.HTTPUpgradeOptions\n"
                "\tcase C.V2RayTransportTypeXHTTP:\n\t\tv = &o.XHTTPOptions",
            ),
        ],
    )

    # 4. подключение клиента (серверная часть не нужна: мы клиент)
    patch(
        os.path.join(core, "transport", "v2ray", "transport.go"),
        [
            (
                '"github.com/sagernet/sing-box/transport/v2rayhttp"',
                '"github.com/sagernet/sing-box/transport/v2rayhttp"\n'
                '\txhttp "github.com/sagernet/sing-box/transport/v2rayxhttp"',
            ),
            (
                "\tcase C.V2RayTransportTypeHTTPUpgrade:\n"
                "\t\treturn v2rayhttpupgrade.NewClient(ctx, dialer, serverAddr, options.HTTPUpgradeOptions, tlsConfig)",
                "\tcase C.V2RayTransportTypeHTTPUpgrade:\n"
                "\t\treturn v2rayhttpupgrade.NewClient(ctx, dialer, serverAddr, options.HTTPUpgradeOptions, tlsConfig)\n"
                "\tcase C.V2RayTransportTypeXHTTP:\n"
                "\t\treturn xhttp.NewClient(ctx, dialer, serverAddr, options.XHTTPOptions, tlsConfig)",
            ),
        ],
    )

    print("транспорт xhttp добавлен в ядро")


if __name__ == "__main__":
    main()
