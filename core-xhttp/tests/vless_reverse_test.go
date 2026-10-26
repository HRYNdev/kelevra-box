// Артефакт проверки, НЕ часть сборки (как остальные файлы в tests/): своего go.mod
// нет, собирается только внутри дерева sing-box.
//
// Зачем: проверка правки 8 из core-xhttp/apply.py — домашней стороны VLESS reverse
// (protocol/vless/kelevra_reverse*.go). Портал здесь поддельный, кадры собираются
// руками по Xray common/mux/frame.go (FrameMetadata.WriteTo на обратном mux: в New
// после адреса идут source и local). Совместимость с настоящим Xray проверяется
// отдельно, живым прогоном.
//
// Чтобы прогнать: после apply.py скопировать файл в protocol/vless/ дерева и
//   go test ./protocol/vless/ -run 'TestRvs' -v

package vless

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-vmess"
	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"

	"github.com/gofrs/uuid/v5"
)

const rvsTestUUID = "b831381d-6324-4d53-ad4f-8cda48b30811"

// ---------------------------------------------------------------- портал (клиент mux.cool)

func addrPort(t *testing.T, a M.Socksaddr) []byte {
	var b bytes.Buffer
	if err := vmess.AddressSerializer.WriteAddrPort(&b, a); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}

// portalFrame собирает кадр, как его пишет Xray-портал.
func portalFrame(t *testing.T, id uint16, status, option, network byte, target, source M.Socksaddr, data []byte) []byte {
	var meta bytes.Buffer
	binary.Write(&meta, binary.BigEndian, id)
	meta.WriteByte(status)
	meta.WriteByte(option)
	if network != 0 {
		meta.WriteByte(network)
		meta.Write(addrPort(t, target))
		if source.IsValid() {
			meta.WriteByte(network) // Network - 1 в Xray: TCP=1, UDP=2
			meta.Write(addrPort(t, source))
			meta.WriteByte(rvsNetworkTCP) // local
			meta.Write(addrPort(t, M.ParseSocksaddr("127.0.0.1:443")))
		}
	}
	var frame bytes.Buffer
	binary.Write(&frame, binary.BigEndian, uint16(meta.Len()))
	frame.Write(meta.Bytes())
	if option&rvsOptionData != 0 {
		binary.Write(&frame, binary.BigEndian, uint16(len(data)))
		frame.Write(data)
	}
	return frame.Bytes()
}

type gotFrame struct {
	id     uint16
	status byte
	option byte
	addr   M.Socksaddr
	data   []byte
}

// readBridgeFrame читает кадр моста, как ClientWorker.fetchOutput в Xray.
func readBridgeFrame(r io.Reader) (gotFrame, error) {
	var f gotFrame
	var l uint16
	if err := binary.Read(r, binary.BigEndian, &l); err != nil {
		return f, err
	}
	meta := make([]byte, l)
	if _, err := io.ReadFull(r, meta); err != nil {
		return f, err
	}
	f.id = binary.BigEndian.Uint16(meta)
	f.status, f.option = meta[2], meta[3]
	if len(meta) > 4 && meta[4] == rvsNetworkUDP {
		a, err := vmess.AddressSerializer.ReadAddrPort(bytes.NewReader(meta[5:]))
		if err != nil {
			return f, err
		}
		f.addr = a
	}
	if f.option&rvsOptionData != 0 {
		if err := binary.Read(r, binary.BigEndian, &l); err != nil {
			return f, err
		}
		f.data = make([]byte, l)
		if _, err := io.ReadFull(r, f.data); err != nil {
			return f, err
		}
	}
	return f, nil
}

func controlMessage(state byte) []byte {
	msg := []byte{}
	if state != 0 {
		msg = append(msg, 0x08, state) // поле 1, varint
	}
	random := bytes.Repeat([]byte{0xab}, 33)
	msg = append(msg, 0x9a, 0x06, byte(len(random))) // поле 99, длина
	return append(msg, random...)
}

// ---------------------------------------------------------------- роутер-эхо

type echoRouter struct {
	slowPort    uint16 // TCP на этот порт роутер держит и не читает (медленный читатель)
	mu          sync.Mutex
	packetDests []M.Socksaddr // адреса всех UDP-пакетов, дошедших до роутера
	inbounds    []string
	dests       []M.Socksaddr
	sources     []M.Socksaddr
}

func (r *echoRouter) note(metadata adapter.InboundContext) {
	r.mu.Lock()
	r.inbounds = append(r.inbounds, metadata.Inbound)
	r.dests = append(r.dests, metadata.Destination)
	r.sources = append(r.sources, metadata.Source)
	r.mu.Unlock()
}

func (r *echoRouter) RouteConnection(context.Context, net.Conn, adapter.InboundContext) error {
	return errors.New("unused")
}

func (r *echoRouter) RoutePacketConnection(context.Context, N.PacketConn, adapter.InboundContext) error {
	return errors.New("unused")
}

func (r *echoRouter) RouteConnectionEx(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	r.note(metadata)
	if r.slowPort != 0 && metadata.Destination.Port == r.slowPort {
		return
	}
	go func() {
		_, err := io.Copy(conn, conn)
		conn.Close()
		onClose(err)
	}()
}

func (r *echoRouter) RoutePacketConnectionEx(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	r.note(metadata)
	go func() {
		for {
			b := buf.NewPacket()
			dest, err := conn.ReadPacket(b)
			if err == nil {
				r.mu.Lock()
				r.packetDests = append(r.packetDests, dest)
				r.mu.Unlock()
			}
			if err != nil {
				b.Release()
				conn.Close()
				onClose(err)
				return
			}
			if err = conn.WritePacket(b, dest); err != nil {
				return
			}
		}
	}()
}

// ---------------------------------------------------------------- тесты кадров

func TestRvsRequestHeader(t *testing.T) {
	user := uuid.FromStringOrNil(rvsTestUUID)
	header := rvsRequestHeader(user)
	want := append(append([]byte{0}, user.Bytes()...), 0, 0x04)
	if !bytes.Equal(header, want) {
		t.Fatalf("header %x, want %x", header, want)
	}
}

func TestRvsParseFrameMeta(t *testing.T) {
	target := M.ParseSocksaddr("10.0.0.1:80")
	source := M.ParseSocksaddr("1.2.3.4:5555")
	raw := portalFrame(t, 2, rvsStatusNew, 0, rvsNetworkTCP, target, source, nil)
	f, err := parseRvsFrameMeta(raw[2:])
	if err != nil {
		t.Fatal(err)
	}
	if f.id != 2 || f.status != rvsStatusNew || f.network != rvsNetworkTCP || f.target != target || f.source != source {
		t.Fatalf("new tcp: %+v", f)
	}

	control := M.Socksaddr{Fqdn: rvsControlDomain}
	raw = portalFrame(t, 1, rvsStatusNew, 0, rvsNetworkUDP, control, M.Socksaddr{}, nil)
	f, err = parseRvsFrameMeta(raw[2:])
	if err != nil || f.network != rvsNetworkUDP || f.target.Fqdn != rvsControlDomain || f.source.IsValid() {
		t.Fatalf("new control: %+v %v", f, err)
	}

	wg := M.ParseSocksaddr("100.76.0.1:51820")
	raw = portalFrame(t, 3, rvsStatusKeep, rvsOptionData, rvsNetworkUDP, wg, M.Socksaddr{}, []byte{1})
	f, err = parseRvsFrameMeta(raw[2 : len(raw)-3])
	if err != nil || f.status != rvsStatusKeep || f.target != wg {
		t.Fatalf("keep udp: %+v %v", f, err)
	}

	raw = portalFrame(t, 3, rvsStatusKeep, 0, 0, M.Socksaddr{}, M.Socksaddr{}, nil)
	f, err = parseRvsFrameMeta(raw[2:])
	if err != nil || f.target.IsValid() || f.network != 0 {
		t.Fatalf("keep plain: %+v %v", f, err)
	}

	// New с набивкой из нулей после адреса (так Xray пишет без source) — не ошибка.
	raw = portalFrame(t, 4, rvsStatusNew, 0, rvsNetworkTCP, target, M.Socksaddr{}, nil)
	padded := append(append([]byte{}, raw[2:]...), 0, 0, 0)
	if f, err = parseRvsFrameMeta(padded); err != nil || f.source.IsValid() || f.target != target {
		t.Fatalf("new padded: %+v %v", f, err)
	}

	if _, err = parseRvsFrameMeta([]byte{0, 1, 1}); err == nil {
		t.Fatal("short meta accepted")
	}
	if _, err = parseRvsFrameMeta([]byte{0, 1, rvsStatusNew, 0, 9, 0, 80, 1, 1, 1, 1, 1}); err == nil {
		t.Fatal("unknown network accepted")
	}
}

func TestRvsEncodeFrame(t *testing.T) {
	wg := M.ParseSocksaddr("100.76.0.1:51820")
	frame := encodeRvsFrame(7, rvsStatusKeep, rvsOptionData, wg, []byte("abc"))
	defer frame.Release()
	got, err := readBridgeFrame(bytes.NewReader(frame.Bytes()))
	if err != nil {
		t.Fatal(err)
	}
	if got.id != 7 || got.status != rvsStatusKeep || got.addr != wg || string(got.data) != "abc" {
		t.Fatalf("%+v", got)
	}
	end := encodeRvsFrame(7, rvsStatusEnd, 0, M.Socksaddr{}, nil)
	defer end.Release()
	if !bytes.Equal(end.Bytes(), []byte{0, 4, 0, 7, rvsStatusEnd, 0}) {
		t.Fatalf("end %x", end.Bytes())
	}
}

func TestRvsControlState(t *testing.T) {
	if s, err := parseRvsControlState(controlMessage(0)); err != nil || s != 0 {
		t.Fatalf("active: %d %v", s, err)
	}
	if s, err := parseRvsControlState(controlMessage(rvsControlDrain)); err != nil || s != rvsControlDrain {
		t.Fatalf("drain: %d %v", s, err)
	}
	if s, err := parseRvsControlState(nil); err != nil || s != 0 {
		t.Fatalf("empty: %d %v", s, err)
	}
	for _, bad := range [][]byte{{0x08}, {0x9a, 0x06, 50, 1}, {0x0b}} {
		if _, err := parseRvsControlState(bad); err == nil {
			t.Fatalf("bad message %x accepted", bad)
		}
	}
}

// ---------------------------------------------------------------- мост целиком

type testPortal struct {
	t      *testing.T
	conn   net.Conn
	frames chan gotFrame
}

func acceptPortal(t *testing.T, conn net.Conn) *testPortal {
	header := make([]byte, 19)
	if _, err := io.ReadFull(conn, header); err != nil {
		t.Fatal(err)
	}
	if want := rvsRequestHeader(uuid.FromStringOrNil(rvsTestUUID)); !bytes.Equal(header, want) {
		t.Fatalf("request header %x", header)
	}
	p := &testPortal{t: t, conn: conn, frames: make(chan gotFrame, 64)}
	go func() {
		defer close(p.frames)
		for {
			f, err := readBridgeFrame(conn)
			if err != nil {
				return
			}
			p.frames <- f
		}
	}()
	// ответ VLESS (версия 0, без дополнений) и служебная сессия, как NewPortalWorker
	p.send(append([]byte{0, 0}, portalFrame(t, 1, rvsStatusNew, rvsOptionData, rvsNetworkUDP, M.Socksaddr{Fqdn: rvsControlDomain}, M.Socksaddr{}, controlMessage(0))...))
	return p
}

func (p *testPortal) send(b []byte) {
	if _, err := p.conn.Write(b); err != nil {
		p.t.Fatal(err)
	}
}

func (p *testPortal) expect(id uint16, status byte) gotFrame {
	select {
	case f, ok := <-p.frames:
		if !ok {
			p.t.Fatal("bridge closed the connection")
		}
		if f.id != id || f.status != status {
			p.t.Fatalf("frame id=%d status=%d, want id=%d status=%d", f.id, f.status, id, status)
		}
		return f
	case <-time.After(3 * time.Second):
		p.t.Fatalf("no frame id=%d status=%d", id, status)
	}
	return gotFrame{}
}

type pipeDialer struct {
	count atomic.Int32
	conns chan net.Conn
	fail  atomic.Bool
}

func (d *pipeDialer) dial(ctx context.Context) (net.Conn, error) {
	d.count.Add(1)
	if d.fail.Load() {
		return nil, errors.New("connection refused")
	}
	bridgeSide, portalSide := net.Pipe()
	d.conns <- portalSide
	return bridgeSide, nil
}

func waitFor(t *testing.T, what string, cond func() bool) {
	deadline := time.Now().Add(3 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatal("timeout waiting for ", what)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestRvsBridgeSessions(t *testing.T) {
	router := &echoRouter{}
	dialer := &pipeDialer{conns: make(chan net.Conn, 4)}
	bridge := newReverseBridgeWith(context.Background(), router, logger.NOP(), "dom-most", rvsTestUUID, dialer.dial)
	defer bridge.Close()

	bridge.monitor()
	p := acceptPortal(t, <-dialer.conns)

	// TCP: туда-обратно
	target := M.ParseSocksaddr("10.0.0.1:80")
	source := M.ParseSocksaddr("1.2.3.4:5555")
	p.send(portalFrame(t, 2, rvsStatusNew, rvsOptionData, rvsNetworkTCP, target, source, []byte("hello")))
	if f := p.expect(2, rvsStatusKeep); string(f.data) != "hello" || f.addr.IsValid() {
		t.Fatalf("tcp echo %+v", f)
	}
	big := bytes.Repeat([]byte("x"), 20000)
	for rest := big; len(rest) > 0; { // как Xray: поток кадрами по 8 КБ
		n := min(len(rest), rvsMaxChunk)
		p.send(portalFrame(t, 2, rvsStatusKeep, rvsOptionData, 0, M.Socksaddr{}, M.Socksaddr{}, rest[:n]))
		rest = rest[n:]
	}
	var echoed []byte
	for len(echoed) < len(big) {
		f := p.expect(2, rvsStatusKeep)
		if len(f.data) > rvsMaxChunk {
			t.Fatalf("chunk %d > %d", len(f.data), rvsMaxChunk)
		}
		echoed = append(echoed, f.data...)
	}
	if !bytes.Equal(echoed, big) {
		t.Fatal("tcp big echo mismatch")
	}

	// UDP: датаграмма 1280 байт, ответ с адресом
	wg := M.ParseSocksaddr("100.76.0.1:51820")
	packet := bytes.Repeat([]byte{0x5a}, 1280)
	p.send(portalFrame(t, 3, rvsStatusNew, rvsOptionData, rvsNetworkUDP, wg, source, packet))
	if f := p.expect(3, rvsStatusKeep); f.addr != wg || !bytes.Equal(f.data, packet) {
		t.Fatalf("udp echo addr=%s len=%d", f.addr, len(f.data))
	}
	// пакет той же сессии без адреса — на закреплённый адрес
	p.send(portalFrame(t, 3, rvsStatusKeep, rvsOptionData, 0, M.Socksaddr{}, M.Socksaddr{}, []byte("q")))
	if f := p.expect(3, rvsStatusKeep); f.addr != wg || string(f.data) != "q" {
		t.Fatalf("udp keep addr=%s data=%q", f.addr, f.data)
	}

	router.mu.Lock()
	if len(router.inbounds) != 2 || router.inbounds[0] != "dom-most" || router.inbounds[1] != "dom-most" ||
		router.dests[0] != target || router.dests[1] != wg || router.sources[0] != source {
		t.Fatalf("routed %v %v %v", router.inbounds, router.dests, router.sources)
	}
	router.mu.Unlock()

	// Keep на чужую сессию — End в ответ
	p.send(portalFrame(t, 9, rvsStatusKeep, rvsOptionData, 0, M.Socksaddr{}, M.Socksaddr{}, []byte("?")))
	p.expect(9, rvsStatusEnd)

	// End от портала закрывает сессию у нас; эхо закрывает свою сторону без End
	w := bridge.workers[0]
	if n := w.sessionCount(); n != 3 {
		t.Fatalf("sessions %d, want 3 (control, tcp, udp)", n)
	}
	p.send(portalFrame(t, 2, rvsStatusEnd, 0, 0, M.Socksaddr{}, M.Socksaddr{}, nil))
	waitFor(t, "tcp session removed", func() bool { return w.sessionCount() == 2 })

	// Наша сторона закрывает UDP — End порталу
	w.access.Lock()
	udp := w.sessions[3]
	w.access.Unlock()
	udp.Close()
	p.expect(3, rvsStatusEnd)

	// DRAIN: соединение уведено, monitor звонит ещё раз, старое без сессий закрывается
	p.send(portalFrame(t, 1, rvsStatusKeep, rvsOptionData, 0, M.Socksaddr{}, M.Socksaddr{}, controlMessage(rvsControlDrain)))
	waitFor(t, "drain", w.draining.Load)
	bridge.monitor()
	waitFor(t, "redial after drain", func() bool { return dialer.count.Load() == 2 })
	acceptPortal(t, <-dialer.conns)
	waitFor(t, "drained worker closed", w.isClosed)
}

func TestRvsBridgeRedial(t *testing.T) {
	saved := reverseSilenceTimeout
	savedInterval := reverseCheckInterval
	reverseSilenceTimeout = 300 * time.Millisecond
	reverseCheckInterval = 50 * time.Millisecond
	defer func() {
		reverseSilenceTimeout = saved
		reverseCheckInterval = savedInterval
	}()

	dialer := &pipeDialer{conns: make(chan net.Conn, 4)}
	dialer.fail.Store(true)
	bridge := newReverseBridgeWith(context.Background(), &echoRouter{}, logger.NOP(), "dom-most", rvsTestUUID, dialer.dial)
	defer bridge.Close()
	bridge.start()

	// портал лежит — мост пробует снова и снова, каждый тик
	waitFor(t, "redial attempts", func() bool { return dialer.count.Load() >= 3 })
	dialer.fail.Store(false)
	p := acceptPortal(t, <-dialer.conns)
	before := dialer.count.Load()

	// обрыв портала — перезвон
	p.conn.Close()
	p = acceptPortal(t, <-dialer.conns)
	if dialer.count.Load() <= before {
		t.Fatal("no redial after portal close")
	}

	// портал молчит (не шлёт пульс) — мост рвёт и звонит снова
	before = dialer.count.Load()
	select {
	case conn := <-dialer.conns:
		acceptPortal(t, conn)
	case <-time.After(3 * time.Second):
		t.Fatal("no redial after portal silence")
	}
	if dialer.count.Load() <= before {
		t.Fatal("dial count did not grow")
	}
	_ = p
}

func TestRvsBridgeScale(t *testing.T) {
	dialer := &pipeDialer{conns: make(chan net.Conn, 4)}
	bridge := newReverseBridgeWith(context.Background(), &echoRouter{}, logger.NOP(), "dom-most", rvsTestUUID, dialer.dial)
	defer bridge.Close()
	bridge.monitor()
	p := acceptPortal(t, <-dialer.conns)
	bridge.monitor()
	if len(bridge.workers) != 1 {
		t.Fatal("second dial without load")
	}
	// 17 сессий + служебная на одном соединении — больше 16, нужен второй звонок
	for i := 0; i < 17; i++ {
		p.send(portalFrame(t, uint16(10+i), rvsStatusNew, 0, rvsNetworkTCP, M.Socksaddr{Addr: netip.MustParseAddr("10.0.0.2"), Port: 22}, M.Socksaddr{}, nil))
	}
	w := bridge.workers[0]
	waitFor(t, "sessions", func() bool { return w.sessionCount() == 18 })
	bridge.monitor()
	waitFor(t, "second dial under load", func() bool { return dialer.count.Load() == 2 })
	acceptPortal(t, <-dialer.conns)
}

// Смена сети (InterfaceUpdated): соединения рвутся, звонок сразу, не через тик.
func TestRvsBridgeReset(t *testing.T) {
	savedInterval := reverseCheckInterval
	reverseCheckInterval = time.Hour
	defer func() { reverseCheckInterval = savedInterval }()

	dialer := &pipeDialer{conns: make(chan net.Conn, 4)}
	bridge := newReverseBridgeWith(context.Background(), &echoRouter{}, logger.NOP(), "dom-most", rvsTestUUID, dialer.dial)
	defer bridge.Close()
	bridge.start()
	p := acceptPortal(t, <-dialer.conns)
	bridge.reset()
	select {
	case conn := <-dialer.conns:
		acceptPortal(t, conn)
	case <-time.After(time.Second):
		t.Fatal("no immediate redial after reset")
	}
	if _, ok := <-p.frames; ok {
		t.Fatal("old connection still open after reset")
	}
}

func readRvsState(t *testing.T, path string) (reverseStateFile, map[string]any, bool) {
	content, err := os.ReadFile(path)
	if err != nil {
		return reverseStateFile{}, nil, false
	}
	var state reverseStateFile
	var raw map[string]any
	if json.Unmarshal(content, &state) != nil || json.Unmarshal(content, &raw) != nil {
		t.Fatalf("state is not json: %q", content)
	}
	return state, raw, true
}

// Файл состояния: формат, запись при изменениях, атомарность (без хвостов .tmp).
func TestRvsBridgeStateFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "dom-uzel", "most-most-vps2.json")
	dialer := &pipeDialer{conns: make(chan net.Conn, 4)}
	bridge := newReverseBridgeWith(context.Background(), &echoRouter{}, logger.NOP(), "dom-most", rvsTestUUID, dialer.dial)
	bridge.state = newReverseState(path, "most-vps2", "203.0.113.7:443")
	bridge.start()

	// до звонка: файл есть, связи нет, пульса не было (null)
	var state reverseStateFile
	var raw map[string]any
	waitFor(t, "initial state", func() bool {
		var ok bool
		state, raw, ok = readRvsState(t, path)
		return ok
	})
	for _, key := range []string{"tag", "server", "connected", "since", "last_pulse", "sessions", "conns"} {
		if _, ok := raw[key]; !ok {
			t.Fatalf("no %q in state %v", key, raw)
		}
	}
	if state.Tag != "most-vps2" || state.Server != "203.0.113.7:443" || state.Connected || state.LastPulse != nil || raw["last_pulse"] != nil {
		t.Fatalf("initial state %+v", state)
	}
	if _, err := time.Parse(time.RFC3339, state.Since); err != nil {
		t.Fatal("since: ", err)
	}

	// звонок + пульс + сессия
	p := acceptPortal(t, <-dialer.conns)
	p.send(portalFrame(t, 2, rvsStatusNew, rvsOptionData, rvsNetworkTCP, M.ParseSocksaddr("10.0.0.1:80"), M.Socksaddr{}, []byte("hi")))
	p.expect(2, rvsStatusKeep)
	waitFor(t, "connected state", func() bool {
		state, _, _ = readRvsState(t, path)
		return state.Connected && state.Conns == 1 && state.Sessions == 1 && state.LastPulse != nil
	})
	if _, err := time.Parse(time.RFC3339, *state.LastPulse); err != nil {
		t.Fatal("last_pulse: ", err)
	}
	connectedSince := state.Since

	// обрыв: connected=false сразу, since сдвигается, пульс помнится
	time.Sleep(1100 * time.Millisecond) // RFC3339 — секунды
	p.conn.Close()
	waitFor(t, "disconnected state", func() bool {
		state, _, _ = readRvsState(t, path)
		return !state.Connected && state.Conns == 0 && state.Sessions == 0
	})
	if state.Since == connectedSince || state.LastPulse == nil {
		t.Fatalf("since/last_pulse after disconnect: %+v", state)
	}
	bridge.Close()
	state, _, _ = readRvsState(t, path)
	if state.Connected {
		t.Fatal("connected after close")
	}
	entries, _ := os.ReadDir(filepath.Dir(path))
	if len(entries) != 1 {
		t.Fatalf("leftover files: %v", entries)
	}
}

// Без state_path файлов нет.
func TestRvsBridgeNoStateFile(t *testing.T) {
	dialer := &pipeDialer{conns: make(chan net.Conn, 4)}
	bridge := newReverseBridgeWith(context.Background(), &echoRouter{}, logger.NOP(), "dom-most", rvsTestUUID, dialer.dial)
	if bridge.state != nil {
		t.Fatal("state without state_path")
	}
	bridge.notifyState() // не паникует без файла
	bridge.Close()
}

// Без изменений файл всё равно переписывается раз в reverseStateInterval.
func TestRvsBridgeStatePeriodic(t *testing.T) {
	saved := reverseStateInterval
	reverseStateInterval = 100 * time.Millisecond
	defer func() { reverseStateInterval = saved }()
	path := filepath.Join(t.TempDir(), "most.json")
	dialer := &pipeDialer{conns: make(chan net.Conn, 4)}
	dialer.fail.Store(true)
	bridge := newReverseBridgeWith(context.Background(), &echoRouter{}, logger.NOP(), "dom-most", rvsTestUUID, dialer.dial)
	bridge.state = newReverseState(path, "most-vps1", "127.0.0.1:443")
	defer bridge.Close()
	bridge.start()
	waitFor(t, "first write", func() bool { _, err := os.Stat(path); return err == nil })
	first, _ := os.Stat(path)
	waitFor(t, "periodic rewrite", func() bool {
		info, err := os.Stat(path)
		return err == nil && !os.SameFile(first, info)
	})
}

// ---------------------------------------------------------------- пределы (договор, «Безопасность», п.8)

func setRvsLimit[T any](t *testing.T, v *T, value T) {
	saved := *v
	*v = value
	t.Cleanup(func() { *v = saved })
}

func startRvsBridge(t *testing.T, router *echoRouter) (*reverseBridge, *testPortal) {
	dialer := &pipeDialer{conns: make(chan net.Conn, 4)}
	bridge := newReverseBridgeWith(context.Background(), router, logger.NOP(), "dom-most", rvsTestUUID, dialer.dial)
	t.Cleanup(func() { bridge.Close() })
	bridge.monitor()
	return bridge, acceptPortal(t, <-dialer.conns)
}

// (1) Потолок сессий: новая сверх — End с ошибкой, в роутер не идёт; место
// освободилось — снова принимаем.
func TestRvsSessionLimit(t *testing.T) {
	setRvsLimit(t, &rvsMaxSessions, 3)
	router := &echoRouter{}
	_, p := startRvsBridge(t, router)
	target := M.ParseSocksaddr("10.0.0.1:80")
	for id := uint16(2); id <= 4; id++ {
		p.send(portalFrame(t, id, rvsStatusNew, rvsOptionData, rvsNetworkTCP, target, M.Socksaddr{}, []byte("a")))
		p.expect(id, rvsStatusKeep)
	}
	p.send(portalFrame(t, 5, rvsStatusNew, rvsOptionData, rvsNetworkUDP, M.ParseSocksaddr("100.76.0.1:51820"), M.Socksaddr{}, []byte("b")))
	if f := p.expect(5, rvsStatusEnd); f.option&rvsOptionError == 0 {
		t.Fatal("refused session End without error flag")
	}
	router.mu.Lock()
	routed := len(router.inbounds)
	router.mu.Unlock()
	if routed != 3 {
		t.Fatalf("routed %d sessions, want 3", routed)
	}
	p.send(portalFrame(t, 2, rvsStatusEnd, 0, 0, M.Socksaddr{}, M.Socksaddr{}, nil))
	time.Sleep(50 * time.Millisecond)
	p.send(portalFrame(t, 6, rvsStatusNew, rvsOptionData, rvsNetworkTCP, target, M.Socksaddr{}, []byte("c")))
	if f := p.expect(6, rvsStatusKeep); string(f.data) != "c" {
		t.Fatalf("after free slot: %+v", f)
	}
}

// (2) UDP без трафика дольше таймаута закрывается; живая UDP-сессия — нет.
func TestRvsUDPIdleTimeout(t *testing.T) {
	setRvsLimit(t, &rvsUDPIdleTimeout, 300*time.Millisecond)
	setRvsLimit(t, &reverseCheckInterval, 50*time.Millisecond)
	_, p := startRvsBridge(t, &echoRouter{})
	wg := M.ParseSocksaddr("100.76.0.1:51820")
	p.send(portalFrame(t, 2, rvsStatusNew, rvsOptionData, rvsNetworkUDP, wg, M.Socksaddr{}, []byte("idle")))
	p.expect(2, rvsStatusKeep)
	p.send(portalFrame(t, 3, rvsStatusNew, rvsOptionData, rvsNetworkUDP, wg, M.Socksaddr{}, []byte("live")))
	p.expect(3, rvsStatusKeep)
	start := time.Now()
	gotIdleEnd := false
	for time.Since(start) < 900*time.Millisecond {
		p.send(portalFrame(t, 3, rvsStatusKeep, rvsOptionData, 0, M.Socksaddr{}, M.Socksaddr{}, []byte("k")))
		deadline := time.After(100 * time.Millisecond)
	collect:
		for {
			select {
			case f := <-p.frames:
				if f.status == rvsStatusEnd && f.id == 3 {
					t.Fatal("live udp session closed")
				}
				if f.status == rvsStatusEnd && f.id == 2 {
					gotIdleEnd = true
				}
			case <-deadline:
				break collect
			}
		}
	}
	if !gotIdleEnd {
		t.Fatal("idle udp session not closed")
	}
}

// (3) Медленный читатель: его очередь переполнилась — сессия закрыта (End), а
// чтение mux не встало: пульс доходит, другая сессия отвечает, бюджет памяти
// вернулся.
func TestRvsSlowReader(t *testing.T) {
	router := &echoRouter{slowPort: 9}
	bridge, p := startRvsBridge(t, router)
	w := bridge.workers[0]
	slow := M.ParseSocksaddr("10.0.0.1:9")
	chunk := bytes.Repeat([]byte("s"), rvsMaxChunk)
	p.send(portalFrame(t, 2, rvsStatusNew, rvsOptionData, rvsNetworkTCP, slow, M.Socksaddr{}, chunk))
	done := make(chan struct{})
	go func() { // весь поток уходит без блокировки, хотя его никто не читает
		for i := 0; i < rvsTCPQueueLen+8; i++ {
			p.send(portalFrame(t, 2, rvsStatusKeep, rvsOptionData, 0, M.Socksaddr{}, M.Socksaddr{}, chunk))
		}
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("mux read blocked by slow session")
	}
	p.expect(2, rvsStatusEnd) // и ещё по End на каждый опоздавший кадр, как в Xray
	before := bridge.lastPulse.Load()
	time.Sleep(30 * time.Millisecond) // часы Windows идут шагами до 15 мс
	p.send(portalFrame(t, 1, rvsStatusKeep, rvsOptionData, 0, M.Socksaddr{}, M.Socksaddr{}, controlMessage(0)))
	waitFor(t, "pulse after flood", func() bool { return bridge.lastPulse.Load() != before })
	p.send(portalFrame(t, 3, rvsStatusNew, rvsOptionData, rvsNetworkTCP, M.ParseSocksaddr("10.0.0.1:80"), M.Socksaddr{}, []byte("ok")))
	for {
		f := <-p.frames
		if f.id == 2 && f.status == rvsStatusEnd {
			continue
		}
		if f.id != 3 || f.status != rvsStatusKeep || string(f.data) != "ok" {
			t.Fatalf("other session %+v", f)
		}
		break
	}
	waitFor(t, "queued bytes released", func() bool { return w.queued.Load() == 0 })
}

// (3) Общий бюджет очередей соединения: сверх — сессия закрывается, как при
// полной своей очереди.
func TestRvsQueueBudget(t *testing.T) {
	setRvsLimit(t, &rvsMaxQueuedBytes, int64(3*rvsMaxChunk))
	bridge, p := startRvsBridge(t, &echoRouter{slowPort: 9})
	w := bridge.workers[0]
	chunk := bytes.Repeat([]byte("q"), rvsMaxChunk)
	p.send(portalFrame(t, 2, rvsStatusNew, rvsOptionData, rvsNetworkTCP, M.ParseSocksaddr("10.0.0.1:9"), M.Socksaddr{}, chunk))
	p.send(portalFrame(t, 3, rvsStatusNew, rvsOptionData, rvsNetworkTCP, M.ParseSocksaddr("10.0.0.2:9"), M.Socksaddr{}, chunk))
	p.send(portalFrame(t, 3, rvsStatusKeep, rvsOptionData, 0, M.Socksaddr{}, M.Socksaddr{}, chunk))
	p.send(portalFrame(t, 3, rvsStatusKeep, rvsOptionData, 0, M.Socksaddr{}, M.Socksaddr{}, chunk))
	p.expect(3, rvsStatusEnd)
	// End уходит раньше, чем закрытая сессия вернёт байты в бюджет
	waitFor(t, "budget: only session 2 queued", func() bool { return w.queued.Load() == int64(rvsMaxChunk) })
}

// (4) Кадр с данными больше rvsMaxFrameData рвёт соединение.
func TestRvsFrameTooLarge(t *testing.T) {
	bridge, p := startRvsBridge(t, &echoRouter{})
	w := bridge.workers[0]
	p.send(portalFrame(t, 2, rvsStatusNew, rvsOptionData, rvsNetworkTCP, M.ParseSocksaddr("10.0.0.1:80"), M.Socksaddr{}, []byte("x")))
	p.expect(2, rvsStatusKeep)
	go p.conn.Write(portalFrame(t, 2, rvsStatusKeep, rvsOptionData, 0, M.Socksaddr{}, M.Socksaddr{}, make([]byte, rvsMaxFrameData+1)))
	waitFor(t, "connection closed on oversized frame", w.isClosed)
}

// ---------------------------------------------------------------- круг 2 (договор, «Безопасность», п.9)

// Keep с чужим адресом не доходит до роутера: UDP-пакет выброшен, TCP-сессия
// закрыта. До роутера доходят только пакеты на адрес из кадра New.
func TestRvsPinnedAddress(t *testing.T) {
	router := &echoRouter{}
	_, p := startRvsBridge(t, router)
	wg := M.ParseSocksaddr("100.76.0.1:51820")
	p.send(portalFrame(t, 2, rvsStatusNew, rvsOptionData, rvsNetworkUDP, wg, M.Socksaddr{}, []byte("first")))
	p.expect(2, rvsStatusKeep)
	for _, other := range []M.Socksaddr{
		M.ParseSocksaddr("100.76.0.1:22"),   // тот же адрес, другой порт: NAT override_address его бы пропустил
		M.ParseSocksaddr("192.168.1.1:53"),  // чужой адрес: ушёл бы мимо правил
		M.ParseSocksaddr("127.0.0.1:51820"), // прямо на хаб
		{Fqdn: "router.dom", Port: 51820},   // домен
	} {
		p.send(portalFrame(t, 2, rvsStatusKeep, rvsOptionData, rvsNetworkUDP, other, M.Socksaddr{}, []byte("evil")))
	}
	p.send(portalFrame(t, 2, rvsStatusKeep, rvsOptionData, rvsNetworkUDP, wg, M.Socksaddr{}, []byte("same")))
	if f := p.expect(2, rvsStatusKeep); string(f.data) != "same" || f.addr != wg {
		t.Fatalf("after foreign packets got %+v", f)
	}
	router.mu.Lock()
	dests := append([]M.Socksaddr(nil), router.packetDests...)
	router.mu.Unlock()
	if len(dests) != 2 {
		t.Fatalf("router got %d packets, want 2: %v", len(dests), dests)
	}
	for _, d := range dests {
		if d != wg {
			t.Fatalf("router got packet to %s", d)
		}
	}

	// TCP-сессия с кадром, несущим UDP-адрес, — кадр сломан, сессия закрыта
	p.send(portalFrame(t, 3, rvsStatusNew, rvsOptionData, rvsNetworkTCP, M.ParseSocksaddr("10.0.0.1:80"), M.Socksaddr{}, []byte("t")))
	p.expect(3, rvsStatusKeep)
	p.send(portalFrame(t, 3, rvsStatusKeep, rvsOptionData, rvsNetworkUDP, M.ParseSocksaddr("10.0.0.1:81"), M.Socksaddr{}, []byte("x")))
	p.expect(3, rvsStatusEnd)
}

// Не больше rvsMaxWorkers соединений к одному порталу, даже если все уведены.
func TestRvsWorkerCap(t *testing.T) {
	dialer := &pipeDialer{conns: make(chan net.Conn, 8)}
	bridge := newReverseBridgeWith(context.Background(), &echoRouter{}, logger.NOP(), "dom-most", rvsTestUUID, dialer.dial)
	defer bridge.Close()
	for i := 1; i <= rvsMaxWorkers+2; i++ {
		bridge.monitor()
		if i > rvsMaxWorkers {
			time.Sleep(50 * time.Millisecond)
			if n := dialer.count.Load(); n != int32(rvsMaxWorkers) {
				t.Fatalf("dials %d, cap %d", n, rvsMaxWorkers)
			}
			continue
		}
		waitFor(t, "dial", func() bool { return dialer.count.Load() == int32(i) })
		p := acceptPortal(t, <-dialer.conns)
		// сессия держит соединение живым, DRAIN уводит его
		p.send(portalFrame(t, 2, rvsStatusNew, 0, rvsNetworkTCP, M.ParseSocksaddr("10.0.0.1:80"), M.Socksaddr{}, nil))
		p.send(portalFrame(t, 1, rvsStatusKeep, rvsOptionData, 0, M.Socksaddr{}, M.Socksaddr{}, controlMessage(rvsControlDrain)))
		w := bridge.workers[len(bridge.workers)-1]
		waitFor(t, "drain", w.draining.Load)
	}
}

// Общие пределы на весь процесс: сумма по соединениям разных аутбаундов.
func TestRvsTotalLimits(t *testing.T) {
	setRvsLimit(t, &rvsMaxTotalSessions, int(rvsTotalSessions.Load())+2)
	_, a := startRvsBridge(t, &echoRouter{})
	_, b := startRvsBridge(t, &echoRouter{})
	target := M.ParseSocksaddr("10.0.0.1:80")
	a.send(portalFrame(t, 2, rvsStatusNew, rvsOptionData, rvsNetworkTCP, target, M.Socksaddr{}, []byte("a")))
	a.expect(2, rvsStatusKeep)
	b.send(portalFrame(t, 2, rvsStatusNew, rvsOptionData, rvsNetworkTCP, target, M.Socksaddr{}, []byte("b")))
	b.expect(2, rvsStatusKeep)
	b.send(portalFrame(t, 3, rvsStatusNew, rvsOptionData, rvsNetworkTCP, target, M.Socksaddr{}, []byte("c")))
	if f := b.expect(3, rvsStatusEnd); f.option&rvsOptionError == 0 {
		t.Fatal("over total limit: End without error")
	}
	a.send(portalFrame(t, 2, rvsStatusEnd, 0, 0, M.Socksaddr{}, M.Socksaddr{}, nil))
	time.Sleep(50 * time.Millisecond)
	b.send(portalFrame(t, 4, rvsStatusNew, rvsOptionData, rvsNetworkTCP, target, M.Socksaddr{}, []byte("d")))
	if f := b.expect(4, rvsStatusKeep); string(f.data) != "d" {
		t.Fatalf("after free slot %+v", f)
	}
}

func TestRvsTotalQueueBudget(t *testing.T) {
	setRvsLimit(t, &rvsMaxTotalQueuedBytes, rvsTotalQueued.Load()+int64(2*rvsMaxChunk))
	_, a := startRvsBridge(t, &echoRouter{slowPort: 9})
	_, b := startRvsBridge(t, &echoRouter{slowPort: 9})
	chunk := bytes.Repeat([]byte("q"), rvsMaxChunk)
	slow := M.ParseSocksaddr("10.0.0.1:9")
	a.send(portalFrame(t, 2, rvsStatusNew, rvsOptionData, rvsNetworkTCP, slow, M.Socksaddr{}, chunk))
	a.send(portalFrame(t, 2, rvsStatusKeep, rvsOptionData, 0, M.Socksaddr{}, M.Socksaddr{}, chunk))
	b.send(portalFrame(t, 2, rvsStatusNew, rvsOptionData, rvsNetworkTCP, slow, M.Socksaddr{}, chunk))
	b.expect(2, rvsStatusEnd) // бюджет процесса занят соединением a
}
