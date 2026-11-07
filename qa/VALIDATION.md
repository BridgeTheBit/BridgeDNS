# Nava 0.1.6 validation

- SDK35/JDK17 package compilation, APK v2/v3 signing and 16KB zip alignment passed. VersionCode7 / versionName0.1.6. Signing certificate unchanged from 0.1.3 onward.
- All three core/JNI ABIs rebuilt with NDK28. Required NavaResolvers and JNI resolvers exports plus previous exports verified for all nine native libraries. APK library copies exactly match final outputs.
- APK SHA256: 5a1a70689820630d2e8be54ad44237123eb21e543631bc86983bfc63397b68cc.
- Fourteen JVM tests pass: previous profile/config/data-probe tests plus HTTP fixed/chunked payload counting, sample bounds, incomplete-body detection and rejection of HTTP errors/redirects as successful measurements.
- Ten targeted Nava tests pass under Go -race; pending DNS response delivery/cancellation and real resolver snapshot counters added. Fresh pinned checkout patched twice passes these tests under -race, with no server code changes.
- Local DNS/SOCKS integration against unchanged upstream server: a single cold DNS request gets an answer on the same UDP association, correct source endpoint and transaction ID preserved. 65,536-byte TCP echo and exact payload counters pass in Multipath. Live resolver JSON rows/counters verified. Standard connection with persisted successful-path reconnection also passes. See integration-016.json.
- Resolver dashboard snapshots are requested only while visible and transferred through an app-private atomic JSON file rather than Binder payloads. Rate calculation uses counter deltas; unknown latency/loss is not invented. Runtime per-path DNS rates exclude initial synchronous MTU probes, unlike the main total DNS counter.
- Speed tests use verified HTTPS through explicit local SOCKS only, 64KiB max download/8KiB upload. HTTP body counted without framing; upload requires a 2xx response. A 45-second phase deadline and socket read timeouts bound measurements; user Stop closes the active socket. No direct network fallback or provider analytics module.
- Actual Cloudflare speed test through the user's tunnel, Android TUN routing, Chrome navigation, UI rendering/scrolling and live dashboard on a phone/emulator were NOT executed. Browser reset may have additional causes. No claim of complete browser repair or carrier speed improvement.
- Previous release validation and unrelated upstream environment/flaky-suite limits follow.

## Previous release validation

# Nava 0.1.5 validation

- JDK17 / SDK35 Java/resources build passed; Android package versionCode6 / versionName0.1.5.
- Three ABIs rebuilt with NDK28. All nine native libraries pass required-symbol and 16KB-alignment checks. APK copies match final binaries; scan-priority code is present in all three core ABIs.
- v2/v3 APK signature verified. Development certificate unchanged from 0.1.3/0.1.4: acb3fc0af12dd1121ab7e2ec6da97fe41245ab1a0caf3fa642e929991cc6ca40.
- APK SHA256: 27ddaf23692ea4939579ce095baf939c018ada4e73224c6ab2529b782a49c8ed.
- Eleven JVM tests passed: resolver parsing/data probe/profile configuration plus scan setting migration, settings roundtrip, exports with/without secrets, cache scope stability and separation, invalid concurrency rejection.
- Seven targeted Go Nava tests passed under -race, including persistent history, fresh validation requirement, concurrent cache writes, corrupt cache fallback and configurable worker bounds.
- Clean pinned upstream checkout: core patch applied twice and targeted tests passed. Reproducible patch does not duplicate added config fields.
- Local integration with unchanged upstream DNS server passed for Standard and Multipath; 65,536 bytes exact TCP roundtrip and byte counters. Discovery continues after proxy startup. Same-process second connection reads its persisted history and tests three prior successful paths first despite dead paths moved to the beginning of the input file. See integration-015.json.
- UI stat columns have equal weighted widths, equal row heights, centered text and proportional font sizing. Profile chevron defines intrinsic dimensions and draws inside supplied bounds.
- Profile deletion saves the new encrypted collection before updating the UI, selects a remaining item or returns to an empty default. User confirmation is required by the app's delete dialog. Export with key is an explicit menu action; temporary private snapshot is removed after completion/cancel. File export/import uses Android SAF; keyless import opens a pre-filled edit form and requires a key before saving.
- Android UI rendering, native socket protection, SAF dialogs, encrypted profile deletion and full-device TUN were NOT exercised on a phone/emulator. No performance gain on a carrier is claimed. Previous upstream broad-suite environment/flaky failures are documented below; targeted new tests passed.

## Previous release validation

# Nava 0.1.4 validation

- APK builds directly with SDK35, JDK17 and NDK28; arm64-v8a, armeabi-v7a, x86_64.
- APK v2/v3 signatures verified. Same development certificate as 0.1.3: acb3fc0af12dd1121ab7e2ec6da97fe41245ab1a0caf3fa642e929991cc6ca40.
- APK SHA256: f2b94157e2e237443bd0cc9a65e962bdbc095f14e4f5b28fa10803cae0d41b8a.
- All nine native libraries: required exports and 16KB ELF alignment passed; HEV sample JNI loader excluded. Every APK .so exactly matches final build output. Multipath implementation present in all three core ABIs.
- Ten JVM tests passed: resolver parsing, data probe and profile migration/config roundtrip. ProfileTest uses the real org.json runtime.
- Four targeted Go Nava tests pass under -race, including 10-path striping, weighted distribution, resend alternate path, disabled-path removal, one-path fallback and concurrent planners.
- Local UDP DNS / SOCKS / embedded-core integration passes in Standard and Multipath with an unchanged upstream server binary: 65,536 bytes echo exactly, exact payload counters, startup before background scan completion. Separate >10-path fixture verified traffic through ten relay addresses after discovery. See integration-014.json. Synthetic loopback fixture does not predict mobile throughput gains.
- Broad upstream client/config suite was also run: config passed; client suite had a probabilistic pre-existing round-robin expectation failure and three Unix-socket tests blocked by this environment. The same Unix-socket failures reproduce on a pristine pinned upstream checkout. The balancer test expects round-robin in a mode implemented with random selection; a standalone baseline run may pass. No edits were made to that balancer or those tests. Logs retained here.
- Android device/emulator UI, on-device VPN protect handshake, TUN routing and carrier throughput were NOT executed. No claim of device validation.
- No server code or wire protocol changes. Standard remains the migration default. Multipath changes client scheduling and one-copy data/poll policy; setup control retains duplication. Scores update at most every 500ms, selection checks validity each dispatch. Configurable path limit, zero means all healthy. No guaranteed speed increase.
