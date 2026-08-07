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

# --------------------------------------------------------------------------------------
# Своя control-плоскость для datachannel.
#
# Замер 07.08.2026 на живом стенде: в покое ответ на CONTROL_PING приходит за 44 мс,
# под четырьмя параллельными качками — за 1-10 секунд (пик 9.7 с). Дефолт ядра рвёт
# сессию после четырёх промахов по 15 с, поэтому рабочая связь ложилась под нагрузкой,
# а на телефоне это выглядело как «поработало и встало».
#
# Причина: у datachannel control-плоскости нет вовсе (её реализует только vp8channel),
# поэтому пинг и мегабайты данных едут одним потоком smux — понг физически стоит
# в очереди за данными. У LiveKit есть topic в пакете данных: пускаем control своим
# topic'ом мимо общей очереди отправки, и на приёме разводим до расшифровки.
# --------------------------------------------------------------------------------------

print("internal/engine/engine.go:")
patch(
    "internal/engine/engine.go",
    [
        (
            "// PeerSession is implemented by engines that can address byte payloads to a",
            "// ControlSession is implemented by engines that can carry control frames\n"
            "// independently of bulk data, so that liveness pings do not queue behind\n"
            "// megabytes of payload.\n"
            "type ControlSession interface {\n"
            "\tSendControl(data []byte) error\n"
            "\tSetOnControlData(cb func([]byte))\n"
            "\tControlCanSend() bool\n"
            "}\n\n"
            "// PeerSession is implemented by engines that can address byte payloads to a",
            "интерфейс ControlSession",
        ),
    ],
)

print("internal/engine/livekit/livekit.go:")
patch(
    "internal/engine/livekit/livekit.go",
    [
        (
            '\tdataPublishTopic        = "olcrtc"\n',
            '\tdataPublishTopic        = "olcrtc"\n'
            "\t// Отдельный topic для control-плоскости: пинги не должны стоять в очереди\n"
            "\t// за данными. Приёмник разводит пакеты по topic ещё до расшифровки.\n"
            '\tcontrolPublishTopic     = "olcrtc-ctl"\n',
            "topic для control",
        ),
        (
            "type roomHandle interface {\n\tpublishData(data []byte) error\n",
            "type roomHandle interface {\n"
            "\tpublishData(data []byte) error\n"
            "\tpublishControl(data []byte) error\n",
            "roomHandle.publishControl",
        ),
        (
            "func (r *sdkRoom) publishTrack(track webrtc.TrackLocal) error {",
            "func (r *sdkRoom) publishControl(data []byte) error {\n"
            "\tif err := r.room.LocalParticipant.PublishDataPacket(\n"
            "\t\tlksdk.UserData(data),\n"
            "\t\tlksdk.WithDataPublishTopic(controlPublishTopic),\n"
            "\t\tlksdk.WithDataPublishReliable(true),\n"
            "\t); err != nil {\n"
            '\t\treturn fmt.Errorf("publish control packet: %w", err)\n'
            "\t}\n"
            "\treturn nil\n"
            "}\n\n"
            "func (r *sdkRoom) publishTrack(track webrtc.TrackLocal) error {",
            "sdkRoom.publishControl",
        ),
        (
            "\tonData          func([]byte)\n",
            "\tonData          func([]byte)\n"
            "\tonControlData   atomic.Value // func([]byte)\n",
            "поле onControlData",
        ),
        (
            "\t\t\tOnDataReceived: func(data []byte, _ lksdk.DataReceiveParams) {\n"
            "\t\t\t\tif s.onData != nil {\n"
            "\t\t\t\t\ts.onData(data)\n"
            "\t\t\t\t}\n"
            "\t\t\t},\n",
            "\t\t\tOnDataReceived: func(data []byte, params lksdk.DataReceiveParams) {\n"
            "\t\t\t\tif params.Topic == controlPublishTopic {\n"
            "\t\t\t\t\tif cb, ok := s.onControlData.Load().(func([]byte)); ok && cb != nil {\n"
            "\t\t\t\t\t\tcb(data)\n"
            "\t\t\t\t\t}\n"
            "\t\t\t\t\treturn\n"
            "\t\t\t\t}\n"
            "\t\t\t\tif s.onData != nil {\n"
            "\t\t\t\t\ts.onData(data)\n"
            "\t\t\t\t}\n"
            "\t\t\t},\n",
            "разбор по topic на приёме",
        ),
        (
            "func (s *Session) currentRoom() roomHandle {",
            "// SendControl публикует control-фрейм напрямую, минуя очередь отправки:\n"
            "// иначе пинг снова встанет за данными, только уже на своей стороне.\n"
            "func (s *Session) SendControl(data []byte) error {\n"
            "\tif s.closed.Load() {\n"
            "\t\treturn ErrSessionClosed\n"
            "\t}\n"
            "\troom := s.currentRoom()\n"
            "\tif room == nil || room.connectionState() != lksdk.ConnectionStateConnected {\n"
            "\t\treturn ErrRoomNotConnected\n"
            "\t}\n"
            "\treturn room.publishControl(data)\n"
            "}\n\n"
            "// SetOnControlData регистрирует приёмник control-фреймов.\n"
            "func (s *Session) SetOnControlData(cb func([]byte)) {\n"
            "\ts.onControlData.Store(cb)\n"
            "}\n\n"
            "// ControlCanSend сообщает, готова ли комната принять control-фрейм.\n"
            "func (s *Session) ControlCanSend() bool {\n"
            "\tif s.closed.Load() {\n"
            "\t\treturn false\n"
            "\t}\n"
            "\troom := s.currentRoom()\n"
            "\treturn room != nil && room.connectionState() == lksdk.ConnectionStateConnected\n"
            "}\n\n"
            "func (s *Session) currentRoom() roomHandle {",
            "методы control-плоскости",
        ),
    ],
)

print("internal/transport/datachannel/transport.go:")
patch(
    "internal/transport/datachannel/transport.go",
    [
        (
            "// Connect starts the transport connection.",
            "// ControlSend отправляет control-фрейм по отдельному каналу носителя.\n"
            "// Реализует transport.ControlPlane: без этого пинг и данные едут одним\n"
            "// потоком smux, и понг опаздывает на секунды под нагрузкой.\n"
            "func (p *streamTransport) ControlSend(data []byte) error {\n"
            "\tcs, ok := p.session.(engine.ControlSession)\n"
            "\tif !ok {\n"
            "\t\treturn p.Send(data)\n"
            "\t}\n"
            "\tif err := cs.SendControl(data); err != nil {\n"
            '\t\treturn fmt.Errorf("session send control: %w", err)\n'
            "\t}\n"
            "\treturn nil\n"
            "}\n\n"
            "// SetControlOnData регистрирует приёмник control-фреймов носителя.\n"
            "func (p *streamTransport) SetControlOnData(cb func([]byte)) {\n"
            "\tif cs, ok := p.session.(engine.ControlSession); ok {\n"
            "\t\tcs.SetOnControlData(cb)\n"
            "\t}\n"
            "}\n\n"
            "// ControlCanSend сообщает о готовности control-плоскости.\n"
            "func (p *streamTransport) ControlCanSend() bool {\n"
            "\tif cs, ok := p.session.(engine.ControlSession); ok {\n"
            "\t\treturn cs.ControlCanSend()\n"
            "\t}\n"
            "\treturn p.CanSend()\n"
            "}\n\n"
            "// Connect starts the transport connection.",
            "ControlPlane у datachannel",
        ),
    ],
)

print("патч наложен")
