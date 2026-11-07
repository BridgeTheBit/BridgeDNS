package main

/*
#include <stdlib.h>
*/
import "C"
import (
	"context"
	"masterdnsvpn-go/internal/client"
	"masterdnsvpn-go/internal/config"
	"sync"
	"unsafe"
)

var mu sync.Mutex
var cancel context.CancelFunc
var running *client.Client
var lastMetrics [8]uint64
var lastResolvers = "[]"

//export NavaRun
func NavaRun(cfg, resolvers, log *C.char) *C.char {
	mu.Lock()
	if cancel != nil {
		mu.Unlock()
		return C.CString("core already running")
	}
	ctx, stop := context.WithCancel(context.Background())
	cancel = stop
	lastMetrics = [8]uint64{}
	lastResolvers = "[]"
	mu.Unlock()
	defer func() { stop(); mu.Lock(); cancel = nil; mu.Unlock() }()
	path := C.GoString(resolvers)
	c, err := client.Bootstrap(C.GoString(cfg), C.GoString(log), config.ClientConfigOverrides{ResolversFilePath: &path})
	if err != nil {
		return C.CString(err.Error())
	}
	c.EnableNava()
	mu.Lock()
	running = c
	mu.Unlock()
	defer func() {
		c.StopNava()
		mu.Lock()
		lastMetrics = c.NavaMetrics()
		lastResolvers = c.NavaResolversJSON()
		running = nil
		mu.Unlock()
	}()
	if err = c.Run(ctx); err != nil {
		return C.CString(err.Error())
	}
	return C.CString("")
}

//export NavaStop
func NavaStop() {
	mu.Lock()
	defer mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

//export NavaSnapshot
func NavaSnapshot(out *C.longlong) {
	mu.Lock()
	defer mu.Unlock()
	snapshot := lastMetrics
	if running != nil {
		snapshot = running.NavaMetrics()
	}
	target := unsafe.Slice(out, 8)
	for i, value := range snapshot {
		target[i] = C.longlong(value)
	}
}

//export NavaResolvers
func NavaResolvers() *C.char {
	mu.Lock()
	defer mu.Unlock()
	if running != nil {
		return C.CString(running.NavaResolversJSON())
	}
	return C.CString(lastResolvers)
}
func main() {}
