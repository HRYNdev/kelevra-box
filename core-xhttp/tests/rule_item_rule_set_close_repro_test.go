// Артефакт проверки, НЕ часть сборки. Живёт вне модуля cores/go.mod и вне
// корня репозитория, поэтому обычный `go build`/`go vet`/gradle-сборка kelevra-box
// его не задевает: тут нет своего go.mod, а github.com/sagernet/sing-box —
// не наш модуль, этот файл собирается только внутри дерева sing-box.
//
// Зачем: доказательство фикса из core-xhttp/apply.py (шаг «5. паника ядра при
// пересборке» — снятие clear(r.setList) в route/rule/rule_item_rule_set.go).
// Чтобы прогнать: скопировать файл в route/rule/ уже выкачанного дерева
// sing-box v1.14.0-beta.4 (до применения apply.py — тест красный, после —
// зелёный) и запустить:
//   go test ./route/rule/ -run TestRuleSetItemCloseKeepsInFlightSnapshotUsable -v
//
// Диагноз: RuleSetItem.Close() в апстриме звал clear(r.setList) — зануляет
// элементы ОБЩЕГО backing-массива слайса на месте. Другая горутина в этот
// момент внутри matchWithOuterGroups/Match делает `for _, ruleSet := range
// r.setList` — range снимает заголовок слайса один раз в начале цикла и потом
// просто идёт по общему массиву. После clear() необойдённые ею элементы стали
// nil-интерфейсами, и вызов метода на них падает с runtime error: invalid
// memory address or nil pointer dereference — это и есть боевой краш с
// телефона при BoxService.restartCore.
//
// Прогон 21.08.2026 на свежем чекауте v1.14.0-beta.4:
//   (a) до apply.py: FAIL, "panic while reading a snapshot taken before Close
//       (this is the боевой краш): runtime error: invalid memory address or
//       nil pointer dereference"
//   (b) apply.py: строка `clear(r.setList)` пропала (git diff показывает -1
//       строку), `r.setList = nil` осталась
//   (c) после apply.py: PASS; go test ./route/rule/... целиком PASS

package rule

import (
	"context"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing/common/x/list"

	"go4.org/netipx"
)

// stubRuleSet is the minimal adapter.RuleSet needed to exercise RuleSetItem
// without pulling in a real rule-set provider.
type stubRuleSet struct{ name string }

func (s *stubRuleSet) Name() string { return s.name }

func (s *stubRuleSet) StartContext(context.Context, *adapter.HTTPStartContext) error { return nil }

func (s *stubRuleSet) Metadata() adapter.RuleSetMetadata { return adapter.RuleSetMetadata{} }

func (s *stubRuleSet) ExtractIPSet() []*netipx.IPSet { return nil }

func (s *stubRuleSet) IncRef() {}

func (s *stubRuleSet) DecRef() {}

func (s *stubRuleSet) Cleanup() {}

func (s *stubRuleSet) RegisterCallback(adapter.RuleSetUpdateCallback) *list.Element[adapter.RuleSetUpdateCallback] {
	return nil
}

func (s *stubRuleSet) UnregisterCallback(*list.Element[adapter.RuleSetUpdateCallback]) {}

func (s *stubRuleSet) Close() error { return nil }

func (s *stubRuleSet) Match(*adapter.InboundContext) bool { return true }

func (s *stubRuleSet) String() string { return s.name }

// TestRuleSetItemCloseKeepsInFlightSnapshotUsable проверяет боевой краш
// "invalid memory address or nil pointer dereference" из BoxService.restartCore.
//
// `for _, ruleSet := range r.setList` в matchWithOuterGroups/Match читает заголовок
// слайса (указатель/длину) один раз в начале цикла и потом просто идёт по общему
// backing-массиву. Здесь это эмулируется без горутин и без гонки: снимок слайса
// снимается ДО Close(), точно как это делает `range`, а читается ПОСЛЕ Close() —
// то есть ровно то, что увидела бы уже стартовавшая горутина-читатель.
//
// Если Close() зануляет элементы общего backing-массива на месте (clear(r.setList)),
// то в снимке, снятом до Close(), после Close() оказываются nil-интерфейсы, и вызов
// любого метода на них паникует ровно так, как падало на телефоне при пересборке
// ядра. Патч должен оставлять `r.setList = nil` без `clear`, тогда старый backing-
// массив остаётся валиден для уже взятых снимков.
func TestRuleSetItemCloseKeepsInFlightSnapshotUsable(t *testing.T) {
	item := &RuleSetItem{
		setList: []adapter.RuleSet{&stubRuleSet{name: "a"}, &stubRuleSet{name: "b"}},
	}

	// То же самое, что видит `range r.setList` внутри matchWithOuterGroups в
	// момент входа в цикл: заголовок слайса захвачен, backing-массив общий.
	snapshot := item.setList

	if err := item.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	// На боевом крашилось ИМЕННО здесь: вызов метода на nil-интерфейсе даёт
	// "invalid memory address or nil pointer dereference". Ловим панику явно,
	// чтобы прогон был детерминированным (без реального падения тестового
	// бинаря) и чтобы напечатать её текст как доказательство.
	func() {
		defer func() {
			if rec := recover(); rec != nil {
				t.Fatalf("panic while reading a snapshot taken before Close (this is the боевой краш): %v", rec)
			}
		}()
		for _, ruleSet := range snapshot {
			_ = ruleSet.Match(&adapter.InboundContext{})
		}
	}()
}
