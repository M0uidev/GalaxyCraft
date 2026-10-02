#!/bin/sh
# Runs the mod's unit tests with the portable JDK 25 if no JAVA_HOME is set.
cd "$(dirname "$0")" || exit 1
: "${JAVA_HOME:=$(ls -d "$HOME"/.local/opt/jdk-25* 2>/dev/null | head -1)}"
export JAVA_HOME
./gradlew test --console=plain -q "$@" || exit $?
cat build/test-results/test/*.xml | grep -o 'tests="[0-9]*"' | cut -d'"' -f2 | paste -sd+ | bc | sed 's/$/ tests passed/'
