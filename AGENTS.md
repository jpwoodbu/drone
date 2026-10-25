# Project Rules

## Passive Telemetry Parsing

- Prefer to parse returned data passively from inbound DUML telemetry streams
  rather than issuing active polling queries or write-type commands to the
  aircraft.
- Outbound command traffic sent to aircraft modules (especially during flight
  or motor start) can interfere with flight-critical hardware routines (such as
  aborting motor start commands). Only transmit outbound packets when
  explicitly necessary and confirmed safe.

## Mirror Application Target Architecture

- The `:mirror` application must be treated as a standard 2D flat Android
  application.
- While the intended deployment environment includes Android-based VR headsets
  (such as Meta Quest), the app itself does not use VR or immersive SDKs and
  runs as a regular 2D window that can execute across any compatible Android
  device.

## Mirror Application Status Bars Terminology

- In the `:mirror` application UI, refer to the upper telemetry bar as the
  "top status bar" and the lower diagnostics bar as the "bottom status bar".

## DUML Protocol Documentation

- Refer to the local reverse-engineered DUML v1 reference documentation in
  `docs/duml/` before researching online or implementing DUML packets.

