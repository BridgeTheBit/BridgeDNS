package client

import (
	"encoding/json"
	"sync/atomic"
)

type navaPathTraffic struct{ up, down atomic.Uint64 }

func (c *Client) navaPathWire(label string, upload bool, n int) {
	if c.nava == nil || n <= 0 {
		return
	}
	v, _ := c.nava.pathTraffic.LoadOrStore(label, &navaPathTraffic{})
	s := v.(*navaPathTraffic)
	if upload {
		s.up.Add(uint64(n))
	} else {
		s.down.Add(uint64(n))
	}
}
func (c *Client) NavaResolversJSON() string {
	type row struct {
		Key         string  `json:"key"`
		Resolver    string  `json:"resolver"`
		Domain      string  `json:"domain"`
		Healthy     bool    `json:"healthy"`
		Tested      bool    `json:"tested"`
		RTT         float64 `json:"rtt_ms"`
		Loss        float64 `json:"loss_pct"`
		Sent        uint64  `json:"sent"`
		UploadMTU   int     `json:"upload_mtu"`
		DownloadMTU int     `json:"download_mtu"`
		Up          uint64  `json:"up"`
		Down        uint64  `json:"down"`
	}
	rows := make([]row, 0)
	b := c.balancer
	b.mu.RLock()
	defer b.mu.RUnlock()
	for i, conn := range b.connections {
		sent, _, lost, sum, count := b.stats[i].snapshot()
		rtt := -1.0
		if count > 0 {
			rtt = float64(sum) / float64(count) / 1000
		} else if conn.MTUResolveTime > 0 {
			rtt = float64(conn.MTUResolveTime.Microseconds()) / 1000
		}
		loss := -1.0
		if sent > 0 {
			loss = 100 * float64(lost) / float64(sent)
			if loss > 100 {
				loss = 100
			}
		}
		v := row{Key: conn.Key, Resolver: conn.ResolverLabel, Domain: conn.Domain, Healthy: conn.IsValid, RTT: rtt, Loss: loss, Sent: sent, UploadMTU: conn.UploadMTUBytes, DownloadMTU: conn.DownloadMTUBytes}
		if c.nava != nil {
			if observed := c.nava.observed.Load(); observed != nil {
				_, v.Tested = observed.Load(conn.Key)
			}
			if value, ok := c.nava.pathTraffic.Load(conn.ResolverLabel); ok {
				traffic := value.(*navaPathTraffic)
				v.Up = traffic.up.Load()
				v.Down = traffic.down.Load()
			}
		}
		rows = append(rows, v)
	}
	encoded, err := json.Marshal(rows)
	if err != nil {
		return "[]"
	}
	return string(encoded)
}
