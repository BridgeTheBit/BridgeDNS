package client

import (
	"context"
	"encoding/json"
	"sync/atomic"
	"testing"
	"time"
)

func TestNavaDNSWaitsForMissAndDeliversAnswer(t *testing.T) {
	var calls atomic.Int32
	delivered := false
	navaAwaitDNS(context.Background(), []byte("query"), func(q []byte, reply func([]byte)) bool {
		if calls.Add(1) < 3 {
			return false
		}
		reply([]byte("answer"))
		return true
	}, func(b []byte) { delivered = string(b) == "answer" })
	if !delivered || calls.Load() != 3 {
		t.Fatal("pending DNS answer was lost")
	}
}
func TestNavaDNSCancellationStopsPendingLookup(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	done := make(chan struct{})
	go func() {
		navaAwaitDNS(ctx, nil, func([]byte, func([]byte)) bool { t.Error("lookup after cancellation"); return false }, func([]byte) { t.Error("unexpected response") })
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("DNS wait did not cancel")
	}
}
func TestNavaResolverSnapshotUsesRealCountersAndUnknownLatency(t *testing.T) {
	b := navaTestBalancer(2)
	c := &Client{balancer: b, nava: &navaState{}}
	c.navaPathWire("", true, 17)
	c.navaPathWire("", false, 31)
	var rows []map[string]any
	if e := json.Unmarshal([]byte(c.NavaResolversJSON()), &rows); e != nil {
		t.Fatal(e)
	}
	if len(rows) != 2 || rows[0]["up"].(float64) != 17 || rows[0]["down"].(float64) != 31 {
		t.Fatal(rows)
	}
}
