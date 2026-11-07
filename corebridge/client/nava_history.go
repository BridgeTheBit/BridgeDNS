package client

import (
	"encoding/json"
	"os"
	"sort"
	"sync"
	"time"
)

// A previous success is only a scan-order hint. Every path still has to pass
// this connection's MTU tests; cached MTUs or session credentials are never used.
type navaHistory struct {
	mu   sync.Mutex
	path string
	good map[string]int64
}

func (h *navaHistory) load(path string) {
	h.path = path
	h.good = make(map[string]int64)
	if path == "" {
		return
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return
	}
	var saved map[string]int64
	if json.Unmarshal(b, &saved) == nil && saved != nil {
		h.good = saved
	}
}
func (h *navaHistory) prioritize(candidates []Connection) ([]Connection, int) {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := append([]Connection(nil), candidates...)
	count := 0
	for _, c := range out {
		if h.good[c.Key] > 0 {
			count++
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return h.good[out[i].Key] > h.good[out[j].Key] })
	return out, count
}
func (h *navaHistory) remember(key string) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.good == nil {
		h.good = make(map[string]int64)
	}
	h.good[key] = time.Now().UnixNano()
	if h.path == "" {
		return nil
	}
	b, err := json.Marshal(h.good)
	if err != nil {
		return err
	}
	// Atomic replacement keeps the last complete file if Android stops the core
	// mid-write. App-private storage is created by TunnelService, not the core.
	tmp := h.path + ".tmp"
	if err = os.WriteFile(tmp, b, 0600); err != nil {
		return err
	}
	if err = os.Rename(tmp, h.path); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return nil
}
func (c *Client) navaScanWorkers(total int) int {
	n := c.cfg.NavaScanParallelism
	if n <= 0 {
		n = 4
	}
	return min(total, min(n, 64))
}
func (c *Client) navaRememberHealthy(conn Connection) {
	if err := c.nava.history.remember(conn.Key); err != nil {
		c.log.Warnf("Nava could not save resolver scan priority")
	}
}
