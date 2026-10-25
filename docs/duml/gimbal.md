# DUML Gimbal Subsystem (`MODULE_GIMBAL = 0x04`)

The Camera Gimbal subsystem controls the 3-axis motorized mechanical
stabilization platform, compensates for aircraft attitude roll/pitch changes,
and directs camera pitch tilt.

---

## 1. Gimbal Telemetry Push (`CMD_GIMBAL_PUSH_STATUS`)

- **Source Module ID:** `MODULE_GIMBAL` (`0x04`)
- **Destination Module ID:** `MODULE_MOBILE_APP` (`0x02`) or Broadcast (`0x00`)
- **Command Set:** `0x04` (`MODULE_GIMBAL`)
- **Command ID:** `0x05` (`CMD_GIMBAL_PUSH_STATUS`) or `0x01`
  (`CMD_GIMBAL_CONTROL`)
- **Command Type:** `0x00` (Push / Request without ACK)
- **Transmission Rate:** ~10–20 Hz

### Payload Structure

| Byte Offset   | Size    | Type     | Field Name | Description                                                                                                                                                                                                               |
| :------------ | :------ | :------- | :--------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `0x00`–`0x01` | 2 bytes | Int16 LE | `pitchRaw` | Gimbal physical pitch angle in tenths of a degree (`0.1°`).<br>• `0.0°`: Level horizon<br>• `-90.0°`: Straight down pointing at ground (nadir)<br>• `+15.0°..+30.0°`: Upward tilt (on extended gimbal range)              |
| `0x02`–`0x03` | 2 bytes | Int16 LE | `rollRaw`  | Gimbal physical roll angle in tenths of a degree (`0.1°`). Horizon stabilization keeps this near `0.0°`.                                                                                                                  |
| `0x04`–`0x05` | 2 bytes | Int16 LE | `yawRaw`   | Gimbal physical yaw angle in tenths of a degree (`0.1°`) relative to the aircraft nose.                                                                                                                                   |
| `0x06`        | 1 byte  | UInt8    | `mode`     | Stabilization operating mode:<br>• `0`: **Follow Mode** (Yaw follows aircraft heading smoothly)<br>• `1`: **FPV Mode** (Gimbal roll/yaw locks to aircraft attitude)<br>• `2`: **Free Mode** (Gimbal points independently) |

---

## 2. Gimbal Angle Scaling & Limits

- **Pitch Angle:** `pitchDegrees = pitchRaw * 0.1f`
  - Valid physical range: `-135.0°` to `+90.0°`
  - Standard user tilt range: `-90.0°` (downward) to `0.0°` (horizontal)
- **Roll Angle:** `rollDegrees = rollRaw * 0.1f`
  - Valid physical range: `-90.0°` to `+90.0°`
- **Yaw Angle:** `yawDegrees = yawRaw * 0.1f`
  - Valid physical range: `-360.0°` to `+360.0°`

---

## 3. Outbound Gimbal Control

Gimbal attitude and speed can be commanded via either native gimbal packets or
SDK control commands:

### Commands

1. **Native Gimbal Control (`0x04 / 0x01`):**
   - Direct target angle setting for pitch/roll/yaw.
2. **SDK Gimbal Speed Control (`0x01 / 0x1A`):**
   - Velocity-based rotation rate (deg/sec).
3. **SDK Gimbal Angle Control (`0x01 / 0x1B`):**
   - Absolute or relative angle repositioning.

### Hardware Gimbal Dial Mapping

The physical left-index gimbal dial on the remote controller streams continuous
position values in `DataRcGetPushParams` (`0x06 / 0x05`, bytes `8..9`):

- Center detent: `1024`
- Range: `364` (full counter-clockwise / pitch down) to `1684` (full clockwise /
  pitch up)
