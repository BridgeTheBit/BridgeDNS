#!/usr/bin/env python3
"""Local embedded-core integration: fast start, background scan and exact counters."""
import argparse,ctypes,pathlib,socket,subprocess,tempfile,threading,time,os,json
p=argparse.ArgumentParser();p.add_argument('--server',required=True);p.add_argument('--core',required=True);p.add_argument('--multipath',action='store_true');args=p.parse_args()
work=pathlib.Path(tempfile.mkdtemp(prefix='nava-test-'))
def recv(s,n):
 b=b''
 while len(b)<n:
  v=s.recv(n-len(b))
  if not v:raise EOFError()
  b+=v
 return b
def echo(c):
 try:
  c.settimeout(5);h=recv(c,2);recv(c,h[1]);c.sendall(b'\x05\x00');h=recv(c,4)
  if h[3]==3:recv(c,recv(c,1)[0])
  else:recv(c,4 if h[3]==1 else 16)
  recv(c,2);c.sendall(b'\x05\x00\x00\x01'+b'\0'*6)
  while True:
   b=c.recv(65536)
   if not b:break
   c.sendall(b)
 except (OSError,EOFError):pass
 finally:c.close()
listener=socket.socket();listener.bind(('127.0.0.1',0));listener.listen()
def accept():
 while True:
  try:c,_=listener.accept()
  except OSError:return
  threading.Thread(target=echo,args=(c,),daemon=True).start()
threading.Thread(target=accept,daemon=True).start()
s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.bind(('127.0.0.1',0));dnsport=s.getsockname()[1];s.close()
blackhole=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);blackhole.bind(('0.0.0.0',0));badport=blackhole.getsockname()[1]
relays=[]; relay_packets={}
def relay(ip):
 front=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);front.bind((ip,dnsport));relays.append(front)
 def one(packet,peer):
  try:
   with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as back:
    back.settimeout(2);back.sendto(packet,('127.0.0.1',dnsport));reply,_=back.recvfrom(65535);front.sendto(reply,peer)
  except OSError:pass
 def loop():
  while True:
   try:packet,peer=front.recvfrom(65535);relay_packets[ip]=relay_packets.get(ip,0)+1
   except OSError:return
   threading.Thread(target=one,args=(packet,peer),daemon=True).start()
 threading.Thread(target=loop,daemon=True).start()
for i in range(2,12):relay('127.0.0.'+str(i))
key='0123456789abcdef0123456789abcdef';(work/'key.txt').write_text(key)
(work/'server.toml').write_text(f'DOMAIN=["v.example.com"]\nPROTOCOL_TYPE="SOCKS5"\nUDP_HOST="127.0.0.1"\nUDP_PORT={dnsport}\nDATA_ENCRYPTION_METHOD=1\nENCRYPTION_KEY_FILE="{work}/key.txt"\nUSE_EXTERNAL_SOCKS5=true\nFORWARD_IP="127.0.0.1"\nFORWARD_PORT={listener.getsockname()[1]}\nLOG_LEVEL="WARN"\n')
(work/'client.toml').write_text(f'DOMAINS=["v.example.com"]\nENCRYPTION_KEY="{key}"\nDATA_ENCRYPTION_METHOD=1\nPROTOCOL_TYPE="SOCKS5"\nLISTEN_IP="127.0.0.1"\nLISTEN_PORT=18001\nLOCAL_DNS_ENABLED=false\nMAX_UPLOAD_MTU=150\nMAX_DOWNLOAD_MTU=1232\nMTU_TEST_PARALLELISM=2\nMTU_TEST_RETRIES=1\nMTU_TEST_TIMEOUT=0.5\nPACKET_DUPLICATION_COUNT=2\nSETUP_PACKET_DUPLICATION_COUNT=3\nLOG_LEVEL="INFO"\n')
if args.multipath:
 with (work/'client.toml').open('a') as f:f.write('NAVA_MULTIPATH_ENABLED=true\nNAVA_MULTIPATH_LIMIT=0\n')
(work/'resolvers.txt').write_text(''.join(f'127.0.0.{i}:{dnsport if i<12 else badport}\n' for i in range(1,13)))
server=subprocess.Popen([str(pathlib.Path(args.server).resolve()),'-config',str(work/'server.toml')],stdout=open(work/'server.log','w'),stderr=subprocess.STDOUT)
core=ctypes.CDLL(str(pathlib.Path(args.core).resolve()));core.NavaRun.argtypes=[ctypes.c_char_p]*3;core.NavaRun.restype=ctypes.c_void_p
core.NavaSnapshot.argtypes=[ctypes.POINTER(ctypes.c_longlong)]
def metrics():
 result=(ctypes.c_longlong*8)();core.NavaSnapshot(result);return list(result)
def run():
 result=core.NavaRun(str(work/'client.toml').encode(),str(work/'resolvers.txt').encode(),str(work/'client.log').encode())
 if result:
  if ctypes.string_at(result):print(ctypes.string_at(result).decode(),flush=True)
  free=ctypes.CDLL(None).free;free.argtypes=[ctypes.c_void_p];free(result)
time.sleep(.3);thread=threading.Thread(target=run,daemon=True);thread.start();started=time.monotonic()
try:
 for _ in range(120):
  try:client=socket.create_connection(('127.0.0.1',18001),timeout=.2);break
  except OSError:time.sleep(.05)
 else:raise AssertionError('proxy not ready')
 initial=metrics();assert initial[4]>=1 and initial[5]==12,initial
 assert initial[6]<12 and initial[7]==1,('scan did not continue in background',initial)
 client.settimeout(10);client.sendall(b'\x05\x01\0');assert recv(client,2)==b'\x05\0'
 host=b'example.com';client.sendall(b'\x05\x01\0\x03'+bytes([len(host)])+host+b'\x01\xbb');assert recv(client,10)[1]==0
 
 for _ in range(160):
  if metrics()[7]==0:break
  time.sleep(.05)
 time.sleep(.6);relay_packets.clear()
 payload=os.urandom(65536);client.sendall(payload);assert recv(client,len(payload))==payload;client.close()
 for _ in range(160):
  snapshot=metrics()
  if snapshot[7]==0:break
  time.sleep(.05)
 assert snapshot[0]==65536 and snapshot[1]==65536,('payload counters',snapshot)
 assert snapshot[2]>65536 and snapshot[3]>65536,('wire counters',snapshot)
 assert snapshot[4]==11 and snapshot[5]==12 and snapshot[6]==12 and snapshot[7]==0,('final scan',snapshot)
 assert len([n for n in relay_packets.values() if n>0])==10,relay_packets
 result={'pass':True,'relay_packets_after_discovery':relay_packets,'multipath':args.multipath,'proxy_started_before_scan_completed':True,'initial_metrics':initial,'final_metrics':snapshot,'round_trip_bytes':65536,'elapsed_seconds':round(time.monotonic()-started,2)}
 print(json.dumps(result),flush=True)
finally:
 core.NavaStop();thread.join(4);server.terminate()
 try:server.wait(3)
 except subprocess.TimeoutExpired:server.kill()
 print('Fixture logs:',work,flush=True)
