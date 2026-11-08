// Kelevra (core-xhttp/apply.py, правка 8a/8b): сторож линии ClientBind.
//
// Кладётся apply.py в transport/wireguard/ (tests/v_derevo/**), в бинарь не входит.
// Прогон: go test -count=1 -run Kelevra ./transport/wireguard/
//
// Воспроизводит «молчаливую смерть»: detour (у нас vless+XHTTP) отдаёт net.Conn,
// у которого Write проходит, а Read висит до Close — ни FIN, ни RST. Апстрим
// (и сторож, выключенный через kelevraStorozhInitiations = 0) на такой линии
// висит навсегда: передиала нет. Со сторожем ClientBind рвёт линию после N
// безответных handshake initiation и передиаливает, а на новой (живой) линии
// ответ пира доходит до receive.
//
// Сети нет: линия — фальшивый net.Conn в памяти, пир — отвечает на initiation
// (тип 1) пакетом с типом 2, как настоящий WireGuard.

package wireguard

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service/pause"
	"github.com/sagernet/wireguard-go/conn"
)

// fakeLink — линия до пира. Write считает отправленное; Read отдаёт то, что
// «пришло», или висит до Close (молчаливая смерть: ни ошибки, ни данных).
type fakeLink struct {
	remote    *net.UDPAddr
	answers   chan []byte
	closed    chan struct{}
	closeOnce sync.Once
	writes    atomic.Int32
	alive     bool // живой пир: отвечает на initiation
}

func (l *fakeLink) Read(p []byte) (int, error) {
	select {
	case b := <-l.answers:
		return copy(p, b), nil
	case <-l.closed:
		return 0, net.ErrClosed
	}
}

func (l *fakeLink) Write(p []byte) (int, error) {
	select {
	case <-l.closed:
		return 0, net.ErrClosed
	default:
	}
	l.writes.Add(1)
	if l.alive && len(p) > 0 && p[0] == kelevraWGMessageInitiation {
		resp := make([]byte, 92)
		resp[0] = 2 // handshake response
		select {
		case l.answers <- resp:
		default:
		}
	}
	return len(p), nil
}

func (l *fakeLink) Close() error {
	l.closeOnce.Do(func() { close(l.closed) })
	return nil
}

func (l *fakeLink) LocalAddr() net.Addr                { return &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 1} }
func (l *fakeLink) RemoteAddr() net.Addr               { return l.remote }
func (l *fakeLink) SetDeadline(t time.Time) error      { return nil }
func (l *fakeLink) SetReadDeadline(t time.Time) error  { return nil }
func (l *fakeLink) SetWriteDeadline(t time.Time) error { return nil }

// fakeDialer — detour. Каждый DialContext даёт новую линию; живыми становятся
// линии начиная с номера aliveFrom (с нуля), -1 — все мёртвые.
type fakeDialer struct {
	mu        sync.Mutex
	links     []*fakeLink
	aliveFrom int
}

func (d *fakeDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	l := &fakeLink{
		remote:  destination.UDPAddr(),
		answers: make(chan []byte, 16),
		closed:  make(chan struct{}),
	}
	l.alive = d.aliveFrom >= 0 && len(d.links) >= d.aliveFrom
	d.links = append(d.links, l)
	return l, nil
}

func (d *fakeDialer) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("not used")
}

func (d *fakeDialer) count() int {
	d.mu.Lock()
	defer d.mu.Unlock()
	return len(d.links)
}

func (d *fakeDialer) link(i int) *fakeLink {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.links[i]
}

// runReceive — как RoutineReceiveIncoming у wireguard-go: зовёт receive в цикле,
// выходит по net.ErrClosed, на прочие ошибки — пауза и снова.
func runReceive(bind *ClientBind, got chan<- []byte, stopped chan<- struct{}) {
	defer close(stopped)
	packets := [][]byte{make([]byte, 2048)}
	sizes := make([]int, 1)
	eps := make([]conn.Endpoint, 1)
	for {
		n, err := bind.receive(packets, sizes, eps)
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return
			}
			time.Sleep(time.Second / 3)
			continue
		}
		if n == 1 {
			got <- append([]byte(nil), packets[0][:sizes[0]]...)
		}
	}
}

func newTestBind(t *testing.T, dialer *fakeDialer) (*ClientBind, conn.Endpoint, chan []byte, chan struct{}) {
	t.Helper()
	peer := netip.MustParseAddrPort("100.76.0.1:51820")
	ctx := pause.WithDefaultManager(context.Background())
	bind := NewClientBind(ctx, logger.NOP(), dialer, true, peer, [3]uint8{})
	if _, _, err := bind.Open(0); err != nil {
		t.Fatalf("open: %v", err)
	}
	got := make(chan []byte, 16)
	stopped := make(chan struct{})
	go runReceive(bind, got, stopped)
	t.Cleanup(func() {
		bind.Close()
		select {
		case <-stopped:
		case <-time.After(5 * time.Second):
			t.Errorf("receive loop did not stop after Close")
		}
	})
	return bind, remoteEndpoint(peer), got, stopped
}

func setStorozh(t *testing.T, initiations int32, window time.Duration) {
	t.Helper()
	oldN, oldW := kelevraStorozhInitiations, kelevraStorozhWindow
	kelevraStorozhInitiations, kelevraStorozhWindow = initiations, window
	t.Cleanup(func() { kelevraStorozhInitiations, kelevraStorozhWindow = oldN, oldW })
}

func initiation() [][]byte {
	b := make([]byte, 148)
	b[0] = kelevraWGMessageInitiation
	return [][]byte{b}
}

// Без сторожа (поведение апстрима): линия молчит, Write проходит — передиала
// нет, сколько ни шли рукопожатий. Это и есть «Дом мёртв до перезапуска ядра».
func TestKelevraClientBindSilentLinkHangsWithoutWatchdog(t *testing.T) {
	setStorozh(t, 0, 0)
	dialer := &fakeDialer{aliveFrom: -1}
	bind, ep, got, _ := newTestBind(t, dialer)

	for i := 0; i < 10; i++ {
		if err := bind.Send(initiation(), ep, 0); err != nil {
			t.Fatalf("send %d: %v", i, err)
		}
		time.Sleep(20 * time.Millisecond)
	}
	if n := dialer.count(); n != 1 {
		t.Fatalf("без сторожа ожидался один диал, получили %d", n)
	}
	select {
	case p := <-got:
		t.Fatalf("на мёртвой линии ничего не должно прийти, пришло %d байт", len(p))
	default:
	}
}

// Со сторожем: после N безответных initiation (и окна) линия рвётся, connect()
// передиаливает, ответ пира с новой линии доходит до receive.
func TestKelevraClientBindWatchdogReconnectsSilentLink(t *testing.T) {
	setStorozh(t, 3, 150*time.Millisecond)
	dialer := &fakeDialer{aliveFrom: 1} // первая линия мёртвая, вторая живая
	bind, ep, got, _ := newTestBind(t, dialer)

	deadline := time.Now().Add(5 * time.Second)
	var sawWatchdogErr bool
	for dialer.count() < 2 && time.Now().Before(deadline) {
		if err := bind.Send(initiation(), ep, 0); err != nil {
			sawWatchdogErr = true
		}
		time.Sleep(60 * time.Millisecond)
	}
	if dialer.count() != 2 {
		t.Fatalf("сторож не передиалил: диалов %d", dialer.count())
	}
	if !sawWatchdogErr {
		t.Fatalf("ожидалась ошибка Send в момент разрыва линии сторожем")
	}
	select {
	case <-dialer.link(0).closed:
	case <-time.After(2 * time.Second):
		t.Fatalf("первая (мёртвая) линия не закрыта")
	}

	// На новой линии пир живой: initiation → response доходит до receive.
	if err := bind.Send(initiation(), ep, 0); err != nil {
		t.Fatalf("send on new link: %v", err)
	}
	select {
	case p := <-got:
		if len(p) == 0 || p[0] != 2 {
			t.Fatalf("ожидали handshake response (тип 2), получили %v", p[:min(4, len(p))])
		}
	case <-time.After(3 * time.Second):
		t.Fatalf("ответ пира с новой линии не дошёл до receive")
	}
	if n := dialer.count(); n != 2 {
		t.Fatalf("после оживления лишние диалы: %d", n)
	}
}

// Живая линия: пир отвечает — сторож не срабатывает, сколько бы рукопожатий ни шло.
func TestKelevraClientBindWatchdogNoFalsePositiveOnLiveLink(t *testing.T) {
	setStorozh(t, 3, 100*time.Millisecond)
	dialer := &fakeDialer{aliveFrom: 0}
	bind, ep, got, _ := newTestBind(t, dialer)

	for i := 0; i < 12; i++ {
		if err := bind.Send(initiation(), ep, 0); err != nil {
			t.Fatalf("send %d: %v", i, err)
		}
		select {
		case <-got:
		case <-time.After(2 * time.Second):
			t.Fatalf("ответ пира %d не дошёл", i)
		}
		time.Sleep(60 * time.Millisecond)
	}
	if n := dialer.count(); n != 1 {
		t.Fatalf("на живой линии ожидался один диал, получили %d", n)
	}
}

// Пакеты данных (тип 4) и keepalive сторож не считает: при простое клиент шлёт
// keepalive, пир на них не отвечает — это не смерть линии.
func TestKelevraClientBindWatchdogIgnoresNonInitiation(t *testing.T) {
	setStorozh(t, 2, 0)
	dialer := &fakeDialer{aliveFrom: -1}
	bind, ep, _, _ := newTestBind(t, dialer)

	data := make([]byte, 32)
	data[0] = 4 // transport data / keepalive
	for i := 0; i < 10; i++ {
		if err := bind.Send([][]byte{data}, ep, 0); err != nil {
			t.Fatalf("send %d: %v", i, err)
		}
	}
	if n := dialer.count(); n != 1 {
		t.Fatalf("данные без ответа не должны рвать линию, диалов %d", n)
	}
}

// Правка 8b: Close линии не держит connect(): done закрыт до самого Close detour.
func TestKelevraWireConnCloseMarksDoneBeforeSlowDetourClose(t *testing.T) {
	block := make(chan struct{})
	slow := &slowClosePacketConn{block: block}
	w := &wireConn{PacketConn: slow, done: make(chan struct{})}

	closed := make(chan struct{})
	go func() {
		w.Close()
		close(closed)
	}()
	select {
	case <-w.done:
	case <-time.After(2 * time.Second):
		t.Fatalf("done не закрыт, пока Close detour висит")
	}
	select {
	case <-closed:
		t.Fatalf("Close вернулся раньше, чем закрылся detour")
	default:
	}
	if err := w.Close(); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("повторный Close: ожидали net.ErrClosed, получили %v", err)
	}
	close(block)
	select {
	case <-closed:
	case <-time.After(2 * time.Second):
		t.Fatalf("Close не завершился после разблокировки detour")
	}
}

type slowClosePacketConn struct {
	block chan struct{}
}

func (s *slowClosePacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	<-s.block
	return 0, nil, net.ErrClosed
}
func (s *slowClosePacketConn) WriteTo(p []byte, addr net.Addr) (int, error) { return len(p), nil }
func (s *slowClosePacketConn) Close() error                                 { <-s.block; return nil }
func (s *slowClosePacketConn) LocalAddr() net.Addr                          { return &net.UDPAddr{} }
func (s *slowClosePacketConn) SetDeadline(t time.Time) error                { return nil }
func (s *slowClosePacketConn) SetReadDeadline(t time.Time) error            { return nil }
func (s *slowClosePacketConn) SetWriteDeadline(t time.Time) error           { return nil }
