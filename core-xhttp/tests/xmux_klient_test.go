// Артефакт проверки, НЕ часть сборки (как остальные файлы в tests/): своего
// go.mod нет, собирается только внутри дерева sing-box.
//
// Зачем: доказательство трёх правок клиента xmux в core-xhttp/v2rayxhttp:
//  1. одна сессия = одно соединение xmux для GET и POST (как у Xray);
//  2. ошибка одного запроса не закрывает соседние потоки того же соединения;
//  3. новая сессия идёт на наименее загруженное соединение.
//
// Чтобы прогнать: после apply.py скопировать файл в transport/v2rayxhttp/ дерева и
//   go test -tags with_utls,with_quic -ldflags=-checklinkname=0 ./transport/v2rayxhttp/ -run 'TestXmux' -v
// На ядре до правок TestXmuxOneSessionOneConn и TestXmuxRequestErrorKeepsNeighbours
// падают, TestXmuxLeastLoaded падает почти всегда (выбор был случайным);
// TestXmuxAcquire* на старом ядре не собираются (AcquireXmuxClient новый).
//
// Сеть — только локальный HTTP/2-сервер httptest на 127.0.0.1.

package xhttp

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// ---- стенд: локальный h2-сервер, запоминает, по какому TCP пришёл запрос ----

type seenReq struct {
	method string
	path   string
	remote string // адрес клиента = конкретное TCP-соединение
}

type h2Stand struct {
	srv  *httptest.Server
	mu   sync.Mutex
	seen []seenReq
}

func newH2Stand(t *testing.T) *h2Stand {
	st := &h2Stand{}
	st.srv = httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		st.mu.Lock()
		st.seen = append(st.seen, seenReq{r.Method, r.URL.Path, r.RemoteAddr})
		st.mu.Unlock()
		switch {
		case strings.Contains(r.URL.Path, "/abort"):
			// ошибка ОДНОГО потока: сервер сбрасывает поток (RST_STREAM) до
			// заголовков ответа, соединение остаётся живым
			panic(http.ErrAbortHandler)
		case r.Method == http.MethodGet:
			// stream-down: отдаём данные, пока клиент не уйдёт
			w.WriteHeader(200)
			w.(http.Flusher).Flush()
			chunk := make([]byte, 4096)
			for {
				select {
				case <-r.Context().Done():
					return
				default:
				}
				if _, err := w.Write(chunk); err != nil {
					return
				}
				w.(http.Flusher).Flush()
				time.Sleep(5 * time.Millisecond)
			}
		default:
			io.Copy(io.Discard, r.Body)
			w.WriteHeader(200)
		}
	}))
	st.srv.EnableHTTP2 = true
	st.srv.StartTLS()
	t.Cleanup(st.srv.Close)
	return st
}

func (st *h2Stand) requests() []seenReq {
	st.mu.Lock()
	defer st.mu.Unlock()
	return append([]seenReq(nil), st.seen...)
}

func (st *h2Stand) addr() M.Socksaddr {
	return M.ParseSocksaddr(strings.TrimPrefix(st.srv.URL, "https://"))
}

func testTLS(t *testing.T) tls.Config {
	cfg, err := tls.NewClient(context.Background(), log.NewNOPFactory().Logger(), "127.0.0.1", option.OutboundTLSOptions{
		Enabled:    true,
		Insecure:   true,
		ServerName: "example.com",
		ALPN:       []string{"h2"},
	})
	if err != nil {
		t.Fatal(err)
	}
	return cfg
}

func testOptions(t *testing.T, js string) option.V2RayXHTTPOptions {
	var o option.V2RayXHTTPOptions
	if err := json.Unmarshal([]byte(js), &o); err != nil {
		t.Fatal(err)
	}
	return o
}

// ---- 1. одна сессия = одно соединение для GET и POST ----

func TestXmuxOneSessionOneConn(t *testing.T) {
	st := newH2Stand(t)
	opts := testOptions(t, `{"mode":"stream-up","path":"/x","x_padding_bytes":"100-200","xmux":{"max_connections":2}}`)
	tr, err := NewClient(context.Background(), N.SystemDialer, st.addr(), opts, testTLS(t))
	if err != nil {
		t.Fatal(err)
	}
	defer tr.Close()
	const sessions = 8
	for i := 0; i < sessions; i++ {
		conn, err := tr.DialContext(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		conn.Write([]byte("hello"))
		defer conn.Close()
	}
	// ждём, пока на сервер придут обе половины всех сессий
	deadline := time.Now().Add(5 * time.Second)
	var reqs []seenReq
	for time.Now().Before(deadline) {
		reqs = st.requests()
		if len(reqs) >= 2*sessions {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	bySession := map[string]map[string]string{} // путь -> метод -> TCP
	conns := map[string]bool{}
	for _, r := range reqs {
		if bySession[r.path] == nil {
			bySession[r.path] = map[string]string{}
		}
		bySession[r.path][r.method] = r.remote
		conns[r.remote] = true
	}
	if len(bySession) != sessions {
		t.Fatalf("сессий на сервере %d, ждали %d", len(bySession), sessions)
	}
	split := 0
	for path, m := range bySession {
		if m["GET"] == "" || m["POST"] == "" {
			t.Fatalf("сессия %s: нет одной из половин %v", path, m)
		}
		if m["GET"] != m["POST"] {
			split++
			t.Logf("сессия %s: GET по %s, POST по %s", path, m["GET"], m["POST"])
		}
	}
	if split > 0 {
		t.Fatalf("%d из %d сессий разъехались по разным TCP", split, sessions)
	}
	if len(conns) != 2 {
		t.Fatalf("TCP-соединений %d, при max_connections 2 ждали 2", len(conns))
	}
}

// c_max_reuse_times 2: соединение выдерживает две сессии (а не одну, как
// было, когда каждая сессия списывала два использования)
func TestXmuxReuseTimesCountsSessions(t *testing.T) {
	st := newH2Stand(t)
	opts := testOptions(t, `{"mode":"stream-up","path":"/x","x_padding_bytes":"100-200","xmux":{"max_connections":1,"c_max_reuse_times":2}}`)
	tr, err := NewClient(context.Background(), N.SystemDialer, st.addr(), opts, testTLS(t))
	if err != nil {
		t.Fatal(err)
	}
	defer tr.Close()
	for i := 0; i < 4; i++ {
		conn, err := tr.DialContext(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		conn.Write([]byte("x"))
		defer conn.Close()
	}
	deadline := time.Now().Add(5 * time.Second)
	var reqs []seenReq
	for time.Now().Before(deadline) {
		if reqs = st.requests(); len(reqs) >= 8 {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	conns := map[string]bool{}
	for _, r := range reqs {
		conns[r.remote] = true
	}
	if len(conns) != 2 {
		t.Fatalf("4 сессии при c_max_reuse_times 2: TCP %d, ждали 2", len(conns))
	}
}

// ---- 2. ошибка одного запроса не закрывает соседние ----

func TestXmuxRequestErrorKeepsNeighbours(t *testing.T) {
	for _, kind := range []string{"get", "post"} {
		t.Run(kind, func(t *testing.T) {
			st := newH2Stand(t)
			opts := testOptions(t, `{"mode":"stream-up","path":"/x","x_padding_bytes":"100-200"}`)
			dc := createHTTPClient(context.Background(), st.addr(), N.SystemDialer, &opts.V2RayXHTTPBaseOptions, testTLS(t)).(*DefaultDialerClient)
			defer dc.Close()
			base := st.srv.URL

			// «большая загрузка»: длинный поток вниз
			big, _, _, err := dc.OpenStream(context.Background(), base+"/x/big", "s1", nil, false)
			if err != nil {
				t.Fatal(err)
			}
			defer big.Close()
			var got atomic.Int64
			readErr := make(chan error, 1)
			go func() {
				b := make([]byte, 32*1024)
				for {
					n, err := big.Read(b)
					got.Add(int64(n))
					if err != nil {
						readErr <- err
						return
					}
				}
			}()
			time.Sleep(200 * time.Millisecond)
			if got.Load() == 0 {
				t.Fatal("загрузка не пошла")
			}

			// ошибка одного запроса на том же соединении
			if kind == "get" {
				rc, _, _, _ := dc.OpenStream(context.Background(), base+"/x/abort", "s2", nil, false)
				b := make([]byte, 1)
				if _, err := rc.Read(b); err == nil {
					t.Fatal("сброшенный поток должен вернуть ошибку")
				}
			} else {
				if err := dc.PostPacket(context.Background(), base+"/x/abort", "s2", "0", nil); err == nil {
					t.Fatal("сброшенный POST должен вернуть ошибку")
				}
			}
			if !dc.IsClosed() {
				t.Fatal("после ошибки соединение должно выбыть из выдачи (IsClosed)")
			}

			// загрузка продолжается
			before := got.Load()
			select {
			case err := <-readErr:
				t.Fatalf("ошибка одного запроса оборвала соседнюю загрузку: %v", err)
			case <-time.After(500 * time.Millisecond):
			}
			if got.Load() <= before {
				t.Fatal("загрузка встала после ошибки соседнего запроса")
			}
		})
	}
}

// выбывшее соединение физически закрывается, только когда с него ушла
// последняя сессия
type fakeConn struct {
	closed   atomic.Bool
	released atomic.Int32
}

func (f *fakeConn) IsClosed() bool { return f.closed.Load() }
func (f *fakeConn) Close()         { f.closed.Store(true); f.released.Add(1) }

func TestXmuxEvictedClosesAfterLastSession(t *testing.T) {
	var made []*fakeConn
	m := NewXmuxManager(option.V2RayXHTTPXmuxOptions{MaxConnections: option.Range[int]{From: 1, To: 1}}, func() XmuxConn {
		f := &fakeConn{}
		made = append(made, f)
		return f
	})
	a := m.GetXmuxClient(context.Background())
	a.AddOpenUsage(1) // живая сессия
	a.XmuxConn.(*fakeConn).closed.Store(true)
	b := m.GetXmuxClient(context.Background()) // выбрасывает a
	if a == b {
		t.Fatal("выбывшее соединение снова выдано")
	}
	if made[0].released.Load() != 0 {
		t.Fatal("выбывшее соединение закрыто при живой сессии")
	}
	a.AddOpenUsage(-1)
	if made[0].released.Load() != 1 {
		t.Fatal("выбывшее соединение не закрыто после ухода последней сессии")
	}
}

// ---- 3. выбор наименее загруженного ----

func newFakeManager(x option.V2RayXHTTPXmuxOptions) *XmuxManager {
	return NewXmuxManager(x, func() XmuxConn { return &fakeConn{} })
}

func TestXmuxLeastLoaded(t *testing.T) {
	m := newFakeManager(option.V2RayXHTTPXmuxOptions{MaxConnections: option.Range[int]{From: 2, To: 2}})
	a := m.GetXmuxClient(context.Background())
	b := m.GetXmuxClient(context.Background())
	if a == b {
		t.Fatal("max_connections 2: ждали два соединения")
	}
	a.AddOpenUsage(3)
	b.AddOpenUsage(1)
	for i := 0; i < 50; i++ {
		if c := m.GetXmuxClient(context.Background()); c != b {
			t.Fatalf("попытка %d: выбрано соединение с %d сессиями при соседе с %d", i, c.GetOpenUsage(), b.GetOpenUsage())
		}
	}
}

func TestXmuxLeastLoadedTieIsRandom(t *testing.T) {
	m := newFakeManager(option.V2RayXHTTPXmuxOptions{MaxConnections: option.Range[int]{From: 2, To: 2}})
	a := m.GetXmuxClient(context.Background())
	b := m.GetXmuxClient(context.Background())
	hits := map[*XmuxClient]int{}
	for i := 0; i < 200; i++ {
		hits[m.GetXmuxClient(context.Background())]++
	}
	if hits[a] < 40 || hits[b] < 40 {
		t.Fatalf("при равной нагрузке выбор не случайный: %d / %d", hits[a], hits[b])
	}
}

// одновременные сессии раскладываются поровну (засчитываются под замком)
func TestXmuxAcquireConcurrentSpreads(t *testing.T) {
	m := newFakeManager(option.V2RayXHTTPXmuxOptions{MaxConnections: option.Range[int]{From: 2, To: 2}})
	var wg sync.WaitGroup
	got := make(chan *XmuxClient, 12)
	for i := 0; i < 12; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); got <- m.AcquireXmuxClient(context.Background()) }()
	}
	wg.Wait()
	close(got)
	count := map[*XmuxClient]int{}
	for c := range got {
		count[c]++
	}
	if len(count) != 2 {
		t.Fatalf("соединений %d, ждали 2", len(count))
	}
	for c, n := range count {
		if n != 6 || c.GetOpenUsage() != 6 {
			t.Fatalf("12 сессий на 2 соединения: %d (open %d), ждали 6/6", n, c.GetOpenUsage())
		}
	}
}

// max_concurrency: соединение не получает больше N сессий, при заполнении
// всех открывается новое
func TestXmuxAcquireRespectsConcurrency(t *testing.T) {
	m := newFakeManager(option.V2RayXHTTPXmuxOptions{MaxConcurrency: option.Range[int]{From: 2, To: 2}})
	seen := map[*XmuxClient]bool{}
	for i := 0; i < 7; i++ {
		c := m.AcquireXmuxClient(context.Background())
		seen[c] = true
		if c.GetOpenUsage() > 2 {
			t.Fatalf("на соединении %d сессий при max_concurrency 2", c.GetOpenUsage())
		}
	}
	if len(seen) != 4 {
		t.Fatalf("7 сессий при max_concurrency 2: соединений %d, ждали 4", len(seen))
	}
	// освободилось место на одном — следующая сессия идёт туда, а не в новое
	var freed *XmuxClient
	for c := range seen {
		if c.GetOpenUsage() == 2 {
			freed = c
			break
		}
	}
	freed.AddOpenUsage(-1)
	if c := m.AcquireXmuxClient(context.Background()); !seen[c] {
		t.Fatal("при свободном месте открыто лишнее соединение")
	}
}
