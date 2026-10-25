# DUML Module Identifiers & Addressing

In DUML v1, each packet header specifies a **Source Module ID** (byte `0x04`)
and a **Destination Module ID** (byte `0x05`).

---

## 1. Module ID Byte Structure

A module ID byte encodes both the target subsystem class and an optional
instance index:

```
 7   6   5   4   3   2   1   0
+---+---+---+---+---+---+---+---+
|  Device   |    Device Type    |
|   Index   |     (0..31)       |
+---+---+---+---+---+---+---+---+
```

- **Device Type (`bits 0..4`)**: The hardware category (`0x00` through `0x1F`).
- **Device Index (`bits 5..7`)**: Zero-based instance number (e.g., `0` for
  primary camera, `1` for secondary camera).

---

## 2. Complete Device Type Reference

| Device Type             | Hex    | Decimal | Constant Name               | Description                                       |
| :---------------------- | :----- | :------ | :-------------------------- | :------------------------------------------------ |
| **Broadcast / Any**     | `0x00` | 0       | `MODULE_BROADCAST`          | Addressed to all listening modules on the bus     |
| **Camera**              | `0x01` | 1       | `MODULE_CAMERA`             | Ambarella / imaging processor subsystem           |
| **Mobile App**          | `0x02` | 2       | `MODULE_MOBILE_APP`         | Mobile application (DJI GO 4, Pilot, or Bridge)   |
| **Flight Controller**   | `0x03` | 3       | `MODULE_FLIGHT_CONTROLLER`  | Main autopilot MCU (FlyC)                         |
| **Gimbal**              | `0x04` | 4       | `MODULE_GIMBAL`             | Camera gimbal controller                          |
| **Center Board**        | `0x05` | 5       | `MODULE_CENTER_BOARD`       | Internal power distribution and bus routing board |
| **Remote Controller**   | `0x06` | 6       | `MODULE_REMOTE_CONTROLLER`  | Ground remote controller MCU                      |
| **Wi-Fi Air Side**      | `0x07` | 7       | `MODULE_WIFI_AIR`           | Aircraft Wi-Fi transceiver chip                   |
| **DM36x Air Side**      | `0x08` | 8       | `MODULE_DM36X_AIR`          | Video compression / streaming chip on aircraft    |
| **HD Link MCU Air**     | `0x09` | 9       | `MODULE_VIDEO_TRANSMISSION` | Aircraft OcuSync / Lightbridge transmission MCU   |
| **PC Assistant**        | `0x0A` | 10      | `MODULE_PC_ASSISTANT`       | DJI Assistant 2 software over USB                 |
| **Smart Battery**       | `0x0B` | 11      | `MODULE_BATTERY`            | Intelligent Flight Battery BMS MCU                |
| **Motor ESC**           | `0x0C` | 12      | `MODULE_ESC`                | Electronic Speed Controller board                 |
| **DM36x Ground Side**   | `0x0D` | 13      | `MODULE_DM36X_GROUND`       | Video decompression / display MCU on ground side  |
| **HD Link MCU Ground**  | `0x0E` | 14      | `MODULE_HD_LINK_GROUND`     | Remote controller OcuSync MCU                     |
| **USB Ctrl Air Side**   | `0x0F` | 15      | `MODULE_USB_AIR`            | Aircraft USB OTG controller                       |
| **USB Ctrl Ground**     | `0x10` | 16      | `MODULE_USB_GROUND`         | Remote controller USB AOA interface               |
| **Monocular Vision**    | `0x11` | 17      | `MODULE_MONO_VISION`        | Front/rear optical flow and obstacle sensing      |
| **Binocular Vision**    | `0x12` | 18      | `MODULE_STEREO_VISION`      | Stereo vision depth calculation system            |
| **HD Link FPGA Air**    | `0x13` | 19      | `MODULE_FPGA_AIR`           | Aircraft SDR / baseband FPGA                      |
| **HD Link FPGA Ground** | `0x14` | 20      | `MODULE_FPGA_GROUND`        | Ground station / controller baseband FPGA         |
| **Simulator**           | `0x15` | 21      | `MODULE_SIMULATOR`          | PC flight simulation interface                    |
| **Base Station**        | `0x16` | 22      | `MODULE_BASE_STATION`       | D-RTK base station                                |
| **Airborne RTK**        | `0x17` | 23      | `MODULE_AIR_RTK`            | High-precision RTK GPS module on aircraft         |
| **RC Battery**          | `0x18` | 24      | `MODULE_RC_BATTERY`         | Remote controller internal battery circuit        |

---

## 3. Command Types (`byte 0x08`)

The command type byte defines message exchange semantics:

```
 7   6   5   4   3   2   1   0
+---+---+---+---+---+---+---+---+
| R | A | Encrypt / Session /   |
| E | C |    Sequence flags     |
| S | K |                       |
+---+---+---+---+---+---+---+---+
```

- **`0x00` (Push / Broadcast)**: Asynchronous status update sent without waiting
  for an acknowledgment. Used for high-frequency telemetry (e.g. `0x03 / 0x43`
  Flight Controller OSD, `0x06 / 0x05` RC sticks).
- **`0x40` (Request with ACK)**: Unicast command request that expects the
  receiver to execute the operation and respond with an ACK frame.
- **`0x80` (Response / ACK)**: Acknowledgement reply indicating execution status
  (typically `0x00` for success, or a non-zero error code).
