#!/usr/bin/env sh
# Lightweight repository wrapper. It uses a system Gradle when present and otherwise
# bootstraps the pinned distribution into the normal Gradle user cache.
set -eu
VERSION=8.7
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/phone-guardian-wrapper/gradle-$VERSION"
if [ ! -x "$CACHE/bin/gradle" ]; then
  command -v curl >/dev/null 2>&1 || { echo "Gradle is not installed and curl is unavailable." >&2; exit 1; }
  command -v unzip >/dev/null 2>&1 || { echo "Gradle is not installed and unzip is unavailable." >&2; exit 1; }
  tmp="${TMPDIR:-/tmp}/gradle-$VERSION.zip"
  curl --fail --location --retry 2 "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$tmp"
  mkdir -p "${CACHE%/*}"
  unzip -q -o "$tmp" -d "${CACHE%/*}"
  mv "${CACHE%/*}/gradle-$VERSION" "$CACHE"
  rm -f "$tmp"
fi
exec "$CACHE/bin/gradle" "$@"
