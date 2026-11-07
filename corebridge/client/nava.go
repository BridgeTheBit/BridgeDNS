package client

import (
	"context"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

type navaState struct {
	router       navaRouter
	pathTraffic  sync.Map
	observed     atomic.Pointer[sync.Map]
	history      navaHistory
	upload       atomic.Uint64
	download     atomic.Uint64
	wireUpload   atomic.Uint64
	wireDownload atomic.Uint64
	scan         atomic.Pointer[navaScan]
}
type navaScan struct {
	checked         atomic.Uint64
	scanning        atomic.Bool
	cancel          context.CancelFunc
	ready           chan struct{}
	readyOnce       sync.Once
	done            chan struct{}
	up, down, chars int
}

func (c *Client) EnableNava() {
	c.nava = &navaState{}
	c.nava.history.load(c.cfg.NavaResolverCacheFile)
	if c.cfg.NavaMultipathEnabled {
		c.log.Infof("Nava multipath enabled: max data paths=%d (0=all healthy); weighted packet striping", c.cfg.NavaMultipathLimit)
	}
}
func (c *Client) NavaMetrics() [8]uint64 {
	var out [8]uint64
	s := c.nava
	if s == nil {
		return out
	}
	out[0] = s.upload.Load()
	out[1] = s.download.Load()
	out[2] = s.wireUpload.Load()
	out[3] = s.wireDownload.Load()
	out[4] = uint64(c.balancer.ActiveCount())
	out[5] = uint64(c.balancer.TotalCount())
	if scan := s.scan.Load(); scan != nil {
		out[6] = scan.checked.Load()
		if scan.scanning.Load() {
			out[7] = 1
		}
	}
	return out
}
func (c *Client) StopNava() {
	if c.nava != nil {
		if s := c.nava.scan.Load(); s != nil {
			s.cancel()
			<-s.done
		}
	}
}
func (c *Client) navaRuntimeStarted() {
	if c.nava == nil {
		return
	}
	if s := c.nava.scan.Load(); s != nil {
		s.readyOnce.Do(func() {
			s.up = c.syncedUploadMTU
			s.down = c.syncedDownloadMTU
			s.chars = c.syncedUploadChars
			close(s.ready)
		})
	}
}
func (c *Client) navaMayRecheck() bool {
	if c.nava == nil {
		return true
	}
	scan := c.nava.scan.Load()
	return scan == nil || !scan.scanning.Load()
}
func (c *Client) navaWire(up bool, n int) {
	if c.nava == nil || n <= 0 {
		return
	}
	if up {
		c.nava.wireUpload.Add(uint64(n))
	} else {
		c.nava.wireDownload.Add(uint64(n))
	}
}

type navaMeteredConn struct {
	net.Conn
	state *navaState
}

func (c *navaMeteredConn) Read(b []byte) (int, error) {
	n, e := c.Conn.Read(b)
	if n > 0 {
		c.state.upload.Add(uint64(n))
	}
	return n, e
}
func (c *navaMeteredConn) Write(b []byte) (int, error) {
	n, e := c.Conn.Write(b)
	if n > 0 {
		c.state.download.Add(uint64(n))
	}
	return n, e
}
func (c *navaMeteredConn) CloseWrite() error {
	if v, ok := c.Conn.(interface{ CloseWrite() error }); ok {
		return v.CloseWrite()
	}
	return nil
}

type navaProbeResult struct {
	conn   Connection
	result mtuConnectionProbeResult
	reason mtuRejectReason
}

func (c *Client) runNavaInitialMTUTests(parent context.Context) error {
	if old := c.nava.scan.Load(); old != nil {
		old.cancel()
		<-old.done
	}
	ctx, cancel := context.WithCancel(parent)
	scan := &navaScan{cancel: cancel, ready: make(chan struct{}), done: make(chan struct{})}
	scan.scanning.Store(true)
	c.nava.scan.Store(scan)
	c.nava.observed.Store(&sync.Map{})
	candidates, preferred := c.nava.history.prioritize(c.balancer.AllConnections())
	if preferred > 0 {
		c.log.Infof("Nava scan priority: rechecking %d previously healthy paths first", preferred)
	}
	if len(candidates) == 0 {
		scan.scanning.Store(false)
		close(scan.done)
		cancel()
		return ErrNoValidConnections
	}
	for _, conn := range candidates {
		c.balancer.SetConnectionValidity(conn.Key, false)
	}
	caps := c.precomputeUploadCaps()
	workers := c.navaScanWorkers(len(candidates))
	c.log.Infof("Nava fast start: looking for 2 healthy resolvers; remaining candidates continue in background (%d total, parallel=%d)", len(candidates), workers)
	probeCtx, stopProbes := context.WithCancel(ctx)
	jobs := make(chan Connection)
	results := make(chan navaProbeResult, workers)
	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for conn := range jobs {
				if probeCtx.Err() != nil {
					return
				}
				r, reason := c.probeConnectionMTU(probeCtx, conn, caps[conn.Domain])
				select {
				case results <- navaProbeResult{conn, r, reason}:
				case <-probeCtx.Done():
					return
				}
			}
		}()
	}
	go func() {
		defer close(jobs)
		for _, conn := range candidates {
			select {
			case jobs <- conn:
			case <-probeCtx.Done():
				return
			}
		}
	}()
	go func() { wg.Wait(); close(results) }()
	doneKeys := make(map[string]bool)
	var grace <-chan time.Time
	var timer *time.Timer
initial:
	for {
		select {
		case <-ctx.Done():
			break initial
		case <-grace:
			break initial
		case r, ok := <-results:
			if !ok {
				break initial
			}
			doneKeys[r.conn.Key] = true
			scan.checked.Add(1)
			c.nava.observed.Load().Store(r.conn.Key, true)
			decision := buildMTUDecision(r.result, r.reason)
			if !decision.active {
				c.log.Warnf("Nava resolver rejected: %s via %s | reason=%v | upload=%d download=%d | checked=%d/%d", r.conn.Domain, r.conn.ResolverLabel, map[mtuRejectReason]string{mtuRejectUpload: "UPLOAD_MTU", mtuRejectDownload: "DOWNLOAD_MTU"}[r.reason], r.result.UploadBytes, r.result.DownloadBytes, scan.checked.Load(), len(candidates))
			}
			c.applyMTUDecision(r.conn.Key, decision)
			if decision.active {
				c.navaRememberHealthy(r.conn)
				c.log.Infof("Nava resolver healthy: %s | checked=%d/%d", r.conn.ResolverLabel, scan.checked.Load(), len(candidates))
				if c.balancer.ActiveCount() >= 2 {
					break initial
				}
				if timer == nil {
					timer = time.NewTimer(2 * time.Second)
					grace = timer.C
				}
			}
		}
	}
	if timer != nil {
		timer.Stop()
	}
	stopProbes()
	wg.Wait()
	if ctx.Err() != nil {
		scan.scanning.Store(false)
		close(scan.done)
		cancel()
		return ctx.Err()
	}
	valid, up, down, chars := summarizeValidMTUConnections(c.balancer.ActiveConnections())
	if len(valid) == 0 {
		scan.scanning.Store(false)
		close(scan.done)
		cancel()
		return ErrNoValidConnections
	}
	up = max(minUploadMTUFloor, up-4)
	chars = c.encodedCharsForPayload(up)
	c.applySyncedMTUState(up, down, chars)
	pending := make([]Connection, 0, len(candidates)-len(doneKeys))
	for _, conn := range candidates {
		if !doneKeys[conn.Key] {
			pending = append(pending, conn)
		}
	}
	c.log.Infof("Nava starting tunnel with %d healthy resolvers; %d remaining, fixed MTU %d/%d", len(valid), len(pending), up, down)
	go c.runNavaBackgroundScan(ctx, scan, pending)
	return nil
}
func (c *Client) runNavaBackgroundScan(ctx context.Context, scan *navaScan, pending []Connection) {
	defer close(scan.done)
	defer scan.scanning.Store(false)
	if len(pending) == 0 {
		return
	}
	select {
	case <-ctx.Done():
		return
	case <-scan.ready:
	}
	jobs := make(chan Connection)
	var wg sync.WaitGroup
	c.log.Infof("Nava background scan parallel=%d", c.navaScanWorkers(len(pending)))
	for i := 0; i < c.navaScanWorkers(len(pending)); i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for conn := range jobs {
				if ctx.Err() != nil {
					return
				}
				ok := c.navaCheckFixedMTU(ctx, conn, scan.up, scan.down)
				if ctx.Err() != nil {
					return
				}
				if ok {
					c.balancer.SetConnectionMTU(conn.Key, scan.up, scan.chars, scan.down)
					c.balancer.SetConnectionValidityWithLog(conn.Key, true, true)
					c.balancer.SeedConservativeStats(conn.Key)
					c.navaRememberHealthy(conn)
				}
				c.nava.observed.Load().Store(conn.Key, true)
				checked := scan.checked.Add(1)
				c.log.Infof("Nava background resolver %s: healthy=%t | checked=%d/%d", conn.ResolverLabel, ok, checked, c.balancer.TotalCount())
			}
		}()
	}
	for _, conn := range pending {
		select {
		case jobs <- conn:
		case <-ctx.Done():
			close(jobs)
			wg.Wait()
			return
		}
	}
	close(jobs)
	wg.Wait()
	c.log.Infof("Nava initial background scan complete: %d/%d healthy", c.balancer.ActiveCount(), c.balancer.TotalCount())
}
func (c *Client) navaCheckFixedMTU(ctx context.Context, conn Connection, up, down int) bool {
	transport, err := c.newUDPQueryTransport(ctx, conn.ResolverLabel)
	if err != nil {
		return false
	}
	defer transport.conn.Close()
	timeout := c.mtuTestTimeout
	if timeout <= 0 {
		timeout = 2 * time.Second
	}
	for direction := 0; direction < 2; direction++ {
		passed := false
		for attempt := 0; attempt < max(1, c.mtuTestRetries); attempt++ {
			if ctx.Err() != nil {
				return false
			}
			var ok bool
			var e error
			if direction == 0 {
				ok, _, e = c.sendUploadMTUProbe(ctx, conn, transport, up, timeout, mtuProbeOptions{Quiet: true})
			} else {
				ok, _, e = c.sendDownloadMTUProbe(ctx, conn, transport, down, up, timeout, mtuProbeOptions{Quiet: true})
			}
			if e == nil && ok {
				passed = true
				break
			}
		}
		if !passed {
			return false
		}
	}
	return true
}
