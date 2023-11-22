#!/bin/bash

# Quit if any command produces an error.
set -e

# Build and run the CHRE unit test binary.
JOB_COUNT=$((`grep -c ^processor /proc/cpuinfo`))

make google_x86_googletest_debug -j$JOB_COUNT
./out/google_x86_googletest_debug/libchre $1
