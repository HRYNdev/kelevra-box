#!/usr/bin/env python3
"""
Правит ядро sing-box под нас: транспорт xhttp + наши починки.

Зачем транспорт: наш боевой транспорт дома — xhttp, но в апстриме sing-box его нет
(`unknown transport type: xhttp`), а форк с поддержкой сделан на старой базе
и с нашим приложением не собирается. Поэтому переносим транспорт в свежее ядро сами.

Зачем починки: своих багов апстрима, которые стреляют именно в нашем сценарии
(ядро пересобирается на каждое движение комнаты), ждать исправленными неоткуда.
Каждая правка ниже подписана: что ловили в бою и почему лечится именно так.

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

    # 5. паника ядра при пересборке: Close() зануляет элементы ОБЩЕГО backing-массива
    # (clear(r.setList)) под ногами у горутины, которая в этот момент читает тот же
    # слайс внутри matchWithOuterGroups/Match (range снимает заголовок слайса один раз
    # и после этого просто идёт по общему массиву). Та горутина получает nil-интерфейс
    # и падает с "invalid memory address or nil pointer dereference" — боевой краш с
    # телефона при BoxService.restartCore. r.setList = nil новых читателей не портит
    # (получат пустой слайс), а старым читателям backing-массив остаётся валиден.
    patch(
        os.path.join(core, "route", "rule", "rule_item_rule_set.go"),
        [(
            "\tfor _, ruleSet := range r.setList {\n"
            "\t\truleSet.DecRef()\n"
            "\t}\n"
            "\tclear(r.setList)\n"
            "\tr.setList = nil\n",
            "\tfor _, ruleSet := range r.setList {\n"
            "\t\truleSet.DecRef()\n"
            "\t}\n"
            "\tr.setList = nil\n",
        )],
    )

    print("транспорт xhttp добавлен в ядро")

    # --------------------------------------------------------------------------------
    # 5. Паника ядра при движении комнаты.
    #
    # Поймано в бою 15.08.2026, краш-репорт с телефона хозяина (версия 1.14.95, ядро
    # 1.14.0-beta.4): `nil pointer dereference` в route/rule/rule_item_rule_set.go:78,
    # то есть на `ruleSet.Match()` внутри matchWithOuterGroups.
    #
    # Причина. `RuleSetItem.Close()` зовёт `clear(r.setList)` ПЕРЕД `r.setList = nil`.
    # `clear` зануляет элементы живого backing-массива, а `range r.setList` в другой
    # горутине этот массив уже держит: `range` снимает заголовок слайса один раз, и
    # обнуление элементов из-под него он не замечает. Обход достаёт nil-интерфейс и
    # зовёт по нему метод. Само зануление ничего не даёт: ссылок на массив после
    # `r.setList = nil` не остаётся, его забирает сборщик мусора.
    #
    # Почему стреляет именно у нас. Ядро пересобирается на КАЖДОЕ движение комнаты
    # (BoxService.restartCore ← setRoomWanted/resumeTunnel, AutoMode.apply в обе
    # стороны), а запросы в этот момент летят. У обычного пользователя sing-box
    # пересборка — редкое событие, поэтому в апстриме это не всплывало.
    #
    # Лечение: убрать `clear` (это и есть источник nil) и читать `r.setList` в обходе
    # ровно один раз, в локальную переменную. Снимок не закрывает теоретически рваное
    # чтение заголовка слайса (для этого нужен был бы atomic.Value или RWMutex вокруг
    # поля), но убирает возможность прочитать поле дважды за один обход и делает
    # намерение явным.
    # --------------------------------------------------------------------------------
    patch(
        os.path.join(core, "route", "rule", "rule_item_rule_set.go"),
        [
            (
                "\tclear(r.setList)\n\tr.setList = nil\n",
                "\t// БЕЗ clear: он зануляет ЖИВОЙ backing-массив, по которому прямо сейчас\n"
                "\t// идёт range в другой горутине, и та достаёт nil-интерфейс. Массив и так\n"
                "\t// освобождается сборщиком, как только пропадёт последняя ссылка.\n"
                "\tr.setList = nil\n",
            ),
            (
                "func (r *RuleSetItem) Match(metadata *adapter.InboundContext) bool {\n"
                "\tfor _, ruleSet := range r.setList {\n",
                "func (r *RuleSetItem) Match(metadata *adapter.InboundContext) bool {\n"
                "\t// снимок: поле пересобирается Start/Close параллельно с обходом\n"
                "\tsetList := r.setList\n"
                "\tfor _, ruleSet := range setList {\n",
            ),
            (
                "\touterDone := outerGroups.done()\n\tfor _, ruleSet := range r.setList {\n",
                "\touterDone := outerGroups.done()\n"
                "\t// снимок: поле пересобирается Start/Close параллельно с обходом\n"
                "\tsetList := r.setList\n"
                "\tfor _, ruleSet := range setList {\n",
            ),
        ],
    )

    print("паника rule_set при пересборке ядра: правка наложена")


if __name__ == "__main__":
    main()
