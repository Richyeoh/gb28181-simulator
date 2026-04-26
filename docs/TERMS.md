# GB28181 / SIP / PS Terms

## Protocol Roles and Entities

- GB28181: Chinese video surveillance networking standard.
- Platform: Upper-level management/control side (usually server side).
- Device: IPC/NVR/edge endpoint side (usually client side).
- UAC (User Agent Client): SIP request initiator.
- UAS (User Agent Server): SIP request responder.

## SIP Core Concepts

- Transaction: Request/response exchange unit (branch + method context).
- Dialog: Long-lived SIP relationship identified by Call-ID + tags.
- Route set: Ordered routing information from Record-Route/Route.
- Loose routing (`lr`): Request-URI keeps remote target, Route carries hops.
- Strict routing: Request-URI points to first route hop, target moved to Route tail.

## SIP Methods Used in This Project

- REGISTER: Device registration and refresh/deregister.
- MESSAGE: MANSCDP XML query/control/response payload carrier.
- SUBSCRIBE: Event subscription request.
- NOTIFY: Event notification delivery.
- INVITE: Media session setup.
- ACK: Confirmation for INVITE final responses.
- BYE: Session teardown.
- CANCEL: Cancel pending INVITE transaction.
- OPTIONS: Capability/keepalive probing.
- PRACK: Reliable provisional response acknowledgement (`100rel`).

## SIP Headers and Related Terms

- Via/branch: Transaction routing and uniqueness key.
- From/To/tag: Dialog endpoint identifiers.
- Call-ID: Global call/dialog identifier.
- CSeq: Sequencing and method binding for requests.
- Contact: Reachable target URI.
- Record-Route/Route: Multi-hop routing control.
- WWW-Authenticate / Authorization: Digest auth challenge/response.
- RSeq / RAck: Reliable provisional response sequencing (`100rel`/PRACK).

## Media Terms

- SDP (Session Description Protocol): Media negotiation in INVITE/200.
- PS (Program Stream): Media payload format used in GB28181 contexts.
- RTP (Real-time Transport Protocol): Packetized media transport.
- SSRC: RTP stream synchronization source identifier.
- Payload type (PT): RTP payload format indicator (e.g., PS mapping).

## GB28181 Data Layer Terms

- MANSCDP: XML message format family in GB28181.
- CmdType: Command category field in XML (Catalog/DeviceInfo/etc.).
- Keepalive: Device liveness report.
- Catalog: Device/channel directory info.
- DeviceInfo / DeviceStatus / RecordInfo / Alarm: Common query domains.
- DeviceControl / PTZ: Control command categories.
- MobilePosition: Position event reporting.

## In This Simulator

- SIP signaling supports UDP/TCP/TLS.
- PS over RTP is simulated payload transport (not full production media stack).
- Interop features include digest auth, routing, transaction retry/caching, and basic PRACK/100rel behavior.
