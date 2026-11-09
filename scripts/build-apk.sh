#!/usr/bin/env bash
# Full build: native binaries + release APKs (one per CPU type) into dist/.
# Usage: scripts/build-apk.sh [abi...]   e.g. scripts/build-apk.sh arm64-v8a
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
"$ROOT/scripts/build-native.sh" "$@"
(cd "$ROOT/android" && ./gradlew --no-daemon -q assembleRelease)
mkdir -p "$ROOT/dist"
cp "$ROOT"/android/app/build/outputs/apk/release/*.apk "$ROOT/dist/"
ls -lh "$ROOT/dist"
