# G10 BLE protocol notes

## Observed GATT profile

### Standard services

- GAP `1800`
- GATT `1801`
- Device Information `180A`
- Battery `180F`

### Control/transport service

- Service `FFF0`
- Characteristic `FFF1`: Write / Write Without Response (do not use in v0.1)
- Characteristic `FFF2`: Read / Notify

### Firmware update service

Observed proprietary/OAD-style service:

- `F000FFC0-0451-4000-B000-000000000000`
- `F000FFC1-0451-4000-B000-000000000000` — Img Identify
- `F000FFC2-0451-4000-B000-000000000000` — Img Block

v0.1 intentionally ignores this entire service.

## Device info observed in LightBlue

- Manufacturer Name: `BEKEN SAS`
- Model Number: `BK-BLE-1.0`
- Hardware Revision: `1.0.0`
- Firmware Revision: `6.1.2`
- Software Revision: `6.3.0`

## Reverse-engineering plan

1. Passive FFF2 notifications only.
2. Record timestamp + HEX + length.
3. Change one physical state at a time using only normal scooter controls.
4. Compare byte positions and message cadence.
5. Do not infer semantics from a single packet.
6. Do not send write commands until frame format, checksum and command semantics are verified.
7. Keep OTA/OAD permanently isolated from normal telemetry code.

## v0.2 passive analyzer

The app now records packet length, byte indexes changed relative to the previous FFF2 packet, and a user-selected test marker. This is intended to correlate observed state changes without sending control commands.
