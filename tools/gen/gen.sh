#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
# Generates the bundled offline case pack into ./cases using the JVM generator.
./gradlew -q :app:execGen --args="$PWD/cases"
