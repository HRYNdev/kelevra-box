package vless

// Kelevra (core-xhttp/apply.py, правка 8): серверная сторона mux.cool поверх одного
// Rvs-соединения к порталу. Формат кадра — Xray common/mux/frame.go:
//
//	2 байта длина метаданных | 2 id сессии | 1 статус | 1 опция
//	[New, или Keep с UDP-адресом: 1 сеть | 2 порт | тип и адрес]
//	[только New на обратном mux: откуда пришёл клиент портала и куда (source, local)]
//	[опция Data: 2 байта длина | данные]
//
// Первая сессия от портала — служебная: UDP на домен «reverse», в ней раз в 10 с
// protobuf Control{state=1, random=99}; state=DRAIN значит «новых сессий сюда не
// будет», и мост звонит ещё раз, а это соединение доживает свои сессии и закрывается.
// Глобального ID (xudp) на обратном mux портал не шлёт: место после адреса занято
// source/local. UDP приходит обычной пакетной сессией, у пакета может быть свой адрес.

import (
	std_bufio "bufio"
	"context"
	"encoding/binary"
	"io"
	"net"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-vmess"
	"github.com/sagernet/sing-vmess/vless"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/buf"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/common/pipe"
)

const (
	rvsStatusNew       byte = 0x01
	rvsStatusKeep      byte = 0x02
	rvsStatusEnd       byte = 0x03
	rvsStatusKeepAlive byte = 0x04

	rvsOptionData  byte = 0x01
	rvsOptionError byte = 0x02

	rvsNetworkTCP byte = 0x01
	rvsNetworkUDP byte = 0x02

	rvsControlDomain = "reverse"
	rvsControlDrain  = 1

	rvsMaxMetaLen = 512  // как в Xray: больше — поток сломан
	rvsMaxChunk   = 8192 // Xray режет поток по 8 КБ и не берёт пакет больше buf.Size
	// Данные одного кадра: Xray пишет не больше 8 КБ. Больше 16 КБ — поток сломан
	// или чужой: рвём соединение, а не держим в памяти до 64 КБ на кадр.
	rvsMaxFrameData = 16 * 1024
)

// Пределы против нагрузки через мост (договор, «Безопасность», п.8): правило
// портала пускает UDP на мост от любой учётки VPS, поэтому мост — точка DoS дома.
// Переменные, а не константы, только ради теста.
var (
	// Потолок одновременных сессий на одно соединение к порталу; новая сверх —
	// сразу End с ошибкой, в роутер не идёт.
	rvsMaxSessions = 512
	// UDP-сессия без трафика в обе стороны дольше этого закрывается (End).
	// WireGuard шлёт keepalive раз в 25 с, живой туннель так не закроется.
	rvsUDPIdleTimeout = 120 * time.Second
	// Очередь одной сессии к роутеру: TCP — 128 кадров (до 1 МБ), UDP — 64 пакета.
	// У mux.cool нет своего управления потоком: ждать места в очереди значит
	// стопорить чтение всего соединения и пульс портала. Поэтому полная очередь
	// TCP закрывает сессию (End), а у UDP теряется пакет.
	rvsTCPQueueLen = 128
	rvsUDPQueueLen = 64
	// Всего в очередях одного соединения — не больше 8 МБ (512 сессий иначе дали
	// бы до 128 МБ); сверх — как полная очередь.
	rvsMaxQueuedBytes int64 = 8 << 20
	// End-кадры пишет отдельная горутина: чтение соединения их не ждёт. Очередь
	// полна — End теряется, портал закроет сессию сам.
	rvsEndQueueLen = 1024
	// Те же пределы на весь процесс — сумма по всем соединениям всех аутбаундов
	// с reverse: соединений к порталу до 4 (rvsMaxWorkers), порталов несколько.
	rvsMaxTotalSessions          = 1024
	rvsMaxTotalQueuedBytes int64 = 32 << 20
)

// Счётчики на весь процесс для пределов выше.
var rvsTotalSessions, rvsTotalQueued atomic.Int64

// ---------------------------------------------------------------- кадр

type rvsFrame struct {
	id      uint16
	status  byte
	option  byte
	network byte
	target  M.Socksaddr
	source  M.Socksaddr
}

// parseRvsFrameMeta разбирает метаданные кадра (без двух байт длины), как
// FrameMetadata.UnmarshalFromBuffer(readSourceAndLocal=true) в Xray.
func parseRvsFrameMeta(meta []byte) (rvsFrame, error) {
	var f rvsFrame
	if len(meta) < 4 {
		return f, E.New("reverse: short frame meta: ", len(meta))
	}
	f.id = binary.BigEndian.Uint16(meta)
	f.status = meta[2]
	f.option = meta[3]
	if f.status == rvsStatusNew || (f.status == rvsStatusKeep && len(meta) > 4 && meta[4] == rvsNetworkUDP) {
		if len(meta) < 8 {
			return f, E.New("reverse: short frame meta: ", len(meta))
		}
		f.network = meta[4]
		if f.network != rvsNetworkTCP && f.network != rvsNetworkUDP {
			return f, E.New("reverse: unknown network: ", f.network)
		}
		r := &byteReader{b: meta[5:]}
		target, err := vmess.AddressSerializer.ReadAddrPort(r)
		if err != nil {
			return f, E.Cause(err, "reverse: read target")
		}
		f.target = target
		if f.status == rvsStatusNew && len(r.b) > 0 {
			// source: откуда к порталу пришёл клиент; local нам не нужен.
			// Ноль — набивка, как в Xray; битый хвост сессию не ломает.
			if network := r.b[0]; network == rvsNetworkTCP || network == rvsNetworkUDP {
				r.b = r.b[1:]
				if source, err := vmess.AddressSerializer.ReadAddrPort(r); err == nil {
					f.source = source
				}
			}
		}
	}
	return f, nil
}

type byteReader struct{ b []byte }

func (r *byteReader) Read(p []byte) (int, error) {
	if len(r.b) == 0 {
		return 0, io.EOF
	}
	n := copy(p, r.b)
	r.b = r.b[n:]
	return n, nil
}

// encodeRvsFrame: кадр ответа (Keep/End) целиком в один буфер, чтобы писать в
// соединение одним вызовом под одной блокировкой.
func encodeRvsFrame(id uint16, status byte, option byte, udpAddr M.Socksaddr, data []byte) *buf.Buffer {
	metaLen := 4
	if udpAddr.IsValid() {
		metaLen += 1 + vmess.AddressSerializer.AddrPortLen(udpAddr)
	}
	size := 2 + metaLen
	if option&rvsOptionData != 0 {
		size += 2 + len(data)
	}
	buffer := buf.NewSize(size)
	binary.BigEndian.PutUint16(buffer.Extend(2), uint16(metaLen))
	binary.BigEndian.PutUint16(buffer.Extend(2), id)
	common.Must(buffer.WriteByte(status), buffer.WriteByte(option))
	if udpAddr.IsValid() {
		common.Must(buffer.WriteByte(rvsNetworkUDP))
		common.Must(vmess.AddressSerializer.WriteAddrPort(buffer, udpAddr))
	}
	if option&rvsOptionData != 0 {
		binary.BigEndian.PutUint16(buffer.Extend(2), uint16(len(data)))
		common.Must1(buffer.Write(data))
	}
	return buffer
}

// rvsRequestHeader — заголовок VLESS с командой Rvs: версия 0, UUID, пустые
// дополнения, команда 4, без адреса (Xray proxy/vless/encoding.EncodeRequestHeader).
func rvsRequestHeader(user [16]byte) []byte {
	header := make([]byte, 0, 19)
	header = append(header, vless.Version)
	header = append(header, user[:]...)
	header = append(header, 0, reverseCommand)
	return header
}

// parseRvsControlState достаёт state (поле 1) из protobuf Control; остальные поля
// (random=99) пропускаются.
func parseRvsControlState(b []byte) (uint64, error) {
	var state uint64
	for len(b) > 0 {
		key, n := binary.Uvarint(b)
		if n <= 0 {
			return 0, E.New("reverse: bad control message")
		}
		b = b[n:]
		switch key & 7 {
		case 0:
			v, n := binary.Uvarint(b)
			if n <= 0 {
				return 0, E.New("reverse: bad control message")
			}
			b = b[n:]
			if key>>3 == 1 {
				state = v
			}
		case 1:
			if len(b) < 8 {
				return 0, E.New("reverse: bad control message")
			}
			b = b[8:]
		case 2:
			l, n := binary.Uvarint(b)
			if n <= 0 || uint64(len(b)-n) < l {
				return 0, E.New("reverse: bad control message")
			}
			b = b[n+int(l):]
		case 5:
			if len(b) < 4 {
				return 0, E.New("reverse: bad control message")
			}
			b = b[4:]
		default:
			return 0, E.New("reverse: bad control message")
		}
	}
	return state, nil
}

// ---------------------------------------------------------------- соединение к порталу

type reverseWorker struct {
	bridge       *reverseBridge
	ctx          context.Context
	cancel       context.CancelFunc
	access       sync.Mutex
	conn         net.Conn
	sessions     map[uint16]*reverseSession
	controlID    uint16
	hasControl   bool
	writeAccess  sync.Mutex
	closed       atomic.Bool
	closeOnce    sync.Once
	draining     atomic.Bool
	connected    atomic.Bool
	controlEnded atomic.Bool
	lastRead     atomic.Int64
	queued       atomic.Int64 // байт в очередях сессий
	endQueue     chan rvsEnd
}

type rvsEnd struct {
	id     uint16
	option byte
}

func newReverseWorker(b *reverseBridge) *reverseWorker {
	ctx, cancel := context.WithCancel(b.ctx)
	return &reverseWorker{
		bridge:   b,
		ctx:      ctx,
		cancel:   cancel,
		sessions: make(map[uint16]*reverseSession),
		endQueue: make(chan rvsEnd, rvsEndQueueLen),
	}
}

func (w *reverseWorker) isClosed() bool {
	return w.closed.Load()
}

// sessionCount — как ServerWorker.ActiveConnections в Xray: со служебной сессией.
func (w *reverseWorker) sessionCount() int {
	w.access.Lock()
	defer w.access.Unlock()
	n := len(w.sessions)
	if w.hasControl {
		n++
	}
	return n
}

func (w *reverseWorker) run() {
	defer w.close()
	b := w.bridge
	// Контекст звонка живёт, пока живо соединение: транспорты поверх HTTP держат
	// на нём поток. Зависший звонок рвёт таймер.
	dialTimer := time.AfterFunc(reverseDialTimeout, w.close)
	conn, err := b.dial(w.ctx)
	if err == nil {
		_, err = conn.Write(rvsRequestHeader(b.user))
	}
	dialTimer.Stop()
	if err != nil {
		if conn != nil {
			conn.Close()
		}
		if b.ctx.Err() != nil {
			return
		}
		if b.failing.Swap(true) {
			b.logger.DebugContext(w.ctx, "reverse[", b.tag, "]: dial portal: ", err)
		} else {
			b.logger.WarnContext(w.ctx, "reverse[", b.tag, "]: dial portal: ", err)
		}
		return
	}
	w.access.Lock()
	if w.closed.Load() {
		w.access.Unlock()
		conn.Close()
		return
	}
	w.conn = conn
	w.access.Unlock()
	w.lastRead.Store(time.Now().UnixNano())
	go w.watch()
	go w.endWriter()
	err = w.readLoop(conn)
	if b.ctx.Err() == nil && !w.closed.Load() {
		b.logger.InfoContext(w.ctx, "reverse[", b.tag, "]: portal connection closed: ", err)
	}
}

// watch: молчание портала дольше reverseSilenceTimeout рвёт соединение; уведённое
// (DRAIN) закрывается, когда в нём не осталось сессий.
func (w *reverseWorker) watch() {
	ticker := time.NewTicker(reverseCheckInterval)
	defer ticker.Stop()
	for {
		select {
		case <-w.ctx.Done():
			return
		case <-ticker.C:
		}
		w.closeIdleUDP()
		if w.draining.Load() {
			w.access.Lock()
			empty := len(w.sessions) == 0
			w.access.Unlock()
			if empty {
				w.close()
				return
			}
			continue
		}
		if !w.controlEnded.Load() && time.Since(time.Unix(0, w.lastRead.Load())) > reverseSilenceTimeout {
			w.bridge.logger.WarnContext(w.ctx, "reverse[", w.bridge.tag, "]: portal silent for ", reverseSilenceTimeout, ", reconnecting")
			w.close()
			return
		}
	}
}

func (w *reverseWorker) readLoop(conn net.Conn) error {
	reader := std_bufio.NewReaderSize(conn, 32*1024)
	err := vless.ReadResponse(reader)
	if err != nil {
		return E.Cause(err, "read response")
	}
	b := w.bridge
	b.failing.Store(false)
	b.logger.InfoContext(w.ctx, "reverse[", b.tag, "]: connected to portal")
	w.connected.Store(true)
	b.notifyState()
	var meta [rvsMaxMetaLen]byte
	for {
		err = w.readFrame(reader, meta[:])
		if err != nil {
			return err
		}
	}
}

func (w *reverseWorker) readFrame(reader io.Reader, metaBuffer []byte) error {
	var lenBytes [2]byte
	if _, err := io.ReadFull(reader, lenBytes[:]); err != nil {
		return err
	}
	metaLen := int(binary.BigEndian.Uint16(lenBytes[:]))
	if metaLen > rvsMaxMetaLen {
		return E.New("reverse: invalid meta length: ", metaLen)
	}
	meta := metaBuffer[:metaLen]
	if _, err := io.ReadFull(reader, meta); err != nil {
		return err
	}
	w.lastRead.Store(time.Now().UnixNano())
	frame, err := parseRvsFrameMeta(meta)
	if err != nil {
		return err
	}
	var data *buf.Buffer
	if frame.option&rvsOptionData != 0 {
		if _, err = io.ReadFull(reader, lenBytes[:]); err != nil {
			return err
		}
		dataLen := int(binary.BigEndian.Uint16(lenBytes[:]))
		if dataLen > rvsMaxFrameData {
			return E.New("reverse: frame data too large: ", dataLen)
		}
		data = buf.NewSize(dataLen)
		if _, err = data.ReadFullFrom(reader, dataLen); err != nil {
			data.Release()
			return err
		}
		w.lastRead.Store(time.Now().UnixNano())
	}
	switch frame.status {
	case rvsStatusNew:
		w.handleNew(frame, data)
	case rvsStatusKeep:
		w.handleKeep(frame, data)
	case rvsStatusEnd:
		w.handleEnd(frame)
		releaseRvs(data)
	case rvsStatusKeepAlive:
		releaseRvs(data)
	default:
		releaseRvs(data)
		return E.New("reverse: unknown frame status: ", frame.status)
	}
	return nil
}

func releaseRvs(data *buf.Buffer) {
	if data != nil {
		data.Release()
	}
}

func (w *reverseWorker) handleNew(frame rvsFrame, data *buf.Buffer) {
	if frame.network == rvsNetworkUDP && frame.target.Fqdn == rvsControlDomain {
		w.access.Lock()
		w.controlID = frame.id
		w.hasControl = true
		w.access.Unlock()
		w.controlEnded.Store(false)
		w.handleControl(data)
		return
	}
	w.access.Lock()
	previous := w.sessions[frame.id]
	if previous == nil && (len(w.sessions) >= rvsMaxSessions || rvsTotalSessions.Load() >= int64(rvsMaxTotalSessions)) {
		w.access.Unlock()
		releaseRvs(data)
		w.bridge.logger.DebugContext(w.ctx, "reverse[", w.bridge.tag, "]: session limit reached, refused ", frame.target)
		w.sendEnd(frame.id, rvsOptionError)
		return
	}
	session := newReverseSession(w, frame)
	rvsTotalSessions.Add(1)
	w.sessions[frame.id] = session
	w.access.Unlock()
	w.bridge.notifyState()
	if previous != nil {
		// портал занял id заново — старую сессию он уже забыл, End ей не нужен
		previous.closeWith(true)
		previous.drain()
	}
	go session.route()
	if data != nil {
		session.deliver(data, frame.target)
	}
}

func (w *reverseWorker) handleKeep(frame rvsFrame, data *buf.Buffer) {
	w.access.Lock()
	isControl := w.hasControl && frame.id == w.controlID
	session := w.sessions[frame.id]
	w.access.Unlock()
	if isControl {
		w.handleControl(data)
		return
	}
	if data == nil {
		return
	}
	if session == nil {
		// Как Xray: сессии нет — сказать порталу, чтобы закрыл свою.
		data.Release()
		w.sendEnd(frame.id, 0)
		return
	}
	// Адрес сессии закреплён кадром New (договор, «Безопасность», круг 2, п.9).
	// Роутер сверяет правила один раз, по первому адресу, а NAT override_address
	// подменяет у пакетов только этот адрес (без порта): пакет на другой адрес или
	// порт ушёл бы мимо правил. Поэтому чужой адрес до роутера не доходит: UDP —
	// пакет выброшен, TCP с UDP-адресом — кадр сломан, сессия закрывается.
	if frame.target.IsValid() && frame.target != session.target {
		data.Release()
		w.bridge.logger.DebugContext(w.ctx, "reverse[", w.bridge.tag, "]: packet to ", frame.target, " in session to ", session.target, " dropped")
		if !session.udp {
			session.Close()
		}
		return
	}
	session.deliver(data, session.target)
}

func (w *reverseWorker) handleEnd(frame rvsFrame) {
	w.access.Lock()
	if w.hasControl && frame.id == w.controlID {
		w.hasControl = false
		w.access.Unlock()
		w.controlEnded.Store(true)
		return
	}
	session := w.sessions[frame.id]
	delete(w.sessions, frame.id)
	w.access.Unlock()
	if session != nil {
		session.closeWith(true)
	}
}

func (w *reverseWorker) handleControl(data *buf.Buffer) {
	if data == nil {
		return
	}
	defer data.Release()
	state, err := parseRvsControlState(data.Bytes())
	if err != nil {
		// Xray в этом случае гасит соединение по таймеру; нам хватит пропустить.
		w.bridge.logger.DebugContext(w.ctx, err)
		return
	}
	w.bridge.lastPulse.Store(time.Now().UnixNano())
	w.bridge.notifyState()
	if state == rvsControlDrain && !w.draining.Swap(true) {
		w.bridge.logger.InfoContext(w.ctx, "reverse[", w.bridge.tag, "]: portal drains this connection")
	}
}

func (w *reverseWorker) writeFrame(frame *buf.Buffer) error {
	defer frame.Release()
	w.access.Lock()
	conn := w.conn
	w.access.Unlock()
	if conn == nil || w.closed.Load() {
		return net.ErrClosed
	}
	w.writeAccess.Lock()
	_, err := conn.Write(frame.Bytes())
	w.writeAccess.Unlock()
	if err != nil {
		w.close()
	}
	return err
}

// queueAdd учитывает байты в очередях соединения и всего процесса; false — предел
// превышен, ничего не учтено.
func (w *reverseWorker) queueAdd(size int64) bool {
	worker := w.queued.Add(size)
	total := rvsTotalQueued.Add(size)
	if size > 0 && (worker > rvsMaxQueuedBytes || total > rvsMaxTotalQueuedBytes) {
		w.queued.Add(-size)
		rvsTotalQueued.Add(-size)
		return false
	}
	return true
}

// sendEnd ставит End в очередь писателя; readLoop записи в соединение не ждёт.
func (w *reverseWorker) sendEnd(id uint16, option byte) {
	select {
	case w.endQueue <- rvsEnd{id, option}:
	default:
	}
}

func (w *reverseWorker) endWriter() {
	for {
		select {
		case <-w.ctx.Done():
			return
		case end := <-w.endQueue:
			if w.writeFrame(encodeRvsFrame(end.id, rvsStatusEnd, end.option, M.Socksaddr{}, nil)) != nil {
				return
			}
		}
	}
}

// closeIdleUDP закрывает UDP-сессии без трафика дольше rvsUDPIdleTimeout.
func (w *reverseWorker) closeIdleUDP() {
	now := time.Now().UnixNano()
	var idle []*reverseSession
	w.access.Lock()
	for _, session := range w.sessions {
		if session.udp && time.Duration(now-session.lastActive.Load()) > rvsUDPIdleTimeout {
			idle = append(idle, session)
		}
	}
	w.access.Unlock()
	for _, session := range idle {
		w.bridge.logger.DebugContext(w.ctx, "reverse[", w.bridge.tag, "]: udp session to ", session.target, " idle, closing")
		session.Close()
	}
}

func (w *reverseWorker) close() {
	w.closeOnce.Do(func() {
		w.access.Lock()
		w.closed.Store(true)
		conn := w.conn
		sessions := w.sessions
		w.sessions = make(map[uint16]*reverseSession)
		w.hasControl = false
		w.access.Unlock()
		w.cancel()
		if conn != nil {
			conn.Close()
		}
		for _, session := range sessions {
			session.closeWith(true)
			session.drain() // соединения нет — читать некому, байты вернуть в бюджет
		}
		w.bridge.notifyState()
	})
}

func (w *reverseWorker) removeSession(session *reverseSession) {
	w.access.Lock()
	if w.sessions[session.id] == session {
		delete(w.sessions, session.id)
	}
	w.access.Unlock()
	w.bridge.notifyState()
}

// ---------------------------------------------------------------- сессия

type rvsChunk struct {
	data        *buf.Buffer
	destination M.Socksaddr
}

type reverseSession struct {
	worker       *reverseWorker
	id           uint16
	udp          bool
	target       M.Socksaddr
	source       M.Socksaddr
	queue        chan rvsChunk
	done         chan struct{}
	closeOnce    sync.Once
	remoteClosed atomic.Bool
	readDeadline pipe.Deadline
	pending      *buf.Buffer
	lastActive   atomic.Int64
}

func newReverseSession(w *reverseWorker, frame rvsFrame) *reverseSession {
	udp := frame.network == rvsNetworkUDP
	queueLen := rvsTCPQueueLen
	if udp {
		queueLen = rvsUDPQueueLen
	}
	session := &reverseSession{
		worker:       w,
		id:           frame.id,
		udp:          udp,
		target:       frame.target,
		source:       frame.source,
		queue:        make(chan rvsChunk, queueLen),
		done:         make(chan struct{}),
		readDeadline: pipe.MakeDeadline(),
	}
	session.lastActive.Store(time.Now().UnixNano())
	return session
}

func (s *reverseSession) route() {
	b := s.worker.bridge
	var metadata adapter.InboundContext
	metadata.Inbound = b.tag
	metadata.InboundType = C.TypeVLESS
	metadata.Source = s.source
	metadata.Destination = s.target
	ctx := log.ContextWithNewID(s.worker.ctx)
	onClose := func(error) { s.Close() }
	if s.udp {
		b.logger.InfoContext(ctx, "reverse[", b.tag, "]: inbound packet connection to ", s.target)
		b.router.RoutePacketConnectionEx(ctx, (*reversePacketConn)(s), metadata, onClose)
	} else {
		b.logger.InfoContext(ctx, "reverse[", b.tag, "]: inbound connection to ", s.target)
		b.router.RouteConnectionEx(ctx, (*reverseConn)(s), metadata, onClose)
	}
}

// deliver никогда не ждёт: место в очереди есть — кладём; нет — у UDP теряется
// пакет, а TCP-сессия закрывается (End), чтобы медленный читатель не стопорил
// чтение всего mux и пульс портала.
func (s *reverseSession) deliver(data *buf.Buffer, destination M.Socksaddr) {
	if s.isDone() || (s.udp && data.Len() > rvsMaxChunk) {
		data.Release()
		return
	}
	w := s.worker
	size := int64(data.Len())
	if !w.queueAdd(size) {
		data.Release()
		s.overflow()
		return
	}
	select {
	case s.queue <- rvsChunk{data, destination}:
		s.lastActive.Store(time.Now().UnixNano())
		if s.isDone() && !s.remoteClosed.Load() {
			s.drain() // закрыли, пока клали: читателя больше не будет
		}
	default:
		w.queueAdd(-size)
		data.Release()
		s.overflow()
	}
}

func (s *reverseSession) overflow() {
	if s.udp {
		return
	}
	s.worker.bridge.logger.DebugContext(s.worker.ctx, "reverse[", s.worker.bridge.tag, "]: session to ", s.target, " is not reading, closing")
	s.Close()
}

func (s *reverseSession) dequeued(chunk rvsChunk) rvsChunk {
	s.worker.queueAdd(-int64(chunk.data.Len()))
	return chunk
}

// drain выбрасывает то, что уже некому читать, и возвращает байты в бюджет.
func (s *reverseSession) drain() {
	for {
		select {
		case chunk := <-s.queue:
			s.dequeued(chunk).data.Release()
		default:
			return
		}
	}
}

func (s *reverseSession) next() (rvsChunk, error) {
	select {
	case chunk := <-s.queue:
		return s.dequeued(chunk), nil
	default:
	}
	select {
	case chunk := <-s.queue:
		return s.dequeued(chunk), nil
	case <-s.done:
		select {
		case chunk := <-s.queue:
			return s.dequeued(chunk), nil
		default:
		}
		if s.remoteClosed.Load() {
			return rvsChunk{}, io.EOF
		}
		return rvsChunk{}, net.ErrClosed
	case <-s.readDeadline.Wait():
		return rvsChunk{}, os.ErrDeadlineExceeded
	}
}

// closeWith: remote — сессию закрыл портал (End) или умерло соединение; тогда
// End в ответ не пишем.
func (s *reverseSession) closeWith(remote bool) {
	if remote {
		s.remoteClosed.Store(true)
	}
	s.closeOnce.Do(func() {
		rvsTotalSessions.Add(-1)
		close(s.done)
		s.worker.removeSession(s)
		if !remote {
			s.worker.sendEnd(s.id, 0)
		}
	})
}

func (s *reverseSession) Close() error {
	s.closeWith(false)
	s.drain()
	return nil
}

func (s *reverseSession) closedErr() error {
	if s.remoteClosed.Load() {
		return io.ErrClosedPipe
	}
	return net.ErrClosed
}

func (s *reverseSession) isDone() bool {
	select {
	case <-s.done:
		return true
	default:
		return false
	}
}

func (s *reverseSession) SetReadDeadline(t time.Time) error {
	s.readDeadline.Set(t)
	return nil
}

func (s *reverseSession) SetWriteDeadline(time.Time) error {
	return nil
}

func (s *reverseSession) SetDeadline(t time.Time) error {
	return s.SetReadDeadline(t)
}

func (s *reverseSession) LocalAddr() net.Addr {
	return s.target
}

// reverseConn — TCP-сессия портала для роутера.
type reverseConn reverseSession

func (c *reverseConn) session() *reverseSession { return (*reverseSession)(c) }

func (c *reverseConn) Read(p []byte) (int, error) {
	s := c.session()
	for s.pending == nil {
		chunk, err := s.next()
		if err != nil {
			return 0, err
		}
		if chunk.data.IsEmpty() {
			chunk.data.Release()
			continue
		}
		s.pending = chunk.data
	}
	n, _ := s.pending.Read(p)
	if s.pending.IsEmpty() {
		s.pending.Release()
		s.pending = nil
	}
	return n, nil
}

func (c *reverseConn) Write(p []byte) (int, error) {
	s := c.session()
	written := 0
	for len(p) > 0 {
		if s.isDone() {
			return written, s.closedErr()
		}
		chunk := p
		if len(chunk) > rvsMaxChunk {
			chunk = chunk[:rvsMaxChunk]
		}
		if err := s.worker.writeFrame(encodeRvsFrame(s.id, rvsStatusKeep, rvsOptionData, M.Socksaddr{}, chunk)); err != nil {
			return written, err
		}
		written += len(chunk)
		p = p[len(chunk):]
	}
	return written, nil
}

func (c *reverseConn) Close() error                       { return c.session().Close() }
func (c *reverseConn) LocalAddr() net.Addr                { return c.target }
func (c *reverseConn) RemoteAddr() net.Addr               { return c.source }
func (c *reverseConn) SetDeadline(t time.Time) error      { return c.session().SetDeadline(t) }
func (c *reverseConn) SetReadDeadline(t time.Time) error  { return c.session().SetReadDeadline(t) }
func (c *reverseConn) SetWriteDeadline(t time.Time) error { return nil }

// reversePacketConn — UDP-сессия портала для роутера (N.PacketConn).
type reversePacketConn reverseSession

func (c *reversePacketConn) session() *reverseSession { return (*reverseSession)(c) }

func (c *reversePacketConn) ReadPacket(buffer *buf.Buffer) (M.Socksaddr, error) {
	chunk, err := c.session().next()
	if err != nil {
		return M.Socksaddr{}, err
	}
	defer chunk.data.Release()
	if _, err = buffer.Write(chunk.data.Bytes()); err != nil {
		return M.Socksaddr{}, err
	}
	return c.target, nil // только закреплённый адрес, см. handleKeep
}

// WritePacket: ответ с адресом, откуда он пришёл (роутер уже вернул подменённый
// адрес назад), — портал отдаст его клиенту как источник (full cone, как Xray).
func (c *reversePacketConn) WritePacket(buffer *buf.Buffer, destination M.Socksaddr) error {
	defer buffer.Release()
	s := c.session()
	if s.isDone() {
		return s.closedErr()
	}
	if buffer.Len() > rvsMaxChunk {
		// Портал Xray на таком пакете рвёт весь mux (packet size too large).
		return nil
	}
	if !destination.IsValid() {
		destination = s.target
	}
	s.lastActive.Store(time.Now().UnixNano())
	return s.worker.writeFrame(encodeRvsFrame(s.id, rvsStatusKeep, rvsOptionData, destination, buffer.Bytes()))
}

func (c *reversePacketConn) Close() error                       { return c.session().Close() }
func (c *reversePacketConn) LocalAddr() net.Addr                { return c.target }
func (c *reversePacketConn) SetDeadline(t time.Time) error      { return c.session().SetDeadline(t) }
func (c *reversePacketConn) SetReadDeadline(t time.Time) error  { return c.session().SetReadDeadline(t) }
func (c *reversePacketConn) SetWriteDeadline(t time.Time) error { return nil }
