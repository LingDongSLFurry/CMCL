#!/usr/bin/env sh
set -eu
base=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec java -Djava.awt.headless=true -Dfile.encoding=UTF-8 -jar "$base/cairn.jar" "$@"
