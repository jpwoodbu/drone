# DUML Protocol Upstream Sources & Citations

This repository caches reverse-engineered DUML v1 protocol specifications to
accelerate development and eliminate repetitive web lookups. The protocol
details documented in this directory were derived from the following
authoritative upstream sources:

---

## 1. Primary Open-Source Reverse Engineering Repositories

### `o-gs/dji-firmware-tools`

The primary open-source reference for DJI binary communications, packet
structures, and Wireshark dissectors.

- **Repository:**
  [https://github.com/o-gs/dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools)
- **Key Dissector Files (`comm_dissector/wireshark/`):**
  - **`dji-dumlv1-proto.lua`**: Core DUML v1 packet routing, CRC-8 seed
    (`0x77`), CRC-16 seed (`0x3692`), AOA envelope framing (`0x55 0xCC`), and
    `SPECIAL_UART_CMD_TEXT` (`0x01`) mappings:
    - `0x20`: `SDK Ctrl Camera Shot Ctrl` (Photo capture)
    - `0x21`: `SDK Ctrl Camera Start Video Ctrl` (Start video recording)
    - `0x22`: `SDK Ctrl Camera Stop Video Ctrl` (Stop video recording)
  - **`dji-dumlv1-camera.lua`**: Camera command set (`0x02`),
    `CAMERA_UART_CMD_TEXT`, push state info (`0x80`), work modes, and SD card
    state definitions.
  - **`dji-dumlv1-flyc.lua`**: Flight controller command set (`0x03`), OSD
    telemetry framing (`0x43`), coordinate scalings, and automated function
    controls (`0x2A`).
  - **`dji-dumlv1-gimbal.lua`**: Gimbal command set (`0x04`), attitude push
    status (`0x05`), and angle/speed commands.
- **CLI Tools:**
  - `comm_serialtalk.py`: Serial/UART packet sender.
  - `comm_mkdupc.py`: DUML packet builder and checksum generator.
- **Firmware Symbol Maps (`symbols/`):**
  - **`P3X_FW_V01.11.0030_m0100_part_sys.map` / `.idc`**: IDA Pro symbol tables
    extracted from the Ambarella A9 camera system partition. Confirmed internal
    DUML message dispatching functions for the camera:
    - `dji_msg_do_capture`: Function handler dispatched for `cmdSet = 0x02`,
      `cmdId = 0x01` (`[0x01]` Single Photo).
    - `dji_msg_do_record`: Function handler dispatched for `cmdSet = 0x02`,
      `cmdId = 0x02` (`[0x01]` Start, `[0x00]` Stop).
    - `dji_msg_camera_work_mode`: Function handler dispatched for
      `cmdSet = 0x02`, `cmdId = 0x10` (`[0x00]` Photo, `[0x01]` Video).

---

## 2. Research & Academic Protocol Analyses

### Christian Doppler Laboratory Digidow / Johannes Kepler University Linz

- **Author:** Thomas Christof
- **Thesis:** _DJI Wi-Fi Protocol Reverse Engineering_ (Master's Thesis,
  Johannes Kepler University Linz, 2021)
- **Domain:** [https://digidow.eu](https://digidow.eu)
- **Publication Link:**
  [https://digidow.eu/publications/theses/master_thesis_christof.pdf](https://digidow.eu/publications/theses/master_thesis_christof.pdf)
- **Contributions:**
  - Reverse-engineered the Wi-Fi UDP encapsulation of DUML frames on consumer
    aircraft.
  - Independently verified DUML command dispatching tables, specifically
    `[Cmd(513, 0x02, "Camera", 0x01, "Do Capture Photo")]`
    (`Camera_DoCapturePhoto = 0x0201`), `Camera_DoRecord = 0x0202`, and
    `Camera_SetMode = 0x0210`.
  - Documented packet sniffer and filter predicate implementations for isolating
    DUML telemetry streams.

---

## 3. DJI Mobile SDK Internal Middleware

### `dji.midware` Android SDK Architecture

Decompiled DJI Android applications (`DJI GO 4`, `DJI Pilot`) contain the
internal packet framing and serialization classes under `dji.midware`:

- **`dji.midware.data.model.P3`**:
  - `DataRcGetPushParams`: RC stick channel decoding and byte 12 button bitmask
    (`0x80` Record, `0x40` Shutter, `0x10` Fn/C1, `0x08` C2, `0x01` RTH).
  - `DataCameraGetPushStateInfo`: Camera operational status, recording timer,
    photo states, and micro-SD capacity bytes.
  - `DataFlycGetPushFlightRecord` / `DataFlycGetPushDeport`: Flight controller
    OSD data.
  - `DataSmartBatteryGetPushDynamicData`: Smart battery BMS fuel-gauge
    telemetry.
  - `DataFlycFunctionControl`: Auto-Takeoff, Auto-Landing, and RTH initiation
    commands.
- **`dji.midware.data.config.P3`**:
  - `CmdSet`: Subsystem command set enumerations.
  - `CmdIdCamera`, `CmdIdFlyc`, `CmdIdGimbal`, `CmdIdBattery`: Internal command
    ID definitions.

---

## 4. Official DJI Onboard SDK Open Protocol

### DJI Onboard SDK (OSDK)

- **Documentation:** [https://developer.dji.com](https://developer.dji.com) /
  [https://github.com/dji-sdk/Onboard-SDK](https://github.com/dji-sdk/Onboard-SDK)
- **Contributions:**
  - Control Command Set (`0x01`): Open Protocol specification for external
    companion computers.
  - Explicit confirmation of `0x20` (Take Photo), `0x21` (Start Video
    Recording), and `0x22` (Stop Video Recording) with 1-byte command parameter.

---

## 5. Independent Native Clean-Room Implementations

### `sworrl/GlassFalcon`

An open-source (GPL-3.0), vendor-independent Android ground-control application
and SDK that communicates directly with DJI aircraft over USB using the native
DUML protocol (without DJI accounts, cloud infrastructure, or the official DJI
Mobile SDK).

- **Repository:**
  [https://github.com/sworrl/GlassFalcon](https://github.com/sworrl/GlassFalcon)
- **Key Implementation Files
  (`android/sdk/src/main/kotlin/dev/glassfalcon/core/`):**
  - **`DumlCommands.kt`**: Houses command builders for camera, flight control
    (FlyC), and gimbal subsystems. Contains reverse-engineered packet layouts
    derived from live kprobe captures during genuine `DJI GO 4` USB sessions
    with `wm240` (Mavic 2 / Mavic Air family).
  - **`Duml.kt`**: DUML framing, CRC-8 / CRC-16 implementations, and transport
    abstraction across USB (Android Open Accessory and CDC-ACM) and TCP.
- **Contributions to DUML Understanding:**
  - Resolved the distinction between packet routing destination
    (`destinationModuleId = MODULE_CAMERA (0x01)`) and the embedded `commandSet`
    byte:
    - **Native Camera Subsystem (`commandSet = 0x02`)**: All native Ambarella
      camera commands reside on `0x02`, including `0x02 / 0x01`
      (`Do Capture Photo`, payload `[0x01]` Single Photo), `0x02 / 0x02`
      (`Do Record`, payload `[0x01]` Start, `[0x00]` Stop), and `0x02 / 0x10`
      (`Work Mode Set`, payload `[0x00]` Photo, `[0x01]` Video).
    - **Special App/Remote Control (`commandSet = 0x01`)**: Early
      implementations mistakenly assumed camera actions belonged under
      `commandSet = 0x01` due to an erroneous comment header in
      `dji-dumlv1-camera.lua`. `0x01` represents `SPECIAL_UART_CMD_TEXT`
      (`0x01 / 0x01` App Control, `0x01 / 0x02` Remote Control), which does not
      trigger physical camera recording or photo capture.
  - Explained why Onboard SDK companion computer commands (`0x01 / 0x20`,
    `0x21`, `0x22`) are rejected by camera firmware with error code `0xE0`
    (`DJI_ERROR_COMMAND_NOT_SUPPORTED`) when sent from mobile app endpoints
    (`MODULE_MOBILE_APP = 0x02`).

---

## 6. Workflow for Future Protocol Additions

If a future feature requires a DUML packet that is not yet documented in
`docs/duml/`:

1. Check the local files in `docs/duml/` first to see if related commands exist
   in that subsystem.
2. If looking up an unmapped command or payload offset, search directly inside
   `o-gs/dji-firmware-tools` (specifically
   `comm_dissector/wireshark/dji-dumlv1-*.lua`) or `sworrl/GlassFalcon`
   (`DumlCommands.kt`).
3. For mobile-originated commands, check the corresponding
   `dji.midware.data.model.P3.Data*` class mapping in the decompiled DJI SDK.
4. Once verified on hardware or in unit tests, append the findings to the
   appropriate markdown file in `docs/duml/` so future AI agents and developers
   have instant local access.
