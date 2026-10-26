package option

// Kelevra (core-xhttp/apply.py, правка 8): опция reverse у VLESS-аутбаунда —
// домашняя сторона (bridge) VLESS reverse к порталу Xray. Сессии портала уходят
// в роутер как со входа с тегом Tag.
type VLESSReverseOptions struct {
	Tag string `json:"tag"`
	// Файл состояния для службы узла дома; пусто — не писать.
	StatePath string `json:"state_path,omitempty"`
}
