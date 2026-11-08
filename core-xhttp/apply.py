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
    # 6. Reality: X25519MLKEM768 в ClientHello — только по признаку в профиле.
    #
    # Xray-core с v26.9.8 (XTLS/REALITY 8cdf7bf9, 08.09.2026) отвергает ClientHello без
    # ключа X25519MLKEM768 перед X25519: сервер молча уводит соединение на цель, клиент
    # пишет `reality verification failed`. Апстрим sing-box (по v1.14.3 включительно)
    # вырезает этот ключ из ClientHello ВСЕГДА — sing-box#4520, открыт с 11.09.2026.
    #
    # Новое рукопожатие включается не всем сразу, а выходу, у которого в профиле стоит
    # признак `tls.reality.support_x25519mlkem768: true` — то же поле и тот же смысл, что
    # в sing-box-extended («не вырезать ключ»). Без признака работает код апстрима как
    # есть: ClientHello такой же, как у ядра без правки (тест
    # tests/reality_priznak_clienthello_test.go сверяет с пресетом uTLS по каждому полю).
    #
    # Почему поле, а не «всегда»: опции разбираются с DisallowUnknownFields, старое ядро
    # (без этой правки) на незнакомом поле падает целиком. Поэтому сервер пишет признак
    # только клиентам, которые его знают (по User-Agent), и только тем людям и
    # устройствам, кому новое рукопожатие включено — кольцом, без выпуска.
    #
    # С признаком — перенос коммита iskhakovt/sing-box@dfd19d104fd1dbed9428694e166bf79283c73686
    # (предложен в #4520, апстрим не принял на 20.10.2026; взят по SHA, не по ветке):
    # X25519MLKEM768 остаётся, только когда отпечаток шлёт И группу, И ключ (chrome:
    # GREASE, X25519MLKEM768, X25519 — как настоящий Chrome и Xray). Если одно без
    # другого (randomized выбирает их независимо) или отпечаток ML-KEM не шлёт вовсе
    # (firefox/safari/edge/ios/qq в metacubex/utls) — вырезается, как у апстрима: группа
    # без ключа даёт HelloRetryRequest, который REALITY не передаёт; ключ без группы
    # запрещён RFC 8446 §4.2.8. Сессия по-прежнему запечатывается отдельным ключом
    # X25519 (KeyShareKeys.Ecdhe), его понимают все версии сервера.
    #
    # Чем платим (только с признаком): сервер без поддержки X25519MLKEM768 (Xray до
    # v25.5.16, sing-box до 1.12) с отпечатком chrome не пустит, если цель выберет
    # ML-KEM. У нас Xray 26.3.27 — таблица в #4520: ✓ с правкой и без.
    # --------------------------------------------------------------------------------
    patch(
        os.path.join(core, "option", "tls.go"),
        [(
            "type OutboundRealityOptions struct {\n"
            "\tEnabled   bool   `json:\"enabled,omitempty\"`\n"
            "\tPublicKey string `json:\"public_key,omitempty\"`\n"
            "\tShortID   string `json:\"short_id,omitempty\"`\n"
            "}\n",
            "type OutboundRealityOptions struct {\n"
            "\tEnabled   bool   `json:\"enabled,omitempty\"`\n"
            "\tPublicKey string `json:\"public_key,omitempty\"`\n"
            "\tShortID   string `json:\"short_id,omitempty\"`\n"
            "\t// Kelevra (core-xhttp/apply.py, правка 6): не вырезать X25519MLKEM768 из\n"
            "\t// ClientHello (имя и смысл — как в sing-box-extended).\n"
            "\tSupportX25519MLKEM768 bool `json:\"support_x25519mlkem768,omitempty\"`\n"
            "}\n",
        )],
    )
    rc = os.path.join(core, "common", "tls", "reality_client.go")
    patch(
        rc,
        [
            (
                "\tpublicKey []byte\n"
                "\tshortID   [8]byte\n"
                "}\n",
                "\tpublicKey []byte\n"
                "\tshortID   [8]byte\n"
                "\t// Kelevra (core-xhttp/apply.py, правка 6): reality.support_x25519mlkem768\n"
                "\tkelevraMLKEM bool\n"
                "}\n",
            ),
            (
                "&RealityClientConfig{ctx, uClient.(*UTLSClientConfig), publicKey, shortID}",
                "&RealityClientConfig{ctx, uClient.(*UTLSClientConfig), publicKey, shortID, options.Reality.SupportX25519MLKEM768}",
            ),
            (
                "\t\te.uClient.Clone().(*UTLSClientConfig),\n"
                "\t\te.publicKey,\n"
                "\t\te.shortID,\n"
                "\t}\n",
                "\t\te.uClient.Clone().(*UTLSClientConfig),\n"
                "\t\te.publicKey,\n"
                "\t\te.shortID,\n"
                "\t\te.kelevraMLKEM,\n"
                "\t}\n",
            ),
            (
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
                "\t// Kelevra (core-xhttp/apply.py, правка 6): X25519MLKEM768 остаётся, только когда\n"
                "\t// профиль просит (reality.support_x25519mlkem768) и отпечаток шлёт и группу, и\n"
                "\t// ключ. Иначе — код апстрима как есть: ClientHello как у ядра без правки.\n"
                "\tvar mlkemCurve, mlkemKeyShare bool\n"
                "\tif e.kelevraMLKEM {\n"
                "\t\tfor _, extension := range uConn.Extensions {\n"
                "\t\t\tswitch ext := extension.(type) {\n"
                "\t\t\tcase *utls.SupportedCurvesExtension:\n"
                "\t\t\t\tmlkemCurve = common.Contains(ext.Curves, utls.X25519MLKEM768)\n"
                "\t\t\tcase *utls.KeyShareExtension:\n"
                "\t\t\t\tmlkemKeyShare = common.Any(ext.KeyShares, func(share utls.KeyShare) bool {\n"
                "\t\t\t\t\treturn share.Group == utls.X25519MLKEM768\n"
                "\t\t\t\t})\n"
                "\t\t\t}\n"
                "\t\t}\n"
                "\t}\n"
                "\t// Группа без ключа даёт HelloRetryRequest, который REALITY не передаёт;\n"
                "\t// ключ без группы запрещён RFC 8446 4.2.8 — тогда вырезаем, как апстрим.\n"
                "\tif !mlkemCurve || !mlkemKeyShare {\n"
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
            ),
        ],
    )

    print("reality: X25519MLKEM768 в ClientHello по признаку support_x25519mlkem768: правка наложена")

    # --------------------------------------------------------------------------------
    # 7. Reality: ChaCha20-Poly1305 первым, когда в ClientHello есть X25519MLKEM768.
    #
    # Правка 6 (по признаку) оставляет X25519MLKEM768 у chrome, и ClientHello становится
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
    # Привязка. ChaCha первым ровно тогда, когда правка 6 оставила X25519MLKEM768 (то
    # есть у выхода в профиле признак support_x25519mlkem768, а отпечаток шлёт группу и
    # ключ) и отпечаток из семейства Chromium. Без признака, а также без ML-KEM
    # (randomized его выкинул, firefox/safari его не шлют) ClientHello не меняется.
    # Отдельного поля нет: ML-KEM с AES первым режется, включать одно без другого
    # незачем, а выключается всё вместе — снятием признака на сервере.
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

    # --------------------------------------------------------------------------------
    # 8. Живость канала: «Дом» (WireGuard через detour XHTTP) не оживает после
    #    молчаливой смерти соединения.
    #
    # Поймано в бою 31.10–06.11.2026 (ноутбук, Windows): после смены Wi-Fi / сна вход
    # «Дом» мёртв до перезапуска ядра. Механизм по коду v1.14.1 (разбор —
    # data/work/kanal-perehody-20261107/otchety/yadro.md):
    #
    #  - transport/wireguard/client_bind.go: connect() передиаливает detour только когда
    #    ReadFrom или Write вернули ошибку (receive:126-136, Send:187-191); дедлайнов
    #    нет. Линия, по которой Write проходит, а ответов нет, держит «Дом» мёртвым.
    #  - transport/v2rayxhttp (наш): TCP+TLS до портала шли без срока (контекст запроса
    #    — WithoutCancel), заголовки ответа на stream-down GET ждались без срока; h2
    #    PING-проверка (ReadIdleTimeout 45 с) была, PingTimeout — по умолчанию 15 с.
    #
    # Лечение в три слоя, каждый сам по себе ограничивает зависание:
    #  a) сторож ClientBind (новый файл transport/wireguard/kelevra_client_bind_storozh.go
    #     + точечные вставки в client_bind.go): N handshake initiation подряд без
    #     единого входящего пакета ≥ окна → рвём wireConn → connect() передиаливает
    #     detour. Сквозная проверка клиент↔мост, не зависит от того, где умерла линия
    #     (TCP, сервер, портал→мост). Рвёт 4-й безответный initiation: ≈ 15 с после
    #     первого безответного, ≈ 30 с после смерти линии (wireguard-go замечает
    #     тишину через KeepaliveTimeout + RekeyTimeout = 15 с, timers.go).
    #     Заодно connect(): быстрый путь читал c.conn без замка, а передиал пишет его
    #     под connAccess — гонка апстрима, которую видит go test -race; со сторожем
    #     передиалы чаще. Теперь c.conn читается под connAccess (поведение то же:
    #     пока линия жива, замок никто не держит).
    #  b) wireConn.Close: done закрывается до самого Close detour и вне замка —
    #     медленный Close (h2 ждёт RST_STREAM на мёртвом TCP) не держит передиал.
    #  c) XHTTP (файлы транспорта правятся прямо в v2rayxhttp/): таймаут на TCP+TLS,
    #     таймаут заголовков GET, явные PingTimeout/WriteByteTimeout у h2.
    #
    # Тесты: tests/v_derevo/** копируются в дерево как *_test.go (в бинарь не входят):
    #   go test -count=1 -run Kelevra ./transport/wireguard/ ./transport/v2rayxhttp/
    # --------------------------------------------------------------------------------
    shutil.copy2(
        os.path.join(HERE, "wireguard", "kelevra_client_bind_storozh.go"),
        os.path.join(core, "transport", "wireguard", "kelevra_client_bind_storozh.go"),
    )
    patch(
        os.path.join(core, "transport", "wireguard", "client_bind.go"),
        [
            # 8a. connect(): быстрый путь читает c.conn под connAccess (гонка апстрима:
            # запись c.conn при передиале — под замком, чтение здесь было без него)
            (
                "func (c *ClientBind) connect() (*wireConn, error) {\n"
                "\tserverConn := c.conn\n"
                "\tif serverConn != nil {\n",
                "func (c *ClientBind) connect() (*wireConn, error) {\n"
                "\t// Kelevra (правка 8): c.conn пишется под connAccess — и читаем под ним\n"
                "\tc.connAccess.Lock()\n"
                "\tserverConn := c.conn\n"
                "\tc.connAccess.Unlock()\n"
                "\tif serverConn != nil {\n",
            ),
            # 8a. поле сторожа в wireConn (поля выровнены как gofmt)
            (
                "\tconn   net.Conn\n"
                "\taccess sync.Mutex\n"
                "\tdone   chan struct{}\n"
                "}",
                "\tconn    net.Conn\n"
                "\taccess  sync.Mutex\n"
                "\tdone    chan struct{}\n"
                "\tstorozh kelevraStorozh // Kelevra (правка 8): безответные рукопожатия рвут линию\n"
                "}",
            ),
            # 8a. receive: любой входящий пакет — линия жива
            (
                "\tsizes[0] = n\n"
                "\tif n > 3 {\n",
                "\tudpConn.storozh.poluchen() // Kelevra (правка 8): входящий пакет — линия жива\n"
                "\tsizes[0] = n\n"
                "\tif n > 3 {\n",
            ),
            # 8a. Send: N initiation без ответа — рвём линию, следующий вызов передиалит
            (
                "\t\t_, err = udpConn.WriteToUDPAddrPort(buf, destination)\n"
                "\t\tif err != nil {\n"
                "\t\t\tudpConn.Close()\n"
                "\t\t\treturn err\n"
                "\t\t}\n"
                "\t}\n"
                "\treturn nil\n"
                "}",
                "\t\t_, err = udpConn.WriteToUDPAddrPort(buf, destination)\n"
                "\t\tif err != nil {\n"
                "\t\t\tudpConn.Close()\n"
                "\t\t\treturn err\n"
                "\t\t}\n"
                "\t\t// Kelevra (правка 8): N рукопожатий подряд без единого входящего пакета —\n"
                "\t\t// линия мертва; рвём, connect() передиалит detour (kelevra_client_bind_storozh.go)\n"
                "\t\tif rvat, skolko, za := udpConn.storozh.otpravlen(buf); rvat {\n"
                "\t\t\tc.logger.Warn(\"kelevra link watchdog: \", skolko,\n"
                "\t\t\t\t\" handshake initiations unanswered for \", za.Round(time.Millisecond), \", reconnecting\")\n"
                "\t\t\tudpConn.Close()\n"
                "\t\t\treturn E.New(\"kelevra link watchdog: peer silent, reconnecting\")\n"
                "\t\t}\n"
                "\t}\n"
                "\treturn nil\n"
                "}",
            ),
            # 8b. wireConn.Close: done — сразу и под замком, сам Close detour — вне замка
            (
                "func (w *wireConn) Close() error {\n"
                "\tw.access.Lock()\n"
                "\tdefer w.access.Unlock()\n"
                "\tselect {\n"
                "\tcase <-w.done:\n"
                "\t\treturn net.ErrClosed\n"
                "\tdefault:\n"
                "\t}\n"
                "\tw.PacketConn.Close()\n"
                "\tclose(w.done)\n"
                "\treturn nil\n"
                "}",
                "func (w *wireConn) Close() error {\n"
                "\tw.access.Lock()\n"
                "\tselect {\n"
                "\tcase <-w.done:\n"
                "\t\tw.access.Unlock()\n"
                "\t\treturn net.ErrClosed\n"
                "\tdefault:\n"
                "\t}\n"
                "\tclose(w.done)\n"
                "\tw.access.Unlock()\n"
                "\t// Kelevra (правка 8): Close detour — вне замка и после done: пока он ждёт\n"
                "\t// (h2 RST_STREAM на мёртвом TCP), connect() уже видит done и передиаливает.\n"
                "\treturn w.PacketConn.Close()\n"
                "}",
            ),
        ],
    )
    shutil.copytree(
        os.path.join(HERE, "tests", "v_derevo"),
        core,
        dirs_exist_ok=True,
    )

    print("живость канала (сторож ClientBind, таймауты XHTTP): правка наложена")

    # --------------------------------------------------------------------------------
    # 9. Мёртвое h2-соединение к узлу — за секунды, а не за минуту.
    #
    # Поймано на телефоне 06–07.11.2026 (мобильная сеть): приложения не грузятся
    # ~15 с и дольше, после перезапуска приложения — грузятся сразу. Сеть не
    # менялась, ядро не перезапускалось, проба канала новым потоком — 125–151 мс.
    # В журнале 49 раз `http2: client connection lost`, подвисшие потоки живут ровно
    # ~1m0s–1m3s = ReadIdleTimeout 45 с + PingTimeout: h2-соединение к узлу молча
    # умерло (TCP не рвётся), а новые потоки приложений садятся на него (xmux отдаёт
    # освободившийся клиент) и ждут PING-проверку x/net.
    #
    # Лечение — только файлы транспорта (копируются шагом 1, якорей в апстриме нет):
    #  - v2rayxhttp/kelevra_mertvoe.go (новый): свой http2.ClientConnPool. Соединение
    #    помнит последний байт от узла. Новый поток на соединении, молчавшем ≥ 10 с, —
    #    PING сразу; поток без заголовков ответа 3 с — PING, и пока он в полёте,
    #    соединение новым потокам не выдаётся. Мёртвым считается, только если PING без
    #    ответа 4 с, после PING ни байта от узла и тишина ≥ 7 с: тогда ClientConn.Close()
    #    — все его потоки сразу получают ошибку, приложения переподключаются. Байт от
    #    узла во время PING — соединение живо (загруженное отвечает медленнее, но
    #    данные идут). Строка в журнал ядра INFO `kelevra h2: connection to … dead: …`.
    #  - client.go: h2 через kelevraNewH2Transport; ReadIdleTimeout 45 → 30 с.
    #  - kelevra_zhivost.go: PingTimeout 10 → 5 с.
    #  - dialer.go: OpenStream ставит надзор на поток (httptrace.GotConn) и снимает
    #    его, когда client.Do вернулся; Close закрывает наш пул сам (без linkname).
    #    Ошибка потока на соединении, которое признала мёртвым наша проверка, весь
    #    клиент не закрывает: свежее соединение, поднятое за время проверки, живёт.
    #    То же для PostPacket (packet-up, auto): соединение запоминается через GotConn.
    #  - тишина для решения «мертво» — по большему из монотонных и стенных часов (сон
    #    телефона); живость доказана меньше срока ответа назад — PING не шлём (ждущие
    #    потоки медленного сервера иначе слали бы каждый свой); дозвон под подозрением
    #    не удался — пул пересматривается до ошибки.
    # Итог: поток на мёртвом соединении получает ошибку через ≈ 7 с (после простоя
    # соединения ≥ 10 с — через ≈ 4 с), долгие потоки без новых — через ≈ 35 с.
    # «Дом» (ClientBind через XHTTP) и сторож правки 8 не меняются: для них это та же
    # ошибка чтения, по которой ClientBind передиаливает.
    #
    # Тесты: tests/v_derevo/transport/v2rayxhttp/kelevra_mertvoe_test.go (кладёт
    # шаг 8), прогон тот же: go test -count=1 -run Kelevra ./transport/v2rayxhttp/
    # --------------------------------------------------------------------------------
    if not os.path.exists(os.path.join(core, "transport", "v2rayxhttp", "kelevra_mertvoe.go")):
        fail("нет transport/v2rayxhttp/kelevra_mertvoe.go — шаг 1 не скопировал правку 9")
    print("мёртвое h2-соединение к узлу распознаётся за секунды: правка наложена")


if __name__ == "__main__":
    main()
