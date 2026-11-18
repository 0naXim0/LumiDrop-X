#!/bin/sh
# LumiDropX Build Setup — run once before first ./gradlew build
# Downloads the gradle-wrapper.jar which cannot be distributed in source repositories.

JAR="gradle/wrapper/gradle-wrapper.jar"
# Official Gradle 8.5 wrapper jar (matches distributionUrl in gradle-wrapper.properties)
URL="https://github.com/gradle/gradle/raw/v8.5.0/gradle/wrapper/gradle-wrapper.jar"

if [ -f "$JAR" ] && [ $(wc -c < "$JAR") -gt 10000 ]; then
  echo "gradle-wrapper.jar already present ($(wc -c < "$JAR") bytes). Nothing to do."
  exit 0
fi

echo "Downloading gradle-wrapper.jar for Gradle 8.5..."
mkdir -p gradle/wrapper

if command -v curl >/dev/null 2>&1; then
  curl -L --fail -o "$JAR" "$URL" && echo "Done." || { echo "curl failed."; exit 1; }
elif command -v wget >/dev/null 2>&1; then
  wget -q -O "$JAR" "$URL" && echo "Done." || { echo "wget failed."; exit 1; }
else
  echo "ERROR: curl or wget required. Install one and retry."
  exit 1
fi

echo "You can now run:  ./gradlew assembleDebug"
