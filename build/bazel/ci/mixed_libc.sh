#!/bin/bash -eux
# Verifies mixed builds succeeds when building "libc".
# This verification script is designed to be used for continuous integration
# tests, though may also be used for manual developer verification.

if [[ -z ${DIST_DIR+x} ]]; then
  echo "DIST_DIR not set. Using out/dist. This should only be used for manual developer testing."
  DIST_DIR="out/dist"
fi
if [[ -z ${TARGET_PRODUCT+x} ]]; then
  echo "TARGET_PRODUCT not set. Have you run lunch?"
  exit 1
fi

TARGETS=(
  CaptivePortalLogin
  com.android.neuralnetworks
  framework-minus-apex
  libsimpleperf


  # TODO(b/266459895): uncomment these after re-enabling libunwindstack
  # com.android.media
  # com.android.media.swcodec
  # com.android.runtime
)

# Run a mixed build of "libc"
# TODO(b/254572169): Remove DISABLE_ARTIFACT_PATH_REQUIREMENT before launching --bazel-mode.
build/soong/soong_ui.bash --make-mode \
  --mk-metrics \
  --bazel-mode-dev \
  DISABLE_ARTIFACT_PATH_REQUIREMENTS=true \
  BP2BUILD_VERBOSE=1 \
  BAZEL_STARTUP_ARGS="--max_idle_secs=5" \
  BAZEL_BUILD_ARGS="--color=no --curses=no --show_progress_rate_limit=5" \
  "${TARGETS[@]}" \
  dist DIST_DIR=$DIST_DIR

echo "Verifying libc.so..."
LIBC_OUTPUT_FILE="$(find out/ -regex '.*/bazel-out/[^/]*android_arm64.*-opt.*/bin/bionic/libc/libc.so' || echo '')"
LIBC_STUB_OUTPUT_FILE="$(find out/ -regex '.*/bazel-out/[^/]*android_arm64.*-opt.*/bin/bionic/libc/liblibc_stub_libs-current_so.so' || echo '')"

if [ -z "$LIBC_OUTPUT_FILE" -a -z "$LIBC_STUB_OUTPUT_FILE" ]; then
  echo "Could not find libc.so or its stub lib at expected path."
  exit 1
fi

if [ -L "$LIBC_OUTPUT_FILE" ]; then
  # It's problematic to have libc.so be a symlink, as it means that installed
  # libc.so in an Android system image will be a symlink to a location outside
  # of that system image.
  echo "$LIBC_OUTPUT_FILE is expected as a file not a symlink"
  exit 1
fi

echo "libc.so verified."
