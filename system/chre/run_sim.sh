#!/bin/bash

# Quit if any command produces an error.
set -e

# Build and run the CHRE simulator.
JOB_COUNT=$((`grep -c ^processor /proc/cpuinfo`))

# Export the variant Makefile.
export CHRE_VARIANT_MK_INCLUDES=variant/simulator/variant.mk

make google_x86_linux_debug -j$JOB_COUNT
./out/google_x86_linux_debug/libchre ${@:1}
