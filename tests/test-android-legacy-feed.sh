#!/usr/bin/env bash
# Host-side parser tests. Uses the kxml2 copy bundled with the Android SDK,
# so no Android device or emulator is required.
set -euo pipefail
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Set ANDROID_SDK_ROOT or ANDROID_HOME}}"
kxml=$(find "$sdk" -path '*net/sf/kxml/kxml2/2.3.0/kxml2-2.3.0.jar' -print -quit)
if [[ -z "$kxml" ]]; then
  kxml=$(find "$HOME/.gradle/caches" -name 'kxml2-2.3.0.jar' -print -quit 2>/dev/null || true)
fi
[[ -n "$kxml" ]] || { printf 'kxml2 2.3.0 not found in SDK or Gradle cache\n' >&2; exit 1; }
work=$(mktemp -d "$PWD/.temp/legacy-feed-test.XXXXXX")
trap 'rm -rf "$work"' EXIT
javac -cp "$kxml" -d "$work" \
  android/legacy/src/main/java/com/podcastapp/legacy/FeedRepository.java \
  android/legacy/src/main/java/com/podcastapp/legacy/FeedHttp.java \
  android/legacy/src/main/java/com/podcastapp/legacy/FeedInput.java \
  android/legacy/src/main/java/com/podcastapp/legacy/FeedXml.java \
  android/legacy/tests/LegacyFeedHostTest.java
java -cp "$work:$kxml" LegacyFeedHostTest
