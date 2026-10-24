//go:build with_utls

// Kelevra (core-xhttp/apply.py, правка 7): ChaCha20-Poly1305 первым в
// ClientHello Reality у отпечатков семейства Chromium, когда в ClientHello
// есть X25519MLKEM768. Файл копируется в common/tls/ дерева sing-box.

package tls

import (
	utls "github.com/metacubex/utls"
)

// kelevraChromium: отпечатки, у которых настоящий TLS-стек — BoringSSL.
// BoringSSL ставит ChaCha20-Poly1305 первым (и в TLS 1.3, и в TLS 1.2), когда
// у процессора нет ускорения AES (EVP_has_aes_hardware() == false,
// ssl/ssl_cipher.cc). Значит, такой порядок у Chrome встречается в природе.
func kelevraChromium(id utls.ClientHelloID) bool {
	switch id.Client {
	case "Chrome", "Edge", "360Browser", "QQBrowser":
		return true
	}
	return false
}

// kelevraChaChaFirst переставляет ChaCha20-Poly1305 перед AES-GCM:
//
//	TLS 1.3: AES128, AES256, CHACHA -> CHACHA, AES128, AES256
//	TLS 1.2: ECDSA/RSA-AES-GCM ..., ECDSA/RSA-CHACHA -> ECDSA/RSA-CHACHA, ECDSA/RSA-AES-GCM ...
//
// Как BoringSSL без ускорения AES. Всё остальное (GREASE, CBC, голый RSA,
// взаимный порядок AES) стоит на месте; набор шифров не меняется, только порядок.
func kelevraChaChaFirst(in []uint16) []uint16 {
	aes13 := map[uint16]bool{utls.TLS_AES_128_GCM_SHA256: true, utls.TLS_AES_256_GCM_SHA384: true}
	aes12 := map[uint16]bool{
		utls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256: true, utls.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256: true,
		utls.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384: true, utls.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384: true,
	}
	est := func(nabor []uint16) []uint16 {
		var r []uint16
		for _, s := range nabor {
			for _, c := range in {
				if c == s {
					r = append(r, s)
					break
				}
			}
		}
		return r
	}
	chacha13 := est([]uint16{utls.TLS_CHACHA20_POLY1305_SHA256})
	// порядок BoringSSL для TLS 1.2: сначала ECDSA, потом RSA
	chacha12 := est([]uint16{utls.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256, utls.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256})
	perenos := map[uint16]bool{}
	for _, c := range chacha13 {
		perenos[c] = true
	}
	for _, c := range chacha12 {
		perenos[c] = true
	}
	out := make([]uint16, 0, len(in))
	gotovo13, gotovo12 := false, false
	for _, c := range in {
		if perenos[c] {
			continue
		}
		if aes13[c] && !gotovo13 {
			out = append(out, chacha13...)
			gotovo13 = true
		}
		if aes12[c] && !gotovo12 {
			out = append(out, chacha12...)
			gotovo12 = true
		}
		out = append(out, c)
	}
	// AES этого поколения в списке нет: ChaCha остаётся в конце, как и был
	if !gotovo13 {
		out = append(out, chacha13...)
	}
	if !gotovo12 {
		out = append(out, chacha12...)
	}
	return out
}

// kelevraRealityChaCha вызывается после BuildHandshakeState и решения правки 6
// о X25519MLKEM768. Возвращает true, если порядок шифров изменён — тогда
// вызывающий заново собирает ClientHello (BuildHandshakeState).
func kelevraRealityChaCha(uConn *utls.UConn, id utls.ClientHelloID, mlkem bool) bool {
	if !mlkem || !kelevraChromium(id) || uConn.HandshakeState.Hello == nil {
		return false
	}
	uConn.HandshakeState.Hello.CipherSuites = kelevraChaChaFirst(uConn.HandshakeState.Hello.CipherSuites)
	return true
}
