# BridgeDNS

BridgeDNS is an Android client for a personal [MasterDnsVPN](https://github.com/masterking32/MasterDnsVPN) server. It carries supported TCP traffic through DNS queries and responses and exposes the tunnel either as an Android VPN or as a local SOCKS5 proxy.

## First release

Version `0.1.0` builds four signed APKs:

- `BridgeDNS-arm64-v8a-v0.1.0.apk`
- `BridgeDNS-armeabi-v7a-v0.1.0.apk`
- `BridgeDNS-x86_64-v0.1.0.apk`
- `BridgeDNS-universal-v0.1.0.apk`

The app requires a personal MasterDnsVPN server, a delegated tunnel domain, the matching server key and reachable DNS resolvers. General UDP traffic is not supported by this client core.

See [README_FA.md](README_FA.md) for the Persian documentation.

## Build

The GitHub Actions release workflow rebuilds the pinned native dependencies, packages each ABI, signs the APKs with the repository's release key stored in Actions Secrets, and publishes the four assets for the release tag.
