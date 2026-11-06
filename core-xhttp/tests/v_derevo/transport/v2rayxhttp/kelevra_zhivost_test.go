// Kelevra (core-xhttp/apply.py, правка 8c): живость канала XHTTP.
//
// Кладётся apply.py в transport/v2rayxhttp/ (tests/v_derevo/**), в бинарь не входит.
// Прогон: go test -count=1 -run Kelevra ./transport/v2rayxhttp/
//
// Сервер — настоящий HTTP/2 (h2c, без TLS) на петле; клиент — наш
// DefaultDialerClient поверх http2.Transport, как в createHTTPClient (httpVersion "2").
// Проверяется:
//  - сервер принял stream-down GET и молчит (заголовков нет) — старый код висел бы
//    в Read без срока; теперь Read отдаёт ошибку за kelevraResponseHeaderTimeout;
//  - заголовки пришли вовремя — таймер снят, тело читается и после срока
//    (отмена контекста после заголовков оборвала бы чтение — этого быть не должно);
//  - Close тела будит висящий Read (h2: BreakWithError).

package xhttp

import (
	"context"
	gotls "crypto/tls"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-box/option"
	"golang.org/x/net/http2"
	"golang.org/x/net/http2/h2c"
)

func newH2CTestServer(t *testing.T, handler http.HandlerFunc) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(h2c.NewHandler(handler, &http2.Server{}))
	t.Cleanup(func() {
		srv.CloseClientConnections()
		srv.Close()
	})
	return srv
}

func newH2CDialerClient(t *testing.T) *DefaultDialerClient {
	t.Helper()
	c := &DefaultDialerClient{
		options: &option.V2RayXHTTPBaseOptions{},
		client: &http.Client{
			Transport: &http2.Transport{
				AllowHTTP: true,
				DialTLSContext: func(ctx context.Context, network, addr string, _ *gotls.Config) (net.Conn, error) {
					var d net.Dialer
					return d.DialContext(ctx, network, addr)
				},
				ReadIdleTimeout:  45 * time.Second,
				PingTimeout:      kelevraH2PingTimeout,
				WriteByteTimeout: kelevraH2WriteByteTimeout,
			},
		},
		httpVersion:   "2",
		uploadRawPool: &sync.Pool{},
	}
	t.Cleanup(c.Close)
	return c
}

func setHeaderTimeout(t *testing.T, d time.Duration) {
	t.Helper()
	old := kelevraResponseHeaderTimeout
	kelevraResponseHeaderTimeout = d
	t.Cleanup(func() { kelevraResponseHeaderTimeout = old })
}

// readOnce читает из rc в отдельной горутине и отдаёт результат или таймаут.
func readOnce(rc io.Reader, wait time.Duration) (n int, err error, timedOut bool) {
	type res struct {
		n   int
		err error
	}
	ch := make(chan res, 1)
	go func() {
		buf := make([]byte, 64)
		n, err := rc.Read(buf)
		ch <- res{n, err}
	}()
	select {
	case r := <-ch:
		return r.n, r.err, false
	case <-time.After(wait):
		return 0, nil, true
	}
}

// Сервер принял GET и молчит без заголовков — молчаливая смерть на уровне HTTP.
// Старый код: Read висит, пока жив процесс. Новый: ошибка за срок заголовков.
func TestKelevraOpenStreamSilentServerReadFailsWithinHeaderTimeout(t *testing.T) {
	setHeaderTimeout(t, 300*time.Millisecond)
	srv := newH2CTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		<-r.Context().Done() // ни заголовков, ни тела
	})
	c := newH2CDialerClient(t)

	start := time.Now()
	rc, _, _, err := c.OpenStream(context.Background(), srv.URL+"/x", "sess1", nil, false)
	if err != nil {
		t.Fatalf("OpenStream: %v", err)
	}
	n, rerr, timedOut := readOnce(rc, 5*time.Second)
	if timedOut {
		t.Fatalf("Read висит на молчащем сервере дольше 5 с (срок заголовков %v)", kelevraResponseHeaderTimeout)
	}
	if rerr == nil {
		t.Fatalf("ожидали ошибку чтения, получили %d байт без ошибки", n)
	}
	if took := time.Since(start); took > 3*time.Second {
		t.Fatalf("восстановление заняло %v, ожидали около %v", took, kelevraResponseHeaderTimeout)
	}
	if !c.IsClosed() {
		t.Fatalf("после провала stream-down клиент должен быть закрыт (xmux его заменит)")
	}
}

// Заголовки пришли вовремя: таймер снят, тело читается и после истечения срока.
func TestKelevraOpenStreamHeadersInTimeBodyOutlivesTimeout(t *testing.T) {
	setHeaderTimeout(t, 200*time.Millisecond)
	srv := newH2CTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		time.Sleep(600 * time.Millisecond) // заведомо дольше срока заголовков
		_, _ = io.WriteString(w, "hello")
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	})
	c := newH2CDialerClient(t)

	rc, _, _, err := c.OpenStream(context.Background(), srv.URL+"/x", "sess2", nil, false)
	if err != nil {
		t.Fatalf("OpenStream: %v", err)
	}
	n, rerr, timedOut := readOnce(rc, 5*time.Second)
	if timedOut {
		t.Fatalf("тело не дочиталось за 5 с")
	}
	if rerr != nil || n != len("hello") {
		t.Fatalf("ожидали «hello» без ошибки, получили n=%d err=%v", n, rerr)
	}
	if c.IsClosed() {
		t.Fatalf("клиент закрыт на живой линии")
	}
	if err := rc.Close(); err != nil {
		t.Logf("close body: %v", err)
	}
}

// Close тела будит висящий Read: иначе ClientBind.Close ждал бы выхода receive вечно.
func TestKelevraOpenStreamCloseWakesBlockedRead(t *testing.T) {
	setHeaderTimeout(t, 5*time.Second)
	srv := newH2CTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		<-r.Context().Done() // заголовки есть, данных нет
	})
	c := newH2CDialerClient(t)

	rc, _, _, err := c.OpenStream(context.Background(), srv.URL+"/x", "sess3", nil, false)
	if err != nil {
		t.Fatalf("OpenStream: %v", err)
	}
	// Ждём, пока заголовки точно пришли и Read висит на теле.
	time.Sleep(200 * time.Millisecond)
	type res struct {
		n   int
		err error
	}
	ch := make(chan res, 1)
	go func() {
		buf := make([]byte, 64)
		n, err := rc.Read(buf)
		ch <- res{n, err}
	}()
	time.Sleep(200 * time.Millisecond)
	if err := rc.Close(); err != nil {
		t.Logf("close: %v", err)
	}
	select {
	case r := <-ch:
		if r.err == nil {
			t.Fatalf("после Close ожидали ошибку чтения, получили %d байт", r.n)
		}
	case <-time.After(3 * time.Second):
		t.Fatalf("Read не проснулся после Close")
	}
}

// Срок заголовков не действует на stream-up POST (uploadOnly): сервер Xray его
// заголовки до конца сессии может не отдавать — рвать нельзя.
func TestKelevraHeaderDeadlineDisabledForUploadOnly(t *testing.T) {
	setHeaderTimeout(t, 50*time.Millisecond)
	ctx, headersGot, cancel := kelevraHeaderDeadline(context.Background(), false)
	defer cancel()
	headersGot()
	time.Sleep(150 * time.Millisecond)
	if ctx.Err() != nil {
		t.Fatalf("контекст uploadOnly отменён: %v", ctx.Err())
	}

	ctx2, headersGot2, cancel2 := kelevraHeaderDeadline(context.Background(), true)
	defer cancel2()
	time.Sleep(150 * time.Millisecond)
	if ctx2.Err() == nil {
		t.Fatalf("контекст stream-down без заголовков должен отмениться за срок")
	}
	headersGot2()
}
