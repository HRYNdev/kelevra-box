package vless

// Kelevra (core-xhttp/apply.py, правка 8): домашняя сторона (bridge) VLESS reverse,
// совместимая с порталом Xray (XTLS/Xray-core#5101, проверено против 26.3.27).
//
// Опция `"reverse": {"tag": "..."}` у VLESS-аутбаунда: аутбаунд сам держит к порталу
// соединения командой Rvs (0x04, адреса в заголовке нет), портал поверх них ведёт
// mux.cool как клиент, а мы — как сервер: каждую его сессию (TCP или UDP) отдаём в
// роутер так, будто она пришла со входа с этим тегом. Как в Xray (proxy/vless/outbound,
// Reverse.monitor): раз в 2 с выкидываем мёртвые и уведённые порталом (DRAIN)
// соединения и звоним заново, если живых нет или на одно приходится больше 16 сессий.
// Несколько аутбаундов с одним тегом — пул: каждый держит своё.
//
// Чего нет: flow (vision) и multiplex вместе с reverse — отказ при разборе конфига.

import (
	"context"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	N "github.com/sagernet/sing/common/network"

	"github.com/gofrs/uuid/v5"
)

const (
	reverseCommand        = 0x04 // protocol.RequestCommandRvs в Xray
	reverseSessionsPerOne = 16
	// Соединений к одному порталу не больше: портал может уводить их (DRAIN)
	// сколько угодно часто, а каждое держит до rvsMaxSessions сессий.
	rvsMaxWorkers = 4
)

// Переменные, а не константы, только ради теста.
var (
	reverseCheckInterval = 2 * time.Second
	// Портал шлёт Control раз в 10 с; три пропуска подряд — соединение мёртвое,
	// даже если TCP об этом ещё не узнал (Xray ждал бы connIdle, 300 с).
	reverseSilenceTimeout = 30 * time.Second
	reverseDialTimeout    = 20 * time.Second
)

// Start: мост звонит, когда всё ядро (роутер, аутбаунды) уже запущено.
func (h *Outbound) Start(stage adapter.StartStage) error {
	if h.reverse != nil && stage == adapter.StartStateStarted {
		h.reverse.start()
	}
	return nil
}

// dialReverse — то же соединение до сервера, что у обычного запроса (vlessDialer):
// транспорт v2ray, либо TLS/Reality, либо голый TCP, со всеми опциями дайлера.
func (h *Outbound) dialReverse(ctx context.Context) (net.Conn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = h.serverAddr
	if h.transport != nil {
		return h.transport.DialContext(ctx)
	} else if h.tlsDialer != nil {
		return h.tlsDialer.DialTLSContext(ctx, h.serverAddr)
	}
	return h.dialer.DialContext(ctx, N.NetworkTCP, h.serverAddr)
}

type reverseBridge struct {
	ctx     context.Context
	cancel  context.CancelFunc
	router  adapter.ConnectionRouterEx
	logger  logger.ContextLogger
	tag     string
	user    [16]byte
	dial    func(ctx context.Context) (net.Conn, error)
	access  sync.Mutex
	workers []*reverseWorker
	once    sync.Once
	started atomic.Bool
	failing atomic.Bool
	// файл состояния (kelevra_reverse_state.go), nil — не пишется
	state     *reverseState
	lastPulse atomic.Int64
}

func newReverseBridge(ctx context.Context, router adapter.ConnectionRouterEx, logger logger.ContextLogger, outbound *Outbound, options option.VLESSOutboundOptions) (*reverseBridge, error) {
	if options.Reverse.Tag == "" {
		return nil, E.New("reverse: tag is empty")
	}
	if options.Flow != "" {
		return nil, E.New("reverse: flow is not supported: ", options.Flow)
	}
	if options.Multiplex != nil && options.Multiplex.Enabled {
		return nil, E.New("reverse: multiplex is not supported")
	}
	bridge := newReverseBridgeWith(ctx, router, logger, options.Reverse.Tag, options.UUID, outbound.dialReverse)
	if options.Reverse.StatePath != "" {
		bridge.state = newReverseState(options.Reverse.StatePath, outbound.Tag(), outbound.serverAddr.String())
	}
	return bridge, nil
}

func newReverseBridgeWith(ctx context.Context, router adapter.ConnectionRouterEx, logger logger.ContextLogger, tag string, userID string, dial func(ctx context.Context) (net.Conn, error)) *reverseBridge {
	// UUID как в sing-vmess/vless.NewClient: не UUID — значит строка для UUIDv5.
	user, err := uuid.FromString(userID)
	if err != nil {
		user = uuid.NewV5(uuid.Nil, userID)
	}
	ctx, cancel := context.WithCancel(ctx)
	return &reverseBridge{
		ctx:    ctx,
		cancel: cancel,
		router: router,
		logger: logger,
		tag:    tag,
		user:   user,
		dial:   dial,
	}
}

func (b *reverseBridge) start() {
	b.once.Do(func() {
		b.started.Store(true)
		go b.loop()
		if b.state != nil {
			go b.stateLoop()
		}
	})
}

func (b *reverseBridge) loop() {
	ticker := time.NewTicker(reverseCheckInterval)
	defer ticker.Stop()
	for {
		b.monitor()
		select {
		case <-b.ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

// monitor — Reverse.monitor из Xray: выкинуть закрытые, посчитать живые (не DRAIN),
// позвонить ещё раз, если живых нет или они перегружены.
func (b *reverseBridge) monitor() {
	b.access.Lock()
	defer b.access.Unlock()
	if b.ctx.Err() != nil {
		return
	}
	var alive []*reverseWorker
	var active, sessions int
	for _, w := range b.workers {
		if w.isClosed() {
			continue
		}
		alive = append(alive, w)
		if !w.draining.Load() {
			active++
			sessions += w.sessionCount()
		}
	}
	b.workers = alive
	if (active == 0 || sessions/active > reverseSessionsPerOne) && len(alive) < rvsMaxWorkers {
		w := newReverseWorker(b)
		b.workers = append(b.workers, w)
		go w.run()
	}
}

// reset — сеть сменилась (sing-box при этом и так рвёт все соединения роутера):
// рвём свои и звоним сразу, не ждём тика. Первый такой сброс sing-box делает
// сам на старте, когда находит интерфейс по умолчанию, — без звонка сразу мост
// терял бы на этом 2 с.
func (b *reverseBridge) reset() {
	b.closeWorkers()
	if b.started.Load() {
		go b.monitor()
	}
}

func (b *reverseBridge) closeWorkers() {
	b.access.Lock()
	workers := b.workers
	b.workers = nil
	b.access.Unlock()
	for _, w := range workers {
		w.close()
	}
}

func (b *reverseBridge) Close() error {
	b.cancel()
	b.closeWorkers()
	if b.state != nil && b.started.Load() {
		b.writeState(true) // последнее слово: connected=false
	}
	return nil
}
