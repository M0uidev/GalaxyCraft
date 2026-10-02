#!/bin/sh
# Builds and runs the host library's tests.
cd "$(dirname "$0")" || exit 1
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Debug > /dev/null || exit 1
/usr/bin/ninja -C build > /dev/null || exit 1
exec ./build/galaxycraft_host_tests
