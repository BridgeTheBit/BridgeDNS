#!/usr/bin/env python3
"""Check the native integration contract before packaging an APK."""
from pathlib import Path
import re, subprocess, sys

root=Path(__file__).resolve().parent.parent
abis=tuple(sys.argv[1:]) or ('arm64-v8a','armeabi-v7a','x86_64')
for abi in abis:
    folder=root/'app/src/main/jniLibs'/abi
    for name,required in {
        'libmasterdns.so':('NavaRun','NavaStop','NavaSnapshot','NavaResolvers'),
        'libhev-socks5-tunnel.so':('hev_socks5_tunnel_main_from_file','hev_socks5_tunnel_quit'),
        'libbridgedns.so':('Java_dev_bridgedns_vpn_NativeBridge_selfTest','Java_dev_bridgedns_vpn_NativeBridge_metrics','Java_dev_bridgedns_vpn_NativeBridge_resolvers','Java_dev_bridgedns_vpn_NativeBridge_runCore','Java_dev_bridgedns_vpn_NativeBridge_runTun')
    }.items():
        path=folder/name
        symbols=subprocess.check_output(['readelf','--dyn-syms','-W',str(path)],text=True)
        for symbol in required:
            assert re.search(r'\b'+symbol+r'\b',symbols),f'{path}: missing {symbol}'
        if name=='libhev-socks5-tunnel.so':
            assert not re.search(r'\bJNI_OnLoad\b',symbols),f'{path}: sample-app JNI loader would reject Nava'
        headers=subprocess.check_output(['readelf','-lW',str(path)],text=True)
        aligns=[int(line.split()[-1],16) for line in headers.splitlines() if line.strip().startswith('LOAD ')]
        assert aligns and min(aligns)>=16384,f'{path}: not 16 KB aligned'
        print(f'PASS {abi}/{name}')
