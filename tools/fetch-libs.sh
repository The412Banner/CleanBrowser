#!/usr/bin/env bash
# Downloads prebuilt libraries that are not on Maven Central into app/libs and checks their SHA-256.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/libs
fetch() { # url file sha256
  if [ ! -f "app/libs/$2" ] || ! echo "$3  app/libs/$2" | sha256sum -c --status; then
    curl -fsSL --retry 3 -o "app/libs/$2" "$1"
  fi
  echo "$3  app/libs/$2" | sha256sum -c
}
JLT=https://github.com/frostwire/frostwire-jlibtorrent/releases/download/release/2.1.2.1
fetch "$JLT/jlibtorrent-android-2.1.2.1.aar" jlibtorrent-android-2.1.2.1.aar afde827b1ba717655ae9d93f8e17f8f14449f8580201a0625097539ecf2c749d
