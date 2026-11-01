#!/usr/bin/env bash

# Map target names ("bridge", "mirror-google", and "mirror-meta") to `adb`
# serial identifiers in a `devices.conf` file in this directory.  Run `adb
# devices -l` to find device identifiers in the first column.
#
# The `devices.conf` format uses key=value pairs, for example:
#   bridge=adb-redacted._adb-tls-connect._tcp
#   mirror-meta=192.168.86.208:5555
#   mirror-google=adb-redacted-phone._adb-tls-connect._tcp
#
# Mirror targets fall back to a generic `mirror` entry if not defined
# individually.

usage() {
    printf 'Usage: %s <bridge|mirror-google|mirror-meta> [-r]\n' "${0##*/}" >&2
    printf 'Run `adb devices -l` to inspect connected devices.\n' >&2
}

# Require a valid target and allow only an optional `-r` flag.
if (( $# < 1 || $# > 2 )) || [[ ${1:-} != bridge && ${1:-} != mirror-google && ${1:-} != mirror-meta ]] || [[ -n ${2:-} && ${2:-} != -r ]]; then
    usage
    exit 2
fi

target=$1
release=${2:-}

if [[ "$release" == "-r" ]]; then
    build_type="release"
else
    build_type="debug"
fi

case "$target" in
    bridge)
        apk_path="bridge/build/outputs/apk/$build_type/bridge-$build_type.apk"
        ;;
    mirror-google)
        apk_path="mirror/build/outputs/apk/play/$build_type/mirror-play-$build_type.apk"
        ;;
    mirror-meta)
        apk_path="mirror/build/outputs/apk/horizon/$build_type/mirror-horizon-$build_type.apk"
        ;;
esac

if [[ ! -f "$apk_path" ]]; then
    printf 'APK not found: %s\n' "$apk_path" >&2
    exit 1
fi

# Resolve the target device serial from the configuration file.
config_file="${0%/*}/devices.conf"

if [[ ! -f "$config_file" ]]; then
    printf 'Configuration file not found: %s\n' "$config_file" >&2
    exit 1
fi

get_serial() {
    local key=$1
    local val
    val=$(grep -E "^[[:space:]]*${key}[[:space:]]*=" "$config_file" | head -n 1 | cut -d= -f2-)
    # Strip inline comments if present.
    val="${val%%#*}"
    # Strip surrounding whitespace.
    val="${val#"${val%%[![:space:]]*}"}"
    val="${val%"${val##*[![:space:]]}"}"
    printf '%s' "$val"
}

# Look for `<target>=<serial>`, falling back to `mirror=<serial>` for mirror targets.
serial=$(get_serial "$target")
if [[ -z "$serial" && "$target" == mirror-* ]]; then
    serial=$(get_serial "mirror")
fi

if [[ -z "$serial" ]]; then
    printf 'No serial configured for "%s" in %s\n' "$target" "$config_file" >&2
    exit 1
fi

printf 'Deploying %s to %s...\n' "$apk_path" "$serial"
exec adb -s "$serial" install -r "$apk_path"
