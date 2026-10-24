//go:build with_utls

// Артефакт проверки, НЕ часть сборки (как reality_mlkem_clienthello_test.go
// рядом): своего go.mod нет, собирается только внутри дерева sing-box.
//
// Зачем: доказательство привязки правок 6 и 7 из core-xhttp/apply.py к признаку
// профиля reality.support_x25519mlkem768. Без признака ClientHello должен быть
// таким же, как у ядра без правок (апстрим: X25519MLKEM768 вырезан, шифры в
// порядке пресета), — иначе включение кольцом ничего не стоит. С признаком —
// ML-KEM и ChaCha первым (подробно — reality_chacha_clienthello_test.go).
//
// Разбор признака из целого профиля — reality_priznak_json_test.go (пакет tls_test).
//
// Чтобы прогнать: после apply.py скопировать в common/tls/ дерева этот файл
// ВМЕСТЕ с reality_chacha_clienthello_test.go (общие помощники разбора) и
//   go test -tags with_utls ./common/tls/ -run 'TestRealityPriznak' -v
//
// Тест не ходит в сеть: ClientHello снимается с другого конца net.Pipe.

package tls

import (
	"context"
	"fmt"
	"net"
	"testing"

	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/logger"

	utls "github.com/metacubex/utls"
)

// chObrazecApstrim: ClientHello голого пресета uTLS, из которого X25519MLKEM768
// вырезан ровно так, как это делает апстрим sing-box в ClientHandshake.
func chObrazecApstrim(t *testing.T, id utls.ClientHelloID) chHello {
	t.Helper()
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	u := utls.UClient(a, &utls.Config{ServerName: "kelevra.test", NextProtos: []string{"h2", "http/1.1"}}, id)
	if err := u.BuildHandshakeState(); err != nil {
		t.Fatal(err)
	}
	for _, extension := range u.Extensions {
		if ce, ok := extension.(*utls.SupportedCurvesExtension); ok {
			ce.Curves = common.Filter(ce.Curves, func(curveID utls.CurveID) bool {
				return curveID != utls.X25519MLKEM768
			})
		}
		if ks, ok := extension.(*utls.KeyShareExtension); ok {
			ks.KeyShares = common.Filter(ks.KeyShares, func(share utls.KeyShare) bool {
				return share.Group != utls.X25519MLKEM768
			})
		}
	}
	if err := u.BuildHandshakeState(); err != nil {
		t.Fatal(err)
	}
	return chRazobrat(t, u.HandshakeState.Hello.Raw)
}

// chSravnit: всё, что видно в ClientHello, кроме случайных значений
// (GREASE, random, session id, сами ключи) и порядка расширений (chrome
// перемешивает его на каждое соединение).
func chSravnit(t *testing.T, imya string, h, o chHello) {
	t.Helper()
	if fmt.Sprintf("%04x", chBezGrease(h.shifry)) != fmt.Sprintf("%04x", chBezGrease(o.shifry)) {
		t.Fatalf("%s: шифры\n есть %04x\n ждём %04x", imya, chBezGrease(h.shifry), chBezGrease(o.shifry))
	}
	if (len(h.shifry) > 0 && chGrease(h.shifry[0])) != (len(o.shifry) > 0 && chGrease(o.shifry[0])) {
		t.Fatalf("%s: GREASE в шифрах: есть %04x, ждём %04x", imya, h.shifry, o.shifry)
	}
	if chSort(h.rasshir) != chSort(o.rasshir) {
		t.Fatalf("%s: расширения\n есть %v\n ждём %v", imya, chSort(h.rasshir), chSort(o.rasshir))
	}
	for _, para := range []struct {
		chto string
		a, b []uint16
	}{
		{"группы", h.gruppy, o.gruppy},
		{"key_share", h.klyuchi, o.klyuchi},
		{"подписи", h.podpisi, o.podpisi},
		{"версии", h.versii, o.versii},
	} {
		if fmt.Sprint(chBezGrease(para.a)) != fmt.Sprint(chBezGrease(para.b)) {
			t.Fatalf("%s: %s: есть %04x, ждём %04x", imya, para.chto, para.a, para.b)
		}
	}
	if fmt.Sprint(h.alpn) != fmt.Sprint(o.alpn) {
		t.Fatalf("%s: ALPN: есть %v, ждём %v", imya, h.alpn, o.alpn)
	}
}

var chPresety = []struct {
	fp string
	id utls.ClientHelloID
}{
	{"chrome", utls.HelloChrome_Auto},
	{"edge", utls.HelloEdge_Auto},
	{"firefox", utls.HelloFirefox_Auto},
	{"safari", utls.HelloSafari_Auto},
	{"ios", utls.HelloIOS_Auto},
}

// Без признака — как у ядра без правок 6 и 7: по каждому пресету сверка с
// апстримом поле за полем, X25519MLKEM768 нет нигде, ChaCha не первым у chrome.
func TestRealityPriznakNetKakApstrim(t *testing.T) {
	for _, p := range chPresety {
		for i := 0; i < 5; i++ {
			h := chReality(t, p.fp, false)
			if chIndex(h.gruppy, gruppaMLKEMch) >= 0 || chIndex(h.klyuchi, gruppaMLKEMch) >= 0 {
				t.Fatalf("%s без признака шлёт X25519MLKEM768: группы %v, ключи %v", p.fp, h.gruppy, h.klyuchi)
			}
			chSravnit(t, p.fp+" без признака", h, chObrazecApstrim(t, p.id))
		}
	}
	h := chReality(t, "chrome", false)
	if chBezGrease(h.shifry)[0] == 0x1303 {
		t.Fatalf("chrome без признака: ChaCha первым (%04x) — правка 7 сработала без признака", h.shifry)
	}
	for i := 0; i < 40; i++ {
		h := chReality(t, "randomized", false)
		if chIndex(h.gruppy, gruppaMLKEMch) >= 0 || chIndex(h.klyuchi, gruppaMLKEMch) >= 0 {
			t.Fatalf("randomized без признака шлёт X25519MLKEM768: группы %v, ключи %v", h.gruppy, h.klyuchi)
		}
	}
}

// С признаком: chrome — X25519MLKEM768 и ChaCha первым; firefox (ML-KEM в пресете
// нет) — как без признака.
func TestRealityPriznakDa(t *testing.T) {
	h := chReality(t, "chrome", true)
	if chIndex(h.gruppy, gruppaMLKEMch) < 0 || chIndex(h.klyuchi, gruppaMLKEMch) < 0 {
		t.Fatalf("chrome с признаком без X25519MLKEM768: группы %v, ключи %v", h.gruppy, h.klyuchi)
	}
	if chBezGrease(h.shifry)[0] != 0x1303 {
		t.Fatalf("chrome с признаком: первым не ChaCha: %04x", h.shifry)
	}
	f := chReality(t, "firefox", true)
	if chIndex(f.gruppy, gruppaMLKEMch) >= 0 {
		t.Skipf("firefox в этой версии uTLS шлёт ML-KEM: %v", f.gruppy)
	}
	chSravnit(t, "firefox с признаком", f, chObrazecApstrim(t, utls.HelloFirefox_Auto))
}

// Clone (ядро клонирует конфиг TLS на соединение) признак не теряет.
func TestRealityPriznakClone(t *testing.T) {
	for _, priznak := range []bool{false, true} {
		cfg, err := NewRealityClient(context.Background(), logger.NOP(), "kelevra.test", option.OutboundTLSOptions{
			Enabled:    true,
			ServerName: "kelevra.test",
			UTLS:       &option.OutboundUTLSOptions{Enabled: true, Fingerprint: "chrome"},
			Reality: &option.OutboundRealityOptions{
				Enabled:               true,
				PublicKey:             "wtxAyNcu29RnRbEPxqx5IieDo8ruCPRrerGsGshFdSA",
				ShortID:               "87ed",
				SupportX25519MLKEM768: priznak,
			},
		})
		if err != nil {
			t.Fatal(err)
		}
		if cfg.(*RealityClientConfig).kelevraMLKEM != priznak || cfg.Clone().(*RealityClientConfig).kelevraMLKEM != priznak {
			t.Fatalf("признак %v потерян (в конфиге %v, в копии %v)", priznak,
				cfg.(*RealityClientConfig).kelevraMLKEM, cfg.Clone().(*RealityClientConfig).kelevraMLKEM)
		}
	}
}
