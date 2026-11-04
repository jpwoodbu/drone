# Screenshots

Screenshots are stored outside of this repo. This document is meant to record a
description of the screenshots used in each store front so that they can be
reproduced, if needed.

## DPI values

The Meta Horizon Store wants 16:9 screenshots and the Google Play Store will
accept 16:9 screenshots for all device categories. It's convenient (at least for
me) to use a single device to create each screenshot and adjust its DPI with
`adb`. For example:

```sh
adb shell wm density 360
```

The default DPI can be restored with:

```sh
adb shelly wm density reset
```

These are the DPIs used for various types of devices:

| Device         | DPI |
| -------------- | --: |
| Phone          | 560 |
| Meta VR        | 360 |
| Android XR     | 360 |
| 7-inch tablet  | 360 |
| 10-inch tablet | 320 |

## Screenshot counts

Meta requires exactly 5 screenshots.

Google requires a variable number of screenshots depending on the device
category. Thankfully, 5 screenshots satisfies the requirement for each category.
Reusing the screenshots taken for the Meta Horizon Store seems reasonable.

## Drone Mirror

- Top Status with video using imperial units
- HUD with video using metric units
- Settings dialog
- Flight modes dialog
- Network stats dialog

## Drone Bridge

This app is only published to Google Play and only includes _Phone_ screenshots.

- Main screen, scrolled to top, USB connected
- Main screen, scrolled to expanded _Live Video Feed_
- Main screen, scrolled to _Controller Telemetry_
- Main screen, scrolled to _Aircraft Telemetry_
