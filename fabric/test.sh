#!/bin/sh
# Runs the mod's unit tests with the portable JDK 25 if no JAVA_HOME is set.
cd "$(dirname "$0")"
: "${JAVA_HOME:=$(ls -d "$HOME"/.local/opt/jdk-25* 2>/dev/null | head -1)}"
export JAVA_HOME
exec ./gradlew test --console=plain -q "$@"
