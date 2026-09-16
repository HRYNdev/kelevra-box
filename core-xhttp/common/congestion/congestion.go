package congestion

import (
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/congestion"
	congestion_meta1 "github.com/sagernet/sing-quic/congestion_meta1"
	congestion_meta2 "github.com/sagernet/sing-quic/congestion_meta2"
	E "github.com/sagernet/sing/common/exceptions"
)

func NewCongestionControl(name string, cwnd int, timeFunc func() time.Time) (func(conn *quic.Conn) congestion.CongestionControl, error) {
	if timeFunc == nil {
		timeFunc = time.Now
	}
	if cwnd == 0 {
		cwnd = 32
	}
	switch name {
	// sing-quic 0.7.0 переписал и сам BBR: NewBbrSender и DefaultClock из
	// congestion_meta2 удалены, остался NewBbrSenderWithProfile(размер, профиль)
	// — без часов и без начального окна (так его зовёт и сам апстрим:
	// sing-quic/tuic/congestion.go:37). Начальное окно там прибито к
	// initialCongestionWindowPackets = 32 — ровно наш прежний дефолт, поэтому
	// при cwnd 0 или 32 поведение то же, что было. Другое значение исполнить
	// нечем: вместо тихой подмены окна отдаём отказ.
	case "", "bbr":
		if cwnd != 32 {
			return nil, E.New("cwnd ", cwnd, " для bbr больше не задаётся: sing-quic 0.7.0 прибил начальное окно к 32 пакетам; убери cwnd или поставь 32")
		}
		return func(conn *quic.Conn) congestion.CongestionControl {
			return congestion_meta2.NewBbrSenderWithProfile(
				congestion.ByteCount(conn.Config().InitialPacketSize),
				congestion_meta2.ProfileStandard,
			)
		}, nil
	// sing-quic 0.7.0 (его тянет ядро sing-box v1.14.1) удалил пакеты
	// congestion_bbr1 и congestion_bbr2 целиком — остались только
	// congestion_meta1 и congestion_meta2. На этих двух снятых пакетах
	// держались bbr_standard, bbr2 и bbr2_variant, и из-за них не собиралось
	// ВСЁ ядро: kelevracores -> libbox -> include -> transport/v2ray ->
	// v2rayxhttp -> common/congestion -> снятый пакет (лог CI PR #30, 16.09).
	// Поведение по умолчанию не тронуто: case "", "bbr" сидит на
	// congestion_meta2, который на месте. Имена ниже нигде в приложении не
	// выставляются (grep по app/ — ноль), поэтому вместо тихой подмены
	// отдаём внятный отказ: молчаливый фоллбэк на другой алгоритм был бы
	// хуже — конфиг просил одно, а получил бы другое.
	case "bbr_standard", "bbr2", "bbr2_variant":
		return nil, E.New("congestion control ", name, " снят вместе с пакетами sing-quic bbr1/bbr2; доступны: bbr (по умолчанию), cubic, reno, new_reno")
	case "cubic":
		return func(conn *quic.Conn) congestion.CongestionControl {
			return congestion_meta1.NewCubicSender(
				congestion_meta1.DefaultClock{TimeFunc: timeFunc},
				congestion.ByteCount(conn.Config().InitialPacketSize),
				false,
			)
		}, nil
	case "reno":
		return func(conn *quic.Conn) congestion.CongestionControl {
			return congestion_meta1.NewCubicSender(
				congestion_meta1.DefaultClock{TimeFunc: timeFunc},
				congestion.ByteCount(conn.Config().InitialPacketSize),
				true,
			)
		}, nil
	default:
		return nil, E.New("unknown congestion control: ", name)
	}
}
