# Vendor Profiles

This simulator supports vendor behavior profiles for rapid interop simulation:

- `HIKVISION`
- `DAHUA`
- `UNIVIEW`

## What changes by vendor

- `User-Agent` value in REGISTER and requests
- REGISTER `Contact` suffix pattern
- `Manufacturer` and `Model` in MANSCDP XML responses
- Catalog optional fields (`Parental` / `CivilCode` / `Address`)
- PTZ zoom command bytes
- SDP extra attributes in INVITE/200 flows
- Heartbeat and REGISTER refresh timing defaults
- SUBSCRIBE default expires values
- Playback/download speed defaults

## Run examples

```bash
./gradlew run --args="all 2016 udp hikvision"
./gradlew run --args="all 2022 tcp dahua"
./gradlew run --args="all 2022 tls uniview"
./gradlew run --args="all 2022 tcp hikvision configs/vendor-strategy.sample.json"
```

## External strategy override

You can override vendor behavior from JSON (see `configs/vendor-strategy.sample.json`):

- Manufacturer/model strings
- Contact suffix
- PTZ zoom bytes
- Heartbeat and register refresh timings
- Subscribe expires defaults
- Playback speed
- SDP extra line
- Catalog extra field key/value

## Built-in presets

- `configs/vendor-hikvision.json`
- `configs/vendor-dahua.json`
- `configs/vendor-uniview.json`

Quick launch:

```powershell
./scripts/run-vendor.ps1 -Vendor hikvision -Version 2022 -Protocol tcp -Mode all
```

## Current behavior notes

- `HIKVISION`: adds `a=framerate:25` in SDP
- `DAHUA`: adds `a=recvonly` SDP compatibility hint and `DeviceTime`
- `UNIVIEW`: adds `a=quality:5` SDP compatibility hint
- Timing:
  - `HIKVISION`: heartbeat 20s, refresh 50s, subscribe expires 3600
  - `DAHUA`: heartbeat 25s, refresh 55s, subscribe expires 1800
  - `UNIVIEW`: heartbeat 30s, refresh 60s, subscribe expires 3000

You can extend profile-specific branches in:

- `Gb28181ServerSimulator`
- `Gb28181ClientSimulator`
- `Config` vendor helper functions
