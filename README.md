# Kotlin GB28181 Simulator (Client + Server)

This project provides a runnable GB28181 simulator in Kotlin for integration testing.

## Current scope

- SIP over UDP/TCP/TLS transport
- Device registration flow: `REGISTER -> 401 Digest -> REGISTER(auth) -> 200`
- Basic MANSCDP `MESSAGE` support:
  - Keepalive
  - Catalog query/response
  - DeviceInfo query/response
  - DeviceStatus query/response
  - RecordInfo query/response
  - Alarm query/response
  - ConfigDownload/ConfigUpload query/response
- Subscription support:
  - SUBSCRIBE
  - NOTIFY
  - Catalog event notify
- Control support:
  - PTZ (MANSCDP control message)
  - DeviceControl (example `TeleBoot`)
  - HomePosition control
  - Focus/Zoom PTZ variants
- Mobile position:
  - MobilePosition subscription
  - MobilePosition notify payload
- Stream signaling:
  - Live `INVITE` with SDP + `ACK`
  - Playback `INVITE`
  - Download `INVITE`
  - Talkback `INVITE`
  - `BYE` teardown
  - INVITE failure simulation (`404`/`488`) and single retry behavior
  - INVITE timeout simulation and single retry behavior
  - SDP profile differences between 2016 and 2022
- Advanced SIP transaction behavior:
  - request retransmission response cache (non-ACK)
  - `CANCEL` flow (`200 CANCEL` + `487 INVITE` + `481` mismatch branch)
  - `OPTIONS` capability response (`Allow` / `Supported`)
  - basic `PRACK` / `100rel` interoperability (`RSeq` / `RAck`)
- Protocol robustness:
  - Via/branch basic validation
  - Dialog header checks for ACK/BYE
  - CSeq monotonic checks
  - compact SIP header aliases (`v/f/t/i/m/l/c/...`)
  - header folding continuation parsing
  - safe multi-value header splitting (quoted/composite values)
  - malformed XML body rejection (`400`)
  - digest stale nonce challenge (`401` with `stale=true`)
  - registration target resolution with `rport` and `Contact` fallback
  - heartbeat timeout detection and offline eviction
- TCP transport resilience:
  - send failure reconnect-once retry
  - long-connection keepalive (`CRLF` heartbeat)
- Protocol profile switch:
  - GB28181-2016
  - GB28181-2022 (adds a few representative XML/SDP fields for simulation)

## Run

```bash
./gradlew run
```

Default SIP ports in simulator:

- server SIP: `5060`
- client SIP: `5061`

Run modes:

```bash
./gradlew run --args="all 2016 udp hikvision"
./gradlew run --args="all 2022 tcp dahua"
./gradlew run --args="all 2022 tls uniview"
./gradlew run --args="server 2022 tcp hikvision"
./gradlew run --args="client 2016 udp dahua"
./gradlew run --args="all 2022 tcp hikvision configs/vendor-strategy.sample.json"
```

`all`, `fault`, `server`, and `client` modes now open an interactive console instead of auto-running
sleep-based demo steps. In interactive modes, client REGISTER/keepalive/refresh are manual by default.
Type `help` after startup to list manual commands.

For IDE debugging with separate entry points:

- `com.goway.gb28181.MainKt` (combined launcher)
- `com.goway.gb28181.ServerMainKt` (server-only launcher)
- `com.goway.gb28181.ClientMainKt` (client-only launcher)

Example interactive commands:

- `q-info`, `q-status`, `q-record`, `q-alarm`
- `channel list`, `channel set`
- `c-ptz`, `c-zoom-in`, `c-home-on`, `c-reboot`
- `s-alarm`, `s-mobile`, `s-catalog`
- `[system]` `help`, `demo`, `status`, `clients`, `exit`
- `[client]` `client new`, `client list`, `client use [clientId]`, `client register [clientId]`, `client keepalive [clientId]`, `client unregister [clientId]`, `client keepalive loop [clientId] [sec]`, `client keepalive stop [clientId]`
- `[query]` `q-info`, `q-status`, `q-record`, `q-alarm`, `q-cfg-down`, `q-cfg-up`
- `[control]` `c-ptz`, `c-zoom-in`, `c-zoom-out`, `c-home-on`, `c-home-off`, `c-reboot`
- `[subscribe]` `s-alarm`, `s-mobile`, `s-catalog`
- `[media]` `i-play start/stop`, `i-playback start/stop`, `i-download start/stop`, `i-talk start/stop`
- `demo` (manual trigger of old scripted sequence), `fault`, `status`, `exit`

Parameter examples:

- `i-play 34020000001320000001 3402000000132000000101 16000`
- `client new 34020000001320000002 5062 12345678`
- `client list`
- `channel set client-1 4`
- `i-play start 34020000001320000001 3402000000132000000101 16000`
- `i-play stop 34020000001320000001 3402000000132000000101`
- `s-alarm 34020000001320000001 120`
- `c-ptz 34020000001320000001 3402000000132000000101 A50F010000`

Vendor arg options:

- `hikvision`
- `dahua`
- `uniview`

Optional 5th arg: custom vendor strategy JSON file path.

## Matrix regression

```powershell
./scripts/run-matrix.ps1
```

The matrix script runs:

- `all 2016 udp`
- `all 2016 tcp`
- `all 2022 udp`
- `all 2022 tcp`

## Negative test regression

```powershell
./scripts/run-negative.ps1
```

This script runs `fault 2022 udp` and validates logs for:

- malformed XML rejection (`400`)
- out-of-order CSeq rejection
- stale nonce digest challenge

## Vendor quick run

```powershell
./scripts/run-vendor.ps1 -Vendor hikvision -Version 2022 -Protocol tcp -Mode all
./scripts/run-vendor.ps1 -Vendor dahua -Version 2022 -Protocol tcp -Mode all
./scripts/run-vendor.ps1 -Vendor uniview -Version 2022 -Protocol tls -Mode all
```

## TLS strict mode

Default TLS mode is simulation-friendly (`SIP_TLS_INSECURE=true`).

For strict TLS:

```powershell
$env:SIP_TLS_INSECURE="false"
$env:SIP_TLS_KEYSTORE_PATH="C:\path\server.jks"
$env:SIP_TLS_KEYSTORE_PASSWORD="changeit"
$env:SIP_TLS_TRUSTSTORE_PATH="C:\path\truststore.jks"
$env:SIP_TLS_TRUSTSTORE_PASSWORD="changeit"
./gradlew run --args="all 2022 tls hikvision"
```

## Interop logs and report

- Session logs are written to `logs/interop-*.log`.
- Build summary report from latest log:

```powershell
./scripts/build-interop-report.ps1
```

- Build report from a specific log:

```powershell
./scripts/build-interop-report.ps1 -LogFile "logs/interop-hikvision-v2022-tcp-all-xxxx.log"
```

## Important notes

- This is a simulation-oriented implementation, not a full production stack.
- RTP media transport is not fully implemented; only SIP/SDP signaling is simulated.
- REGISTER lifecycle simulation includes refresh and deregistration (`Expires: 0`).
- TLS mode is available for simulation; strict production interoperability still requires proper keystore/truststore configuration.
- You can extend it with:
  - Full MANSCDP command set
  - TCP/TLS SIP transport
  - RTP/PS packet sending and receiving
  - Device/channel management persistence

See `docs/COVERAGE_MATRIX.md` for a GB28181-2016/2022 conformance checklist template.

For architecture diagrams and communication flow, see `docs/ARCHITECTURE.md`.
中文架构说明见 `docs/ARCHITECTURE_ZH.md`。

For GB28181/SIP/PS terminology reference, see `docs/TERMS.md`.
