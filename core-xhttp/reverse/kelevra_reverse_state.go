package vless

// Kelevra (core-xhttp/apply.py, правка 8): файл состояния моста для службы узла
// дома (`"reverse": {"state_path": "..."}`). Пишется атомарно (tmp + rename) при
// каждом изменении и не реже раза в 10 с. Нет поля — файла нет.

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
	"time"
)

var reverseStateInterval = 10 * time.Second

type reverseStateFile struct {
	Tag       string  `json:"tag"`
	Server    string  `json:"server"`
	Connected bool    `json:"connected"`
	Since     string  `json:"since"`
	LastPulse *string `json:"last_pulse"`
	Sessions  int     `json:"sessions"`
	Conns     int     `json:"conns"`
}

type reverseState struct {
	path        string
	outboundTag string
	server      string
	changed     chan struct{}
	access      sync.Mutex
	last        reverseStateFile
	written     bool
	since       time.Time
}

func newReverseState(path string, outboundTag string, server string) *reverseState {
	return &reverseState{
		path:        path,
		outboundTag: outboundTag,
		server:      server,
		changed:     make(chan struct{}, 1),
		since:       time.Now(),
	}
}

// notifyState — что-то поменялось; писать будет stateLoop, не вызывающий.
func (b *reverseBridge) notifyState() {
	if b.state == nil {
		return
	}
	select {
	case b.state.changed <- struct{}{}:
	default:
	}
}

func (b *reverseBridge) stateLoop() {
	ticker := time.NewTicker(reverseStateInterval)
	defer ticker.Stop()
	force := false
	for {
		// Не записалось (на Windows rename падает, пока файл открыт читателем) —
		// повтор скоро, а не через 10 с.
		var retry <-chan time.Time
		if !b.writeState(force) {
			retry = time.After(200 * time.Millisecond)
		}
		force = false
		select {
		case <-b.ctx.Done():
			return
		case <-b.state.changed:
		case <-retry:
			force = true
		case <-ticker.C:
			force = true
		}
	}
}

func (b *reverseBridge) snapshotState() (connected bool, sessions int, conns int) {
	b.access.Lock()
	workers := append([]*reverseWorker(nil), b.workers...)
	b.access.Unlock()
	for _, w := range workers {
		if w.isClosed() || !w.connected.Load() {
			continue
		}
		conns++
		w.access.Lock()
		sessions += len(w.sessions)
		w.access.Unlock()
	}
	return conns > 0, sessions, conns
}

// writeState пишет файл, если состояние поменялось, или всегда при force;
// false — запись не удалась.
func (b *reverseBridge) writeState(force bool) bool {
	s := b.state
	connected, sessions, conns := b.snapshotState()
	if b.ctx.Err() != nil {
		connected, sessions, conns = false, 0, 0
	}
	s.access.Lock()
	defer s.access.Unlock()
	since := s.since
	if s.written && s.last.Connected != connected {
		since = time.Now()
	}
	file := reverseStateFile{
		Tag:       s.outboundTag,
		Server:    s.server,
		Connected: connected,
		Since:     since.Format(time.RFC3339),
		Sessions:  sessions,
		Conns:     conns,
	}
	if pulse := b.lastPulse.Load(); pulse != 0 {
		text := time.Unix(0, pulse).Format(time.RFC3339)
		file.LastPulse = &text
	}
	if s.written && !force && file.Connected == s.last.Connected && file.Sessions == s.last.Sessions &&
		file.Conns == s.last.Conns && equalStringPtr(file.LastPulse, s.last.LastPulse) {
		return true
	}
	if err := writeFileAtomic(s.path, file); err != nil {
		b.logger.WarnContext(b.ctx, "reverse[", b.tag, "]: write state: ", err)
		return false
	}
	s.last = file
	s.since = since
	s.written = true
	return true
}

func equalStringPtr(a, b *string) bool {
	if a == nil || b == nil {
		return a == b
	}
	return *a == *b
}

func writeFileAtomic(path string, value any) error {
	content, err := json.Marshal(value)
	if err != nil {
		return err
	}
	dir := filepath.Dir(path)
	if err = os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, "."+filepath.Base(path)+".*.tmp")
	if err != nil {
		return err
	}
	_, err = tmp.Write(append(content, '\n'))
	if closeErr := tmp.Close(); err == nil {
		err = closeErr
	}
	if err == nil {
		err = os.Chmod(tmp.Name(), 0o644)
	}
	if err == nil {
		err = os.Rename(tmp.Name(), path)
	}
	if err != nil {
		os.Remove(tmp.Name())
	}
	return err
}
