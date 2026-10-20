//go:build with_utls

// Артефакт проверки, НЕ часть сборки (как rule_item_rule_set_close_repro_test.go
// рядом): своего go.mod нет, собирается только внутри дерева sing-box.
//
// Зачем: доказательство правки 6 из core-xhttp/apply.py — Reality ClientHello
// несёт X25519MLKEM768, без которого Xray >= 26.9.8 (XTLS/REALITY 8cdf7bf9)
// отвергает клиента (sing-box#4520).
//
// Чтобы прогнать: скопировать файл в common/tls/ выкачанного дерева sing-box и
//   go test -tags with_utls ./common/tls/ -run TestRealityMLKEM -v
// До apply.py: chrome — FAIL (ключа X25519MLKEM768 нет). После — PASS.
//
// Тест не ходит в сеть: ClientHello снимается с другого конца net.Pipe.
//
// Прогон 20.10.2026 на v1.14.1 (1ac1a339): до apply.py FAIL (chrome без
// X25519MLKEM768), после — PASS. Петлевой прогон с настоящим Xray там же:
// chrome с правкой проходит и 26.3.27, и 26.10.17; без правки — только 26.3.27.

package tls

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"testing"
	"time"

	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
)

const gruppaMLKEM = 4588 // X25519MLKEM768

// snyatClientHello запускает рукопожатие Reality с отпечатком fp и возвращает
// группы из supported_groups и key_share первого ClientHello.
func snyatClientHello(t *testing.T, fp string) (gruppy, klyuchi []uint16) {
	t.Helper()
	cfg, err := NewRealityClient(context.Background(), logger.NOP(), "kelevra.test", option.OutboundTLSOptions{
		Enabled:    true,
		ServerName: "kelevra.test",
		UTLS:       &option.OutboundUTLSOptions{Enabled: true, Fingerprint: fp},
		Reality: &option.OutboundRealityOptions{
			Enabled:   true,
			PublicKey: "wtxAyNcu29RnRbEPxqx5IieDo8ruCPRrerGsGshFdSA",
			ShortID:   "87ed",
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
	return razobratClientHello(t, telo)
}

func razobratClientHello(t *testing.T, b []byte) (gruppy, klyuchi []uint16) {
	t.Helper()
	if len(b) < 4 || b[0] != 1 {
		t.Fatalf("не ClientHello")
	}
	p := 4 + 2 + 32
	p += 1 + int(b[p])
	p += 2 + int(binary.BigEndian.Uint16(b[p:]))
	p += 1 + int(b[p])
	konec := p + 2 + int(binary.BigEndian.Uint16(b[p:]))
	p += 2
	for p+4 <= konec {
		tip := binary.BigEndian.Uint16(b[p:])
		dl := int(binary.BigEndian.Uint16(b[p+2:]))
		d := b[p+4 : p+4+dl]
		switch tip {
		case 10: // supported_groups
			for i := 2; i+2 <= len(d); i += 2 {
				gruppy = append(gruppy, binary.BigEndian.Uint16(d[i:]))
			}
		case 51: // key_share
			for i := 2; i+4 <= len(d); {
				klyuchi = append(klyuchi, binary.BigEndian.Uint16(d[i:]))
				i += 4 + int(binary.BigEndian.Uint16(d[i+2:]))
			}
		}
		p += 4 + dl
	}
	return
}

func est(spisok []uint16, g uint16) bool {
	for _, x := range spisok {
		if x == g {
			return true
		}
	}
	return false
}

// chrome: X25519MLKEM768 и в группах, и в ключах, и ключ стоит раньше X25519.
func TestRealityMLKEMChrome(t *testing.T) {
	gruppy, klyuchi := snyatClientHello(t, "chrome")
	if !est(gruppy, gruppaMLKEM) || !est(klyuchi, gruppaMLKEM) {
		t.Fatalf("chrome без X25519MLKEM768: группы %v, ключи %v — Xray >= 26.9.8 не пустит", gruppy, klyuchi)
	}
	for _, k := range klyuchi {
		if k == 29 {
			t.Fatalf("X25519 раньше X25519MLKEM768 в key_share: %v", klyuchi)
		}
		if k == gruppaMLKEM {
			break
		}
	}
}

// randomized: группа и ключ X25519MLKEM768 — либо оба, либо ни одного.
// Группа без ключа даёт HelloRetryRequest, ключ без группы запрещён RFC 8446.
func TestRealityMLKEMRandomizedSoglasovan(t *testing.T) {
	for i := 0; i < 40; i++ {
		gruppy, klyuchi := snyatClientHello(t, "randomized")
		if est(gruppy, gruppaMLKEM) != est(klyuchi, gruppaMLKEM) {
			t.Fatalf("прогон %d: X25519MLKEM768 в группах=%v, в ключах=%v", i, est(gruppy, gruppaMLKEM), est(klyuchi, gruppaMLKEM))
		}
	}
}
