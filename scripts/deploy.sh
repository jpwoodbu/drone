#!/usr/bin/env bash

# Map module names ("bridge" and "mirror") to `adb` serial identifiers
# in a `devices.conf` file in this directory.
# Run `adb devices -l` to find device identifiers in the first column.
#
# The `devices.conf` format uses key=value pairs, for example:
#   bridge=adb-redacted._adb-tls-connect._tcp
#   mirror=adb-redacted-but-different._adb-tls-connect._tcp

usage() {
    printf 'Usage: %s <bridge|mirror> [-r]\n' "${0##*/}" >&2
    printf 'Run `adb devices -l` to inspect connected devices.\n' >&2
}

# Require a module and allow only an optional `-r` flag.
if (( $# < 1 || $# > 2 )) || [[ ${1:-} != bridge && ${1:-} != mirror ]] || [[ -n ${2:-} && ${2:-} != -r ]]; then
    usage
    exit 2
fi

module=$1
release=${2:-}

if [[ "$release" == "-r" ]]; then
    apk_path="$module/build/outputs/apk/release/$module-release.apk"
else
    apk_path="$module/build/outputs/apk/debug/$module-debug.apk"
fi

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

# Look for `<module>=<serial>`, ignoring leading whitespace.
serial=$(grep -E "^[[:space:]]*${module}[[:space:]]*=" "$config_file" | head -n 1 | cut -d= -f2-)
# Strip inline comments if present.
serial="${serial%%#*}"
# Strip surrounding whitespace.
serial="${serial#"${serial%%[![:space:]]*}"}"
serial="${serial%"${serial##*[![:space:]]}"}"

if [[ -z "$serial" ]]; then
    printf 'No serial configured for "%s" in %s\n' "$module" "$config_file" >&2
    exit 1
fi

printf 'Deploying %s to %s...\n' "$apk_path" "$serial"
exec adb -s "$serial" install -r "$apk_path"
