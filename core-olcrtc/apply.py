#!/usr/bin/env python3
"""Патч ядра olcrtc: рабочий datachannel через LiveKit-носитель (WbStream).

Зачем скриптом, а не .patch: diff ломается от одного потерянного пробела в контекстной
строке, и сборка падает с «corrupt patch». Текстовые замены с явной проверкой «нашли
ровно то, что ожидали» переживают и правки апстрима вокруг наших мест.

Разбор причины и замеры → README.md рядом.

Запуск: python3 apply.py <путь к клону olcrtc>   (по умолчанию текущий каталог)
Идемпотентен: повторный запуск ничего не делает и возвращает 0.
"""

import pathlib
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()


def patch(rel: str, edits: list[tuple[str, str, str]]) -> None:
    path = ROOT / rel
    if not path.exists():
        sys.exit(f"НЕТ ФАЙЛА: {path}")
    src = path.read_text(encoding="utf-8")
    for old, new, name in edits:
        if new in src:
            print(f"  {name}: уже применено")
            continue
        if src.count(old) != 1:
            sys.exit(f"ОШИБКА в {rel} [{name}]: ожидал одно вхождение, нашёл {src.count(old)}. Апстрим изменился.")
        src = src.replace(old, new, 1)
        print(f"  {name}: ок")
    path.write_text(src, encoding="utf-8")


print("internal/handshake/handshake.go:")
patch(
    "internal/handshake/handshake.go",
    [
        (
            '\t"io"\n\t"time"\n',
            '\t"io"\n\t"strings"\n\t"time"\n',
            "импорт strings",
        ),
        (
            "// MsgType labels each protocol message.\ntype MsgType string",
            "// maxStaleFrames bounds how many non-handshake frames the client skips while\n"
            "// waiting for the server reply. A control frame from a previous, torn-down\n"
            "// session can arrive on the freshly opened stream ahead of SERVER_WELCOME on\n"
            "// transports that do not tag epochs. The server side has the same protection\n"
            "// (see server.acceptHandshake, maxStaleRetries).\n"
            "const maxStaleFrames = 8\n\n"
            "// controlPrefix marks post-handshake control messages (CONTROL_PING/PONG/...).\n"
            'const controlPrefix = "CONTROL_"\n\n'
            "// MsgType labels each protocol message.\ntype MsgType string",
            "константы",
        ),
        (
            """\traw, err := readFrame(rw)
\tif err != nil {
\t\treturn "", fmt.Errorf("read welcome: %w", err)
\t}

\tvar probe struct {
\t\tType MsgType `json:"type"`
\t}
\tif err := json.Unmarshal(raw, &probe); err != nil {
\t\treturn "", fmt.Errorf("parse reply: %w", err)
\t}

\tswitch probe.Type {
\tcase TypeHello:
\t\treturn "", fmt.Errorf("%w: got %q", ErrUnexpectedMessage, probe.Type)
\tcase TypeWelcome:
\t\treturn parseWelcome(raw)
\tcase TypeReject:
\t\treturn parseReject(raw)
\tdefault:
\t\treturn "", fmt.Errorf("%w: got %q", ErrUnexpectedMessage, probe.Type)
\t}
}""",
            """\tfor skipped := 0; ; skipped++ {
\t\traw, err := readFrame(rw)
\t\tif err != nil {
\t\t\treturn "", fmt.Errorf("read welcome: %w", err)
\t\t}

\t\tvar probe struct {
\t\t\tType MsgType `json:"type"`
\t\t}
\t\tif err := json.Unmarshal(raw, &probe); err != nil {
\t\t\treturn "", fmt.Errorf("parse reply: %w", err)
\t\t}

\t\tswitch probe.Type {
\t\tcase TypeHello:
\t\t\treturn "", fmt.Errorf("%w: got %q", ErrUnexpectedMessage, probe.Type)
\t\tcase TypeWelcome:
\t\t\treturn parseWelcome(raw)
\t\tcase TypeReject:
\t\t\treturn parseReject(raw)
\t\tdefault:
\t\t\t// Stale control frame from a previous session can arrive on the freshly
\t\t\t// opened stream ahead of SERVER_WELCOME on carriers without epoch tagging.
\t\t\tif skipped < maxStaleFrames && strings.HasPrefix(string(probe.Type), controlPrefix) {
\t\t\t\tcontinue
\t\t\t}
\t\t\treturn "", fmt.Errorf("%w: got %q", ErrUnexpectedMessage, probe.Type)
\t\t}
\t}
}""",
            "пропуск залётных control-фреймов",
        ),
    ],
)

print("internal/client/client.go:")
patch(
    "internal/client/client.go",
    [
        (
            "\tcontrolLastPong atomic.Value // time.Time\n",
            "\tcontrolLastPong atomic.Value // time.Time\n\n"
            "\t// inboundReady signals that at least one packet has arrived from the peer.\n"
            "\t// On carriers that deliver data only after the subscription becomes active\n"
            "\t// (LiveKit), this is the proof that our own downstream works: until then the\n"
            "\t// server reply to CLIENT_HELLO is silently dropped by the carrier.\n"
            "\t// Holds chan struct{} with capacity 1, replaced on every link bring-up.\n"
            "\tinboundReady atomic.Value\n",
            "поле inboundReady",
        ),
        (
            "\t\tRequireTargetedPeer: true,\n",
            "\t\t// Transports that do not tag frames with an epoch cannot address a single\n"
            "\t\t// peer (the livekit engine has no SendTo), so the server reply is always a\n"
            "\t\t// broadcast. Requiring targeted frames there makes the client drop it and\n"
            '\t\t// hang forever: the server logs "session opened", the client times out.\n'
            "\t\tRequireTargetedPeer: !isBroadcastOnlyTransport(cfg.Transport),\n",
            "RequireTargetedPeer по транспорту",
        ),
        (
            '\tif err := ln.Connect(ctx); err != nil {\n\t\treturn fmt.Errorf("failed to connect link: %w", err)\n\t}\n',
            "\tc.inboundReady.Store(make(chan struct{}, 1))\n\n"
            '\tif err := ln.Connect(ctx); err != nil {\n\t\treturn fmt.Errorf("failed to connect link: %w", err)\n\t}\n',
            "канал готовности до Connect",
        ),
        (
            "\tif err := waitForPeer(ctx, ln); err != nil {\n\t\treturn err\n\t}\n",
            "\tif err := waitForPeer(ctx, ln); err != nil {\n\t\treturn err\n\t}\n\n"
            "\tif isBroadcastOnlyTransport(cfg.Transport) {\n\t\tc.waitInboundReady(ctx)\n\t}\n",
            "прогрев перед рукопожатием",
        ),
        (
            "\nfunc (c *Client) onData(data []byte) {\n"
            "\tc.sessMu.RLock()\n",
            "\n// inboundWarmupTimeout bounds the wait for the first packet from the peer before\n"
            "// the handshake. Overshooting it is not fatal: we proceed and let the handshake\n"
            "// deadline decide, so behaviour is never worse than upstream.\n"
            "const inboundWarmupTimeout = 20 * time.Second\n\n"
            "// isBroadcastOnlyTransport reports whether the transport delivers frames to the\n"
            "// whole room without epoch tagging or per-peer addressing. Such carriers (the\n"
            "// LiveKit data channel behind WbStream) also start delivering data to a joining\n"
            "// participant with a delay, which is why the handshake needs a warm-up.\n"
            "func isBroadcastOnlyTransport(name string) bool {\n"
            '\treturn name == "datachannel"\n'
            "}\n\n"
            "// waitInboundReady blocks until the first packet from the peer arrives, proving\n"
            "// our downstream is live. Measured 06.08.2026: a SERVER_WELCOME sent at the very\n"
            "// moment of joining is lost, while everything the server sent 5+ seconds later\n"
            "// arrived intact. The server keepalive (every 10s) is what unblocks this.\n"
            "func (c *Client) waitInboundReady(ctx context.Context) {\n"
            "\tch, ok := c.inboundReady.Load().(chan struct{})\n"
            "\tif !ok {\n\t\treturn\n\t}\n"
            "\ttimer := time.NewTimer(inboundWarmupTimeout)\n"
            "\tdefer timer.Stop()\n"
            "\tselect {\n"
            "\tcase <-ch:\n"
            '\t\tlogger.Debugf("carrier downstream is live, proceeding to handshake")\n'
            "\tcase <-timer.C:\n"
            '\t\tlogger.Warnf("no packet from peer in %s, trying the handshake anyway", inboundWarmupTimeout)\n'
            "\tcase <-ctx.Done():\n"
            "\t}\n"
            "}\n\n"
            "func (c *Client) onData(data []byte) {\n"
            "\tif ch, ok := c.inboundReady.Load().(chan struct{}); ok {\n"
            "\t\tselect {\n"
            "\t\tcase ch <- struct{}{}:\n"
            "\t\tdefault:\n"
            "\t\t}\n"
            "\t}\n"
            "\tc.sessMu.RLock()\n",
            "прогрев: помощники и сигнал",
        ),
    ],
)

print("патч наложен")
