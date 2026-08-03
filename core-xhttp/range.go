package option

import (
	"fmt"
	"math/rand"
	"strconv"
	"strings"

	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/json"
)

// Range — диапазон вида "100-1000" либо одиночное число.
// В апстриме такого типа нет (он есть только в форке библиотеки опций),
// поэтому держим свою реализацию: она нужна транспорту xhttp.
type Range[T int | int64] struct {
	From T
	To   T
}

func (r Range[T]) MarshalJSON() ([]byte, error) {
	if r.From == r.To {
		return json.Marshal(r.From)
	}
	return json.Marshal(fmt.Sprintf("%v-%v", r.From, r.To))
}

func (r *Range[T]) UnmarshalJSON(content []byte) error {
	var value any
	if err := json.Unmarshal(content, &value); err != nil {
		return err
	}
	switch v := value.(type) {
	case float64:
		r.From = T(v)
		r.To = T(v)
		return nil
	case string:
		parts := strings.SplitN(strings.TrimSpace(v), "-", 2)
		from, err := strconv.ParseInt(strings.TrimSpace(parts[0]), 10, 64)
		if err != nil {
			return E.Cause(err, "неверный диапазон: ", v)
		}
		to := from
		if len(parts) == 2 {
			to, err = strconv.ParseInt(strings.TrimSpace(parts[1]), 10, 64)
			if err != nil {
				return E.Cause(err, "неверный диапазон: ", v)
			}
		}
		if to < from {
			from, to = to, from
		}
		r.From = T(from)
		r.To = T(to)
		return nil
	default:
		return E.New("неверный тип диапазона: ", string(content))
	}
}

// Rand возвращает случайное значение внутри диапазона: так xhttp размывает
// размеры и паузы, чтобы трафик не был одинаковым.
func (r Range[T]) Rand() T {
	if r.To <= r.From {
		return r.From
	}
	return r.From + T(rand.Int63n(int64(r.To-r.From)+1))
}
