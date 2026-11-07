package client

import (
	"context"
	"net"
	"time"
)

// Keep a SOCKS UDP association open while the server-side DNS answer is in
// flight. The upstream cache still deduplicates tunnel queries; no direct DNS.
func navaAwaitDNS(ctx context.Context, query []byte, lookup func([]byte, func([]byte)) bool, respond func([]byte)) {
	timeout := time.NewTimer(15 * time.Second)
	defer timeout.Stop()
	tick := time.NewTicker(100 * time.Millisecond)
	defer tick.Stop()
	for {
		if ctx.Err() != nil {
			return
		}
		if lookup(query, respond) {
			return
		}
		select {
		case <-ctx.Done():
			return
		case <-timeout.C:
			return
		case <-tick.C:
		}
	}
}
func (c *Client) navaSocksDNS(ctx context.Context, query, header []byte, peer *net.UDPAddr, conn *net.UDPConn) {
	navaAwaitDNS(ctx, query, func(q []byte, respond func([]byte)) bool { return c.ProcessDNSQuery(q, peer, respond) }, func(reply []byte) {
		// Preserve requested source endpoint; 0.0.0.0 in the SOCKS UDP reply can be
		// discarded by tun2socks implementations expecting the original DNS IP.
		packet := append(append([]byte(nil), header...), reply...)
		_, _ = conn.WriteToUDP(packet, peer)
	})
}
