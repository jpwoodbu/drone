#!/usr/bin/env bash

# Device Configuration:
# You can optionally map friendly device nicknames to adb serial identifiers
# by creating a `devices.conf` file in this directory.
# Run `adb devices -l` to find device identifiers in the first column.
#
# `devices.conf` format is key=value, for example:
#   pixel=adb-redacted._adb-tls-connect._tcp
#
# If the target argument matches a key in `devices.conf`, its mapped serial is
# used. Otherwise, the target argument is passed directly to `adb -s`.

usage() {
    printf 'Usage: %s <bridge|mirror> <device name or serial> [-r]\n' "${0##*/}" >&2
    printf 'Run `adb devices -l` to inspect connected devices.\n' >&2
}

# Require a module and device name/serial; allow only an optional -r flag.
if (( $# < 2 || $# > 3 )) || [[ ${1:-} != bridge && ${1:-} != mirror ]] || [[ -n ${3:-} && ${3:-} != -r ]]; then
    usage
    exit 2
fi

module=$1
target=$2
release=${3:-}

if [[ "$release" == "-r" ]]
then
    apk="$module/build/outputs/apk/release/$module-release.apk"
else
    apk="$module/build/outputs/apk/debug/$module-debug.apk"
fi

if [[ ! -f "$apk" ]]
then
    printf 'APK not found: %s\n' "$apk" >&2
    exit 1
fi

# Resolve the target device serial.
# If `devices.conf` exists in the script directory, look up friendly aliases.
config_file="${0%/*}/devices.conf"
serial="$target"

if [[ -f "$config_file" ]]; then
    # Look for `<target>=<serial>`, ignoring leading whitespace.
    mapped_serial=$(grep -E "^[[:space:]]*${target}[[:space:]]*=" "$config_file" | head -n 1 | cut -d= -f2-)
    # Strip inline comments if present.
    mapped_serial="${mapped_serial%%#*}"
    # Strip surrounding whitespace.
    mapped_serial="${mapped_serial#"${mapped_serial%%[![:space:]]*}"}"
    mapped_serial="${mapped_serial%"${mapped_serial##*[![:space:]]}"}"
    if [[ -n "$mapped_serial" ]]; then
        serial="$mapped_serial"
    fi
fi

printf 'Deploying %s to %s...\n' "$apk" "$serial"
exec adb -s "$serial" install -r "$apk"
