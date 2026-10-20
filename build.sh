#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
mkdir -p build/classes dist
find src/main/java -name '*.java' -print > build/sources.list
javac --release 17 -encoding UTF-8 -Xlint:all -d build/classes @build/sources.list
jar --create --file dist/cairn.jar --main-class dev.cairn.Main -C build/classes .
if [ "${1:-}" != "--skip-test" ]; then
  java -Djava.awt.headless=true -jar dist/cairn.jar --self-test
fi
