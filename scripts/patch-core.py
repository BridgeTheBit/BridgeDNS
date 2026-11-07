#!/usr/bin/env python3
"""Add connection-result visibility without changing the wire protocol."""
from pathlib import Path
import sys, re
p=Path(sys.argv[1])/'internal/client/socks_manager.go'
s=p.read_text()
original='c.log.Debugf("🔌 <green>Socks successfully connected for stream %d</green>", packet.StreamID)'
updated='c.log.Infof("SOCKS target TCP connection confirmed | Stream: %d", packet.StreamID)'
assert original in s or updated in s,'Unsupported upstream connection handler'
s=s.replace(original,updated)
anchor='err := c.writeSocksConnectResultLocked(s, socksReplyForPacketType(packet.PacketType))'
line='c.log.Warnf("SOCKS target connection failed | Stream: %d | Reply: %d", packet.StreamID, socksReplyForPacketType(packet.PacketType))'
assert anchor in s,'Unsupported upstream failure handler'
if line not in s:s=s.replace(anchor,line+'\n\t'+anchor)
p.write_text(s)

def patch(name, old, new):
    p=Path(sys.argv[1])/name
    s=p.read_text()
    if new in s:return
    assert old in s, 'Unsupported upstream source: '+name
    p.write_text(s.replace(old,new,1))
patch('internal/client/client.go','type Client struct {','type Client struct {\n\tnava *navaState')
patch('internal/client/mtu.go','func (c *Client) RunInitialMTUTests(ctx context.Context) error {','func (c *Client) RunInitialMTUTests(ctx context.Context) error {\n\tif c.nava != nil { return c.runNavaInitialMTUTests(ctx) }')
patch('internal/client/mtu.go','if c.cfg.RecheckInactiveServersEnabled {\n\t\t\tc.runResolverHealthBatch','if c.cfg.RecheckInactiveServersEnabled && c.navaMayRecheck() {\n\t\t\tc.runResolverHealthBatch')
patch('internal/client/client.go','c.InitVirtualStream0()','c.InitVirtualStream0()\n\t\t\t\tc.navaRuntimeStarted()')
patch('internal/client/stream_client.go','a := arq.NewARQ(streamID, c.sessionID, s, conn, mtu, c.log, arqCfg)','arqConn := conn\n\tif c.nava != nil && conn != nil && streamID != 0 { arqConn = &navaMeteredConn{Conn:conn,state:c.nava} }\n\ta := arq.NewARQ(streamID, c.sessionID, s, arqConn, mtu, c.log, arqCfg)')
patch('internal/client/async_runtime.go','if _, err := conn.WriteToUDP(frame.packet, frame.addr); err == nil {','if n, err := conn.WriteToUDP(frame.packet, frame.addr); err == nil {\n\t\t\t\t\tc.navaWire(true,n)')
patch('internal/client/async_runtime.go','n, addr, err := conn.ReadFromUDP(buf)','n, addr, err := conn.ReadFromUDP(buf)\n\t\t\tc.navaWire(false,n)')
patch('internal/client/tunnel_runtime.go','_, err := conn.Read(buffer)','n, err := conn.Read(buffer)\n\t\tc.navaWire(false,n)')
patch('internal/client/tunnel_runtime.go','if _, err := conn.Write(packet); err != nil {\n\t\treturn nil, err\n\t}','if n, err := conn.Write(packet); err != nil {\n\t\treturn nil, err\n\t} else { c.navaWire(true,n) }')
patch('internal/client/tunnel_runtime.go','for {\n\t\tn, err := conn.Read(buffer)\n\t\tif err != nil {','for {\n\t\tn, err := conn.Read(buffer)\n\t\tc.navaWire(false,n)\n\t\tif err != nil {')
patch('internal/client/tunnel_runtime.go','if _, err := udpConn.Write(packet); err != nil {\n\t\t_ = udpConn.Close()\n\t\treturn err\n\t}','if n, err := udpConn.Write(packet); err != nil {\n\t\t_ = udpConn.Close()\n\t\treturn err\n\t} else { c.navaWire(true,n) }')

patch('internal/client/async_runtime.go','c.balancer.SelectTargets(task.opts.PacketType, task.opts.StreamID, targetCount)','c.navaSelectTargets(task.opts.PacketType, task.opts.StreamID, targetCount)')
patch('internal/client/async_runtime.go','count := c.cfg.PacketDuplicationCount','if c.nava != nil && c.cfg.NavaMultipathEnabled && (packetType == Enums.PACKET_STREAM_DATA || packetType == Enums.PACKET_STREAM_RESEND || packetType == Enums.PACKET_PING) { return 1 }\n\tcount := c.cfg.PacketDuplicationCount')


p=Path(sys.argv[1])/'internal/config/client.go'
s=p.read_text()
for field in [
    'NavaMultipathEnabled bool `toml:"NAVA_MULTIPATH_ENABLED"`',
    'NavaMultipathLimit int `toml:"NAVA_MULTIPATH_LIMIT"`',
    'NavaScanParallelism int `toml:"NAVA_SCAN_PARALLELISM"`',
    'NavaResolverCacheFile string `toml:"NAVA_RESOLVER_CACHE_FILE"`',
]:
    if not re.search(r"^\s*"+field.split()[0]+r"\s+",s,re.M):
        s=s.replace('type ClientConfig struct {','type ClientConfig struct {\n '+field,1)
p.write_text(s)

patch('internal/client/async_runtime.go','c.navaWire(true,n)','c.navaWire(true,n)\n c.navaPathWire(frame.addr.String(),true,n)')
patch('internal/client/async_runtime.go','c.navaWire(false,n)','c.navaWire(false,n)\n if addr!=nil {c.navaPathWire(addr.String(),false,n)}')
patch('internal/client/socks_manager.go','func (c *Client) handleSocksUDPAssociate(ctx context.Context, conn net.Conn, clientAddr string, clientPort uint16, atyp byte) {','func (c *Client) handleSocksUDPAssociate(ctx context.Context, conn net.Conn, clientAddr string, clientPort uint16, atyp byte) {\n ctx,cancel:=context.WithCancel(ctx);defer cancel()\n navaSlots:=make(chan struct{},64)')
patch('internal/client/socks_manager.go','dnsQuery := buf[payloadOffset:n]','dnsQuery := buf[payloadOffset:n]\n if c.nava!=nil {\n queryCopy:=append([]byte(nil),dnsQuery...);headerCopy:=append([]byte(nil),buf[:payloadOffset]...);peerCopy:=*peerAddr\n select {case navaSlots<-struct{}{}:go func(){defer func(){<-navaSlots}();c.navaSocksDNS(ctx,queryCopy,headerCopy,&peerCopy,udpConn)}();default:}\n continue\n }')
patch('internal/client/socks_manager.go','udpConn, err := net.ListenUDP("udp", bindAddr)','network := "udp"\n if c.nava!=nil {bindAddr.IP=replyIP;if replyIP.To4()!=nil {network="udp4"}else{network="udp6"}}\n udpConn, err := net.ListenUDP(network, bindAddr)')
