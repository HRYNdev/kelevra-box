package xhttp

// Kelevra (core-xhttp/apply.py, правка 8): живость канала XHTTP.
//
// Где клиент мог висеть без срока (всё — по коду v1.14.1 + патч, x/net v0.57.0):
//
//  1. createHTTPClient.dialContext: TCP + TLS-рукопожатие шли с контекстом запроса,
//     а запросы создаются с context.WithoutCancel — дедлайна нет. TCP-connect
//     ограничен самим dialer'ом sing-box (C.TCPConnectTimeout, 5 с), а вот TLS
//     (tls.ClientHandshake → HandshakeContext(ctx)) — ничем: сервер принял TCP и
//     молчит → OpenStream не выходит из <-gotConn.Wait(), vless DialContext висит,
//     ClientBind.connect() висит с замком connAccess — весь «Дом» стоит.
//     Лечение: на диал (TCP+TLS) — свой таймаут kelevraDialTimeout.
//
//  2. OpenStream для stream-down (GET): client.Do ждёт заголовков ответа без
//     срока; читающий WaitReadCloser.Read висит на <-w.Wait. Сервер Xray отвечает
//     на GET 200 сразу (hub.go: WriteHeader + Flush до ожидания), так что
//     заголовков дольше kelevraResponseHeaderTimeout не бывает у живой линии.
//     Лечение: контекст запроса отменяется таймером, который снимается, как
//     только заголовки пришли; после этого контекст живёт до Close тела —
//     отменять его раньше нельзя (http2 рвёт чтение тела по ctx.Done).
//     На stream-up POST (uploadOnly) не распространяется: сервер его заголовки
//     может не отдавать до конца сессии (hub.go: 200 без Flush).
//
//  3. http2.Transport: ReadIdleTimeout (45 с) включён и в апстриме, и у нас —
//     мёртвое h2-соединение закрывается PING-проверкой за ReadIdleTimeout +
//     PingTimeout (по умолчанию 15 с). Здесь PingTimeout задан явно и короче, а
//     WriteByteTimeout закрывает соединение, в которое байты не уходят вовсе
//     (мёртвый TCP с полным буфером отправки).

import (
	"context"
	"io"
	"sync"
	"time"

	C "github.com/sagernet/sing-box/constant"
)

var (
	// TCP + TLS до портала, одним куском.
	kelevraDialTimeout = C.TCPTimeout
	// Заголовки ответа на stream-down GET / stream-one.
	kelevraResponseHeaderTimeout = 30 * time.Second
	// h2 PING без ответа столько — соединение мёртво.
	kelevraH2PingTimeout = 10 * time.Second
	// Ни одного байта не записалось столько — соединение мёртво.
	kelevraH2WriteByteTimeout = 30 * time.Second
)

// kelevraHeaderDeadline даёт контекст запроса, который отменится, если заголовки
// ответа не пришли за kelevraResponseHeaderTimeout. headersGot снимает таймер
// (звать один раз, сразу после client.Do) и говорит, успел ли: false — таймер
// уже выстрелил и контекст отменён, даже если Do вернул ответ (заголовки пришли
// на самой границе срока); такое тело http2 оборвёт на первом Read
// (x/net transport.go: ctx.Done), так что это ошибка, а не живой ответ.
// cancel — освобождает контекст (звать при ошибке или при закрытии тела ответа).
func kelevraHeaderDeadline(ctx context.Context, enabled bool) (reqCtx context.Context, headersGot func() bool, cancel context.CancelFunc) {
	if !enabled || kelevraResponseHeaderTimeout <= 0 {
		return ctx, func() bool { return true }, func() {}
	}
	reqCtx, cancel = context.WithCancel(ctx)
	timer := time.AfterFunc(kelevraResponseHeaderTimeout, cancel)
	headersGot = func() bool { return timer.Stop() }
	return reqCtx, headersGot, cancel
}

// kelevraBodyWithCancel — тело ответа, при закрытии отменяющее контекст запроса.
//
// Создаётся в горутине client.Do, закрывается из чужой (splitConn.Close,
// ClientBind). Поля ReadCloser и cancel после создания не меняются, а наружу
// структура уходит только через WaitReadCloser.Set под его замком — это и даёт
// читающей стороне happens-before (без замка go test -race ловил тут гонку).
// Close — ровно один раз, сколько бы горутин его ни звали; остальные получают
// тот же результат.
type kelevraBodyWithCancel struct {
	io.ReadCloser
	cancel context.CancelFunc

	closeOnce sync.Once
	closeErr  error
}

func (b *kelevraBodyWithCancel) Close() error {
	b.closeOnce.Do(func() {
		b.closeErr = b.ReadCloser.Close()
		b.cancel()
	})
	return b.closeErr
}
