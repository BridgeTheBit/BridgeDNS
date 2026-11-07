package client

import (
	"io"
	"net"
	"testing"
)

type navaPartialConn struct{ net.Conn }

func (navaPartialConn) Read(b []byte) (int, error)  { copy(b, []byte("abc")); return 3, io.EOF }
func (navaPartialConn) Write(b []byte) (int, error) { return 2, io.ErrShortWrite }
func TestNavaMeterCountsOnlyBytesTransferredWithErrors(t *testing.T) {
	state := &navaState{}
	conn := &navaMeteredConn{Conn: navaPartialConn{}, state: state}
	buffer := make([]byte, 20)
	_, _ = conn.Read(buffer)
	_, _ = conn.Write(buffer)
	if state.upload.Load() != 3 || state.download.Load() != 2 {
		t.Fatalf("partial transfer counted incorrectly: %d/%d", state.upload.Load(), state.download.Load())
	}
}
