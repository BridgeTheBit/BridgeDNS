package client

import (
	"masterdnsvpn-go/internal/config"
	"os"
	"path/filepath"
	"sync"
	"testing"
)

func TestNavaHistoryPersistsPriorityWithoutTrustingCachedResults(t *testing.T) {
	path := filepath.Join(t.TempDir(), "history.json")
	h := &navaHistory{}
	h.load(path)
	if err := h.remember("old-good"); err != nil {
		t.Fatal(err)
	}
	fresh := &navaHistory{}
	fresh.load(path)
	candidates := []Connection{{Key: "new"}, {Key: "old-good"}, {Key: "other"}}
	ordered, count := fresh.prioritize(candidates)
	if count != 1 || ordered[0].Key != "old-good" || ordered[1].Key != "new" || ordered[2].Key != "other" {
		t.Fatalf("bad scan order: %+v", ordered)
	}
	if candidates[0].Key != "new" {
		t.Fatal("input was mutated")
	}
	for _, c := range ordered {
		if c.IsValid || c.UploadMTUBytes != 0 {
			t.Fatal("cached success was accepted without fresh probe")
		}
	}
	if _, err := os.Stat(path + ".tmp"); !os.IsNotExist(err) {
		t.Fatal("temporary file remains")
	}
}
func TestNavaHistoryConcurrentSuccessesAndCorruptFallback(t *testing.T) {
	path := filepath.Join(t.TempDir(), "cache.json")
	os.WriteFile(path, []byte("broken"), 0600)
	h := &navaHistory{}
	h.load(path)
	var wg sync.WaitGroup
	for _, key := range []string{"a", "b", "c", "d"} {
		wg.Add(1)
		go func(k string) {
			defer wg.Done()
			if err := h.remember(k); err != nil {
				t.Error(err)
			}
		}(key)
	}
	wg.Wait()
	fresh := &navaHistory{}
	fresh.load(path)
	_, n := fresh.prioritize([]Connection{{Key: "a"}, {Key: "b"}, {Key: "c"}, {Key: "d"}, {Key: "new"}})
	if n != 4 {
		t.Fatalf("lost concurrent success: %d", n)
	}
}
func TestNavaScanConcurrencyUsesUserSettingAndCandidateCount(t *testing.T) {
	for _, test := range []struct{ setting, total, want int }{{1, 258, 1}, {8, 258, 8}, {32, 258, 32}, {64, 258, 64}, {64, 3, 3}, {0, 258, 4}, {100, 258, 64}, {8, 0, 0}} {
		c := &Client{cfg: config.ClientConfig{NavaScanParallelism: test.setting}}
		if got := c.navaScanWorkers(test.total); got != test.want {
			t.Fatalf("setting %d, total %d: got %d want %d", test.setting, test.total, got, test.want)
		}
	}
}
