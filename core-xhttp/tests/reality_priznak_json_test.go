//go:build with_utls

// Артефакт проверки, НЕ часть сборки (как reality_priznak_clienthello_test.go
// рядом): своего go.mod нет, собирается только внутри дерева sing-box.
//
// Зачем: признак правки 6 (reality.support_x25519mlkem768) читается из целого
// профиля тем же разбором, что у libbox (experimental/libbox/config.go), а
// незнакомое поле в том же месте этот разбор отвергает. Второе — причина, по
// которой сервер шлёт признак только ядрам, которые его знают: ядро без правки
// на таком профиле не стартует.
//
// Чтобы прогнать: после apply.py скопировать файл в common/tls/ дерева и
//   go test -tags with_utls ./common/tls/ -run 'TestRealityPriznakProfil' -v

package tls_test

import (
	"context"
	"strings"
	"testing"

	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
)

func profilSReality(reality string) string {
	return `{"outbounds":[{"type":"vless","tag":"uzel","server":"192.0.2.1","server_port":443,` +
		`"uuid":"bf000d23-0752-40b4-affe-68f7707a9661","flow":"xtls-rprx-vision",` +
		`"tls":{"enabled":true,"server_name":"kelevra.test","utls":{"enabled":true,"fingerprint":"chrome"},` +
		`"reality":` + reality + `}}]}`
}

func razobratProfil(t *testing.T, profil string) (option.Options, error) {
	t.Helper()
	return json.UnmarshalExtendedContext[option.Options](include.Context(context.Background()), []byte(profil))
}

func realityIz(t *testing.T, o option.Options) *option.OutboundRealityOptions {
	t.Helper()
	if len(o.Outbounds) != 1 {
		t.Fatalf("выходов %d", len(o.Outbounds))
	}
	v, ok := o.Outbounds[0].Options.(*option.VLESSOutboundOptions)
	if !ok || v.TLS == nil || v.TLS.Reality == nil {
		t.Fatalf("не VLESS с Reality: %T", o.Outbounds[0].Options)
	}
	return v.TLS.Reality
}

func TestRealityPriznakProfil(t *testing.T) {
	baza := `{"enabled":true,"public_key":"wtxAyNcu29RnRbEPxqx5IieDo8ruCPRrerGsGshFdSA","short_id":"87ed"`

	o, err := razobratProfil(t, profilSReality(baza+`,"support_x25519mlkem768":true}`))
	if err != nil {
		t.Fatalf("профиль с признаком не разобран: %v", err)
	}
	if !realityIz(t, o).SupportX25519MLKEM768 {
		t.Fatalf("признак не дошёл до опций Reality")
	}

	o, err = razobratProfil(t, profilSReality(baza+`}`))
	if err != nil {
		t.Fatalf("профиль без признака не разобран: %v", err)
	}
	if realityIz(t, o).SupportX25519MLKEM768 {
		t.Fatalf("без поля признак должен быть false")
	}

	_, err = razobratProfil(t, profilSReality(baza+`,"nevedomoe_pole":true}`))
	if err == nil || !strings.Contains(err.Error(), "nevedomoe_pole") {
		t.Fatalf("незнакомое поле в reality принято (%v) — тогда вывод «старое ядро на признаке падает» неверен", err)
	}
}
