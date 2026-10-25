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
| `0x15`–`0x16` | 2 bytes | UInt16 LE | `recordTimeSeconds`      | Active recording elapsed time in seconds (`0` when idle)                                                                                             |
| `0x17`        | 1 byte  | UInt8     | `cameraType`             | Hardware sensor and lens identifier                                                                                                                  |
| `0x1D`–`0x1E` | 2 bytes | UInt16 LE | `altRecordTime`          | Offset 29 fallback for active recording elapsed time on 31+ byte payloads                                                                            |

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

When commanding the camera subsystem to start/stop video recording or snap a
photo, DUML supports both the **SDK Special Command Set (`0x01`)** and the
**Native Camera Command Set (`0x02`)**.

### Video Recording Control

#### Start Video Recording

To start video recording, dispatch these packets wrapped in AOA envelopes
(`0x55 0xCC 0x49 0x57`):

1. **SDK Special Start (`0x01 / 0x21`) -> Camera (`0x01`):**
   - `destinationModuleId`: `MODULE_CAMERA` (`0x01`)
   - `commandType`: `0x40` (`COMMAND_TYPE_REQUEST_WITH_ACK`)
   - `commandSet`: `0x01` (`COMMAND_SET_SPECIAL`)
   - `commandId`: `0x21` (`CMD_SPECIAL_CAMERA_START_RECORD`)
   - `payload`: `[0x00]`
2. **Native Camera Record (`0x02 / 0x11`) -> Camera (`0x01`):**
   - `destinationModuleId`: `MODULE_CAMERA` (`0x01`)
   - `commandType`: `0x40` (`COMMAND_TYPE_REQUEST_WITH_ACK`)
   - `commandSet`: `0x02` (`COMMAND_SET_CAMERA`)
   - `commandId`: `0x11` (`CMD_CAMERA_RECORD_VIDEO`)
   - `payload`: `[0x01]` (`0x01` = Start, `0x00` = Stop)
3. **SDK Special Start (`0x01 / 0x21`) -> Flight Controller (`0x03`):**
   - `destinationModuleId`: `MODULE_FLIGHT_CONTROLLER` (`0x03`)
   - `commandType`: `0x40`
   - `commandSet`: `0x01`
   - `commandId`: `0x21`
   - `payload`: `[0x00]` _(Used on firmware variants where external SDK commands
     route through the autopilot)._

#### Stop Video Recording

To stop video recording:

1. **SDK Special Stop (`0x01 / 0x22`) -> Camera (`0x01`):**
   - `destinationModuleId`: `MODULE_CAMERA` (`0x01`)
   - `commandSet`: `0x01` (`COMMAND_SET_SPECIAL`)
   - `commandId`: `0x22` (`CMD_SPECIAL_CAMERA_STOP_RECORD`)
   - `payload`: `[0x00]`
2. **Native Camera Record (`0x02 / 0x11`) -> Camera (`0x01`):**
   - `destinationModuleId`: `MODULE_CAMERA` (`0x01`)
   - `commandSet`: `0x02` (`COMMAND_SET_CAMERA`)
   - `commandId`: `0x11` (`CMD_CAMERA_RECORD_VIDEO`)
   - `payload`: `[0x00]` (`0x00` = Stop)
3. **SDK Special Stop (`0x01 / 0x22`) -> Flight Controller (`0x03`):**
   - `destinationModuleId`: `MODULE_FLIGHT_CONTROLLER` (`0x03`)
   - `commandSet`: `0x01`
   - `commandId`: `0x22`
   - `payload`: `[0x00]`

---

### Photo Snapshot Control

To trigger still photo capture:

1. **SDK Special Shot (`0x01 / 0x20`) -> Camera (`0x01`):**
   - `destinationModuleId`: `MODULE_CAMERA` (`0x01`)
   - `commandSet`: `0x01` (`COMMAND_SET_SPECIAL`)
   - `commandId`: `0x20` (`CMD_SPECIAL_CAMERA_TAKE_PHOTO`)
   - `payload`: `[0x00]`
2. **Native Camera Take Photo (`0x02 / 0x10`) -> Camera (`0x01`):**
   - `destinationModuleId`: `MODULE_CAMERA` (`0x01`)
   - `commandSet`: `0x02` (`COMMAND_SET_CAMERA`)
   - `commandId`: `0x10` (`CMD_CAMERA_TAKE_PHOTO`)
   - `payload`: `[0x01]`
3. **SDK Special Shot (`0x01 / 0x20`) -> Flight Controller (`0x03`):**
   - `destinationModuleId`: `MODULE_FLIGHT_CONTROLLER` (`0x03`)
   - `commandSet`: `0x01`
   - `commandId`: `0x20`
   - `payload`: `[0x00]`
