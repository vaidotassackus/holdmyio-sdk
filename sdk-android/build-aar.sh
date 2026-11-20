#!/usr/bin/env bash
# Builds a release AAR with the version in its name: sdk-android/dist/holdmyio-<version>.aar.
# The version is taken from Core.SDK_VERSION. Run from a tag, e.g. git checkout 0.2.0 && sdk-android/build-aar.sh
set -euo pipefail
cd "$(dirname "$0")"
VERSION=$(sed -n 's/.*const val SDK_VERSION = "\(.*\)".*/\1/p' holdmyio/src/main/java/com/holdmyio/Core.kt)
./gradlew --console=plain -q :holdmyio:testDebugUnitTest :holdmyio:assembleRelease
mkdir -p dist
cp holdmyio/build/outputs/aar/holdmyio-release.aar "dist/holdmyio-$VERSION.aar"
shasum -a 256 "dist/holdmyio-$VERSION.aar"
