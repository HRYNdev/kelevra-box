package xhttp

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"reflect"
	"strings"
	"sync"
	"unsafe"

	"github.com/sagernet/quic-go/http3"
	common "github.com/sagernet/sing-box/common/xray"
	"github.com/sagernet/sing-box/common/xray/buf"
	"github.com/sagernet/sing-box/common/xray/signal/done"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	"golang.org/x/net/http2"
)

// interface to abstract between use of browser dialer, vs net/http
type DialerClient interface {
	IsClosed() bool
	Close()

	// ctx, url, sessionId, body, uploadOnly
	OpenStream(context.Context, string, string, io.Reader, bool) (io.ReadCloser, net.Addr, net.Addr, error)

	// ctx, url, sessionId, seqStr, payload
	PostPacket(context.Context, string, string, string, buf.MultiBuffer) error
}

// implements xhttp.DialerClient in terms of direct network connections
type DefaultDialerClient struct {
	options     *option.V2RayXHTTPBaseOptions
	client      *http.Client
	closed      bool
	httpVersion string
	// pool of net.Conn, created using dialUploadConn
	uploadRawPool  *sync.Pool
	dialUploadConn func(ctxInner context.Context) (net.Conn, error)

	mtx sync.RWMutex
}

type clientConnPool struct {
	t     *http2.Transport
	mu    sync.Mutex
	conns map[string][]*http2.ClientConn // key is host:port
}

type efaceWords struct {
	typ  unsafe.Pointer
	data unsafe.Pointer
}

//go:linkname transportConnPool golang.org/x/net/http2.(*Transport).connPool
func transportConnPool(t *http2.Transport) http2.ClientConnPool

func (c *DefaultDialerClient) Close() {
	c.mtx.Lock()
	defer c.mtx.Unlock()
	if c.closed {
		return
	}
	c.closed = true
	switch transport := c.client.Transport.(type) {
	case *http.Transport:
		transport.CloseIdleConnections()
	case *http2.Transport:
		// Kelevra (правка 9): наш пул закрываем сам, без linkname во внутренности x/net
		if p, ok := transport.ConnPool.(*kelevraH2Pool); ok {
			p.closeAll()
			return
		}
		connPool := transportConnPool(transport)
		p := (*clientConnPool)((*efaceWords)(unsafe.Pointer(&connPool)).data)
		p.mu.Lock()
		defer p.mu.Unlock()
		for _, vv := range p.conns {
			for _, cc := range vv {
				cc.Close()
			}
		}
	case *http3.Transport:
		transport.Close()
	default:
		panic(E.New("unknown transport type: ", reflect.TypeOf(transport)))
	}
}

func (c *DefaultDialerClient) IsClosed() bool {
	c.mtx.RLock()
	defer c.mtx.RUnlock()
	return c.closed
}

func (c *DefaultDialerClient) OpenStream(ctx context.Context, url string, sessionId string, body io.Reader, uploadOnly bool) (wrc io.ReadCloser, remoteAddr, localAddr net.Addr, err error) {
	// this is done when the TCP/UDP connection to the server was established,
	// and we can unblock the Dial function and print correct net addresses in
	// logs
	gotConn := done.New()
	// Kelevra (правка 9): надзор за потоком на его h2-соединении (kelevra_mertvoe.go).
	// GotConn и строка после client.Do — в одной горутине (RoundTrip синхронный).
	var watch *kelevraStreamWatch
	ctx = httptrace.WithClientTrace(ctx, &httptrace.ClientTrace{
		GotConn: func(connInfo httptrace.GotConnInfo) {
			remoteAddr = connInfo.Conn.RemoteAddr()
			localAddr = connInfo.Conn.LocalAddr()
			watch.stop() // повтор запроса x/net на другом соединении
			watch = kelevraWatchStream(connInfo.Conn, !uploadOnly)
			gotConn.Close()
		},
	})
	method := "GET" // stream-down
	if body != nil {
		method = c.options.GetNormalizedUplinkHTTPMethod() // stream-up/one
	}
	// Kelevra (правка 8): заголовки ответа на stream-down ждём не дольше
	// kelevraResponseHeaderTimeout, иначе Read висит без срока (kelevra_zhivost.go).
	reqCtx, headersGot, cancelReq := kelevraHeaderDeadline(context.WithoutCancel(ctx), !uploadOnly)
	req, _ := http.NewRequestWithContext(reqCtx, method, url, body)
	FillStreamRequest(req, sessionId, "", c.options)
	wrc = &WaitReadCloser{Wait: make(chan struct{})}
	go func() {
		resp, err := c.client.Do(req)
		w := watch
		w.stop() // ответ (или ошибка) есть — проверять соединение по этому потоку больше незачем
		if !headersGot() && err == nil {
			// таймер заголовков выстрелил раньше, чем мы его сняли: контекст уже
			// отменён, тело оборвётся на первом Read — это провал, не живой ответ
			resp.Body.Close()
			err = E.New("xhttp: response headers timeout")
		}
		if err != nil {
			cancelReq()
			// Kelevra (правка 9): соединение потока признала мёртвым наша проверка —
			// пул его уже закрыл и выкинул; закрыть весь клиент значило бы оборвать
			// и потоки на свежем соединении, поднятом, пока шла проверка.
			if !uploadOnly && !w.connDead() { // stream-down is enough
				c.Close()
			}
			gotConn.Close()
			common.Close(body)
			wrc.Close()
			return
		}
		if resp.StatusCode != 200 || uploadOnly { // stream-up
			io.Copy(io.Discard, resp.Body)
			resp.Body.Close() // if it is called immediately, the upload will be interrupted also
			cancelReq()
			common.Close(body)
			wrc.Close()
			return
		}
		// контекст запроса живёт до закрытия тела: отменить раньше — http2 оборвёт чтение
		wrc.(*WaitReadCloser).Set(&kelevraBodyWithCancel{ReadCloser: resp.Body, cancel: cancelReq})
	}()
	<-gotConn.Wait()
	return
}

func (c *DefaultDialerClient) PostPacket(ctx context.Context, url string, sessionId string, seqStr string, payload buf.MultiBuffer) error {
	method := c.options.GetNormalizedUplinkHTTPMethod()
	req, err := http.NewRequestWithContext(context.WithoutCancel(ctx), method, url, nil)
	if err != nil {
		return err
	}
	FillPacketRequest(req, sessionId, seqStr, payload, c.options)
	if c.httpVersion != "1.1" {
		// Kelevra (правка 9): запоминаем соединение запроса — если его признала
		// мёртвым наша проверка, весь клиент не закрываем (kelevra_mertvoe.go).
		// GotConn — в этой же горутине (RoundTrip синхронный).
		var postConn net.Conn
		req = req.WithContext(httptrace.WithClientTrace(req.Context(), &httptrace.ClientTrace{
			GotConn: func(connInfo httptrace.GotConnInfo) { postConn = connInfo.Conn },
		}))
		resp, err := c.client.Do(req)
		if err != nil {
			if !kelevraConnDead(postConn) {
				c.Close()
			}
			return err
		}
		io.Copy(io.Discard, resp.Body)
		defer resp.Body.Close()
		if resp.StatusCode != 200 {
			return E.New("bad status code: ", resp.Status)
		}
	} else {
		// stringify the entire HTTP/1.1 request so it can be
		// safely retried. if instead req.Write is called multiple
		// times, the body is already drained after the first
		// request
		requestBuff := new(bytes.Buffer)
		requestBuff.Grow(512 + int(req.ContentLength))
		common.Must(req.Write(requestBuff))
		var uploadConn any
		var h1UploadConn *H1Conn
		for {
			uploadConn = c.uploadRawPool.Get()
			newConnection := uploadConn == nil
			if newConnection {
				newConn, err := c.dialUploadConn(context.WithoutCancel(ctx))
				if err != nil {
					return err
				}
				h1UploadConn = NewH1Conn(newConn)
				uploadConn = h1UploadConn
			} else {
				h1UploadConn = uploadConn.(*H1Conn)

				// TODO: Replace 0 here with a config value later
				// Or add some other condition for optimization purposes
				if h1UploadConn.UnreadedResponsesCount > 0 {
					resp, err := http.ReadResponse(h1UploadConn.RespBufReader, req)
					if err != nil {
						c.Close()
						return fmt.Errorf("error while reading response: %s", err.Error())
					}
					io.Copy(io.Discard, resp.Body)
					defer resp.Body.Close()
					if resp.StatusCode != 200 {
						return fmt.Errorf("got non-200 error response code: %d", resp.StatusCode)
					}
				}
			}
			_, err := h1UploadConn.Write(requestBuff.Bytes())
			// if the write failed, we try another connection from
			// the pool, until the write on a new connection fails.
			// failed writes to a pooled connection are normal when
			// the connection has been closed in the meantime.
			if err == nil {
				break
			} else if newConnection {
				return err
			}
		}
		c.uploadRawPool.Put(uploadConn)
	}
	return nil
}

// WaitReadCloser — тело ответа, которое появится позже (Set из горутины client.Do).
//
// Kelevra (правка 8): Set, Read и Close — из разных горутин, а ReadCloser был без
// синхронизации (и Wait закрывался через recover от двойного close) — go test
// -race это ловит. Теперь всё состояние под access:
//   - Close после Set закрывает пришедшее тело (как раньше);
//   - Close до Set будит Read (ошибка io.ErrClosedPipe) и не теряется:
//     тело, пришедшее Set'ом позже, закрывается сразу и наружу не отдаётся;
//   - Wait закрывается ровно один раз — тем, кто первый (Set или Close).
type WaitReadCloser struct {
	Wait chan struct{}
	io.ReadCloser

	access sync.Mutex
	closed bool // Close уже звали
}

func (w *WaitReadCloser) Set(rc io.ReadCloser) {
	w.access.Lock()
	if w.closed {
		// Close опередил тело: Wait уже закрыт им, тело никому не нужно.
		w.access.Unlock()
		rc.Close()
		return
	}
	w.ReadCloser = rc
	close(w.Wait)
	w.access.Unlock()
}

func (w *WaitReadCloser) Read(b []byte) (int, error) {
	<-w.Wait
	w.access.Lock()
	rc := w.ReadCloser
	w.access.Unlock()
	if rc == nil {
		return 0, io.ErrClosedPipe
	}
	return rc.Read(b)
}

func (w *WaitReadCloser) Close() error {
	w.access.Lock()
	rc := w.ReadCloser
	if rc == nil && !w.closed {
		close(w.Wait) // тела ещё нет: будим Read; Set увидит closed
	}
	w.closed = true
	w.access.Unlock()
	if rc != nil {
		// вне замка: Close тела h2 может ждать (RST_STREAM), Read его не держит
		return rc.Close()
	}
	return nil
}

func ApplyMetaToRequest(options *option.V2RayXHTTPBaseOptions, req *http.Request, sessionId string, seqStr string) {
	sessionPlacement := options.GetNormalizedSessionPlacement()
	seqPlacement := options.GetNormalizedSeqPlacement()
	sessionKey := options.GetNormalizedSessionKey()
	seqKey := options.GetNormalizedSeqKey()
	if sessionId != "" {
		switch sessionPlacement {
		case option.PlacementPath:
			req.URL.Path = appendToPath(req.URL.Path, sessionId)
		case option.PlacementQuery:
			q := req.URL.Query()
			q.Set(sessionKey, sessionId)
			req.URL.RawQuery = q.Encode()
		case option.PlacementHeader:
			req.Header.Set(sessionKey, sessionId)
		case option.PlacementCookie:
			req.AddCookie(&http.Cookie{Name: sessionKey, Value: sessionId})
		}
	}
	if seqStr != "" {
		switch seqPlacement {
		case option.PlacementPath:
			req.URL.Path = appendToPath(req.URL.Path, seqStr)
		case option.PlacementQuery:
			q := req.URL.Query()
			q.Set(seqKey, seqStr)
			req.URL.RawQuery = q.Encode()
		case option.PlacementHeader:
			req.Header.Set(seqKey, seqStr)
		case option.PlacementCookie:
			req.AddCookie(&http.Cookie{Name: seqKey, Value: seqStr})
		}
	}
}

func appendToPath(path, value string) string {
	if strings.HasSuffix(path, "/") {
		return path + value
	}
	return path + "/" + value
}
