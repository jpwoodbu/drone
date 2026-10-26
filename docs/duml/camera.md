# DUML Camera Subsystem (`MODULE_CAMERA = 0x01`)

The Camera subsystem (Ambarella imaging board and sensor) manages photo capture,
video recording, media file storage on the micro-SD card, and periodic state
broadcasts.

---

## 1. Camera State Push Telemetry (`DataCameraGetPushStateInfo`)

- **Source Module ID:** `MODULE_CAMERA` (`0x01`)
- **Destination Module ID:** `MODULE_MOBILE_APP` (`0x02`) or Broadcast (`0x00`)
- **Command Set:** `0x02` (`COMMAND_SET_CAMERA`) or `0x01`
- **Command ID:** `0x80` (`CMD_CAMERA_PUSH_STATE_INFO`) or `0x01`
  (`CMD_CAMERA_GET_PUSH_STATE_INFO_ALT`)
- **Command Type:** `0x00` (Push / Request without ACK)
- **Transmission Rate:** ~1–2 Hz

### Payload Structure

| Byte Offset   | Size    | Type      | Field Name               | Description                                                                                                                                          |
| :------------ | :------ | :-------- | :----------------------- | :--------------------------------------------------------------------------------------------------------------------------------------------------- |
| `0x00`        | 1 byte  | UInt8     | `stateByte0`             | • **Bits `3..5`**: Photo capture state (`0`=Idle, `1`=Single, etc.)<br>• **Bits `6..7`**: Video recording state (`1` or `2` = Recording, `0` = Idle) |
| `0x01`        | 1 byte  | UInt8     | `stateByte1`             | • **Bit `1`**: SD card present flag (`1` = Card inserted, `0` = No card)<br>• **Bits `2..5`**: SD card state code (`0`=Normal, `8`=Full, etc.)       |
| `0x02`–`0x03` | 2 bytes | UInt16 LE | `reserved`               | Camera subsystem internal status flags                                                                                                               |
| `0x04`        | 1 byte  | UInt8     | `workMode`               | Camera operating mode:<br>• `0`: Shoot Photo (Capture)<br>• `1`: Record Video<br>• `2`: Media Playback<br>• `3`: Media Download                      |
| `0x05`–`0x08` | 4 bytes | Int32 LE  | `sdCardTotalCapacityMb`  | Total formatted capacity of the micro-SD card in Megabytes (MB)                                                                                      |
| `0x09`–`0x0C` | 4 bytes | Int32 LE  | `sdCardRemainCapacityMb` | Remaining free space on the micro-SD card in Megabytes (MB)                                                                                          |
| `0x0D`–`0x10` | 4 bytes | Int32 LE  | `remainPhotoNum`         | Estimated remaining photo shots before SD storage is exhausted                                                                                       |
| `0x11`–`0x14` | 4 bytes | Int32 LE  | `remainVideoTimer`       | Estimated remaining video record duration in seconds at current resolution                                                                           |
| `0x15`        | 1 byte  | UInt8     | `fileIndexMode`          | DCF file numbering convention (`0` = Reset, `1` = Sequence)                                                                                          |
| `0x16`        | 1 byte  | UInt8     | `fastPlayBackInfo`       | Fast playback mode configuration flags                                                                                                               |
| `0x17`–`0x18` | 2 bytes | UInt16 LE | `photoOsdInfo`           | OSD display visibility overlay bitmask                                                                                                               |
| `0x19`–`0x1A` | 2 bytes | Bytes     | `reserved`               | Internal DSP state                                                                                                                                   |
| `0x1B`        | 1 byte  | UInt8     | `inDebugMode`            | Factory debugging enabled flag                                                                                                                       |
| `0x1C`        | 1 byte  | UInt8     | `reserved`               | Reserved                                                                                                                                             |
| `0x1D`–`0x1E` | 2 bytes | UInt16 LE | `recordTimeSeconds`      | Active recording elapsed time in seconds (`0` when idle). Offset 29 decimal                                                                          |
| `0x1F`        | 1 byte  | UInt8     | `maxPhotoNum`            | Maximum burst capture shot count                                                                                                                     |
| `0x20`        | 1 byte  | UInt8     | `histogramEnable`        | Real-time luminance histogram enable flag                                                                                                            |
| `0x21`        | 1 byte  | UInt8     | `cameraType`             | Hardware sensor and lens identifier (`13` = FC6310, etc.). Offset 33 decimal                                                                         |
| `0x22`–`0x23` | 2 bytes | Bytes     | `reserved`               | Reserved                                                                                                                                             |
| `0x24`        | 1 byte  | UInt8     | `version`                | State payload format revision                                                                                                                        |
| `0x25`–`0x26` | 2 bytes | Bytes     | `padding`                | Trailing word alignment padding on 39-byte payloads                                                                                                  |

> [!NOTE] **Offset `0x15` (`21`) vs `0x1D` (`29`) for Recording Time:** On 37-
> and 39-byte extended payloads used across consumer drones (such as the Mavic
> series), byte offset `0x15` (`21`) is `fileIndexMode` (which is typically set
> to `0x01` for sequential file numbering). Reading offset `21` as an elapsed
> time causes the recording timer to freeze at `00:01`. The true active elapsed
> recording timer is stored as a 16-bit little-endian integer at offset `0x1D`
> (`29`–`30`). Legacy 24-byte payloads from older firmware (such as early
> Phantom 3) did not include the intervening OSD configuration fields and
> located `recordTimeSeconds` at offset `21`.

---

## 2. Status Code Lookup Tables

### SD Card State (`Byte 1, Bits 2..5`)

| Value | State Description      | Meaning / User Impact                                                             |
| :---: | :--------------------- | :-------------------------------------------------------------------------------- |
|  `0`  | **Normal**             | SD card is healthy, mounted, and ready for read/write operations.                 |
|  `1`  | **None**               | No micro-SD card detected in the aircraft slot.                                   |
|  `2`  | **Invalid**            | Hardware error reading card; card unreadable.                                     |
|  `3`  | **Write Protected**    | Card write lock is active; cannot record or take photos.                          |
|  `4`  | **Unformatted**        | Filesystem unrecognized; needs formatting before use.                             |
|  `5`  | **Formatting**         | Low-level file system format operation in progress.                               |
|  `6`  | **Illegal Filesystem** | Incompatible partition table or cluster size (e.g. NTFS).                         |
|  `7`  | **Busy**               | Camera buffer flushing to storage.                                                |
|  `8`  | **Full**               | Micro-SD card is completely full; recording cannot start.                         |
|  `9`  | **Slow**               | Card write speed class insufficient for current bitrate (e.g. non-U3 card at 4K). |
| `10`  | **Unknown**            | Unclassified storage status.                                                      |
| `11`  | **No Remain Indices**  | DCF file numbering sequence exhausted (`999_9999`).                               |
| `12`  | **Index Max**          | Directory index counter maxed out.                                                |
| `13`  | **Initializing**       | Card mounting and checking FAT tables on power up.                                |
| `14`  | **To Format**          | Aircraft recommends formatting to recover performance.                            |
| `15`  | **Recovering File**    | Repairing broken MP4 header after unexpected power loss.                          |

### Photo Capture State (`Byte 0, Bits 3..5`)

| Value | Mode Description | Behavior                                                             |
| :---: | :--------------- | :------------------------------------------------------------------- |
|  `0`  | **Idle**         | Camera shutter is idle.                                              |
|  `1`  | **Single**       | Single image capture in progress.                                    |
|  `2`  | **Burst**        | Continuous high-speed burst capture sequence.                        |
|  `3`  | **Interval**     | Periodic timed interval shooting active (timelapse photography).     |
|  `4`  | **HDR**          | Multiple exposure brackets being captured and merged.                |
|  `5`  | **Panorama**     | Automated gimbal step-and-shoot panorama sequence (e.g. 360 sphere). |
|  `6`  | **Raw Burst**    | DNG raw burst capture sequence.                                      |
|  `7`  | **HyperLapse**   | Moving timelapse with automated flight path.                         |

---

## 3. Outbound Camera Control Commands

All native camera subsystem commands in DUML route to `MODULE_CAMERA` (`0x01`)
under Native Camera Command Set `0x02` (`COMMAND_SET_CAMERA` /
`CAMERA_UART_CMD_TEXT`).

> [!IMPORTANT] **Command Set `0x02` vs Legacy Misattribution (`0x01`):**
> Outbound camera control packets always route to `MODULE_CAMERA`
> (`destinationModuleId = 0x01`). The embedded `commandSet` byte in DUML frames
> must be set to `0x02` (`COMMAND_SET_CAMERA`).
>
> In the Wireshark dissector (`dji-firmware-tools`), command set `0x01` is
> designated as `SPECIAL_UART_CMD_TEXT` (`Old Special App Control` `0x01`,
> `Old Special Remote Control` `0x02`, etc.). Early reverse-engineering projects
> (including initial commits in `sworrl/GlassFalcon`) mistakenly dispatched
> camera commands under `commandSet = 0x01` due to an erroneous comment header
> in `dji-dumlv1-camera.lua`. On physical drone hardware, packets dispatched
> under `0x01 / 0x02` are dropped without acknowledgment, while `0x01 / 0x01` is
> consumed by the special app-control layer without triggering physical camera
> shutter exposure.
>
> In the Ambarella camera firmware message dispatcher, `dji_msg_do_capture` is
> triggered by `0x02 / 0x01` and `dji_msg_do_record` is triggered by
> `0x02 / 0x02`.

---

### Video Recording Control

Video recording is initiated and stopped using Native Camera Command Set `0x02`:

#### Start Video Recording

To start video recording, dispatch this packet wrapped in an AOA envelope
(`0x55 0xCC 0x49 0x57`):

- **Destination Module ID:** `MODULE_CAMERA` (`0x01`)
- **Command Type:** `0x40` (`COMMAND_TYPE_REQUEST_WITH_ACK`)
- **Command Set:** `0x02` (`COMMAND_SET_CAMERA`)
- **Command ID:** `0x02` (`CMD_CAMERA_DO_RECORD`)
- **Payload:** `[0x01]` (`CAMERA_RECORD_ACTION_START`)

#### Stop Video Recording

To stop video recording:

- **Destination Module ID:** `MODULE_CAMERA` (`0x01`)
- **Command Type:** `0x40` (`COMMAND_TYPE_REQUEST_WITH_ACK`)
- **Command Set:** `0x02` (`COMMAND_SET_CAMERA`)
- **Command ID:** `0x02` (`CMD_CAMERA_DO_RECORD`)
- **Payload:** `[0x00]` (`CAMERA_RECORD_ACTION_STOP`)

---

### Photo Snapshot Control

To trigger still photo capture:

- **Destination Module ID:** `MODULE_CAMERA` (`0x01`)
- **Command Type:** `0x40` (`COMMAND_TYPE_REQUEST_WITH_ACK`)
- **Command Set:** `0x02` (`COMMAND_SET_CAMERA`)
- **Command ID:** `0x01` (`CMD_CAMERA_DO_CAPTURE_PHOTO`)
- **Payload:** `[0x01]` (`CAMERA_PHOTO_ACTION_SINGLE`)

---

### Camera Work Mode Configuration

To switch the camera operating mode between photo capture and video recording:

- **Destination Module ID:** `MODULE_CAMERA` (`0x01`)
- **Command Type:** `0x40` (`COMMAND_TYPE_REQUEST_WITH_ACK`)
- **Command Set:** `0x02` (`COMMAND_SET_CAMERA`)
- **Command ID:** `0x10` (`CMD_CAMERA_SET_WORK_MODE`)
- **Payload:**
  - `[0x00]`: Shoot Photo Mode (`CAMERA_WORK_MODE_CAPTURE`)
  - `[0x01]`: Record Video Mode (`CAMERA_WORK_MODE_RECORD`)

---

### Camera Status Queries

#### Video Recording Status Query

To query the current video recording status:

- **Destination Module ID:** `MODULE_CAMERA` (`0x01`)
- **Command Type:** `0x40` (`COMMAND_TYPE_REQUEST_WITH_ACK`)
- **Command Set:** `0x02` (`COMMAND_SET_CAMERA`)
- **Command ID:** `0x11` (`CMD_CAMERA_RECORD_VIDEO_QUERY`)
- **Payload:** Empty (`[]`) or query flags
- **ACK Response:** Returns `0x00` with payload `[0x00, 0x01]` indicating active
  video recording state.

---

## 4. Hardware Interaction: Front Arm LED Auto-Suppression

When a photo snapshot is triggered or video recording is active, the aircraft
center board / flight controller automatically turns off the red navigation LEDs
on the front motor arms:

- **During Photo Capture:** The front red LEDs extinguish instantaneously when
  the shutter command (`0x02 / 0x01`) is accepted and exposure begins, then
  re-illuminate once single or burst capture completes.
- **During Video Recording:** The front red LEDs turn off as soon as video
  recording starts (`0x02 / 0x02` with payload `[0x01]`) and remain off for the
  entire recording session. They turn back on immediately when recording is
  stopped (`0x02 / 0x02` with payload `[0x00]`).
- **Purpose:** Prevents red light wash and internal lens flare/reflections from
  corrupting photos or video footage, especially in low light or when flying
  close to reflective surfaces.
- **Autonomous Coordination:** No external DUML command to the flight controller
  (`MODULE_FLIGHT_CONTROLLER`) is required from the mobile client; this
  suppression is coordinated autonomously by the aircraft firmware over internal
  CAN/UART buses between the camera and flight controller boards.
