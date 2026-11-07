package client

import (
	"fmt"
	Enums "masterdnsvpn-go/internal/enums"
	"sync"
	"testing"
	"time"
)

func navaTestBalancer(n int) *Balancer {
	b := NewBalancer(BalancingHybridScore, nil)
	conns := make([]*Connection, n)
	for i := range conns {
		conns[i] = &Connection{Key: fmt.Sprint(i), IsValid: true, MTUResolveTime: 10 * time.Millisecond}
	}
	b.SetConnections(conns)
	for _, c := range conns {
		b.SetConnectionValidity(c.Key, true)
	}
	return b
}
func TestNavaMultipathStripesTenPathsWithoutDataDuplication(t *testing.T) {
	b := navaTestBalancer(12)
	r := &navaRouter{}
	seen := map[string]int{}
	for i := 0; i < 100; i++ {
		paths, e := r.selectTargets(b, Enums.PACKET_STREAM_DATA, 1, 3, 10)
		if e != nil || len(paths) != 1 {
			t.Fatalf("data duplicated: %v %v", paths, e)
		}
		seen[paths[0].Key]++
	}
	if len(seen) != 10 {
		t.Fatalf("wanted 10 paths, got %v", seen)
	}
	for _, n := range seen {
		if n != 10 {
			t.Fatalf("equal paths not evenly shared: %v", seen)
		}
	}
}
func TestNavaMultipathWeightsAndResendFailover(t *testing.T) {
	b := navaTestBalancer(2)
	b.SetConnectionMTU("0", 100, 150, 500)
	for i := 0; i < 10; i++ {
		b.ReportSend("0")
		b.ReportSuccess("0", 10*time.Millisecond)
		b.ReportSend("1")
		b.ReportSuccess("1", 50*time.Millisecond)
	}
	r := &navaRouter{}
	seen := map[string]int{}
	for i := 0; i < 120; i++ {
		p, e := r.selectTargets(b, Enums.PACKET_STREAM_DATA, 2, 2, 0)
		if e != nil {
			t.Fatal(e)
		}
		seen[p[0].Key]++
	}
	if seen["0"] <= seen["1"]*3 || seen["1"] == 0 {
		t.Fatalf("bad adaptive shares: %v", seen)
	}
	last := r.last[2]
	p, e := r.selectTargets(b, Enums.PACKET_STREAM_RESEND, 2, 3, 0)
	if e != nil || p[0].Key == last {
		t.Fatal("resend did not switch path")
	}
	b.SetConnectionValidity("0", false)
	for i := 0; i < 5; i++ {
		p, e = r.selectTargets(b, Enums.PACKET_STREAM_DATA, 2, 2, 0)
		if e != nil || p[0].Key != "1" {
			t.Fatal("disabled path selected")
		}
	}
	p, e = r.selectTargets(b, Enums.PACKET_STREAM_RESEND, 2, 2, 0)
	if e != nil || len(p) != 1 {
		t.Fatal("single path fallback failed")
	}
	b.SetConnectionValidity("1", false)
	if _, e = r.selectTargets(b, Enums.PACKET_STREAM_DATA, 2, 1, 0); e != ErrNoValidConnections {
		t.Fatal("expected no healthy paths")
	}
}
func TestNavaMultipathConcurrentPlanning(t *testing.T) {
	b := navaTestBalancer(10)
	r := &navaRouter{}
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			for j := 0; j < 100; j++ {
				p, e := r.selectTargets(b, Enums.PACKET_STREAM_DATA, uint16(id+1), 3, 4)
				if e != nil || len(p) != 1 {
					t.Errorf("planning failed")
				}
			}
		}(i)
	}
	wg.Wait()
}
