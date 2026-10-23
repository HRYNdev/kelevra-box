//go:build with_utls

// Артефакт проверки, НЕ часть сборки (как reality_mlkem_clienthello_test.go
// рядом): своего go.mod нет, собирается только внутри дерева sing-box.
//
// Зачем: доказательство правки 7 из core-xhttp/apply.py — у chrome с
// X25519MLKEM768 (признак support_x25519mlkem768 в профиле) ChaCha20-Poly1305 идёт первым, а всё остальное в ClientHello
// (набор шифров, группы, ключи, расширения, подписи, ALPN) — как у пресета chrome.
//
// Чтобы прогнать: после apply.py скопировать файл в common/tls/ дерева и
//   go test -tags with_utls ./common/tls/ -run 'TestRealityChaCha|TestKelevraChaCha' -v
// До правки 7 TestRealityChaChaChrome — FAIL (первым AES), после — PASS.
//
// Тест не ходит в сеть: ClientHello снимается с другого конца net.Pipe.

package tls

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"sort"
	"testing"
	"time"

	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"

	utls "github.com/metacubex/utls"
)

type chHello struct {
	shifry   []uint16
	rasshir  []uint16
	gruppy   []uint16
	klyuchi  []uint16
	podpisi  []uint16
	alpn     []string
	versii   []uint16
}

func chGrease(v uint16) bool { return v&0x0f0f == 0x0a0a && v>>8 == v&0xff }

func chBezGrease(v []uint16) []uint16 {
	var r []uint16
	for _, x := range v {
		if !chGrease(x) {
			r = append(r, x)
		}
	}
	return r
}

func chRazobrat(t *testing.T, b []byte) chHello {
	t.Helper()
	if len(b) < 4 || b[0] != 1 {
		t.Fatalf("не ClientHello")
	}
	var h chHello
	p := 4 + 2 + 32
	p += 1 + int(b[p])
	n := int(binary.BigEndian.Uint16(b[p:]))
	for i := 0; i < n; i += 2 {
		h.shifry = append(h.shifry, binary.BigEndian.Uint16(b[p+2+i:]))
	}
	p += 2 + n
	p += 1 + int(b[p])
	konec := p + 2 + int(binary.BigEndian.Uint16(b[p:]))
	p += 2
	for p+4 <= konec {
		tip := binary.BigEndian.Uint16(b[p:])
		dl := int(binary.BigEndian.Uint16(b[p+2:]))
		d := b[p+4 : p+4+dl]
		h.rasshir = append(h.rasshir, tip)
		switch tip {
		case 10:
			for i := 2; i+2 <= len(d); i += 2 {
				h.gruppy = append(h.gruppy, binary.BigEndian.Uint16(d[i:]))
			}
		case 13:
			for i := 2; i+2 <= len(d); i += 2 {
				h.podpisi = append(h.podpisi, binary.BigEndian.Uint16(d[i:]))
			}
		case 16:
			for i := 2; i < len(d); {
				l := int(d[i])
				h.alpn = append(h.alpn, string(d[i+1:i+1+l]))
				i += 1 + l
			}
		case 43:
			for i := 1; i+2 <= len(d); i += 2 {
				h.versii = append(h.versii, binary.BigEndian.Uint16(d[i:]))
			}
		case 51:
			for i := 2; i+4 <= len(d); {
				h.klyuchi = append(h.klyuchi, binary.BigEndian.Uint16(d[i:]))
				i += 4 + int(binary.BigEndian.Uint16(d[i+2:]))
			}
		}
		p += 4 + dl
	}
	return h
}

// chReality снимает первый ClientHello рукопожатия Reality с отпечатком fp;
// priznak — reality.support_x25519mlkem768 в профиле (правка 6).
func chReality(t *testing.T, fp string, priznak bool) chHello {
	t.Helper()
	cfg, err := NewRealityClient(context.Background(), logger.NOP(), "kelevra.test", option.OutboundTLSOptions{
		Enabled:    true,
		ServerName: "kelevra.test",
		ALPN:       []string{"h2", "http/1.1"},
		UTLS:       &option.OutboundUTLSOptions{Enabled: true, Fingerprint: fp},
		Reality: &option.OutboundRealityOptions{
			Enabled:               true,
			PublicKey:             "wtxAyNcu29RnRbEPxqx5IieDo8ruCPRrerGsGshFdSA",
			ShortID:               "87ed",
			SupportX25519MLKEM768: priznak,
		},
	})
	if err != nil {
		t.Fatalf("NewRealityClient(%s): %v", fp, err)
	}
	klient, server := net.Pipe()
	defer server.Close()
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, _ = cfg.(*RealityClientConfig).ClientHandshake(ctx, klient)
		klient.Close()
	}()
	_ = server.SetDeadline(time.Now().Add(5 * time.Second))
	hdr := make([]byte, 5)
	if _, err := io.ReadFull(server, hdr); err != nil {
		t.Fatalf("заголовок записи: %v", err)
	}
	telo := make([]byte, binary.BigEndian.Uint16(hdr[3:5]))
	if _, err := io.ReadFull(server, telo); err != nil {
		t.Fatalf("тело ClientHello: %v", err)
	}
	return chRazobrat(t, telo)
}

// chObrazec: ClientHello голого пресета uTLS (без Reality и без наших правок).
func chObrazec(t *testing.T, id utls.ClientHelloID) chHello {
	t.Helper()
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	u := utls.UClient(a, &utls.Config{ServerName: "kelevra.test", NextProtos: []string{"h2", "http/1.1"}}, id)
	if err := u.BuildHandshakeState(); err != nil {
		t.Fatal(err)
	}
	return chRazobrat(t, u.HandshakeState.Hello.Raw)
}

func chSort(v []uint16) string {
	c := append([]uint16(nil), chBezGrease(v)...)
	sort.Slice(c, func(i, j int) bool { return c[i] < c[j] })
	return fmt.Sprint(c)
}

func chIndex(v []uint16, x uint16) int {
	for i, y := range v {
		if y == x {
			return i
		}
	}
	return -1
}

// chrome + ML-KEM (с признаком): ChaCha первым в TLS 1.3 и 1.2, как BoringSSL без AES;
// остальное — как у пресета chrome.
func TestRealityChaChaChrome(t *testing.T) {
	h := chReality(t, "chrome", true)
	o := chObrazec(t, utls.HelloChrome_Auto)

	if chIndex(h.gruppy, gruppaMLKEMch) < 0 || chIndex(h.klyuchi, gruppaMLKEMch) < 0 {
		t.Fatalf("нет X25519MLKEM768 (правка 6): группы %v, ключи %v", h.gruppy, h.klyuchi)
	}
	if k, x := chIndex(h.klyuchi, gruppaMLKEMch), chIndex(h.klyuchi, 29); x >= 0 && x < k {
		t.Fatalf("X25519 раньше X25519MLKEM768 в key_share: %v", h.klyuchi)
	}

	zhdu := []uint16{
		0x1303, 0x1301, 0x1302, // TLS 1.3: CHACHA, AES128, AES256
		0xcca9, 0xcca8, // TLS 1.2: ECDSA-CHACHA, RSA-CHACHA
		0xc02b, 0xc02f, 0xc02c, 0xc030,
		0xc013, 0xc014, 0x009c, 0x009d, 0x002f, 0x0035,
	}
	if fmt.Sprintf("%04x", chBezGrease(h.shifry)) != fmt.Sprintf("%04x", zhdu) {
		t.Fatalf("порядок шифров:\n есть %04x\n ждём %04x", chBezGrease(h.shifry), zhdu)
	}
	if !chGrease(h.shifry[0]) || !chGrease(o.shifry[0]) {
		t.Fatalf("GREASE в шифрах должен остаться первым: %04x", h.shifry)
	}
	if chSort(h.shifry) != chSort(o.shifry) {
		t.Fatalf("набор шифров изменился:\n есть %v\n у chrome %v", chSort(h.shifry), chSort(o.shifry))
	}
	if fmt.Sprint(chBezGrease(o.shifry)[:3]) == fmt.Sprint(chBezGrease(h.shifry)[:3]) {
		t.Fatalf("образец chrome уже с ChaCha первым — тест ничего не доказывает: %04x", o.shifry)
	}
	if chSort(h.rasshir) != chSort(o.rasshir) {
		t.Fatalf("набор расширений:\n есть %v\n у chrome %v", chSort(h.rasshir), chSort(o.rasshir))
	}
	if fmt.Sprint(chBezGrease(h.gruppy)) != fmt.Sprint(chBezGrease(o.gruppy)) {
		t.Fatalf("группы: есть %v, у chrome %v", h.gruppy, o.gruppy)
	}
	if fmt.Sprint(chBezGrease(h.klyuchi)) != fmt.Sprint(chBezGrease(o.klyuchi)) {
		t.Fatalf("key_share: есть %v, у chrome %v", h.klyuchi, o.klyuchi)
	}
	if fmt.Sprint(h.podpisi) != fmt.Sprint(o.podpisi) {
		t.Fatalf("подписи: есть %04x, у chrome %04x", h.podpisi, o.podpisi)
	}
	if fmt.Sprint(chBezGrease(h.versii)) != fmt.Sprint(chBezGrease(o.versii)) {
		t.Fatalf("версии: есть %04x, у chrome %04x", h.versii, o.versii)
	}
	if fmt.Sprint(h.alpn) != fmt.Sprint(o.alpn) {
		t.Fatalf("ALPN: есть %v, у chrome %v", h.alpn, o.alpn)
	}
}

const gruppaMLKEMch = 4588 // X25519MLKEM768

// Без ML-KEM (firefox его не шлёт) порядок шифров не трогаем: как у пресета.
func TestRealityChaChaFirefoxNeTrogaem(t *testing.T) {
	h := chReality(t, "firefox", true)
	o := chObrazec(t, utls.HelloFirefox_Auto)
	if chIndex(h.gruppy, gruppaMLKEMch) >= 0 {
		t.Skipf("firefox в этой версии uTLS шлёт ML-KEM: %v", h.gruppy)
	}
	if fmt.Sprintf("%04x", h.shifry) != fmt.Sprintf("%04x", o.shifry) {
		t.Fatalf("firefox: порядок шифров изменён:\n есть %04x\n у firefox %04x", h.shifry, o.shifry)
	}
}

// randomized: не семейство Chromium — порядок не трогаем никогда.
func TestKelevraChaChaTolkoChromium(t *testing.T) {
	for _, id := range []utls.ClientHelloID{utls.HelloFirefox_Auto, utls.HelloSafari_Auto, utls.HelloIOS_Auto, utls.HelloAndroid_11_OkHttp, utls.HelloRandomized} {
		if kelevraChromium(id) {
			t.Fatalf("%s не Chromium, а правка 7 его тронет", id.Client)
		}
	}
	for _, id := range []utls.ClientHelloID{utls.HelloChrome_Auto, utls.HelloChrome_120, utls.HelloEdge_Auto} {
		if !kelevraChromium(id) {
			t.Fatalf("%s — Chromium, а правка 7 его пропустит", id.Client)
		}
	}
}

// Перестановка: только порядок, набор тот же; без AES ChaCha остаётся на месте.
func TestKelevraChaChaFirstTablica(t *testing.T) {
	sluchai := []struct{ vhod, zhdu []uint16 }{
		{[]uint16{0x0a0a, 0x1301, 0x1302, 0x1303, 0xc02b, 0xc02f, 0xc02c, 0xc030, 0xcca9, 0xcca8, 0xc013},
			[]uint16{0x0a0a, 0x1303, 0x1301, 0x1302, 0xcca9, 0xcca8, 0xc02b, 0xc02f, 0xc02c, 0xc030, 0xc013}},
		{[]uint16{0x1301, 0x1303, 0x1302}, []uint16{0x1303, 0x1301, 0x1302}},
		{[]uint16{0x1303, 0x1301}, []uint16{0x1303, 0x1301}},
		{[]uint16{0x1301, 0x1302}, []uint16{0x1301, 0x1302}},
		{[]uint16{0xc013, 0xcca8}, []uint16{0xc013, 0xcca8}},
	}
	for i, s := range sluchai {
		est := kelevraChaChaFirst(s.vhod)
		if fmt.Sprintf("%04x", est) != fmt.Sprintf("%04x", s.zhdu) {
			t.Fatalf("случай %d: есть %04x, ждём %04x", i, est, s.zhdu)
		}
	}
}
