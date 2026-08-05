// Package kelevracores существует только затем, чтобы затащить оба нативных ядра
// в ОДИН Go-модуль: тогда `gomobile bind` кладёт их в один .aar с единственным
// Go-рантаймом (один go.Seq, один libgojni.so).
//
// Если биндить ядра двумя отдельными .aar, в приложении оказываются дублирующиеся
// классы go.* и два Go-рантайма в одном процессе — они конфликтуют и валят процесс.
//
// gomobile вызывается со всеми путями пакетов явно (см. .github/workflows/build-olcrtc.yml);
// эти пустые импорты нужны только чтобы зависимости остались в графе модуля.
package kelevracores

import (
	// gobind ищет github.com/sagernet/gomobile/bind в графе ГЛАВНОГО модуля;
	// без этого импорта — "unable to import bind: no Go package". Так же сделано в апстриме
	// (cmd/internal/build_libbox/main.go).
	_ "github.com/sagernet/gomobile"

	_ "github.com/openlibrecommunity/olcrtc/mobile"
	_ "github.com/sagernet/sing-box/experimental/libbox"
)
