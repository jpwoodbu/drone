# Drone: Mavic Air USB Bridge & Mirror

A multi-module Android project (i.e. two applications) for operating the DJI
Mavic Air (possibly other DJI models too) using a VR headset for viewing the
camera feed and telemetry, and the handheld DJI controller for flight control.
These apps do not use the official DJI Mobile SDK, they do not require any cloud
accounts or internet connectivity and they do not collect any data.

The reason there are two applications is because the Meta Quest headsets (my
target VR device) do not support connecting directly to the handheld DJI
controller. The **bridge** app is meant to run on a phone connected to the DJI
controller via USB. It collects the video feed and telemetry data and relays it
via Wi-Fi to the **mirror** app, which is meant to run on a Meta Quest VR
headset. The **bridge** app can run in the background and works with your phone
screen turned off and your phone in your pocket.

This project is meant to solve these problems:

- The Mavic Air remote control phone clamps are too small for modern phones.
- The official DJI Android applications for the Mavic Air may not work on modern
  Android phones.
- Phone screens are difficult to see in bright sunlight.

The **mirror** app is a standard flat 2D Android app. It could technically run
on Android devices other than a VR headset. There's not any clear value in
making it a VR immersive app since the video feed from the Mavic Air is not
stereoscopic and it's helpful to use the passthrough feature on Meta Quest
headsets to see your surroundings, including the drone itself, while having the
**mirror** app only occupy a small portion of your field of view.

## Architecture

The project is structured into three Gradle modules:

```
drone/
├── core/     # Protocol library (:core)
├── bridge/   # USB AOA host and UDP network bridge (:bridge)
└── mirror/   # Telemetry and video viewer (:mirror)
```

1. **`:core`**:
   - Implements DUML v1 (DJI Universal Markup Language) packet framing, parsing,
     validation, and serialization.
   - Demultiplexes DJI's Android Open Accessory (AOA) envelope into DUML
     telemetry and H.264 video streams.
   - Implements the UDP packetization and discovery protocol over port `5600`.

2. **`:bridge`**:
   - Runs on the Android device connected to the `S01A` remote controller via
     USB.
   - Uses Android's `UsbManager` AOA 2.0 interface to stream control packets and
     incoming video.
   - Advertises the bridge service on the local network via mDNS
     (`_drone-bridge._udp.`).
   - Actively polls the aircraft BMS (Battery Management System) at 1 Hz for
     state-of-charge percentage.
   - Runs an Android foreground service (`BridgeForegroundService`) with CPU and
     Wi-Fi locks to maintain low-latency streaming even when the phone screen is
     turned off.

3. **`:mirror`**:
   - Runs on another Android device (e.g. Meta Quest VR headset).
   - Discovers the phone bridge automatically over the local Wi-Fi via mDNS and
     transmits 1 Hz keepalive heartbeats to port `5600`.
   - Decodes H.264 video using hardware `MediaCodec` and displays it onto a
     Compose/Surface texture.
   - Renders live drone telemetry (e.g. battery state, altitude/attitude, GPS
     visibility state, etc.)

## DJI Product Compatibility

The `:bridge` app connects to DJI remote controllers using the **Android Open
Accessory (AOA 2.0)** protocol. The USB accessory descriptor filter
(`accessory_filter.xml`) matches any USB device with `manufacturer="DJI"`, as
well as specific legacy model strings (`WM230`, `T600`, `AG410`,
`com.dji.logiclink`, `WM160`).

Because DJI retained this USB multiplexing and telemetry architecture across
multiple drone generations, the `:bridge` app is expected to work with a wide
range of DJI hardware:

### 1. Guaranteed / Plug-and-Play Compatibility (DJI GO 4 & DJI GO Era)

These models share the exact same AOA multiplex framing, DUML v1 telemetry
structure, and H.264 video stream as the Mavic Air reference platform:

| Product Family           | Aircraft Models                                                         | Remote Controller            | Protocol Notes                                                                                   |
| :----------------------- | :---------------------------------------------------------------------- | :--------------------------- | :----------------------------------------------------------------------------------------------- |
| **Mavic Series**         | **Mavic Air** (`WM230`)                                                 | `S01A`                       | Current reference platform. This is the hardware used during this project's initial development. |
|                          | **Mavic Pro** & **Mavic Pro Platinum** (`WM220`)                        | `GL200A`                     | Identical AOA multiplexing and DUML telemetry packets.                                           |
|                          | **Mavic 2 Pro** & **Mavic 2 Zoom** (`WM240`)                            | `RC1A`, `RC1B`               | OcuSync 2.0 with standard 1080p H.264 video stream.                                              |
|                          | **Mavic 2 Enterprise** (Dual / Zoom / Advanced)                         | `RC1A`, `RC1B`               | Same USB AOA and DUML telemetry foundation.                                                      |
| **Spark & Mini**         | **DJI Spark** (`WM100`)                                                 | `GL100A`                     | Supported when connecting the remote to the phone via USB/OTG cable.                             |
|                          | **Mavic Mini** (`WM160`) & **DJI Mini SE**                              | `MR1SS5`, `MR1SD2`           | Enhanced Wi-Fi remote; outputs DUML v1 and H.264 over USB AOA.                                   |
| **Phantom Series**       | **Phantom 4** (Standard / Advanced / Pro / V2.0 / RTK)                  | `GL300E`, `GL300F`, `GL300K` | Standard remotes with USB-A output ports.                                                        |
|                          | **Phantom 3 Advanced** & **Phantom 3 Professional**                     | `GL300A`, `GL300B`, `GL300C` | Lightbridge over USB (matches `model="T600"` filter).                                            |
| **Inspire Series**       | **Inspire 1** / **Inspire 1 Pro/RAW** (`T600`)                          | `GL658A`, `GL658B`           | The origin of the `model="T600"` accessory filter.                                               |
|                          | **Inspire 2** (`WM620`)                                                 | `GL6D10A`                    | Same Lightbridge 2 AOA multiplex framing and DUML stream.                                        |
| **Matrice (Enterprise)** | **Matrice 100**, **Matrice 200 / 210 / 210 RTK**, **Matrice 600 / Pro** | `AG410`, `T600`, `GL6D10A`   | Matches `model="AG410"` and `model="T600"` filters.                                              |

### 2. High Compatibility (Modern DJI Fly with `RC-N1` / `RC-N2` / `RC-N3`)

Modern consumer drones flown using standard phone-clamp USB remote controllers:

- **Aircraft Models**: **Mini 2**, **Mini 3 / Mini 3 Pro**, **Mini 4 Pro**,
  **Mavic Air 2**, **Air 2S**, **Air 3 / 3S**, **Mavic 3 / 3 Classic / 3 Pro**.
- **Remote Controllers**: **`RC-N1`**, **`RC-N2`**, **`RC-N3`**.
- **Compatibility Status**:
  - **USB & Video**: These controllers continue to use Android Open Accessory
    (AOA) and stream standard H.264 LiveView video chunks.
  - **Telemetry**: Core passive telemetry (GPS coordinates, relative altitude,
    ground speed, Euler attitude angles, satellites, battery) is
    backwards-compatible. Newer aircraft include extended payload fields, but
    our parser in `DumlProtocol.kt` safely reads standard offsets.
  - **Codec Note**: If a newer drone is configured to transmit video exclusively
    in H.265/HEVC rather than H.264, the hardware video decoder in
    `H264VideoDecoder.kt` would need to instantiate `video/hevc` rather than
    `video/avc`.

### 3. Incompatible Hardware

The bridge app is **not** compatible with:

- **Integrated-Screen Remotes (`DJI RC`, `DJI RC 2`, `DJI RC Pro`,
  `Smart Controller`)**: These remotes feature a built-in display and internal
  Android OS. There is no external phone connected via USB AOA (though the
  `:bridge` or `:mirror` apps could theoretically be sideloaded directly onto
  the controller).
- **Direct-Phone Wi-Fi Drones**: Aircraft flown directly from a phone over Wi-Fi
  without a physical remote controller (**Tello**, **Spark** in phone-only mode,
  **Phantom 3 Standard**, **DJI Neo** in phone-only mode). These communicate
  over Wi-Fi UDP sockets rather than a USB remote.
- **DJI FPV & Avata Goggles (`Goggles V2`, `Goggles 2`, `Goggles 3`,
  `Integra`)**: While DJI Goggles include a USB-C port for audience viewing,
  they utilize a proprietary USB bulk streaming protocol rather than the
  standard AOA accessory mode.

## Design Decisions & Notes

### Remote Controller Battery Telemetry

Adding remote controller battery level telemetry to both the `:bridge` and
`:mirror` applications was explored. However:

- The DUML v1 encoding, response structures, and polling commands for remote
  controller internal battery telemetry (associated with command set `0x06` /
  command `0x1E` or destination module `0x18`) are not sufficiently documented
  across open-source protocol references.
- Between the physical remote controller displaying its battery level directly
  on its front panel using four dedicated LEDs and the remote sounding distinct
  audible warning tones when the battery runs low, the pilot already receives
  ample warning before the remote powers down.
- Consequently, software decoding of the remote controller battery level was
  abandoned in favor of relying on the hardware's built-in visual and audible
  indicators.

### Aircraft Battery Telemetry

Aircraft battery telemetry uses the genuine State of Charge (SoC) percentage
reported directly by the DJI Intelligent Flight Battery BMS via active 1 Hz
polling queries (`CMD_BATTERY_DYNAMIC_DATA` = `0x0D / 0x02` addressed
specifically to `MODULE_BATTERY` `0x0B`).

An earlier experimental query sending `0x00 / 0x05` with a payload of `0x01` to
the center board (`MODULE_CENTER_BOARD` `0x05`), misdocumented in some
unofficial references as a push request, was removed because command set `0x00`
command `0x05` acts as a configuration/parameter write on DJI hardware and
actively aborted the flight controller's motor-start routine (CSC stick
command). Likewise, battery queries directed at the flight controller
(`MODULE_FLIGHT_CONTROLLER` `0x03`) were eliminated. Only read-only GET queries
targeting the battery BMS directly are transmitted. Synthetic linear estimation
from voltage curves was deliberately avoided to ensure accurate remaining
capacity reporting across varying flight loads and battery wear. Last-known BMS
percentages are preserved across high-frequency cell voltage packets (`0x03`).

### Camera Gimbal Telemetry

Camera gimbal telemetry (physical pitch, roll, and yaw angles) is decoded
passively from unsolicited DUML push packets (`CMD_GIMBAL_PUSH_STATUS` = `0x05`
from `MODULE_GIMBAL` `0x04`). Angles are encoded as 16-bit signed little-endian
integers with $0.1^\circ$ precision ($0^\circ$ corresponds to level horizon, and
negative angles down to $-90^\circ$ correspond to tilting downward toward
nadir).

To guarantee flight safety and avoid bus contention or flight controller
interference, no active outbound polling queries are issued for gimbal
telemetry. The telemetry streams passively across both `:bridge` and `:mirror`.

### Altitude Units and High-Altitude Ceiling Warning

The `:mirror` application supports displaying relative altitude in either meters
or international feet:

- **Meters Mode**: Formatted with 1 decimal place (e.g. `12.5 m`).
- **Feet Mode**: Formatted as integer feet (e.g. `400 ft`) to optimize HUD
  spacing.
- **Unit Selection**: Can be toggled via the Settings dialog (`⚙`) or by
  directly tapping the `ALTITUDE` metric in the top HUD bar. The preference is
  persisted across app sessions.
- **Ceiling Warning**: When relative altitude reaches or exceeds $400\text{ ft}$
  ($121.92\text{ m}$), the altitude number turns red (`#F85149`) to alert the
  pilot to standard ceiling limits.

### USB Accessory Descriptor Placeholders

When connecting over Android Open Accessory (AOA 2.0), Android requires the USB
host accessory to supply identifying descriptor strings (manufacturer, model,
description, version, URI, and serial number).

DJI remote controllers hardcode dummy placeholder constants for the version
(`v1.2.3.4`) and serial number (`12345678`) fields during the initial USB
control transfer handshake. Because these values are static placeholders rather
than authentic hardware or firmware identifiers, they are omitted from the
`:bridge` diagnostic user interface. Genuine module firmware versions are
instead parsed dynamically from inbound DUML version push packets and queries.

## Building and Testing

### Prerequisites

- Android Studio Ladybug or newer.
- JDK 17 (or bundled Android Studio JBR).
- Android SDK with platform API 34+ (compile SDK 37).

### Build Commands

Run unit tests across all modules:

```bash
./gradlew testDebugUnitTest
```

Run Android lint and static analysis:

```bash
./gradlew lintDebug
```

Assemble debug APKs:

```bash
./gradlew assembleDebug
```

Generated APKs:

- Bridge App: `bridge/build/outputs/apk/debug/bridge-debug.apk`
- Mirror App: `mirror/build/outputs/apk/debug/mirror-debug.apk`

## Disclaimers & Legal Notices

### Aviation Safety & Regulatory Compliance

Operating unmanned aircraft systems (drones) involves inherent operational and
safety risks. Pilots are solely responsible for ensuring safe operation of their
aircraft in compliance with all applicable local laws, civil aviation authority
regulations (such as FAA Part 107 or Recreational regulations in the United
States), airspace authorizations, and Visual Line of Sight (VLOS) requirements.
Do not operate aircraft in a manner that endangers people, property, or other
airspace users.

### Disclaimer of Liability

This software is provided "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE, AND NONINFRINGEMENT. In no event shall the author or
contributors be liable for any direct, indirect, incidental, special, exemplary,
or consequential damages (including, but not limited to, loss of aircraft,
crashes, flyaways, hardware damage, personal injury, or property damage) however
caused and on any theory of liability, whether in contract, strict liability, or
tort (including negligence or otherwise) arising in any way out of the use of
this software, even if advised of the possibility of such damage.

### Trademarks

"DJI", "Mavic", "Spark", "Phantom", "Inspire", "OcuSync", "Lightbridge", and
other associated marks are registered trademarks of SZ DJI Technology Co., Ltd.
This is an independent open-source project that is neither affiliated with,
authorized, sponsored, endorsed, nor supported by SZ DJI Technology Co., Ltd.

## Acknowledgments

This project is made possible thanks to the protocol research and documentation
shared by the open-source community.
