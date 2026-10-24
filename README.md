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

> [!IMPORTANT] This project was developed, tested, and verified **only** using
> the original **DJI Mavic Air (`WM230`)** and its **`S01A` remote controller**.
> No other aircraft or remote controllers have been tested.
>
> The information below outlines potential compatibility or incompatibility
> based on publicly documented protocol characteristics shared across DJI's
> product lineup.

The `:bridge` app connects to DJI remote controllers using the **Android Open
Accessory (AOA 2.0)** protocol. The USB accessory descriptor filter
(`accessory_filter.xml`) matches any USB device with `manufacturer="DJI"`.

### 1. Likely Compatible (DJI GO 4 & DJI GO Era)

These models are believed to share the same USB AOA multiplex framing, DUML v1
telemetry structures, and H.264 video streams as the Mavic Air reference
platform:

| Product Family           | Aircraft Models                                                         | Remote Controller            | Protocol Notes                                                                               |
| :----------------------- | :---------------------------------------------------------------------- | :--------------------------- | :------------------------------------------------------------------------------------------- |
| **Mavic Series**         | **Mavic Air** (`WM230`)                                                 | `S01A`                       | Reference platform used for development and flight verification.                             |
|                          | **Mavic Pro** & **Mavic Pro Platinum** (`WM220`)                        | `GL200A`                     | Expected to share the same AOA multiplex framing and DUML telemetry.                         |
|                          | **Mavic 2 Pro** & **Mavic 2 Zoom** (`WM240`)                            | `RC1A`, `RC1B`               | Expected to share the same AOA multiplex framing and DUML telemetry.                         |
|                          | **Mavic 2 Enterprise** (Dual / Zoom / Advanced)                         | `RC1A`, `RC1B`               | Expected to share the same AOA multiplex framing and DUML telemetry.                         |
| **Spark & Mini**         | **DJI Spark** (`WM100`)                                                 | `GL100A`                     | Requires a USB OTG cable; expected to share the same AOA multiplex framing and DUML telemetry. |
|                          | **Mavic Mini** (`WM160`) & **DJI Mini SE**                              | `MR1SS5`, `MR1SD2`           | Expected to share the same AOA multiplex framing and DUML telemetry.                         |
| **Phantom Series**       | **Phantom 4** (Standard / Advanced / Pro / V2.0 / RTK)                  | `GL300E`, `GL300F`, `GL300K` | Expected to share the same AOA multiplex framing and DUML telemetry.                         |
|                          | **Phantom 3 Advanced** & **Phantom 3 Professional**                     | `GL300A`, `GL300B`, `GL300C` | Expected to share the same AOA multiplex framing and DUML telemetry.                         |
| **Inspire Series**       | **Inspire 1** / **Inspire 1 Pro/RAW** (`T600`)                          | `GL658A`, `GL658B`           | Expected to share the same AOA multiplex framing and DUML telemetry.                         |
|                          | **Inspire 2** (`WM620`)                                                 | `GL6D10A`                    | Expected to share the same AOA multiplex framing and DUML telemetry.                         |
| **Matrice (Enterprise)** | **Matrice 100**, **Matrice 200 / 210 / 210 RTK**, **Matrice 600 / Pro** | `AG410`, `T600`, `GL6D10A`   | Expected to share the same AOA multiplex framing and DUML telemetry.                         |

### 2. Possible Compatibility (Modern DJI Fly with `RC-N1` / `RC-N2` / `RC-N3`)

Modern consumer drones flown using standard phone-clamp USB remote controllers:

- **Aircraft Models**: **Mini 2**, **Mini 3 / Mini 3 Pro**, **Mini 4 Pro**,
  **Mavic Air 2**, **Air 2S**, **Air 3 / 3S**, **Mavic 3 / 3 Classic / 3 Pro**.
- **Remote Controllers**: **`RC-N1`**, **`RC-N2`**, **`RC-N3`**.
- **Potential Compatibility Factors**:
  - **USB & Video**: These controllers also negotiate Android Open Accessory
    (AOA) and stream H.264 video chunks. Note that if a newer aircraft transmits
    video in H.265/HEVC instead of H.264, the hardware video decoder in `:core`
    would need to be updated to handle `video/hevc`.
  - **Telemetry**: Core passive telemetry (coordinates, relative altitude,
    ground speed, attitude angles, satellites, battery) may be
    backwards-compatible, but newer models often use extended payload offsets
    that have not been tested with this parser.

### 3. Incompatible Hardware

The `:bridge` app is not expected to be compatible with:

- **Integrated-Screen Remotes (`DJI RC`, `DJI RC 2`, `DJI RC Pro`,
  `Smart Controller`)**: These remotes feature a built-in display and internal
  Android OS. There is no external phone connected via USB AOA (though the
  `:bridge` app could theoretically be sideloaded directly onto the controller).
- **Direct-Phone Wi-Fi Drones**: Aircraft flown directly from a phone over Wi-Fi
  without a physical remote controller (**Tello**, **Spark** in phone-only mode,
  **Phantom 3 Standard**, **DJI Neo** in phone-only mode).
- **DJI FPV & Avata Goggles (`Goggles V2`, `Goggles 2`, `Goggles 3`,
  `Integra`)**: While DJI Goggles include a USB-C port for audience viewing,
  they utilize a proprietary USB bulk streaming protocol rather than the
  standard AOA accessory mode.

## Building and Testing

### Prerequisites

- Android Studio Ladybug or newer.
- JDK 17 (or bundled Android Studio JBR).
- Android SDK with platform API 34+ (compile SDK 37).

### Build Commands

Run all unit tests and static analysis:

```bash
./gradlew check
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
