#!/bin/sh
# Builds and runs the g++ tests of the module's portable core (src/core).
cd "$(dirname "$0")" || exit 1
mkdir -p build/tests || exit 1
g++ -std=c++17 -Wall -Wextra -Werror -O1 -Isrc/core -o build/tests/test_core \
  tests/test_core.cpp src/core/*.cpp || exit 1
exec ./build/tests/test_core
