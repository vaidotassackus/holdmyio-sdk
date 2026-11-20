#!/usr/bin/env bash
# Sukompiliuoja release AAR su versija pavadinime: sdk-android/dist/holdmyio-<versija>.aar.
# Versija imama iš Core.SDK_VERSION. Naudoti iš tago, pvz. git checkout 0.2.0 && sdk-android/build-aar.sh
set -euo pipefail
cd "$(dirname "$0")"
VERSION=$(sed -n 's/.*const val SDK_VERSION = "\(.*\)".*/\1/p' holdmyio/src/main/java/com/holdmyio/Core.kt)
./gradlew --console=plain -q :holdmyio:testDebugUnitTest :holdmyio:assembleRelease
mkdir -p dist
cp holdmyio/build/outputs/aar/holdmyio-release.aar "dist/holdmyio-$VERSION.aar"
shasum -a 256 "dist/holdmyio-$VERSION.aar"
