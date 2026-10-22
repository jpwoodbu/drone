# Drone: Mavic Air USB Bridge & Mirror

A native, multi-module Android application suite for operating the DJI Mavic Air
(`U11X` / `wm230`) using its native `S01A` remote controller over Android Open
Accessory (AOA 2.0) USB without requiring the official DJI Mobile SDK, cloud
accounts, or internet connectivity.

The system streams live 720p H.264 video and flight telemetry across a local
Wi-Fi link to a companion device (e.g. Meta Quest flat app / not VR).

This app is meant to solve these problems:

- The Mavic Air remote control phone clamps are too small for modern phones.
- The DJI-made Android applications for the Mavic Air may not work on modern
  Android phones.
- Phone screens are difficult to see in bright sunlight.

The intended mode of operation is that you will fly the drone with the DJI
remote control with it connected via USB to an Android phone in your pocket
running the bridge app. A VR headset like a Meta Quest, runs the mirror app
while being connected via Wi-Fi to your phone. The VR headset will be able to
display in 2D the telemetry and video feed from the drone.

Note that the OS on the Meta Quest headsets does not include the needed software
to connect to a USB device like the DJI remote control. If it did, it would be
interesting to connect straight from the Meta Quest to the DJI remote control
via USB.

---

## Architecture

The project is structured into three clean, symmetric Gradle modules:

```
drone/
├── core/     # Pure Kotlin/Android protocol library (:core)
├── bridge/   # USB AOA host and UDP network bridge (:bridge)
└── mirror/   # Low-latency video viewer (:mirror)
```

1. **`:core` (`one.woodbury.drone.core`)**:
   - Implements DUML v1 (DJI Universal Markup Language) packet framing, parsing,
     and serialization.
   - Calculates DUML CRC-8 (polynomial `0x77`) and CRC-16 (polynomial `0x3692`)
     checksums.
   - Demultiplexes AOA outer frames (`0x55 0xCC`) into DUML telemetry
     (`0x49 0x57`) and Annex-B H.264 video streams (`0x4A 0x57`).
   - Implements the lightweight UDP packetization and discovery protocol over
     port `5600`.

2. **`:bridge` (`one.woodbury.drone.bridge`)**:
   - Runs on the Android device tethered to the `S01A` remote controller via
     USB.
   - Uses Android's `UsbManager` AOA 2.0 interface to stream control packets and
     incoming video without proprietary native libraries.
   - Advertises the bridge service on the local network via mDNS
     (`_drone-bridge._udp.`).
   - Actively polls the aircraft BMS (Battery Management System) at 1 Hz for
     state-of-charge percentage.
   - Runs an Android foreground service (`BridgeForegroundService`) with CPU and
     Wi-Fi locks to maintain low-latency streaming even when the phone screen is
     turned off.

3. **`:mirror` (`one.woodbury.drone.mirror`)**:
   - Runs on another Android device (e.g. Meta Quest headset).
   - Discovers the phone bridge automatically over the local Wi-Fi subnet via
     mDNS and transmits 1 Hz keepalive heartbeats to port `5600`.
   - Decodes H.264 video using hardware `MediaCodec` and displays it onto a
     Compose/Surface texture.
   - Renders live telemetry including aircraft BMS battery percentage, flight
     controller OSD metrics, GPS satellites, orientation angles, and velocities.

---

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
unofficial references as a push request, was removed because command set
`0x00` command `0x05` acts as a configuration/parameter write on DJI hardware
and actively aborted the flight controller's motor-start routine (CSC stick
command). Likewise, battery queries directed at the flight controller
(`MODULE_FLIGHT_CONTROLLER` `0x03`) were eliminated. Only read-only GET queries
targeting the battery BMS directly are transmitted. Synthetic linear
estimation from voltage curves was deliberately avoided to ensure accurate
remaining capacity reporting across varying flight loads and battery wear.
Last-known BMS percentages are preserved across high-frequency cell voltage
packets (`0x03`).

### Camera Gimbal Telemetry

Camera gimbal telemetry (physical pitch, roll, and yaw angles) is decoded
passively from unsolicited DUML push packets (`CMD_GIMBAL_PUSH_STATUS` =
`0x05` from `MODULE_GIMBAL` `0x04`). Angles are encoded as 16-bit signed
little-endian integers with $0.1^\circ$ precision ($0^\circ$ corresponds to
level horizon, and negative angles down to $-90^\circ$ correspond to tilting
downward toward nadir).

To guarantee flight safety and avoid bus contention or flight controller
interference, no active outbound polling queries are issued for gimbal
telemetry. The telemetry streams passively across both `:bridge` and `:mirror`.

### Altitude Units and High-Altitude Ceiling Warning

The `:mirror` application supports displaying relative altitude in either
meters or international feet:

- **Meters Mode**: Formatted with 1 decimal place (e.g. `12.5 m`).
- **Feet Mode**: Formatted as integer feet (e.g. `400 ft`) to optimize HUD
  spacing.
- **Unit Selection**: Can be toggled via the Settings dialog (`⚙`) or by
  directly tapping the `ALTITUDE` metric in the top HUD bar. The preference is
  persisted across app sessions.
- **Ceiling Warning**: When relative altitude reaches or exceeds $400\text{ ft}$
  ($121.92\text{ m}$), the altitude number turns red (`#F85149`) to alert the
  pilot to standard ceiling limits.

---

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
