package xhttp

// Kelevra (core-xhttp/apply.py, правка 9): мёртвое h2-соединение к узлу — за секунды.
//
// Что ловили (телефон, мобильная сеть, 06–07.11.2026): потоки приложений висят ровно
// ~1m0s–1m3s и кончаются `http2: client connection lost` (49 раз за двое суток), при
// этом сеть не менялась, ядро не перезапускалось, а проба канала новым потоком
// проходит за 125–151 мс. Это h2-соединение к узлу, которое молча умерло (TCP не
// рвётся, байты от узла не идут), а x/net замечает такое только PING-проверкой:
// ReadIdleTimeout (было 45 с) тишины + PingTimeout. Соединение переиспользуется
// многими потоками подряд (xmux: поток закрылся — клиент свободен для следующего),
// поэтому новый поток приложения садится на уже мёртвое соединение и ждёт ту же
// минуту. Смахнуть приложение помогает, потому что новые соединения приложения
// попадают на другие (живые) h2-соединения пула.
//
// Лечение — свой пул h2-соединений (http2.Transport.ConnPool) и проверка в духе
// Chrome (SpdySession: preface PING перед потоком на давно молчащей сессии и
// закрытие сессии, если после PING не пришло ни байта):
//
//  1. Каждое соединение пула помнит, когда от узла пришёл последний байт
//     (kelevraH2Conn.Read — это кадры h2: данные, заголовки, WINDOW_UPDATE, PING ack).
//  2. Новый поток на соединении, молчавшем ≥ kelevraProbePrefaceIdle, — PING сразу
//     (preface): после простоя соединение под подозрением само по себе.
//  3. Поток, ждущий ответ (stream-down GET, stream-one), не получил заголовков за
//     kelevraProbeNoResponse — PING; пока он в полёте, соединение новым потокам не
//     выдаётся (пул дозвонится новым; не вышло — снова смотрит пул). Идёт чужая
//     проверка (preface) — поток ждёт её итога и сразу решает сам, без нового срока.
//  4. Мёртвым соединение признаётся только если ВСЁ сразу: PING без ответа
//     kelevraProbePingTimeout, после отправки PING от узла не пришло ни байта, и тишина
//     на соединении (большее из монотонных и стенных часов — сон телефона монотонные
//     не считают) не короче kelevraDeadSilence. Тогда ClientConn.Close(): все его
//     потоки сразу получают ошибку, приложения переподключаются (как после смахивания).
//     Загруженное живое соединение отвечает на PING медленнее (ack стоит в очереди за
//     данными), но байты по нему идут — такое не закрывается, сколько бы ни ждал ack.
//
// Без правки: мёртвое соединение — до ReadIdleTimeout + PingTimeout (45 + 10 с) после
// последнего кадра. С правкой: поток на мёртвом соединении получает ошибку через
// ≈ kelevraProbeNoResponse + kelevraProbePingTimeout = 7 с, а после простоя ≥ 10 с —
// через ≈ kelevraProbePingTimeout = 4 с. Плюс общий ReadIdleTimeout снижен до 30 с,
// PingTimeout до 5 с (kelevra_zhivost.go) — для долгих потоков без новых.
//
// Пул свой, а не стандартный x/net, потому что стандартный не даёт ни сопоставить
// поток с его соединением (GotConn отдаёт только net.Conn), ни придержать
// подозрительное соединение. Заодно DefaultDialerClient.Close больше не лезет
// во внутренности x/net через linkname (для h2 с нашим пулом).

import (
	"context"
	"net"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/log"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/sing/service"
	"golang.org/x/net/http2"
)

var (
	// Поток ждёт заголовков ответа столько — проверяем соединение. Сервер Xray
	// отвечает на GET 200 + Flush сразу (hub.go), то есть заголовки — 1 RTT
	// (замер на телефоне 125–151 мс); 3 с — это 20 RTT и с запасом на
	// пробуждение радио (переход RRC idle→connected до ~2 с).
	kelevraProbeNoResponse = 3 * time.Second
	// Соединение молчало столько, а на него сел новый поток — PING сразу.
	// Значение Chrome (connection_at_risk_of_loss, 10 с).
	kelevraProbePrefaceIdle = 10 * time.Second
	// Ответ на PING ждём столько. На живом простаивающем соединении ack — 1 RTT
	// плюс пробуждение радио; на загруженном может быть дольше, но там идут байты
	// (п. 4 выше), так что этот срок сам по себе живое соединение не закрывает.
	kelevraProbePingTimeout = 4 * time.Second
	// Меньше этой тишины (ни одного байта от узла) соединение не хороним, даже если
	// ack не пришёл: короткий провал радио на мобильной сети — не смерть.
	kelevraDeadSilence = 7 * time.Second
	// Как часто во время PING смотреть, не пришли ли байты от узла.
	kelevraProbeReadPoll = 100 * time.Millisecond
)

// kelevraEpoch — точка отсчёта монотонного времени соединений.
var kelevraEpoch = time.Now()

func kelevraMono() time.Duration { return time.Since(kelevraEpoch) }

var errKelevraPoolClosed = E.New("xhttp: h2 connection pool closed")

// kelevraNewH2Transport — http2.Transport XHTTP с нашим пулом соединений.
// dial отдаёт готовое к h2 соединение (TCP + TLS, или голый TCP в тестах h2c).
func kelevraNewH2Transport(dial func(ctx context.Context) (net.Conn, error), readIdleTimeout, idleConnTimeout time.Duration, lg logger.ContextLogger) *http2.Transport {
	if lg == nil {
		lg = logger.NOP()
	}
	t := &http2.Transport{
		IdleConnTimeout:  idleConnTimeout,
		ReadIdleTimeout:  readIdleTimeout,
		PingTimeout:      kelevraH2PingTimeout,
		WriteByteTimeout: kelevraH2WriteByteTimeout,
	}
	t.ConnPool = &kelevraH2Pool{t: t, dial: dial, log: lg}
	return t
}

// kelevraLogger — журнал ядра (тот, что пишется в журнал устройства), если он есть.
func kelevraLogger(ctx context.Context) logger.ContextLogger {
	if f := service.FromContext[log.Factory](ctx); f != nil {
		return f.NewLogger("xhttp")
	}
	return logger.NOP()
}

// kelevraH2Pool — пул h2-соединений одного DefaultDialerClient (один узел).
// Реализует http2.ClientConnPool.
type kelevraH2Pool struct {
	t    *http2.Transport
	dial func(ctx context.Context) (net.Conn, error)
	log  logger.ContextLogger

	access  sync.Mutex
	conns   []*kelevraH2Conn
	dialing *kelevraH2Dial // один дозвон на всех ждущих
	closed  bool
}

type kelevraH2Dial struct {
	done chan struct{}
	conn *kelevraH2Conn
	err  error
}

func (p *kelevraH2Pool) GetClientConn(req *http.Request, addr string) (*http2.ClientConn, error) {
	for attempt := 0; ; attempt++ {
		p.access.Lock()
		if p.closed {
			p.access.Unlock()
			return nil, errKelevraPoolClosed
		}
		if cc := p.pickLocked(); cc != nil {
			p.access.Unlock()
			return cc, nil
		}
		if attempt >= 3 {
			p.access.Unlock()
			return nil, E.New("xhttp: no usable h2 connection after ", attempt, " dials")
		}
		call := p.dialing
		if call == nil {
			call = &kelevraH2Dial{done: make(chan struct{})}
			p.dialing = call
			// контекст запроса без отмены: дозвон общий, срок у него свой (kelevraDialTimeout)
			go p.dialConn(call, context.WithoutCancel(req.Context()))
		}
		p.access.Unlock()
		select {
		case <-call.done:
		case <-req.Context().Done():
			return nil, req.Context().Err()
		}
		if call.err != nil {
			// дозвон не удался — но пока ждали, с прежнего соединения могли снять
			// подозрение (проверка нашла его живым): пересматриваем пул до ошибки
			p.access.Lock()
			cc := p.pickLocked()
			p.access.Unlock()
			if cc != nil {
				return cc, nil
			}
			return nil, call.err
		}
		if call.conn.cc.ReserveNewRequest() {
			return call.conn.cc, nil
		}
		// новое соединение уже не принимает потоков (умерло сразу) — ещё круг
	}
}

// pickLocked — первое годное соединение (с резервом под поток); закрытые выкидывает.
// Под p.access.
func (p *kelevraH2Pool) pickLocked() *http2.ClientConn {
	if p.closed {
		return nil
	}
	live := p.conns[:0]
	var found *http2.ClientConn
	for _, c := range p.conns {
		if c.closed.Load() {
			continue // закрыто (idle, ошибка, мы) — из пула вон
		}
		live = append(live, c)
		if found == nil && !c.suspect.Load() && !c.dead.Load() && c.cc.ReserveNewRequest() {
			found = c.cc
		}
	}
	clear(p.conns[len(live):])
	p.conns = live
	return found
}

func (p *kelevraH2Pool) dialConn(call *kelevraH2Dial, ctx context.Context) {
	var c *kelevraH2Conn
	raw, err := p.dial(ctx)
	if err == nil {
		c = &kelevraH2Conn{Conn: raw, pool: p}
		c.touch()
		var cc *http2.ClientConn
		cc, err = p.t.NewClientConn(c) // при ошибке сам закрывает соединение
		if err != nil {
			c = nil
		} else {
			c.cc = cc
		}
	}
	p.access.Lock()
	p.dialing = nil
	closeNow := false
	if c != nil {
		if p.closed {
			closeNow = true
			err = errKelevraPoolClosed
		} else {
			p.conns = append(p.conns, c)
		}
	}
	call.conn, call.err = c, err
	p.access.Unlock()
	close(call.done)
	if closeNow {
		c.cc.Close()
	}
}

func (p *kelevraH2Pool) MarkDead(cc *http2.ClientConn) {
	p.access.Lock()
	defer p.access.Unlock()
	for i, c := range p.conns {
		if c.cc == cc {
			p.conns = append(p.conns[:i], p.conns[i+1:]...)
			return
		}
	}
}

// closeAll закрывает все соединения пула; после него пул новых не создаёт.
func (p *kelevraH2Pool) closeAll() {
	p.access.Lock()
	p.closed = true
	conns := p.conns
	p.conns = nil
	p.access.Unlock()
	for _, c := range conns {
		c.cc.Close()
	}
}

// kelevraH2Conn — соединение пула: net.Conn под ClientConn плюс отметки живости.
type kelevraH2Conn struct {
	net.Conn
	pool *kelevraH2Pool
	cc   *http2.ClientConn // задан до того, как соединение попало в пул

	readMono    atomic.Int64 // kelevraMono() последнего байта от узла
	readWall    atomic.Int64 // то же стенными часами: сон телефона монотонные не считают
	probeAccess sync.Mutex
	probeDone   chan struct{} // не nil — PING-проверка идёт; закрывается по её итогу
	suspect     atomic.Bool   // проверка по зависшему потоку: новых потоков не давать
	dead        atomic.Bool   // признано мёртвым и закрыто нами
	closed      atomic.Bool   // Close уже звали (кто угодно)
}

func (c *kelevraH2Conn) touch() {
	c.readMono.Store(int64(kelevraMono()))
	c.readWall.Store(time.Now().UnixNano())
}

func (c *kelevraH2Conn) Read(b []byte) (int, error) {
	n, err := c.Conn.Read(b)
	if n > 0 {
		c.touch()
	}
	return n, err
}

func (c *kelevraH2Conn) Close() error {
	c.closed.Store(true)
	return c.Conn.Close()
}

// silence — сколько от узла не было ни байта (монотонно).
func (c *kelevraH2Conn) silence() time.Duration {
	return kelevraMono() - time.Duration(c.readMono.Load())
}

// idle — то же, но с учётом сна устройства (большее из монотонного и стенного).
func (c *kelevraH2Conn) idle() time.Duration {
	wall := time.Duration(time.Now().UnixNano() - c.readWall.Load())
	return max(c.silence(), wall)
}

// streamsBestEffort — потоков на соединении. State() берёт замок записи h2, а на
// мёртвом TCP он может быть занят застрявшей записью — ждём недолго.
func (c *kelevraH2Conn) streamsBestEffort() int {
	ch := make(chan int, 1)
	go func() {
		st := c.cc.State()
		ch <- st.StreamsActive + st.StreamsPending
	}()
	select {
	case n := <-ch:
		return n
	case <-time.After(200 * time.Millisecond):
		return -1
	}
}

const (
	kelevraKindPreface    = "preface"
	kelevraKindNoResponse = "no-response"
)

// kelevraProbeTimings — сроки проверки, снятые в начале потока (пакетные
// переменные читаются один раз — их подменяют тесты).
type kelevraProbeTimings struct {
	noResponse  time.Duration
	pingTimeout time.Duration
	deadSilence time.Duration
}

// probe — PING-проверка соединения. dead: соединение признано мёртвым и закрыто
// (или уже закрыто кем-то). busy не nil — проверку уже ведёт другой поток, канал
// закроется по её итогу (решения тогда нет: alive и dead — false).
func (c *kelevraH2Conn) probe(kind string, tm kelevraProbeTimings, streamStart time.Duration, idleBefore time.Duration) (alive bool, busy <-chan struct{}) {
	if c.dead.Load() || c.closed.Load() {
		return false, nil
	}
	c.probeAccess.Lock()
	if c.probeDone != nil {
		busy = c.probeDone
		c.probeAccess.Unlock()
		return false, busy
	}
	done := make(chan struct{})
	c.probeDone = done
	c.probeAccess.Unlock()
	defer func() {
		c.probeAccess.Lock()
		c.probeDone = nil
		c.probeAccess.Unlock()
		close(done)
	}()
	if kind != kelevraKindPreface {
		c.suspect.Store(true)
		defer c.suspect.Store(false)
	}
	sent := kelevraMono()
	pingErr := make(chan error, 1)
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), tm.pingTimeout)
		pingErr <- c.cc.Ping(ctx)
		cancel()
	}()
	// Любой байт от узла после отправки PING — соединение живо: не ждём ack (на
	// загруженном он стоит в очереди за данными) и сразу снимаем подозрение.
	tick := time.NewTicker(kelevraProbeReadPoll)
	defer tick.Stop()
	var err error
wait:
	for {
		select {
		case err = <-pingErr:
			break wait
		case <-tick.C:
			if time.Duration(c.readMono.Load()) >= sent {
				return true, nil
			}
		}
	}
	if err == nil {
		return true, nil
	}
	if c.closed.Load() {
		return false, nil // закрыли без нас (Close клиента, idle, health check x/net)
	}
	pingWait := kelevraMono() - sent
	// тишина — по большему из монотонных и стенных часов: после сна телефона
	// монотонные сон не считают, и порог тишины задержал бы решение на лишний круг
	silence := c.idle()
	if time.Duration(c.readMono.Load()) >= sent || silence < tm.deadSilence {
		// ack не успел, но байты от узла идут (или тишина ещё коротка) — загружено, не мертво
		c.pool.log.Debug("kelevra h2 probe (", kind, "): ping unanswered ", pingWait.Round(100*time.Millisecond),
			" but peer not silent (", silence.Round(100*time.Millisecond), "), keeping connection to ", c.Conn.RemoteAddr())
		return true, nil
	}
	if !c.dead.CompareAndSwap(false, true) {
		return false, nil
	}
	streams := c.streamsBestEffort()
	var why string
	if kind == kelevraKindPreface {
		why = "new stream after " + idleBefore.Round(100*time.Millisecond).String() + " idle"
	} else {
		why = "stream without response " + (sent - streamStart).Round(100*time.Millisecond).String()
	}
	c.pool.log.Info("kelevra h2: connection to ", c.Conn.RemoteAddr(), " dead: ", why,
		", ping unanswered ", pingWait.Round(100*time.Millisecond),
		", peer silent ", silence.Round(100*time.Millisecond),
		", streams ", streams, "; closing")
	c.cc.Close() // все потоки соединения сразу получают ошибку
	return false, nil
}

// kelevraStreamWatch — надзор за одним потоком на соединении пула (от GotConn до
// ответа). stop — звать, когда client.Do вернулся (заголовки или ошибка).
type kelevraStreamWatch struct {
	c     *kelevraH2Conn
	tm    kelevraProbeTimings
	start time.Duration
	done  chan struct{}
	once  sync.Once
}

// kelevraWatchStream — из httptrace.GotConn. conn не из нашего пула (h1, h3) — nil.
// waitResponse: поток ждёт заголовков ответа (GET stream-down, stream-one); на
// stream-up POST заголовков может не быть до конца сессии — там только preface.
func kelevraWatchStream(conn net.Conn, waitResponse bool) *kelevraStreamWatch {
	c, ok := conn.(*kelevraH2Conn)
	if !ok || c.cc == nil {
		return nil
	}
	w := &kelevraStreamWatch{
		c: c,
		tm: kelevraProbeTimings{
			noResponse:  kelevraProbeNoResponse,
			pingTimeout: kelevraProbePingTimeout,
			deadSilence: kelevraDeadSilence,
		},
		start: kelevraMono(),
		done:  make(chan struct{}),
	}
	if idle := c.idle(); idle >= kelevraProbePrefaceIdle {
		go c.probe(kelevraKindPreface, w.tm, w.start, idle)
	}
	if waitResponse {
		go w.loop()
	}
	return w
}

func (w *kelevraStreamWatch) loop() {
	timer := time.NewTimer(w.tm.noResponse)
	defer timer.Stop()
	select {
	case <-w.done:
		return
	case <-timer.C:
	}
	for {
		select {
		case <-w.done:
			return
		default:
		}
		alive, busy := w.c.probe(kelevraKindNoResponse, w.tm, w.start, 0)
		if busy != nil {
			// проверку ведёт другой поток (например, preface): ждём её итога и
			// сразу решаем своей, не взводя полный срок заново
			select {
			case <-w.done:
				return
			case <-busy:
			}
			continue
		}
		if !alive {
			return
		}
		// живо (загружено) — ждём ещё; верхний срок — kelevraResponseHeaderTimeout,
		// по нему client.Do вернётся и позовёт stop
		timer.Reset(w.tm.noResponse)
		select {
		case <-w.done:
			return
		case <-timer.C:
		}
	}
}

func (w *kelevraStreamWatch) stop() {
	if w == nil {
		return
	}
	w.once.Do(func() { close(w.done) })
}

// connDead — соединение потока признано мёртвым нашей проверкой (и уже закрыто).
func (w *kelevraStreamWatch) connDead() bool {
	return w != nil && w.c.dead.Load()
}
