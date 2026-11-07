#!/usr/bin/env python3
"""Local embedded-core integration: fast start, background scan and exact counters."""
import argparse,ctypes,pathlib,socket,subprocess,tempfile,threading,time,os,json
p=argparse.ArgumentParser();p.add_argument('--server',required=True);p.add_argument('--core',required=True);p.add_argument('--multipath',action='store_true');p.add_argument('--history',action='store_true');args=p.parse_args()
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
relays=[]
def relay(ip):
 front=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);front.bind((ip,dnsport));relays.append(front)
 def one(packet,peer):
  try:
   with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as back:
    back.settimeout(2);back.sendto(packet,('127.0.0.1',dnsport));reply,_=back.recvfrom(65535);front.sendto(reply,peer)
  except OSError:pass
 def loop():
  while True:
   try:packet,peer=front.recvfrom(65535)
   except OSError:return
   threading.Thread(target=one,args=(packet,peer),daemon=True).start()
 threading.Thread(target=loop,daemon=True).start()
relay('127.0.0.2');relay('127.0.0.9')
key='0123456789abcdef0123456789abcdef';(work/'key.txt').write_text(key)
(work/'server.toml').write_text(f'DOMAIN=["v.example.com"]\nPROTOCOL_TYPE="SOCKS5"\nUDP_HOST="127.0.0.1"\nUDP_PORT={dnsport}\nDATA_ENCRYPTION_METHOD=1\nENCRYPTION_KEY_FILE="{work}/key.txt"\nUSE_EXTERNAL_SOCKS5=true\nFORWARD_IP="127.0.0.1"\nFORWARD_PORT={listener.getsockname()[1]}\nLOG_LEVEL="WARN"\n')
(work/'client.toml').write_text(f'DOMAINS=["v.example.com"]\nENCRYPTION_KEY="{key}"\nDATA_ENCRYPTION_METHOD=1\nPROTOCOL_TYPE="SOCKS5"\nLISTEN_IP="127.0.0.1"\nLISTEN_PORT=18001\nLOCAL_DNS_ENABLED=false\nMAX_UPLOAD_MTU=150\nMAX_DOWNLOAD_MTU=1232\nMTU_TEST_PARALLELISM=2\nMTU_TEST_RETRIES=1\nMTU_TEST_TIMEOUT=0.5\nPACKET_DUPLICATION_COUNT=2\nSETUP_PACKET_DUPLICATION_COUNT=3\nLOG_LEVEL="INFO"\n')
if args.multipath:
 with (work/'client.toml').open('a') as f:f.write('NAVA_MULTIPATH_ENABLED=true\nNAVA_MULTIPATH_LIMIT=0\n')
if args.history:
 with (work/'client.toml').open('a') as f:f.write('NAVA_RESOLVER_CACHE_FILE=\"'+str(work/'history.json')+'\"\nNAVA_SCAN_PARALLELISM=2\n')
(work/'resolvers.txt').write_text(''.join(f'127.0.0.{i}:{dnsport if i in (1,2,9) else badport}\n' for i in range(1,10)))
# Local DNS upstream fixture, reached by the unchanged tunnel server.
upstream=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);upstream.bind(('127.0.0.1',0))
def answer_dns():
 while True:
  try:q,peer=upstream.recvfrom(4096)
  except OSError:return
  answer=q[:2]+b'\x81\x80\0\1\0\1\0\0\0\0'+q[12:]+b'\xc0\x0c\0\1\0\1\0\0\0\x3c\0\4\x08\x08\x08\x08'
  upstream.sendto(answer,peer)
threading.Thread(target=answer_dns,daemon=True).start()
with (work/'server.toml').open('a') as f:f.write('DNS_UPSTREAM_SERVERS=["127.0.0.1:'+str(upstream.getsockname()[1])+'"]\n')
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
 initial=metrics();assert initial[4]>=1 and initial[5]==9,initial
 assert initial[6]<9 and initial[7]==1,('scan did not continue in background',initial)
 client.settimeout(10);client.sendall(b'\x05\x01\0');assert recv(client,2)==b'\x05\0'
 host=b'example.com';client.sendall(b'\x05\x01\0\x03'+bytes([len(host)])+host+b'\x01\xbb');assert recv(client,10)[1]==0
 # One cold DNS request must get an answer without closing/recreating UDP ASSOCIATE.
 control=socket.create_connection(('127.0.0.1',18001),timeout=5);control.settimeout(5)
 control.sendall(b'\x05\x01\0');assert recv(control,2)==b'\x05\0'
 control.sendall(b'\x05\x03\0\x01'+b'\0'*6);associate=recv(control,10);assert associate[1]==0,associate
 relay_port=int.from_bytes(associate[8:10],'big')
 dns_query=b'\x12\x34\x01\0\0\1\0\0\0\0\0\0'+b'\x04cold\x04nava\x04test\0\0\1\0\1'
 udp_header=b'\0\0\0\1\x01\x01\x01\x01\0\x35'
 with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as udp:
  udp.settimeout(12);udp.sendto(udp_header+dns_query,('127.0.0.1',relay_port));dns_reply,_=udp.recvfrom(4096)
 assert dns_reply[:10]==udp_header,('SOCKS UDP source endpoint changed',dns_reply[:10])
 assert dns_reply[10:12]==b'\x12\x34' and dns_reply[-4:]==b'\x08'*4,dns_reply
 control.close()
 payload=os.urandom(65536);client.sendall(payload);assert recv(client,len(payload))==payload;client.close()
 for _ in range(160):
  snapshot=metrics()
  if snapshot[7]==0:break
  time.sleep(.05)
 assert snapshot[0]==65536 and snapshot[1]==65536,('payload counters',snapshot)
 assert snapshot[2]>65536 and snapshot[3]>65536,('wire counters',snapshot)
 assert snapshot[4]==3 and snapshot[5]==9 and snapshot[6]==9 and snapshot[7]==0,('final scan',snapshot)
 core.NavaResolvers.restype=ctypes.c_void_p
 pointer=core.NavaResolvers();resolver_rows=json.loads(ctypes.string_at(pointer));free=ctypes.CDLL(None).free;free.argtypes=[ctypes.c_void_p];free(pointer)
 assert len(resolver_rows)==9 and sum(int(row['healthy']) for row in resolver_rows)==3,resolver_rows
 assert sum(row['up'] for row in resolver_rows)>65536 and sum(row['down'] for row in resolver_rows)>65536,resolver_rows
 result={'resolver_snapshot_verified':True,'pass':True,'multipath':args.multipath,'proxy_started_before_scan_completed':True,'initial_metrics':initial,'final_metrics':snapshot,'cold_dns_reply_on_same_association':True,'round_trip_bytes':65536,'elapsed_seconds':round(time.monotonic()-started,2)}
 if args.history:
  core.NavaStop();thread.join(5);assert not thread.is_alive(),'first run did not stop'
  cached=json.loads((work/'history.json').read_text());assert len(cached)==3,cached
  order=[3,4,5,6,7,8,9,2,1]
  (work/'resolvers.txt').write_text(''.join(f'127.0.0.{i}:{dnsport if i in (1,2,9) else badport}\n' for i in order))
  thread=threading.Thread(target=run,daemon=True);thread.start()
  for _ in range(200):
   try:
    with socket.create_connection(('127.0.0.1',18001),timeout=.1):break
   except OSError:time.sleep(.05)
  else:raise AssertionError('cached reconnect did not start')
  second=(work/'client.log').read_text()
  marker='Nava scan priority: rechecking 3 previously healthy paths first'
  assert marker in second,second
  tail=second[second.rfind(marker):]
  assert 'checked=1/9' in tail and 'checked=2/9' in tail,tail
  assert 'Nava resolver rejected:' not in tail.split('Nava starting tunnel')[0],tail
  result['cached_reconnect_prioritized_previous_successes']=True
  result['cached_paths']=len(cached)
 print(json.dumps(result),flush=True)
finally:
 upstream.close();core.NavaStop();thread.join(4);server.terminate()
 try:server.wait(3)
 except subprocess.TimeoutExpired:server.kill()
 print('Fixture logs:',work,flush=True)
