// Kelevra (core-xhttp/apply.py, правка 9): мёртвое h2-соединение к узлу — за секунды.
//
// Кладётся apply.py в transport/v2rayxhttp/ (tests/v_derevo/**), в бинарь не входит.
// Прогон: go test -count=1 -run Kelevra ./transport/v2rayxhttp/
//
// Между клиентом (DefaultDialerClient + наш пул) и h2c-сервером — TCP-прокси, который
// умеет «замёрзнуть» (байты в обе стороны пропадают, TCP не рвётся — как мёртвая
// линия мобильной сети), задерживать ответы узла (медленный загруженный канал) и
// считать кадры PING от клиента.
// Проверяется:
//   - мёртвое соединение: потоки на нём получают ошибку за ≤ ~8 с (боевые сроки);
//   - подозрительное соединение под PING не выдаётся новым потокам — новый поток
//     идёт на свежем соединении сразу;
//   - соединение после простоя проверяется сразу (preface), не дожидаясь срока ответа;
//   - живое медленное соединение под нагрузкой (ack PING опаздывает, но байты идут) и
//     живой медленный сервер не закрываются;
//   - на простое лишних PING нет: проверка потоков их не шлёт, health check — не чаще
//     ReadIdleTimeout.

package xhttp

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing/common/logger"
)

// ---------- прокси ----------

type kelevraTestProxy struct {
	ln       net.Listener
	target   string
	delay    time.Duration // задержка узел→клиент для новых линий
	accepted atomic.Int32

	mu    sync.Mutex
	links []*kelevraTestLink
}

type kelevraTestLink struct {
	client, server net.Conn
	frozen         atomic.Bool
	pings          atomic.Int32 // кадры PING (не ACK) от клиента

	// разбор кадров клиент→сервер (только в горутине up)
	pre  int
	hdr  [9]byte
	hn   int
	skip int
}

func newKelevraTestProxy(t *testing.T, target string, delay time.Duration) *kelevraTestProxy {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	p := &kelevraTestProxy{ln: ln, target: target, delay: delay}
	go p.acceptLoop()
	t.Cleanup(p.close)
	return p
}

func (p *kelevraTestProxy) addr() string { return p.ln.Addr().String() }

func (p *kelevraTestProxy) acceptLoop() {
	for {
		c, err := p.ln.Accept()
		if err != nil {
			return
		}
		s, err := net.Dial("tcp", p.target)
		if err != nil {
			c.Close()
			continue
		}
		l := &kelevraTestLink{client: c, server: s}
		p.mu.Lock()
		p.links = append(p.links, l)
		p.mu.Unlock()
		p.accepted.Add(1)
		go l.up()
		go l.down(p.delay)
	}
}

// freeze — текущие линии молча умирают; новые работают.
func (p *kelevraTestProxy) freeze() {
	p.mu.Lock()
	defer p.mu.Unlock()
	for _, l := range p.links {
		l.frozen.Store(true)
	}
}

func (p *kelevraTestProxy) pings() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	n := 0
	for _, l := range p.links {
		n += int(l.pings.Load())
	}
	return n
}

func (p *kelevraTestProxy) close() {
	p.ln.Close()
	p.mu.Lock()
	defer p.mu.Unlock()
	for _, l := range p.links {
		l.client.Close()
		l.server.Close()
	}
}

func (l *kelevraTestLink) up() {
	defer l.server.Close()
	buf := make([]byte, 32<<10)
	for {
		n, err := l.client.Read(buf)
		if n > 0 {
			l.count(buf[:n])
			if !l.frozen.Load() {
				if _, werr := l.server.Write(buf[:n]); werr != nil {
					return
				}
			}
		}
		if err != nil {
			return
		}
	}
}

func (l *kelevraTestLink) down(delay time.Duration) {
	type chunk struct {
		due time.Time
		b   []byte
	}
	ch := make(chan chunk, 1<<14)
	go func() {
		defer close(ch)
		buf := make([]byte, 32<<10)
		for {
			n, err := l.server.Read(buf)
			if n > 0 {
				ch <- chunk{time.Now().Add(delay), append([]byte(nil), buf[:n]...)}
			}
			if err != nil {
				return
			}
		}
	}()
	defer l.client.Close()
	for c := range ch {
		if d := time.Until(c.due); d > 0 {
			time.Sleep(d)
		}
		if l.frozen.Load() {
			continue
		}
		if _, err := l.client.Write(c.b); err != nil {
			return
		}
	}
}

// count — разбор потока клиент→сервер на кадры h2: считаем PING без ACK.
func (l *kelevraTestLink) count(b []byte) {
	for len(b) > 0 {
		if l.pre < 24 { // преамбула клиента
			k := min(24-l.pre, len(b))
			l.pre += k
			b = b[k:]
			continue
		}
		if l.skip > 0 {
			k := min(l.skip, len(b))
			l.skip -= k
			b = b[k:]
			continue
		}
		k := min(9-l.hn, len(b))
		copy(l.hdr[l.hn:], b[:k])
		l.hn += k
		b = b[k:]
		if l.hn == 9 {
			length := int(l.hdr[0])<<16 | int(l.hdr[1])<<8 | int(l.hdr[2])
			if l.hdr[3] == 0x6 && l.hdr[4]&0x1 == 0 {
				l.pings.Add(1)
			}
			l.skip = length
			l.hn = 0
		}
	}
}

// ---------- журнал ----------

type kelevraCapLogger struct {
	logger.ContextLogger
	mu    sync.Mutex
	lines []string
}

func newKelevraCapLogger() *kelevraCapLogger {
	return &kelevraCapLogger{ContextLogger: logger.NOP()}
}

func (l *kelevraCapLogger) add(level string, args ...any) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.lines = append(l.lines, level+" "+fmt.Sprint(args...))
}

func (l *kelevraCapLogger) Info(args ...any)  { l.add("INFO", args...) }
func (l *kelevraCapLogger) Debug(args ...any) { l.add("DEBUG", args...) }

func (l *kelevraCapLogger) find(sub string) string {
	l.mu.Lock()
	defer l.mu.Unlock()
	for _, s := range l.lines {
		if strings.Contains(s, sub) {
			return s
		}
	}
	return ""
}

// ---------- помощники ----------

func setKelevraProbeTimings(t *testing.T, noResponse, prefaceIdle, pingTimeout, deadSilence time.Duration) {
	t.Helper()
	o1, o2, o3, o4 := kelevraProbeNoResponse, kelevraProbePrefaceIdle, kelevraProbePingTimeout, kelevraDeadSilence
	kelevraProbeNoResponse, kelevraProbePrefaceIdle, kelevraProbePingTimeout, kelevraDeadSilence = noResponse, prefaceIdle, pingTimeout, deadSilence
	t.Cleanup(func() {
		kelevraProbeNoResponse, kelevraProbePrefaceIdle, kelevraProbePingTimeout, kelevraDeadSilence = o1, o2, o3, o4
	})
}

func kelevraTestHandler(w http.ResponseWriter, r *http.Request) {
	switch {
	case strings.HasPrefix(r.URL.Path, "/fast"):
		_, _ = io.WriteString(w, "ok")
	case strings.HasPrefix(r.URL.Path, "/load"):
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		chunk := make([]byte, 512)
		for {
			select {
			case <-r.Context().Done():
				return
			case <-time.After(20 * time.Millisecond):
			}
			if _, err := w.Write(chunk); err != nil {
				return
			}
			w.(http.Flusher).Flush()
		}
	case strings.HasPrefix(r.URL.Path, "/slowheaders"):
		select {
		case <-r.Context().Done():
			return
		case <-time.After(2500 * time.Millisecond):
		}
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	default: // /quiet: заголовки сразу, данных нет
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	}
}

type kelevraReadResult struct {
	err  error
	took time.Duration
}

// readAsync — Read в горутине; результат в канал (время — от start).
func readAsync(rc io.Reader, start time.Time) <-chan kelevraReadResult {
	ch := make(chan kelevraReadResult, 1)
	go func() {
		buf := make([]byte, 64)
		for {
			_, err := rc.Read(buf)
			if err != nil {
				ch <- kelevraReadResult{err, time.Since(start)}
				return
			}
		}
	}()
	return ch
}

// headersIn — пришли ли заголовки ответа на поток OpenStream за wait.
func headersIn(t *testing.T, rc io.ReadCloser, wait time.Duration) bool {
	t.Helper()
	w, ok := rc.(*WaitReadCloser)
	if !ok {
		t.Fatalf("OpenStream вернул %T", rc)
	}
	select {
	case <-w.Wait:
		w.access.Lock()
		got := w.ReadCloser != nil
		w.access.Unlock()
		return got
	case <-time.After(wait):
		return false
	}
}

func openGet(t *testing.T, c *DefaultDialerClient, url string) io.ReadCloser {
	t.Helper()
	rc, _, _, err := c.OpenStream(context.Background(), url, "s"+fmt.Sprint(time.Now().UnixNano()), nil, false)
	if err != nil {
		t.Fatalf("OpenStream %s: %v", url, err)
	}
	return rc
}

// ---------- тесты ----------

// Мёртвое соединение (узел перестал отвечать, TCP не рвётся), боевые сроки.
// Новый поток садится на него — через ≈ 3 + 4 с соединение признано мёртвым, и все
// его потоки (и новый, и старый, уже получивший заголовки) сразу получают ошибку.
// Без правки 9 оба ждали бы PING-проверку x/net: 30–45 с тишины + PingTimeout.
func TestKelevraDeadConnStreamsFailFast(t *testing.T) {
	srv := newH2CTestServer(t, kelevraTestHandler)
	px := newKelevraTestProxy(t, srv.Listener.Addr().String(), 0)
	lg := newKelevraCapLogger()
	c := newH2CDialerClientTo(t, px.addr(), kelevraH2ReadIdleTimeout, lg)
	base := "http://" + px.addr()

	rc1 := openGet(t, c, base+"/quiet")
	if !headersIn(t, rc1, 3*time.Second) {
		t.Fatalf("первый поток без заголовков на живой линии")
	}
	px.freeze()
	start := time.Now()
	rc2 := openGet(t, c, base+"/quiet")
	r1 := readAsync(rc1, start)
	r2 := readAsync(rc2, start)

	limit := kelevraProbeNoResponse + kelevraProbePingTimeout + 1500*time.Millisecond
	for name, ch := range map[string]<-chan kelevraReadResult{"старый поток": r1, "новый поток": r2} {
		select {
		case r := <-ch:
			if r.err == nil {
				t.Fatalf("%s: ожидали ошибку", name)
			}
			if r.took > limit {
				t.Fatalf("%s: ошибка через %v, ждали ≤ %v", name, r.took, limit)
			}
			t.Logf("%s: ошибка через %v (%v)", name, r.took.Round(10*time.Millisecond), r.err)
		case <-time.After(20 * time.Second):
			t.Fatalf("%s: висит дольше 20 с на мёртвом соединении", name)
		}
	}
	if n := px.accepted.Load(); n != 1 {
		t.Fatalf("оба потока должны были идти по одному соединению, соединений %d", n)
	}
	line := lg.find("dead")
	if line == "" || !strings.Contains(line, "stream without response") || !strings.Contains(line, "streams") {
		t.Fatalf("нет строки журнала о мёртвом соединении; журнал: %q", lg.lines)
	}
	t.Logf("журнал: %s", line)
}

// Пока соединение под PING-проверкой (поток ждёт ответа дольше срока), новые
// запросы его не получают — пул дозванивается заново, и новый поток идёт сразу.
func TestKelevraSuspectConnNotHandedOut(t *testing.T) {
	setKelevraProbeTimings(t, 400*time.Millisecond, time.Hour, 2*time.Second, 500*time.Millisecond)
	srv := newH2CTestServer(t, kelevraTestHandler)
	px := newKelevraTestProxy(t, srv.Listener.Addr().String(), 0)
	c := newH2CDialerClientTo(t, px.addr(), kelevraH2ReadIdleTimeout, newKelevraCapLogger())
	base := "http://" + px.addr()

	rc1 := openGet(t, c, base+"/quiet")
	if !headersIn(t, rc1, 3*time.Second) {
		t.Fatalf("первый поток без заголовков")
	}
	px.freeze()
	start := time.Now()
	rc2 := openGet(t, c, base+"/quiet")
	r2 := readAsync(rc2, start)

	time.Sleep(kelevraProbeNoResponse + 300*time.Millisecond) // PING в полёте
	ctx, cancel := context.WithTimeout(context.Background(), 1500*time.Millisecond)
	defer cancel()
	req, _ := http.NewRequestWithContext(ctx, "GET", base+"/fast", nil)
	resp, err := c.client.Do(req)
	if err != nil {
		t.Fatalf("новый запрос, пока старое соединение под проверкой: %v", err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(body) != "ok" {
		t.Fatalf("новый запрос: тело %q", body)
	}
	if n := px.accepted.Load(); n != 2 {
		t.Fatalf("новый запрос должен был пойти по свежему соединению, соединений %d", n)
	}
	select {
	case r := <-r2:
		if r.err == nil {
			t.Fatalf("поток на мёртвом соединении: ожидали ошибку")
		}
		if lim := kelevraProbeNoResponse + kelevraProbePingTimeout + time.Second; r.took > lim {
			t.Fatalf("поток на мёртвом соединении: ошибка через %v, ждали ≤ %v", r.took, lim)
		}
	case <-time.After(10 * time.Second):
		t.Fatalf("поток на мёртвом соединении висит")
	}
}

// Соединение молчало дольше kelevraProbePrefaceIdle — новый поток проверяет его
// сразу и узнаёт о смерти за ≈ kelevraProbePingTimeout, не дожидаясь срока ответа.
func TestKelevraIdleReusedConnProbedAtOnce(t *testing.T) {
	setKelevraProbeTimings(t, 3*time.Second, 300*time.Millisecond, 500*time.Millisecond, 700*time.Millisecond)
	srv := newH2CTestServer(t, kelevraTestHandler)
	px := newKelevraTestProxy(t, srv.Listener.Addr().String(), 0)
	lg := newKelevraCapLogger()
	c := newH2CDialerClientTo(t, px.addr(), kelevraH2ReadIdleTimeout, lg)
	base := "http://" + px.addr()

	rc1 := openGet(t, c, base+"/quiet")
	if !headersIn(t, rc1, 3*time.Second) {
		t.Fatalf("первый поток без заголовков")
	}
	time.Sleep(1200 * time.Millisecond) // простой
	px.freeze()
	start := time.Now()
	rc2 := openGet(t, c, base+"/quiet")
	select {
	case r := <-readAsync(rc2, start):
		if r.err == nil {
			t.Fatalf("ожидали ошибку")
		}
		if r.took >= kelevraProbeNoResponse {
			t.Fatalf("ошибка через %v — preface-проверка не сработала (срок ответа %v)", r.took, kelevraProbeNoResponse)
		}
		t.Logf("ошибка через %v", r.took.Round(10*time.Millisecond))
	case <-time.After(10 * time.Second):
		t.Fatalf("поток висит")
	}
	if line := lg.find("dead"); !strings.Contains(line, "idle") {
		t.Fatalf("в журнале нет строки preface-проверки; журнал: %q", lg.lines)
	}
}

// Живое медленное соединение под нагрузкой: всё от узла приходит с задержкой 1,5 с
// (больше срока ответа + срока PING), но данные идут без перерыва. Новый поток ждёт
// заголовков дольше срока, ack PING опаздывает — соединение не закрывается.
// Соотношение сроков как в бою: срок PING < задержка линии < порог тишины
// (в бою 4 с < … < 7 с при RTT 0,15 с); линия с задержкой больше порога тишины
// неотличима от мёртвой и закрывается — так и задумано.
func TestKelevraSlowLoadedConnNotClosed(t *testing.T) {
	setKelevraProbeTimings(t, 400*time.Millisecond, time.Hour, 600*time.Millisecond, 2500*time.Millisecond)
	srv := newH2CTestServer(t, kelevraTestHandler)
	px := newKelevraTestProxy(t, srv.Listener.Addr().String(), 1500*time.Millisecond)
	lg := newKelevraCapLogger()
	c := newH2CDialerClientTo(t, px.addr(), kelevraH2ReadIdleTimeout, lg)
	base := "http://" + px.addr()

	load := openGet(t, c, base+"/load")
	var got atomic.Int64
	loadErr := make(chan error, 1)
	go func() {
		buf := make([]byte, 4096)
		for {
			n, err := load.Read(buf)
			got.Add(int64(n))
			if err != nil {
				loadErr <- err
				return
			}
		}
	}()
	deadline := time.Now().Add(6 * time.Second)
	for got.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if got.Load() == 0 {
		t.Fatalf("нагрузка не пошла")
	}
	// проверка по нагрузочному потоку (его заголовки шли 1,5 с) видит байты и
	// снимает подозрение за kelevraProbeReadPoll — с запасом
	time.Sleep(300 * time.Millisecond)

	start := time.Now()
	rc2 := openGet(t, c, base+"/quiet")
	if !headersIn(t, rc2, 5*time.Second) {
		t.Fatalf("заголовки второго потока не пришли за 5 с (задержка линии 1,5 с)")
	}
	t.Logf("заголовки второго потока через %v", time.Since(start).Round(10*time.Millisecond))
	before := got.Load()
	time.Sleep(time.Second)
	select {
	case err := <-loadErr:
		t.Fatalf("нагрузочный поток оборван: %v", err)
	default:
	}
	if got.Load() == before {
		t.Fatalf("нагрузочный поток встал")
	}
	if line := lg.find("dead"); line != "" {
		t.Fatalf("живое загруженное соединение признано мёртвым: %s", line)
	}
	if n := px.accepted.Load(); n != 1 {
		t.Fatalf("соединений %d, ждали 1", n)
	}
}

// Живой медленный сервер: заголовки через 2,5 с (дольше срока ответа), линия быстрая —
// PING отвечает, соединение не трогаем, поток получает ответ.
func TestKelevraSlowServerLiveConnNotClosed(t *testing.T) {
	setKelevraProbeTimings(t, 400*time.Millisecond, time.Hour, 600*time.Millisecond, time.Second)
	srv := newH2CTestServer(t, kelevraTestHandler)
	px := newKelevraTestProxy(t, srv.Listener.Addr().String(), 0)
	lg := newKelevraCapLogger()
	c := newH2CDialerClientTo(t, px.addr(), kelevraH2ReadIdleTimeout, lg)
	base := "http://" + px.addr()

	rc := openGet(t, c, base+"/slowheaders")
	if !headersIn(t, rc, 6*time.Second) {
		t.Fatalf("заголовки медленного сервера не пришли")
	}
	if line := lg.find("dead"); line != "" {
		t.Fatalf("живое соединение признано мёртвым: %s", line)
	}
	if p := px.pings(); p < 1 {
		t.Fatalf("ожидали PING-проверку за 2,5 с ожидания при сроке %v, PING %d", kelevraProbeNoResponse, p)
	}
	if c.IsClosed() {
		t.Fatalf("клиент закрыт на живой линии")
	}
}

// Простой: проверка потоков сама PING не шлёт (заголовки приходят быстро,
// соединение не молчало), PING на простое — только health check x/net, не чаще
// ReadIdleTimeout.
func TestKelevraIdleConnPingsAreRare(t *testing.T) {
	t.Run("потоки с быстрым ответом — ни одного PING", func(t *testing.T) {
		srv := newH2CTestServer(t, kelevraTestHandler)
		px := newKelevraTestProxy(t, srv.Listener.Addr().String(), 0)
		c := newH2CDialerClientTo(t, px.addr(), kelevraH2ReadIdleTimeout, newKelevraCapLogger())
		base := "http://" + px.addr()
		for i := 0; i < 10; i++ {
			rc := openGet(t, c, base+"/quiet")
			if !headersIn(t, rc, 3*time.Second) {
				t.Fatalf("поток %d без заголовков", i)
			}
			rc.Close()
		}
		time.Sleep(2 * time.Second)
		if p := px.pings(); p != 0 {
			t.Fatalf("PING на живом простаивающем соединении: %d", p)
		}
		if n := px.accepted.Load(); n != 1 {
			t.Fatalf("соединений %d, ждали 1", n)
		}
	})
	t.Run("health check — не чаще ReadIdleTimeout", func(t *testing.T) {
		const readIdle = 400 * time.Millisecond
		const idleFor = 2200 * time.Millisecond
		srv := newH2CTestServer(t, kelevraTestHandler)
		px := newKelevraTestProxy(t, srv.Listener.Addr().String(), 0)
		lg := newKelevraCapLogger()
		c := newH2CDialerClientTo(t, px.addr(), readIdle, lg)
		rc := openGet(t, c, "http://"+px.addr()+"/quiet")
		if !headersIn(t, rc, 3*time.Second) {
			t.Fatalf("поток без заголовков")
		}
		time.Sleep(idleFor)
		p := px.pings()
		if maxP := int(idleFor/readIdle) + 1; p < 1 || p > maxP {
			t.Fatalf("PING за %v простоя при ReadIdleTimeout %v: %d, ждали 1..%d", idleFor, readIdle, p, maxP)
		}
		if line := lg.find("dead"); line != "" || c.IsClosed() {
			t.Fatalf("живое соединение закрыто на простое: %s", line)
		}
		t.Logf("PING за %v простоя: %d", idleFor, p)
	})
}
