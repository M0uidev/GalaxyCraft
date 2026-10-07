#!/bin/sh
# The module's build is syati/build.py (the same on Linux and Windows); this runs it.
exec python3 "$(dirname "$0")/build.py" "$@"
