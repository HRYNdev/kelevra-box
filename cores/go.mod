module kelevracores

// olcrtc требует 1.26.3, sing-box — 1.24.7; берём максимум
go 1.26.3

require (
	github.com/openlibrecommunity/olcrtc v0.0.0
	// та же версия, что ставит `make lib_install` в sing-box (gomobile/gobind v0.1.13)
	github.com/sagernet/gomobile v0.1.13
	github.com/sagernet/sing-box v0.0.0
)

// Оба ядра берутся из локальных чекаутов, а не из прокси: sing-box патчится нашим
// транспортом xhttp (core-xhttp/apply.py), у olcrtc нет тегов.
// Раскладка в CI: $GITHUB_WORKSPACE/{sing-box-for-android,sing-box,olcrtc};
// workflow всё равно переписывает эти replace абсолютными путями (go mod edit).
replace github.com/sagernet/sing-box => ../../sing-box

replace github.com/openlibrecommunity/olcrtc => ../../olcrtc
