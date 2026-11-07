package client

import (
	Enums "masterdnsvpn-go/internal/enums"
	"sort"
	"sync"
	"time"
)

// Client-side scheduling only: the upstream session, sequence numbers, DNS
// envelope and ARQ acknowledgements remain unchanged on the wire.
type navaRoutePath struct {
	conn           Connection
	weight, credit int64
}
type navaRouter struct {
	mu        sync.Mutex
	paths     []navaRoutePath
	refreshed time.Time
	last      map[uint16]string
}

func (c *Client) navaSelectTargets(kind uint8, stream uint16, copies int) ([]Connection, error) {
	if c.nava == nil || !c.cfg.NavaMultipathEnabled {
		return c.balancer.SelectTargets(kind, stream, copies)
	}
	return c.nava.router.selectTargets(c.balancer, kind, stream, copies, c.cfg.NavaMultipathLimit)
}
func (r *navaRouter) selectTargets(b *Balancer, kind uint8, stream uint16, copies, limit int) ([]Connection, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	now := time.Now()
	// Check validity on every dispatch so a disabled path is never served from
	// the short-lived score cache. Newly discovered paths enter within 500 ms.
	refresh := now.Sub(r.refreshed) >= 500*time.Millisecond || len(r.paths) == 0
	if !refresh {
		for _, p := range r.paths {
			v, ok := b.GetConnectionByKey(p.conn.Key)
			if !ok || !v.IsValid {
				refresh = true
				break
			}
		}
	}
	if refresh {
		r.refresh(b, limit, now)
	}
	if len(r.paths) == 0 {
		return nil, ErrNoValidConnections
	}
	n := min(max(1, copies), len(r.paths))
	if kind == Enums.PACKET_STREAM_DATA || kind == Enums.PACKET_STREAM_RESEND || kind == Enums.PACKET_PING {
		n = 1
	}
	selected := make([]Connection, 0, n)
	used := make(map[string]bool, n)
	// A resend tries another eligible path rather than repeatedly using a
	// stalled resolver. One-path sessions still work through normal ARQ.
	if kind == Enums.PACKET_STREAM_RESEND && len(r.paths) > 1 {
		used[r.last[stream]] = true
	}
	for len(selected) < n {
		best := -1
		var total int64
		for i := range r.paths {
			p := &r.paths[i]
			if used[p.conn.Key] {
				continue
			}
			p.credit += p.weight
			total += p.weight
			if best < 0 || p.credit > r.paths[best].credit {
				best = i
			}
		}
		if best < 0 {
			break
		}
		r.paths[best].credit -= total
		conn := r.paths[best].conn
		selected = append(selected, conn)
		used[conn.Key] = true
	}
	if len(selected) == 0 {
		return nil, ErrNoValidConnections
	}
	if stream != 0 && isBalancerStreamDataLike(kind) {
		if r.last == nil {
			r.last = make(map[uint16]string)
		}
		r.last[stream] = selected[0].Key
	}
	return selected, nil
}
func (r *navaRouter) refresh(b *Balancer, limit int, now time.Time) {
	type candidate struct {
		conn Connection
		cost uint64
	}
	candidates := make([]candidate, 0)
	b.mu.RLock()
	for _, idx := range b.activeIDs {
		conn := b.connections[idx]
		latency := b.latencyScoreLocked(idx)
		if latency == 999000 {
			latency = 250000
			if conn.MTUResolveTime > 0 {
				latency = uint64(conn.MTUResolveTime / time.Microsecond)
			}
		}
		if latency < 1000 {
			latency = 1000
		}
		if latency > 5000000 {
			latency = 5000000
		}
		loss := b.lossScoreLocked(idx)
		if loss > 1000 {
			loss = 1000
		}
		candidates = append(candidates, candidate{conn, latency * (1000 + 4*loss) / 1000})
	}
	b.mu.RUnlock()
	sort.Slice(candidates, func(i, j int) bool {
		if candidates[i].cost == candidates[j].cost {
			return candidates[i].conn.Key < candidates[j].conn.Key
		}
		return candidates[i].cost < candidates[j].cost
	})
	if limit > 0 && len(candidates) > limit {
		candidates = candidates[:limit]
	}
	paths := make([]navaRoutePath, len(candidates))
	for i, p := range candidates {
		paths[i] = navaRoutePath{conn: p.conn, weight: int64(100000000 / p.cost)}
	}
	for i := range paths {
		if paths[i].weight < 1 {
			paths[i].weight = 1
		}
	}
	r.paths = paths
	r.refreshed = now
}
