#!/bin/sh
# Gradle Wrapper JAR Download Script (Unix/macOS)
# =================================================
# The gradle-wrapper.jar is intentionally excluded from this repository.
# This script downloads the official wrapper JAR from the Gradle GitHub repository.
#
# Usage:
#   cd gradle/wrapper
#   sh download-wrapper.sh
#
# Or from project root:
#   sh gradle/wrapper/download-wrapper.sh

set -e

WRAPPER_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR_URL="https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar"
JAR_FILE="$WRAPPER_DIR/gradle-wrapper.jar"

echo "Downloading gradle-wrapper.jar..."
echo "  URL: $JAR_URL"
echo "  Destination: $JAR_FILE"

# Try curl first, then wget
if command -v curl >/dev/null 2>&1; then
    curl -L --fail -o "$JAR_FILE" "$JAR_URL"
elif command -v wget >/dev/null 2>&1; then
    wget --quiet -O "$JAR_FILE" "$JAR_URL"
else
    echo "ERROR: Neither curl nor wget is available. Please install one of them."
    exit 1
fi

if [ -f "$JAR_FILE" ]; then
    echo "SUCCESS: gradle-wrapper.jar downloaded successfully."
    ls -la "$JAR_FILE"
else
    echo "ERROR: Download failed. Please download manually from:"
    echo "  $JAR_URL"
    exit 1
fi
