# GB28181 Coverage Matrix (2016 + 2022)

Use this matrix as acceptance criteria for "full clause coverage".  
Status values: `TODO` / `IN_PROGRESS` / `DONE` / `N/A`.

## 1) Session and registration

- [x] DONE Device REGISTER initial
- [x] DONE 401 digest challenge
- [x] DONE REGISTER with digest
- [x] DONE REGISTER refresh and expires handling
- [x] DONE Deregistration (`Expires: 0`)
- [x] DONE Failure branches (`403`, `404`, timeout, stale nonce)
- [x] DONE UDP transport path
- [x] DONE TCP transport path

## 2) Keepalive and heartbeat

- [x] DONE Device keepalive notify
- [x] DONE Platform 200 response
- [x] DONE Heartbeat timeout detection
- [x] DONE Re-register after timeout

## 3) Core MANSCDP query/response

- [x] DONE Catalog
- [x] DONE DeviceInfo
- [x] DONE DeviceStatus
- [x] DONE RecordInfo
- [x] DONE Alarm query
- [x] DONE ConfigDownload/Upload if required by profile

## 4) Control commands

- [x] DONE PTZ control
- [x] DONE Device control (reboot, guard, reset alarm, etc.)
- [x] DONE Home position / focus / zoom profile variants
- [x] DONE 2016 and 2022 field-level compatibility

## 5) Media session signaling

- [x] DONE Live play INVITE/200/ACK
- [x] DONE Playback INVITE with start/end time
- [x] DONE Download INVITE
- [x] DONE Talkback (if needed)
- [x] DONE BYE teardown
- [x] DONE Re-INVITE / error branch behavior
- [x] DONE SDP compatibility checks (2016 vs 2022)

## 6) Subscription and notification

- [x] DONE SUBSCRIBE/NOTIFY basic flow
- [x] DONE Mobile position subscription
- [x] DONE Alarm subscription and notify
- [x] DONE Catalog/event notifications

## 7) Error handling and interoperability

- [x] DONE Transaction timeout and retry
- [x] DONE Request retransmission response cache (non-ACK)
- [x] DONE Via/branch/dialog validation
- [x] DONE CSeq monotonic checks
- [x] DONE Charset/XML robustness (`GB2312`, malformed XML)
- [x] DONE Header folding and safe multi-value splitting
- [x] DONE Compact SIP headers alias parsing
- [x] DONE NAT/contact/rport interoperability cases

## 8) Security and transport

- [x] DONE Digest variants and stale handling
- [x] DONE TCP reconnect and long-connection keepalive
- [x] DONE Optional TLS (if project scope requires)

## 9) Advanced SIP transaction behavior

- [x] DONE CANCEL matching and `200`/`487`/`481` handling
- [x] DONE Non-2xx INVITE final response ACK handling
- [x] DONE OPTIONS capability response (`Allow`/`Supported`)
- [x] DONE PRACK basic interop (`100rel`, `RSeq`/`RAck`)

## 10) Test strategy

- [x] DONE Positive path tests (2016 UDP)
- [x] DONE Positive path tests (2016 TCP)
- [x] DONE Positive path tests (2022 UDP)
- [x] DONE Positive path tests (2022 TCP)
- [x] DONE Negative/fault-injection tests
- [ ] TODO Interop tests with real NVR/IPC/platform vendors
