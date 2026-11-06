package wireguard

// Kelevra (core-xhttp/apply.py, правка 8): сторож линии ClientBind.
//
// ClientBind держит одно «UDP-соединение» к пиру поверх detour (у нас — vless +
// XHTTP до портала). Апстрим передиаливает его только когда ReadFrom или Write
// вернули ошибку; дедлайнов нет. Если линия умерла молча (TCP жив по мнению
// ядра, но пакеты не доходят; или сервер уже не связан с мостом дома), Write
// успешен, ReadFrom висит — «Дом» мёртв, пока ядро не перезапустят.
//
// Сторож смотрит на то, что видно уже на уровне Bind: тип WireGuard-сообщения
// в первом байте (1 — handshake initiation, 2 — response, 3 — cookie reply,
// 4 — transport data; wireguard-go device/noise-protocol.go). Живой пир отвечает
// на initiation за RTT; wireguard-go повторяет initiation каждые RekeyTimeout
// (5 с) до RekeyAttemptTime (90 с). Если подряд ушло N initiation и за это время
// не пришло НИ ОДНОГО пакета, причём с первого безответного прошло не меньше
// окна — линия мертва: рвём wireConn, следующий Send/receive передиалит detour.
//
// Почему именно initiation, а не «отправил, не получил»: при простое клиент шлёт
// persistent keepalive каждые 25 с, а пир на keepalive ничем не отвечает —
// считать это смертью нельзя. Initiation же без ответа — всегда беда.
//
// Ложные срабатывания: нужно ≥ N initiation (по 5 с каждая) и ни одного входящего
// пакета ≥ окна. Провал связи на 20 с на мобильной сети даёт один лишний передиал
// detour (новая пара потоков на том же h2-соединении) — безвредно, WireGuard
// продолжает рукопожатие уже на новой линии.
//
// Файл кладёт apply.py в transport/wireguard/ рядом с client_bind.go; сами
// вызовы вставляются в client_bind.go точечными правками (см. apply.py, шаг 8).

import (
	"sync"
	"time"
)

// Тип сообщения WireGuard handshake initiation (device.MessageInitiationType).
const kelevraWGMessageInitiation = 1

var (
	// Сколько initiation подряд без единого входящего пакета считаем мёртвой
	// линией. 0 — сторож выключен (поведение апстрима).
	kelevraStorozhInitiations int32 = 4
	// И не раньше, чем через столько после первого безответного initiation.
	kelevraStorozhWindow = 20 * time.Second
)

type kelevraStorozh struct {
	access          sync.Mutex
	unanswered      int32
	firstUnanswered time.Time
}

// poluchen: пришёл любой пакет — линия жива, счёт обнуляем.
func (s *kelevraStorozh) poluchen() {
	s.access.Lock()
	s.unanswered = 0
	s.firstUnanswered = time.Time{}
	s.access.Unlock()
}

// otpravlen учитывает исходящий пакет. true — initiation без ответа слишком
// много и слишком долго: линию пора рвать.
func (s *kelevraStorozh) otpravlen(packet []byte) bool {
	if kelevraStorozhInitiations <= 0 || len(packet) < 4 || packet[0] != kelevraWGMessageInitiation {
		return false
	}
	now := time.Now()
	s.access.Lock()
	defer s.access.Unlock()
	s.unanswered++
	if s.unanswered == 1 {
		s.firstUnanswered = now
		return false
	}
	return s.unanswered >= kelevraStorozhInitiations && now.Sub(s.firstUnanswered) >= kelevraStorozhWindow
}
