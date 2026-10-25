# DUML Flight Controller Subsystem (`MODULE_FLIGHT_CONTROLLER = 0x03`)

The Flight Controller (FlyC autopilot MCU) is the core flight safety component.
It streams high-frequency On-Screen Display (OSD) telemetry and processes
critical navigation and automated flight commands.

---

## 1. Flight Controller OSD Telemetry (`0x03 / 0x43`)

- **Source Module ID:** `MODULE_FLIGHT_CONTROLLER` (`0x03`)
- **Destination Module ID:** `MODULE_MOBILE_APP` (`0x02`) or Broadcast (`0x00`)
- **Command Set:** `0x03` (`MODULE_FLIGHT_CONTROLLER`)
- **Command ID:** `0x43` (`CMD_FLIGHT_CONTROLLER_OSD`)
- **Command Type:** `0x00` (Push / Request without ACK)
- **Transmission Rate:** ~10 Hz

### Payload Structure

| Byte Offset   | Size    | Type       | Field Name         | Description                                                                                                                                                                                        |
| :------------ | :------ | :--------- | :----------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `0x00`–`0x07` | 8 bytes | Float64 LE | `longitudeRadians` | Longitude in radians. Convert via `rad * 180.0 / PI`.                                                                                                                                              |
| `0x08`–`0x0F` | 8 bytes | Float64 LE | `latitudeRadians`  | Latitude in radians. Convert via `rad * 180.0 / PI`.                                                                                                                                               |
| `0x10`–`0x11` | 2 bytes | Int16 LE   | `relativeAltitude` | Barometric altitude relative to takeoff in decimeters (`0.1 m`).                                                                                                                                   |
| `0x12`–`0x13` | 2 bytes | Int16 LE   | `velocityX`        | North/South horizontal velocity in cm/s (`0.01 m/s`).                                                                                                                                              |
| `0x14`–`0x15` | 2 bytes | Int16 LE   | `velocityY`        | East/West horizontal velocity in cm/s (`0.01 m/s`).                                                                                                                                                |
| `0x16`–`0x17` | 2 bytes | Int16 LE   | `velocityZ`        | Vertical climb/descent velocity in cm/s (`0.01 m/s`). Positive = climb.                                                                                                                            |
| `0x18`–`0x19` | 2 bytes | Int16 LE   | `pitch`            | Aircraft pitch angle in tenths of a degree (`0.1°`).                                                                                                                                               |
| `0x1A`–`0x1B` | 2 bytes | Int16 LE   | `roll`             | Aircraft roll angle in tenths of a degree (`0.1°`).                                                                                                                                                |
| `0x1C`–`0x1D` | 2 bytes | Int16 LE   | `yaw`              | Aircraft true compass heading in tenths of a degree (`0.1°`). Range `-1800..1800`.                                                                                                                 |
| `0x1E`        | 1 byte  | UInt8      | `flightMode`       | Flight mode integer code (see state table below).                                                                                                                                                  |
| `0x20`–`0x23` | 4 bytes | UInt32 LE  | `statusFlags`      | Bitmask for aircraft operational state:<br>• **Bit `2` (`0x04`)**: Motors running (`motorsOn`)<br>• **Bit `3` (`0x08`)**: Aircraft airborne (`inAir`)<br>• **Bit `4` (`0x10`)**: Sport mode active |
| `0x24`        | 1 byte  | UInt8      | `gpsSatellites`    | Number of locked GPS/GLONASS satellites.                                                                                                                                                           |
| `0x26`–`0x27` | 2 bytes | Int16 LE   | `ultrasonicHeight` | Downward VPS (Visual Positioning System) height in decimeters (`0.1 m`).                                                                                                                           |
| `0x28`        | 1 byte  | UInt8      | `batterySoc`       | Flight controller fallback battery percentage estimate (`0..100%`).                                                                                                                                |

---

## 2. Flight Mode State Machine (`Byte 30`)

The mode byte (`payload[30]`) encodes the active flight controller state:

|    Code    | Mode String        | Description                                                           |
| :--------: | :----------------- | :-------------------------------------------------------------------- |
|    `0`     | `MANUAL`           | Full manual acrobatic flight (no attitude stabilization).             |
|  `1`, `2`  | `ATTI`             | Altitude stabilization only; drift with wind (no GPS positioning).    |
|  `3`, `4`  | `ATTI-COURSE-LOCK` | Attitude hold with locked orientation heading.                        |
|  `5`, `6`  | `P-GPS`            | Position mode (GPS + GLONASS + VPS optical flow). Normal flight mode. |
|    `7`     | `GPS-CL`           | GPS positioning with Course Lock.                                     |
|    `8`     | `GPS-HL`           | GPS positioning with Home Lock.                                       |
|    `9`     | `GPS-HOTPOINT`     | Point of Interest (orbit around GPS point).                           |
| `10`, `11` | `AUTO-TAKEOFF`     | Automated motor start and vertical climb to 1.2 m hover.              |
|    `12`    | `AUTO-LAND`        | Automated vertical descent and ground motor cutoff.                   |
|    `13`    | `ATTI-LAND`        | Descent landing in attitude mode.                                     |
|    `14`    | `WAYPOINT`         | Autonomous waypoint navigation mission.                               |
|    `15`    | `RTH`              | Return-to-Home navigation to recorded GPS home point.                 |
|    `16`    | `CLICK-GO`         | Tap-to-fly to designated coordinates on map.                          |
|    `17`    | `JOYSTICK`         | Mobile virtual joystick / SDK velocity control.                       |
|    `23`    | `ATTI-LIMITED`     | Attitude mode with ceiling/geofence altitude limits.                  |
|    `24`    | `DRAW`             | Path flight along drawn line on screen.                               |
|    `25`    | `FOLLOW-ME`        | GPS tether following mobile device coordinates.                       |
|    `26`    | `ACTIVE-TRACK`     | Vision-based subject tracking.                                        |
|    `27`    | `TAP-FLY`          | Autonomous vector flight toward screen tap.                           |
|    `28`    | `PANO`             | Automated panorama flight maneuver.                                   |
|    `31`    | `SPORT`            | High-speed sport mode (obstacle avoidance sensors disabled).          |
|    `32`    | `NOVICE`           | Beginner mode with strict distance and altitude geofence.             |
|    `33`    | `FORCE-LAND`       | Emergency critical low-battery forced descent.                        |
|    `35`    | `TERRAIN-FOLLOW`   | Downward sensor distance maintenance.                                 |
|    `36`    | `PALM-CONTROL`     | Gesture palm launch / land control.                                   |
|    `37`    | `QUICK-SHOT`       | Automated cinematic flight paths (Dronie, Rocket, Helix, Asteroid).   |
|    `38`    | `TRIPOD`           | Cine/Tripod mode with dampened stick response (1 m/s max).            |
|    `41`    | `MOTORS-START`     | Combination Stick Command (CSC) motor spin-up before takeoff.         |

---

## 3. Automated Function Control (`0x03 / 0x2A`)

Command Set `0x03`, Command ID `0x2A` (`CMD_FLIGHT_CONTROLLER_FUNCTION_CONTROL`)
executes primary autopilot maneuvers:

- **Source Module ID:** `MODULE_MOBILE_APP` (`0x02`)
- **Destination Module ID:** `MODULE_FLIGHT_CONTROLLER` (`0x03`)
- **Command Set:** `0x03`
- **Command ID:** `0x2A`
- **Command Type:** `0x40` (`COMMAND_TYPE_REQUEST_WITH_ACK`)

### Payload Values (`1 byte`)

| Payload Byte | Maneuver                 | Behavior                                                                      |
| :----------: | :----------------------- | :---------------------------------------------------------------------------- |
|    `0x01`    | **Auto Takeoff**         | Arms motors and climbs automatically to 1.2 m hover.                          |
|    `0x02`    | **Auto Landing**         | Initiates controlled vertical descent and disarms motors upon ground contact. |
|    `0x03`    | **Return-to-Home**       | Climbs to configured RTH altitude and flies back to GPS Home Point.           |
|    `0x04`    | **Cancel Landing / RTH** | Aborts active autonomous descent or RTH flight and resumes manual hover.      |

---

## 4. Safety & Anti-Interference Rules

> [!CAUTION] **Outbound Traffic Safety Rule:** Outbound command traffic sent to
> aircraft modules (especially during flight or motor start) can interfere with
> flight-critical hardware routines.
>
> 1. **Do not send parameter writes or unvetted commands** to
>    `MODULE_FLIGHT_CONTROLLER` (`0x03`) or `MODULE_CENTER_BOARD` (`0x05`). For
>    example, sending experimental parameter query `0x00 / 0x05` to the center
>    board acts as a configuration write that actively aborts the motor-start
>    routine (CSC stick command).
> 2. **Prefer passive telemetry parsing**: Parse all flight data, attitudes, GPS
>    coordinates, and flight modes from incoming unsolicited `0x03 / 0x43` OSD
>    pushes rather than polling.
> 3. Only transmit outbound commands when explicitly commanded by the pilot
>    (such as initiating `Auto-Land` via holding the physical `Fn` button for 2
>    seconds).
