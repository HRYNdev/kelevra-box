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
	_ "github.com/openlibrecommunity/olcrtc/mobile"
	_ "github.com/sagernet/sing-box/experimental/libbox"
)
