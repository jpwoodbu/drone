#!/usr/bin/env bash

usage() {
    printf 'Usage: %s <bridge|mirror> <target device ID> [-r]\n' "${0##*/}" >&2
    printf 'Run `adb devices -l` to get device IDs.\n' >&2
}

# Require a module and device ID; allow only an optional -r flag.
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

exec adb -t "$target" install -r "$apk"
