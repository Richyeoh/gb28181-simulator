# Full Gap Checklist (Simulator vs Full GB Deployment)

This checklist enumerates all major capability areas for a "full GB28181 implementation"
and marks current simulator status.

Status keys:

- `DONE_SIM`: implemented in simulator scope
- `PARTIAL`: partially implemented
- `TODO_FULL`: not complete for production-grade/full interoperability

## A. SIP Core and Dialog

- `DONE_SIM` REGISTER/401 digest/REGISTER(auth)/200
- `DONE_SIM` REGISTER refresh and deregister (`Expires: 0`)
- `DONE_SIM` ACK and BYE basic dialog lifecycle
- `DONE_SIM` ACK CSeq aligned with INVITE CSeq
- `DONE_SIM` INVITE retry on timeout and error response
- `DONE_SIM` Route/Record-Route header parsing and relay for ACK/BYE/NOTIFY
- `DONE_SIM` OPTIONS keepalive and capability response (`Allow`/`Supported`)
- `DONE_SIM` CANCEL handling (`200 CANCEL` + `487 INVITE` + `481` on mismatch)
- `DONE_SIM` PRACK/100rel basic interoperability (`Require:100rel`, `RSeq`/`RAck`)
- `DONE_SIM` Transaction retransmission cache for non-ACK requests
- `PARTIAL` Full transaction state machine (all timers and branch-level RFC corner cases)
- `PARTIAL` Strict RFC-compliant route-set construction for all edge cases

## B. SIP Parsing/Encoding Robustness

- `DONE_SIM` Content-Length bounded body parsing
- `DONE_SIM` Header normalization
- `DONE_SIM` Compact SIP header aliases (`v/f/t/i/m/l/c/...`)
- `DONE_SIM` Multi-value header storage (`headerLines`)
- `DONE_SIM` Header folding (continuation line) parsing
- `DONE_SIM` Safe comma splitting for quoted/composite header values
- `DONE_SIM` Via/rport/contact basic handling
- `PARTIAL` Full ABNF edge coverage (quoted-pair escaping, comments, unusual URI/header grammars)

## C. MANSCDP Commands

- `DONE_SIM` Catalog / DeviceInfo / DeviceStatus / RecordInfo / Alarm
- `DONE_SIM` ConfigDownload / ConfigUpload
- `DONE_SIM` PTZ / DeviceControl / HomePosition / Zoom-Focus variants
- `PARTIAL` Full command matrix from all clauses and vendor private extensions

## D. Subscription/Notify

- `DONE_SIM` SUBSCRIBE/NOTIFY for Alarm/MobilePosition/Catalog
- `PARTIAL` Full event package matrix and renewal edge cases

## E. Media Signaling

- `DONE_SIM` Live/Playback/Download/Talkback INVITE signaling
- `DONE_SIM` SDP 2016/2022 representative differences
- `TODO_FULL` RTP/PS actual media send/receive pipeline
- `TODO_FULL` Media clocking, jitter buffering, packet loss handling, SSRC lifecycle

## F. Security and Transport

- `DONE_SIM` UDP/TCP/TLS transport modes
- `DONE_SIM` TCP/TLS reconnect and keepalive
- `DONE_SIM` TLS strict mode env-based keystore/truststore config
- `PARTIAL` TLS mutual-auth deployment hardening and cert policy profiles

## G. Heartbeat and Presence

- `DONE_SIM` Keepalive notify/200 response
- `DONE_SIM` heartbeat timeout offline eviction
- `DONE_SIM` re-register restore path

## H. Vendor Profiles

- `DONE_SIM` Hikvision/Dahua/Uniview runtime profile switch
- `DONE_SIM` External JSON strategy override
- `PARTIAL` deep vendor-specific edge behavior and private command sets

## I. Test and Interop

- `DONE_SIM` positive matrix scripts
- `DONE_SIM` negative fault injection script
- `TODO_FULL` real device/platform cross-vendor interop campaign
