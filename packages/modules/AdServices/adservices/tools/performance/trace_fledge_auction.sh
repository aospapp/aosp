#!/usr/bin/bash

set -e

cd "$ANDROID_BUILD_TOP"
./external/perfetto/tools/record_android_trace -c \
  packages/modules/AdServices/adservices/tools/performance/trace_config.textproto
