# Design Decisions & Notes

## Remote Controller Battery Telemetry

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

## Aircraft Battery Telemetry

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

## USB Accessory Descriptor Placeholders

When connecting over Android Open Accessory (AOA 2.0), Android requires the USB
host accessory to supply identifying descriptor strings (manufacturer, model,
description, version, URI, and serial number).

DJI remote controllers hardcode dummy placeholder constants for the version
(`v1.2.3.4`) and serial number (`12345678`) fields during the initial USB
control transfer handshake. Because these values are static placeholders rather
than authentic hardware or firmware identifiers, they are omitted from the
`:bridge` diagnostic user interface. Genuine module firmware versions are
instead parsed dynamically from inbound DUML version push packets and queries.

## Read-Only Security Posture of `:mirror` Over Wi-Fi

The `:mirror` application is intentionally kept strictly read-only with respect
to aircraft and remote controller state:

- `:mirror` only transmits 4-byte `"DJIH"` UDP discovery heartbeats to register
  as a video and telemetry subscriber with `:bridge`, and `:bridge` never
  accepts or forwards upstream DUML commands received over the UDP network
  socket.
- Keeping the Wi-Fi link unidirectional for DUML traffic avoids exposing any
  drone flight controls over Wi-Fi, eliminating the risk of unauthorized
  network peers or accidental UI interactions in the headset injecting flight
  commands into the aircraft.
- Any software-assisted flight control actions (such as initiating `Auto-Land`
  via a 2-second hold of the physical `Fn` button) are detected and executed
  exclusively inside `:bridge` over the direct physical USB connection to the
  remote controller, while `:mirror` passively renders the resulting state and
  telemetry updates.
