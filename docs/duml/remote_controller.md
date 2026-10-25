# DUML Remote Controller Subsystem (`0x06`)

The Remote Controller subsystem communicates user stick inputs, wheel movements,
and physical button presses to the mobile device over USB AOA on logical channel
`0x5749`.

---

## 1. Remote Controller Push Telemetry (`DataRcGetPushParams`)

- **Source Module ID:** `MODULE_REMOTE_CONTROLLER` (`0x06`)
- **Destination Module ID:** `MODULE_MOBILE_APP` (`0x02`) or Broadcast (`0x00`)
- **Command Set:** `0x06` (`MODULE_REMOTE_CONTROLLER`)
- **Command ID:** `0x05` (`CMD_RC_GET_PUSH_PARAMS`)
- **Command Type:** `0x00` (Push / Request without ACK)
- **Transmission Rate:** ~10–20 Hz over USB AOA

### Payload Structure

| Byte Offset   | Size    | Type      | Field Name             | Description                                                                                      |
| :------------ | :------ | :-------- | :--------------------- | :----------------------------------------------------------------------------------------------- |
| `0x00`–`0x01` | 2 bytes | UInt16 LE | `rightStickHorizontal` | Channel 0: Roll (Aileron). Range `364..1684`, centered at `1024`. Left `< 1024`, Right `> 1024`. |
| `0x02`–`0x03` | 2 bytes | UInt16 LE | `rightStickVertical`   | Channel 1: Pitch (Elevator). Range `364..1684`, centered at `1024`. Down `< 1024`, Up `> 1024`.  |
| `0x04`–`0x05` | 2 bytes | UInt16 LE | `leftStickVertical`    | Channel 2: Throttle. Range `364..1684`, centered at `1024`. Down `< 1024`, Up `> 1024`.          |
| `0x06`–`0x07` | 2 bytes | UInt16 LE | `leftStickHorizontal`  | Channel 3: Yaw (Rudder). Range `364..1684`, centered at `1024`. Left `< 1024`, Right `> 1024`.   |
| `0x08`–`0x09` | 2 bytes | UInt16 LE | `gimbalWheel`          | Channel 4: Gimbal pitch tilt dial. Range `364..1684`, centered at `1024`.                        |
| `0x0A`–`0x0B` | 2 bytes | UInt16 LE | `flightModeSwitch`     | Flight mode / gear switch hardware position.                                                     |
| `0x0C`        | 1 byte  | UInt8     | `buttonBitmask`        | Physical push button bitmask flags (see below).                                                  |
| `0x0D`        | 1 byte  | UInt8     | `hardwareStatus`       | Hardware link and battery warning flags.                                                         |

---

## 2. Physical Button Bitmask (`Byte 12`)

Byte `12` (`0x0C`) of the payload contains the instantaneous digital state of
all physical push buttons:

```
  7       6       5       4       3       2       1       0
+-------+-------+-------+-------+-------+-------+-------+-------+
| Record|Shutter| Play- |   Fn  |   C   | Resv/ | Pause |  RTH  |
| Video | Photo | back  | Custom1 Custom2 Wheel | Flight| Return|
+-------+-------+-------+-------+-------+-------+-------+-------+
```

|    Bit    | Hex Mask | Flag Constant                | Button Description                                                                    |
| :-------: | :------: | :--------------------------- | :------------------------------------------------------------------------------------ |
| **Bit 7** |  `0x80`  | `RC_BUTTON_MASK_RECORD`      | **Video Record Button**: Left-shoulder button. `1` when pressed, `0` when released.   |
| **Bit 6** |  `0x40`  | `RC_BUTTON_MASK_SHUTTER`     | **Photo Shutter Button**: Right-shoulder button. `1` when pressed, `0` when released. |
| **Bit 5** |  `0x20`  | `RC_BUTTON_MASK_PLAYBACK`    | **Playback Button**: Opens media gallery on DJI GO.                                   |
| **Bit 4** |  `0x10`  | `RC_BUTTON_MASK_FUNCTION`    | **Front `Fn` Button (`Custom1`)**: Front face left button. Used for Auto-Land hold.   |
| **Bit 3** |  `0x08`  | `RC_BUTTON_MASK_CUSTOM2`     | **Right-shoulder `C` Button (`Custom2`)**: Secondary customizable button.             |
| **Bit 2** |  `0x04`  | `RC_BUTTON_MASK_WHEEL_CLICK` | Gimbal dial push / click (on supported controller hardware).                          |
| **Bit 1** |  `0x02`  | `RC_BUTTON_MASK_PAUSE`       | **Flight Pause Button**: Instant brake / emergency hover.                             |
| **Bit 0** |  `0x01`  | `RC_BUTTON_MASK_GO_HOME`     | **Physical Return-to-Home (RTH) Button**: `1` when pressed.                           |

---

## 3. Remote Controller Operational Architecture

### Why Software Must Command the Camera

On consumer Wi-Fi airlink models (such as the DJI Mavic Air with the `S01A`
controller):

- The remote controller acts solely as an input transducer. It does **not**
  autonomously construct or dispatch camera recording or photo capture DUML
  packets over the wireless airlink.
- The remote streams raw stick and button status packets to the mobile host
  application (`DJI GO 4` or `:bridge`).
- The mobile application is responsible for detecting button press transitions
  (rising edges), validating camera readiness (e.g., verifying an SD card is
  inserted and ready), and dispatching the camera record start/stop command to
  `MODULE_CAMERA` (`0x01`).
- If the mobile app does not decode Byte `12` Bit `7` (`0x80`) and dispatch the
  camera command, the aircraft will never record video, will not beep, and will
  not write media files to the SD card.
