# DUML Smart Battery Subsystem (`MODULE_BATTERY = 0x0B`)

The Intelligent Flight Battery integrates a Texas Instruments Battery Management
System (BMS) MCU (`MODULE_BATTERY = 0x0B`) that tracks true State of Charge
(SoC), individual cell voltages, pack discharge current, temperature, and cycle
counts.

---

## 1. Battery BMS Commands

- **Destination Module ID:** `MODULE_BATTERY` (`0x0B`)
- **Command Set:** `0x0D` (`COMMAND_SET_BATTERY`) or `0x0B`
- **Primary Commands:**
  - `CMD_BATTERY_DYNAMIC_DATA` (`0x02`): Real-time pack voltage, current,
    capacity, temperature, and SoC percentage.
  - `CMD_BATTERY_CELL_VOLTAGE` (`0x03`): Millivolt measurements for individual
    battery series cells (e.g. Cell 1, 2, 3 on a 3S pack).
  - `CMD_BATTERY_COMMON` (`0x06`): General manufacturing and lifetime
    statistics.

---

## 2. Dynamic Data Query (`0x0D / 0x02`)

To poll the genuine battery BMS State of Charge directly from the battery pack:

- **Source:** `MODULE_MOBILE_APP` (`0x02`)
- **Destination:** `MODULE_BATTERY` (`0x0B`)
- **Command Type:** `0x40` (`COMMAND_TYPE_REQUEST_WITH_ACK`)
- **Command Set:** `0x0D`
- **Command ID:** `0x02`
- **Payload:** `[]` (empty payload)
- **Polling Rate:** 1 Hz read-only query

### Payload Memory Layouts

DJI firmware variants structure the binary response payload in one of three
layouts. Parsers must check sizes and plausibility ranges:

#### Layout A: 32-Bit Capacities with 2-Byte Prefix (Offset = 2)

_(Common on Mavic Air, Mavic Pro, Spark)_

| Byte Offset   | Size    | Type      | Field Name             | Description                                                              |
| :------------ | :------ | :-------- | :--------------------- | :----------------------------------------------------------------------- |
| `0x00`–`0x01` | 2 bytes | UInt16 LE | `headerPrefix`         | Sub-packet header / status word                                          |
| `0x02`–`0x05` | 4 bytes | Int32 LE  | `packVoltageMv`        | Total pack terminal voltage in millivolts (`3,000..35,000 mV`)           |
| `0x06`–`0x09` | 4 bytes | Int32 LE  | `currentMa`            | Instantaneous current in milliamps. Negative indicates discharge         |
| `0x0A`–`0x0D` | 4 bytes | Int32 LE  | `fullCapacityMah`      | Full charge design/learned capacity in milliamp-hours (`mAh`)            |
| `0x0E`–`0x11` | 4 bytes | Int32 LE  | `remainingCapacityMah` | Remaining usable capacity in milliamp-hours (`mAh`)                      |
| `0x12`–`0x13` | 2 bytes | Int16 LE  | `temperatureRaw`       | Internal thermistor temperature in tenths of a degree Celsius (`0.1 °C`) |
| `0x14`        | 1 byte  | UInt8     | `cellCount`            | Number of series cells in pack (e.g. `3` for Mavic Air 3S)               |
| `0x15`        | 1 byte  | UInt8     | `stateOfCharge`        | Authentic BMS State of Charge (`0..100%`)                                |
| `0x16`–`0x17` | 2 bytes | UInt16 LE | `cycleCount`           | Total lifetime full charge/discharge cycle counter                       |

#### Layout B: 32-Bit Capacities Prefixless (Offset = 0)

Same structure as Layout A, but shifted 2 bytes earlier (`packVoltageMv` begins
at offset `0x00`, `currentMa` at `0x04`, etc.).

#### Layout C: 16-Bit Legacy Capacities

On older Phantom-generation packs, full and remaining capacities are encoded as
16-bit unsigned words at offsets `0x05` and `0x07`.

---

## 3. Individual Cell Voltages (`0x0D / 0x03`)

The battery BMS reports individual cell voltages periodically or on query:

| Byte Offset      | Size        | Type      | Field Name  | Description                                                    |
| :--------------- | :---------- | :-------- | :---------- | :------------------------------------------------------------- |
| `0x00` or `0x02` | 1 byte      | UInt8     | `cellCount` | Number of active cells (`1..8`)                                |
| `Start`..`End`   | `2 * count` | UInt16 LE | `cellMv[i]` | Series cell voltage in millivolts (`2,000..5,000 mV` per cell) |

### Derived Cell Health Diagnostics

- **Cell Delta:** `cellDeltaMillivolts = maxCellVoltage - minCellVoltage`.
  - `< 20 mV`: Excellent cell matching.
  - `20..50 mV`: Normal operational deviation under flight load.
  - `> 60 mV`: Weak/degraded cell alert (risk of early low-voltage cutoff).

---

## 4. Derived Flight Dynamics Formulas

```
Power Draw (Watts) = (Pack Voltage (mV) * -Current (mA)) / 1,000,000

Estimated Flight Time (Minutes) = (Remaining Capacity (mAh) * 60.0) / -Current (mA)
```

---

## 5. Architectural Guidance: BMS vs. OSD Battery Percentage

- **Do not rely on the OSD percentage alone:** The flight controller OSD packet
  (`0x03 / 0x43`, byte 40) only contains an approximate estimate derived from
  motor load and voltage curves.
- **Authoritative BMS percentage:** The authentic fuel-gauge percentage comes
  directly from the battery BMS via `0x0D / 0x02` (byte 21). When available, BMS
  State of Charge should always supersede the flight controller OSD estimate.
- **Never poll the center board (`MODULE_CENTER_BOARD` `0x05`) or flight
  controller (`MODULE_FLIGHT_CONTROLLER` `0x03`) for battery data:**
  Experimental configuration writes (such as `0x00 / 0x05`) sent to the center
  board disrupt the flight controller's internal communications and abort motor
  spin-up routines.
