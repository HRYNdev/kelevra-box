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


def patch_v_funkcii(path, replacements):
    """Правка внутри тела названной функции: якорь = сигнатура + ОДНА строка.

    Обычный patch() берёт якорем сплошной кусок от сигнатуры до места правки, и
    любая вставка апстрима между ними рвёт наложение — при том что сама строка,
    которую мы правим, на месте и правка всё ещё нужна. Замер 15.09.2026: на
    v1.14.1 перед обходом в Match/matchWithOuterGroups появилось
    `snapshot := snapshotRuleMatch(metadata)`, и патч перестал ложиться, хотя
    `clear(r.setList)` в Close() и `range r.setList` в обходе — на месте, то
    есть паника из боя 15.08 у апстрима не починена.

    Поиск ограничен телом функции (до следующего `func `), чтобы промах не ушёл
    незаметно в соседнюю функцию с такой же строкой: их тут три.
    """
    if not os.path.exists(path):
        fail(f"нет файла {path}")
    src = open(path, encoding="utf-8").read()
    for podpis, old, new in replacements:
        nachalo = src.find(podpis)
        if nachalo < 0:
            fail(f"в {os.path.basename(path)} нет функции:\n{podpis}")
        konec = src.find("\nfunc ", nachalo + len(podpis))
        if konec < 0:
            konec = len(src)
        if new.strip() and new in src[nachalo:konec]:
            continue  # уже применено — смотрим ТЕЛО: правки-близнецы совпадают дословно
        mesto = src.find(old, nachalo, konec)
        if mesto < 0:
            fail(
                f"в {os.path.basename(path)}, в теле {podpis}"
                f"\nне найдено место для правки:\n{old}"
            )
        src = src[:mesto] + new + src[mesto + len(old):]
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
    # помощник правки 7 (ChaCha первым в Reality), см. ниже
    shutil.copy2(
        os.path.join(HERE, "tls", "kelevra_reality_chacha.go"),
        os.path.join(core, "common", "tls", "kelevra_reality_chacha.go"),
    )

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

    # --------------------------------------------------------------------------------
    # 5. Паника ядра при движении комнаты.
    #
    # Поймано в бою 15.08.2026, краш-репорт с боевого телефона (версия 1.14.95, ядро
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
        ],
    )

    # Сам обход правим по сигнатуре функции: между ней и циклом апстрим свои
    # строки вставляет (см. patch_v_funkcii), а правится ровно строка цикла.
    patch_v_funkcii(
        os.path.join(core, "route", "rule", "rule_item_rule_set.go"),
        [
            (
                "func (r *RuleSetItem) Match(metadata *adapter.InboundContext) bool {",
                "\tfor _, ruleSet := range r.setList {\n",
                "\t// снимок: поле пересобирается Start/Close параллельно с обходом\n"
                "\tsetList := r.setList\n"
                "\tfor _, ruleSet := range setList {\n",
            ),
            (
                "func (r *RuleSetItem) matchWithOuterGroups(",
                "\tfor _, ruleSet := range r.setList {\n",
                "\t// снимок: поле пересобирается Start/Close параллельно с обходом\n"
                "\tsetList := r.setList\n"
                "\tfor _, ruleSet := range setList {\n",
            ),
        ],
    )

    print("паника rule_set при пересборке ядра: правка наложена")

    # --------------------------------------------------------------------------------
    # 6. Reality: не вырезать X25519MLKEM768 из ClientHello.
    #
    # Xray-core с v26.9.8 (XTLS/REALITY 8cdf7bf9, 08.09.2026) отвергает ClientHello без
    # ключа X25519MLKEM768 перед X25519: сервер молча уводит соединение на цель, клиент
    # пишет `reality verification failed`. Апстрим sing-box (по v1.14.3 включительно)
    # вырезает этот ключ из ClientHello ВСЕГДА — sing-box#4520, открыт с 11.09.2026.
    # Пока это так, Xray на наших узлах выше 26.3.27 поднимать нельзя: отвалятся все
    # клиенты разом.
    #
    # Правка — перенос коммита iskhakovt/sing-box@dfd19d104fd1dbed9428694e166bf79283c73686
    # (предложен в #4520, апстрим не принял на 20.10.2026; взят по SHA, не по ветке):
    # X25519MLKEM768 остаётся, только когда отпечаток шлёт И группу, И ключ (chrome:
    # GREASE, X25519MLKEM768, X25519 — как настоящий Chrome и Xray). Если одно без
    # другого (randomized выбирает их независимо) — вырезается, как раньше: группа без
    # ключа даёт HelloRetryRequest, который REALITY не передаёт; ключ без группы
    # запрещён RFC 8446 §4.2.8. Сессия по-прежнему запечатывается отдельным ключом
    # X25519 (KeyShareKeys.Ecdhe), его понимают все версии сервера.
    #
    # Чем платим: сервер без поддержки X25519MLKEM768 (Xray до v25.5.16, sing-box до
    # 1.12) с отпечатком chrome не пустит, если цель выберет ML-KEM. У нас Xray 26.3.27
    # (замер 20.10.2026) — таблица в #4520: ✓ с правкой и без. firefox/safari/edge/ios/qq
    # гибридного ключа в metacubex/utls не имеют — с Xray ≥26.9.8 не пойдут и с правкой.
    #
    # Опцию `support_x25519mlkem768` из sing-box-extended НЕ переносим намеренно: сервер
    # отдавал бы её всем, а старое ядро на незнакомом поле падает целиком (опции
    # разбираются с DisallowUnknownFields). Здесь правка без новых полей.
    # --------------------------------------------------------------------------------
    patch(
        os.path.join(core, "common", "tls", "reality_client.go"),
        [(
            "\tfor _, extension := range uConn.Extensions {\n"
            "\t\tif ce, ok := extension.(*utls.SupportedCurvesExtension); ok {\n"
            "\t\t\tce.Curves = common.Filter(ce.Curves, func(curveID utls.CurveID) bool {\n"
            "\t\t\t\treturn curveID != utls.X25519MLKEM768\n"
            "\t\t\t})\n"
            "\t\t}\n"
            "\t\tif ks, ok := extension.(*utls.KeyShareExtension); ok {\n"
            "\t\t\tks.KeyShares = common.Filter(ks.KeyShares, func(share utls.KeyShare) bool {\n"
            "\t\t\t\treturn share.Group != utls.X25519MLKEM768\n"
            "\t\t\t})\n"
            "\t\t}\n"
            "\t}\n"
            "\terr = uConn.BuildHandshakeState()\n"
            "\tif err != nil {\n"
            "\t\treturn nil, err\n"
            "\t}\n",
            "\t// Kelevra (core-xhttp/apply.py, правка 6): X25519MLKEM768 остаётся, когда\n"
            "\t// отпечаток шлёт и группу, и ключ, — без него Xray >= 26.9.8 не пускает.\n"
            "\tvar mlkemCurve, mlkemKeyShare bool\n"
            "\tfor _, extension := range uConn.Extensions {\n"
            "\t\tswitch ext := extension.(type) {\n"
            "\t\tcase *utls.SupportedCurvesExtension:\n"
            "\t\t\tmlkemCurve = common.Contains(ext.Curves, utls.X25519MLKEM768)\n"
            "\t\tcase *utls.KeyShareExtension:\n"
            "\t\t\tmlkemKeyShare = common.Any(ext.KeyShares, func(share utls.KeyShare) bool {\n"
            "\t\t\t\treturn share.Group == utls.X25519MLKEM768\n"
            "\t\t\t})\n"
            "\t\t}\n"
            "\t}\n"
            "\t// Группа без ключа даёт HelloRetryRequest, который REALITY не передаёт;\n"
            "\t// ключ без группы запрещён RFC 8446 4.2.8 — тогда вырезаем, как апстрим.\n"
            "\tif mlkemCurve != mlkemKeyShare {\n"
            "\t\tfor _, extension := range uConn.Extensions {\n"
            "\t\t\tif ce, ok := extension.(*utls.SupportedCurvesExtension); ok {\n"
            "\t\t\t\tce.Curves = common.Filter(ce.Curves, func(curveID utls.CurveID) bool {\n"
            "\t\t\t\t\treturn curveID != utls.X25519MLKEM768\n"
            "\t\t\t\t})\n"
            "\t\t\t}\n"
            "\t\t\tif ks, ok := extension.(*utls.KeyShareExtension); ok {\n"
            "\t\t\t\tks.KeyShares = common.Filter(ks.KeyShares, func(share utls.KeyShare) bool {\n"
            "\t\t\t\t\treturn share.Group != utls.X25519MLKEM768\n"
            "\t\t\t\t})\n"
            "\t\t\t}\n"
            "\t\t}\n"
            "\t\terr = uConn.BuildHandshakeState()\n"
            "\t\tif err != nil {\n"
            "\t\t\treturn nil, err\n"
            "\t\t}\n"
            "\t}\n",
        )],
    )

    print("reality: X25519MLKEM768 в ClientHello сохраняется: правка наложена")

    # --------------------------------------------------------------------------------
    # 7. Reality: ChaCha20-Poly1305 первым, когда в ClientHello есть X25519MLKEM768.
    #
    # Правка 6 оставляет X25519MLKEM768 у отпечатка chrome, и ClientHello становится
    # таким же, как у настоящего Chrome на процессоре с AES: AES-GCM первым в
    # шифрах. Замер 23.10.2026 на домашней линии (51 пачка, после каждой — контроль
    # без ML-KEM): именно этот вид — chrome + ML-KEM + AES первым — режется окнами
    # по нескольку минут (0-1 из 10 рукопожатий, и в sing-box, и в голом uTLS без
    # Reality). Тот же chrome + ML-KEM с ChaCha20 первым проходит 100% к тем же
    # узлам и портам; положение SNI и число TCP-сегментов роли не играют.
    #
    # Порядок берём как у BoringSSL на процессоре без ускорения AES (ChaCha первым
    # и в TLS 1.3, и в TLS 1.2) — такой ClientHello Chrome шлёт в природе, набор
    # шифров тот же, меняется только порядок. Сервер Reality (Xray) говорит только
    # TLS 1.3, ему годится любой шифр из списка; какой выбрать, решает цель.
    #
    # Почему без новой опции. Опции разбираются с DisallowUnknownFields: старое ядро
    # на незнакомом поле падает целиком, а профиль сервер отдаёт один на всех. Поэтому
    # правка привязана к уже существующему условию: ChaCha первым ровно тогда, когда
    # правка 6 оставила X25519MLKEM768 (группа и ключ) и отпечаток из семейства
    # Chromium. Без ML-KEM (randomized его выкинул, firefox/safari его не шлют)
    # ClientHello не меняется. Чем платим: выключить ChaCha из профиля нельзя,
    # только сменой отпечатка или новой сборкой.
    # --------------------------------------------------------------------------------
    patch_v_funkcii(
        os.path.join(core, "common", "tls", "reality_client.go"),
        [(
            "func (e *RealityClientConfig) ClientHandshake(",
            "\tif len(uConfig.NextProtos) > 0 {\n",
            "\t// Kelevra (core-xhttp/apply.py, правка 7): с X25519MLKEM768 у отпечатков\n"
            "\t// Chromium ChaCha20-Poly1305 идёт первым (kelevra_reality_chacha.go).\n"
            "\tif kelevraRealityChaCha(uConn, e.uClient.id, mlkemCurve && mlkemKeyShare) {\n"
            "\t\terr = uConn.BuildHandshakeState()\n"
            "\t\tif err != nil {\n"
            "\t\t\treturn nil, err\n"
            "\t\t}\n"
            "\t}\n"
            "\n"
            "\tif len(uConfig.NextProtos) > 0 {\n",
        )],
    )

    print("reality: ChaCha20 первым при X25519MLKEM768 у chrome: правка наложена")


if __name__ == "__main__":
    main()
