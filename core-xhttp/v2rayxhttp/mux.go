package xhttp

import (
	"context"
	"crypto/rand"
	"math"
	"math/big"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/option"
)

type XmuxConn interface {
	Close()
	IsClosed() bool
}

type XmuxClient struct {
	XmuxConn     XmuxConn
	openUsage    int
	leftUsage    int
	LeftRequests atomic.Int32
	UnreusableAt time.Time

	closed bool
	mtx    sync.Mutex
}

func (c *XmuxClient) Close() {
	c.mtx.Lock()
	defer c.mtx.Unlock()
	c.closed = true
	if c.openUsage <= 0 {
		c.XmuxConn.Close()
	}
}

func (c *XmuxClient) AddOpenUsage(delta int) {
	c.mtx.Lock()
	defer c.mtx.Unlock()
	c.openUsage += delta
	if c.closed && c.openUsage <= 0 {
		c.XmuxConn.Close()
	}
}

func (c *XmuxClient) GetOpenUsage() int {
	c.mtx.Lock()
	defer c.mtx.Unlock()
	return c.openUsage
}

type XmuxManager struct {
	options     option.V2RayXHTTPXmuxOptions
	concurrency int
	connections int
	newConnFunc func() XmuxConn
	xmuxClients []*XmuxClient
	mtx         sync.Mutex
}

func NewXmuxManager(options option.V2RayXHTTPXmuxOptions, newConnFunc func() XmuxConn) *XmuxManager {
	return &XmuxManager{
		options:     options,
		concurrency: options.GetNormalizedMaxConcurrency().Rand(),
		connections: options.GetNormalizedMaxConnections().Rand(),
		newConnFunc: newConnFunc,
		xmuxClients: make([]*XmuxClient, 0),
	}
}

func (m *XmuxManager) Close() {
	m.mtx.Lock()
	defer m.mtx.Unlock()
	for _, xmuxClient := range m.xmuxClients {
		xmuxClient.Close()
	}
	m.xmuxClients = m.xmuxClients[:0]
}

func (m *XmuxManager) newXmuxClient() *XmuxClient {
	xmuxClient := &XmuxClient{
		XmuxConn:  m.newConnFunc(),
		leftUsage: -1,
	}
	if x := m.options.GetNormalizedCMaxReuseTimes().Rand(); x > 0 {
		xmuxClient.leftUsage = x - 1
	}
	xmuxClient.LeftRequests.Store(math.MaxInt32)
	if x := m.options.GetNormalizedHMaxRequestTimes().Rand(); x > 0 {
		xmuxClient.LeftRequests.Store(int32(x))
	}
	if x := m.options.GetNormalizedHMaxReusableSecs().Rand(); x > 0 {
		xmuxClient.UnreusableAt = time.Now().Add(time.Duration(x) * time.Second)
	}
	m.xmuxClients = append(m.xmuxClients, xmuxClient)
	return xmuxClient
}

// GetXmuxClient выбирает соединение без учёта сессии (так берёт его цикл
// packet-up при смене соединения посреди сессии, как у Xray).
func (m *XmuxManager) GetXmuxClient(ctx context.Context) *XmuxClient {
	m.mtx.Lock()
	defer m.mtx.Unlock()
	return m.pickLocked()
}

// AcquireXmuxClient выбирает соединение для новой сессии и сразу, под тем же
// замком менеджера, засчитывает на нём одну открытую сессию. Без этого
// одновременные вызовы (страница открывает 6 соединений разом) видят одну и ту
// же нагрузку и складываются на одно соединение. Освобождать — AddOpenUsage(-1).
func (m *XmuxManager) AcquireXmuxClient(ctx context.Context) *XmuxClient {
	m.mtx.Lock()
	defer m.mtx.Unlock()
	xmuxClient := m.pickLocked()
	xmuxClient.AddOpenUsage(1)
	return xmuxClient
}

func (m *XmuxManager) pickLocked() *XmuxClient {
	var evicted []*XmuxClient
	for i := 0; i < len(m.xmuxClients); {
		xmuxClient := m.xmuxClients[i]
		if xmuxClient.XmuxConn.IsClosed() ||
			xmuxClient.leftUsage == 0 ||
			xmuxClient.LeftRequests.Load() <= 0 ||
			(xmuxClient.UnreusableAt != time.Time{} && time.Now().After(xmuxClient.UnreusableAt)) {
			m.xmuxClients = append(m.xmuxClients[:i], m.xmuxClients[i+1:]...)
			evicted = append(evicted, xmuxClient)
		} else {
			i++
		}
	}
	// выбывшее соединение физически закрывается, только когда на нём не
	// осталось открытых сессий (XmuxClient.Close), живые потоки доживают
	for _, c := range evicted {
		c.Close()
	}
	if len(m.xmuxClients) == 0 {
		return m.newXmuxClient()
	}
	if m.connections > 0 && len(m.xmuxClients) < m.connections {
		return m.newXmuxClient()
	}
	xmuxClient := m.leastLoadedLocked()
	if xmuxClient == nil {
		// все соединения упёрлись в max_concurrency
		return m.newXmuxClient()
	}
	if xmuxClient.leftUsage > 0 {
		xmuxClient.leftUsage -= 1
	}
	return xmuxClient
}

// leastLoadedLocked: соединение с наименьшим числом открытых сессий среди тех,
// что не упёрлись в max_concurrency; при равенстве — случайное из равных.
// У Xray выбор чисто случайный: при max_connections 2 страница из 6 потоков
// с вероятностью 1/32 целиком ложится в одно соединение, рядом с загрузкой.
func (m *XmuxManager) leastLoadedLocked() *XmuxClient {
	var best []*XmuxClient
	bestUsage := math.MaxInt
	for _, xmuxClient := range m.xmuxClients {
		usage := xmuxClient.GetOpenUsage()
		if m.concurrency > 0 && usage >= m.concurrency {
			continue
		}
		if usage < bestUsage {
			bestUsage = usage
			best = best[:0]
		}
		if usage == bestUsage {
			best = append(best, xmuxClient)
		}
	}
	if len(best) == 0 {
		return nil
	}
	if len(best) == 1 {
		return best[0]
	}
	i, _ := rand.Int(rand.Reader, big.NewInt(int64(len(best))))
	return best[i.Int64()]
}
