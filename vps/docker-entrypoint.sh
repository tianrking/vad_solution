#!/bin/sh
set -eu

mkdir -p "${WORK_ROOT:-/tmp/audio-silence-service}"
chown -R appuser:appuser "${WORK_ROOT:-/tmp/audio-silence-service}"

exec gosu appuser "$@"
