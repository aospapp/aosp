#!/bin/bash

# Note: not intended to be invoked directly, see rebuild.sh.
#
# Rebuilds Crosvm and its dependencies from a clean state.

: ${TOOLS_DIR:="$(pwd)/tools"}

setup_env() {
  : ${SOURCE_DIR:="$(pwd)/source"}
  : ${WORKING_DIR:="$(pwd)/working"}
  : ${CUSTOM_MANIFEST:=""}

  ARCH="$(uname -m)"
  : ${OUTPUT_DIR:="$(pwd)/${ARCH}-linux-gnu"}
  OUTPUT_BIN_DIR="${OUTPUT_DIR}/bin"
  OUTPUT_ETC_DIR="${OUTPUT_DIR}/etc"
  OUTPUT_SECCOMP_DIR="${OUTPUT_ETC_DIR}/seccomp"
  OUTPUT_LIB_DIR="${OUTPUT_DIR}/bin"

  export PATH="${PATH}:${TOOLS_DIR}:${HOME}/.local/bin"
}

set -o errexit
set -x

fatal_echo() {
  echo "$@"
  exit 1
}

prepare_cargo() {
  echo Setting up cargo...
  cd
  rm -rf .cargo
  # Sometimes curl hangs. When it does, retry
  retry curl -LO \
    "https://static.rust-lang.org/rustup/archive/1.14.0/$(uname -m)-unknown-linux-gnu/rustup-init"
  # echo "0077ff9c19f722e2be202698c037413099e1188c0c233c12a2297bf18e9ff6e7 *rustup-init" | sha256sum -c -
  chmod +x rustup-init
  ./rustup-init -y --no-modify-path
  source $HOME/.cargo/env
  if [[ -n "$1" ]]; then
    rustup target add "$1"
  fi
  rustup component add rustfmt-preview
  rm rustup-init

  if [[ -n "$1" ]]; then
  cat >>~/.cargo/config <<EOF
[target.$1]
linker = "${1/-unknown-/-}"
EOF
  fi
}

install_custom_scripts() {
  # install our custom utility script used by $0 to ${TOOLS_DIR}
  echo "Installing custom scripts..."
  SCRIPTS_TO_INSTALL=("/static/policy-inliner.sh")
  mkdir -p ${TOOLS_DIR} || /bin/true
  for scr in ${SCRIPTS_TO_INSTALL[@]}; do
    if ! [[ -f $scr ]]; then
      >&2 echo "$scr must exist but does not"
     exit 10
    fi
    chmod a+x $scr
    cp -f $scr ${TOOLS_DIR}
  done
}

install_packages() {
  echo Installing packages...
  sudo dpkg --add-architecture arm64
  sudo apt-get update
  sudo apt-get install -y \
      autoconf \
      automake \
      build-essential \
      "$@" \
      cmake \
      curl \
      gcc \
      g++ \
      git \
      libcap-dev \
      libdrm-dev \
      libfdt-dev \
      libegl1-mesa-dev \
      libgl1-mesa-dev \
      libgles2-mesa-dev \
      libssl-dev \
      libtool \
      libusb-1.0-0-dev \
      libwayland-dev \
      make \
      nasm \
      ninja-build \
      pkg-config \
      protobuf-compiler \
      python \
      python3 \
      python3-pip \
      xutils-dev # Needed to pacify autogen.sh for libepoxy
  mkdir -p "${TOOLS_DIR}"
  curl https://storage.googleapis.com/git-repo-downloads/repo > "${TOOLS_DIR}/repo"
  chmod a+x "${TOOLS_DIR}/repo"

  # Meson getting started guide mentions that the distro version is frequently
  # outdated and recommends installing via pip.
  pip3 install meson

  # Tools for building gfxstream
  pip3 install absl-py
  pip3 install urlfetch

  case "$(uname -m)" in
    aarch64)
      prepare_cargo
      ;;
    x86_64)
      # Cross-compilation is x86_64 specific
      sudo apt install -y crossbuild-essential-arm64
      prepare_cargo aarch64-unknown-linux-gnu
      ;;
  esac
}

retry() {
  for i in $(seq 5); do
    "$@" && return 0
    sleep 1
  done
  return 1
}

fetch_source() {
  echo "Fetching source..."

  mkdir -p "${SOURCE_DIR}"
  cd "${SOURCE_DIR}"

  if ! git config user.name; then
    git config --global user.name "AOSP Crosvm Builder"
    git config --global user.email "nobody@android.com"
    git config --global color.ui false
  fi

  if [[ -z "${CUSTOM_MANIFEST}" ]]; then
    # Building Crosvm currently depends using Chromium's directory scheme for subproject
    # directories ('third_party' vs 'external').
    fatal_echo "CUSTOM_MANIFEST must be provided. You most likely want to provide a full path to" \
               "a copy of device/google/cuttlefish_vmm/${ARCH}-linux-gnu/manifest.xml."
  fi

  repo init -q -u https://android.googlesource.com/platform/manifest
  cp "${CUSTOM_MANIFEST}" .repo/manifests
  repo init -m "${CUSTOM_MANIFEST}"
  repo sync
}

prepare_source() {
  if [ "$(ls -A $SOURCE_DIR)" ]; then
    echo "${SOURCE_DIR} is non empty. Run this from an empty directory if you wish to fetch the source." 1>&2
    exit 2
  fi
  fetch_source
}

resync_source() {
  echo "Deleting source directory..."
  rm -rf "${SOURCE_DIR}/.*"
  rm -rf "${SOURCE_DIR}/*"
  fetch_source
}

compile_minijail() {
  echo "Compiling Minijail..."

  cd "${SOURCE_DIR}/external/minijail"

  make -j OUT="${WORKING_DIR}"

  cp "${WORKING_DIR}/libminijail.so" "${OUTPUT_LIB_DIR}"
}

compile_minigbm() {
  echo "Compiling Minigbm..."

  cd "${SOURCE_DIR}/third_party/minigbm"

  # Minigbm's package config file has a default hard-coded path. Update here so
  # that dependent packages can find the files.
  sed -i "s|prefix=/usr\$|prefix=${WORKING_DIR}/usr|" gbm.pc

  # The gbm used by upstream linux distros is not compatible with crosvm, which must use Chrome OS's
  # minigbm.
  local cpp_flags=()
  local make_flags=()
  local minigbm_drv=(${MINIGBM_DRV})
  for drv in "${minigbm_drv[@]}"; do
    cpp_flags+=(-D"DRV_${drv}")
    make_flags+=("DRV_${drv}"=1)
  done

  make -j install \
    "${make_flags[@]}" \
    CPPFLAGS="${cpp_flags[*]}" \
    DESTDIR="${WORKING_DIR}" \
    OUT="${WORKING_DIR}" \
    PKG_CONFIG=pkg-config

  cp ${WORKING_DIR}/usr/lib/libgbm.so.1 "${OUTPUT_LIB_DIR}"
}

compile_epoxy() {
  cd "${SOURCE_DIR}/third_party/libepoxy"

  meson build \
    --libdir="${WORKING_DIR}/usr/lib" \
    --pkg-config-path="${WORKING_DIR}/usr/lib/pkgconfig" \
    --prefix="${WORKING_DIR}/usr" \
    -Dglx=no \
    -Dx11=false \
    -Degl=yes

  cd build

  ninja install

  cp "${WORKING_DIR}"/usr/lib/libepoxy.so.0 "${OUTPUT_LIB_DIR}"
}

compile_virglrenderer() {
  echo "Compiling VirglRenderer..."

    # Note: depends on libepoxy
  cd "${SOURCE_DIR}/third_party/virglrenderer"

  # Meson doesn't like gbm's version code.
  sed -i "s|_gbm_ver = '0.0.0'|_gbm_ver = '0'|" meson.build

  # Meson needs to have dependency information for header lookup.
  sed -i "s|cc.has_header('epoxy/egl.h')|cc.has_header('epoxy/egl.h', dependencies: epoxy_dep)|" meson.build

  # Need to figure out the right way to pass this down...
  grep "install_rpath" src/meson.build || \
    sed -i "s|install : true|install : true, install_rpath : '\$ORIGIN',|" src/meson.build

  meson build \
    --libdir="${WORKING_DIR}/usr/lib" \
    --pkg-config-path="${WORKING_DIR}/usr/lib/pkgconfig" \
    --prefix="${WORKING_DIR}/usr" \
    -Dplatforms=egl \
    -Dgbm_allocation=false

  cd build

  ninja install

  cp "${WORKING_DIR}/usr/lib/libvirglrenderer.so.1" "${OUTPUT_LIB_DIR}"

  cd "${OUTPUT_LIB_DIR}"
  ln -s -f "libvirglrenderer.so.1" "libvirglrenderer.so"
}

compile_gfxstream() {
  echo "Compiling gfxstream..."

    # Note: depends on libepoxy
  cd "${SOURCE_DIR}/external/qemu"

  # TODO: Fix or remove network unit tests that are failing in docker,
  # so we can take out "notests"
  python3 android/build/python/cmake.py --gfxstream_only --notests
  local dist_dir="${SOURCE_DIR}/external/qemu/objs/distribution/emulator/lib64"

  cp "${dist_dir}/libc++.so.1" "${OUTPUT_LIB_DIR}"
  cp "${dist_dir}/libandroid-emu-shared.so" "${OUTPUT_LIB_DIR}"
  cp "${dist_dir}/libemugl_common.so" "${OUTPUT_LIB_DIR}"
  cp "${dist_dir}/libOpenglRender.so" "${OUTPUT_LIB_DIR}"
  cp "${dist_dir}/libgfxstream_backend.so" "${OUTPUT_LIB_DIR}"
}

compile_crosvm() {
  echo "Compiling Crosvm..."

  source "${HOME}/.cargo/env"
  cd "${SOURCE_DIR}/platform/crosvm"

  local crosvm_features=gpu,composite-disk

  if [[ $BUILD_GFXSTREAM -eq 1 ]]; then
      crosvm_features+=,gfxstream
  fi

  RUSTFLAGS="-C link-arg=-Wl,-rpath,\$ORIGIN -C link-arg=-L${OUTPUT_LIB_DIR}" \
    cargo build --features ${crosvm_features}

  # Save the outputs
  cp Cargo.lock "${OUTPUT_DIR}"
  cp target/debug/crosvm "${OUTPUT_BIN_DIR}"

  cargo --version --verbose > "${OUTPUT_DIR}/cargo_version.txt"
  rustup show > "${OUTPUT_DIR}/rustup_show.txt"
}

compile_crosvm_seccomp() {
  # note that this depends on compile_crosvm
  #
  # for aarch64, this function should do nothing
  # as the aarch64 subdirectory does not exist yet
  #
  echo "Processing Crosvm Seccomp..."

  cd "${SOURCE_DIR}/platform/crosvm"
  case ${ARCH} in
    x86_64) subdir="${ARCH}" ;;
    amd64) subdir="x86_64" ;;
    arm64) subdir="aarch64" ;;
    aarch64) subdir="${ARCH}" ;;
    *)
      echo "${ARCH} is not supported"
      exit 15
  esac
  policy-inliner.sh \
    -p $(pwd)/seccomp/$subdir \
    -o ${OUTPUT_SECCOMP_DIR} \
    -c $(pwd)/seccomp/$subdir/common_device.policy
}

compile() {
  echo "Compiling..."
  mkdir -p \
    "${WORKING_DIR}" \
    "${OUTPUT_DIR}" \
    "${OUTPUT_BIN_DIR}" \
    "${OUTPUT_ETC_DIR}" \
    "${OUTPUT_SECCOMP_DIR}" \
    "${OUTPUT_LIB_DIR}"

  compile_minijail

  compile_minigbm

  compile_epoxy

  compile_virglrenderer

  # TODO: Finish the aarch64 cross/native gfxstream build
  if [[ $BUILD_GFXSTREAM -eq 1 ]]; then
      compile_gfxstream
  fi

  compile_crosvm

  compile_crosvm_seccomp

  dpkg-query -W > "${OUTPUT_DIR}/builder-packages.txt"
  repo manifest -r -o "${OUTPUT_DIR}/manifest.xml"
  echo "Results in ${OUTPUT_DIR}"
}

aarch64_retry() {
  MINIGBM_DRV="RADEON VC4" compile
}

aarch64_build() {
  rm -rf "${WORKING_DIR}/*"
  aarch64_retry
}

x86_64_retry() {
  MINIGBM_DRV="I915 RADEON VC4" BUILD_GFXSTREAM=1 compile
}

x86_64_build() {
  rm -rf "${WORKING_DIR}/*"
  x86_64_retry
}

if [[ $# -lt 1 ]]; then
  echo Choosing default config
  set setup_env prepare_source x86_64_build
fi

echo Steps: "$@"

for i in "$@"; do
  echo $i
  case "$i" in
    ARCH=*) ARCH="${i/ARCH=/}" ;;
    CUSTOM_MANIFEST=*) CUSTOM_MANIFEST="${i/CUSTOM_MANIFEST=/}" ;;
    aarch64_build) $i ;;
    aarch64_retry) $i ;;
    setup_env) $i ;;
    install_custom_scripts) $i ;;
    install_packages) $i ;;
    fetch_source) $i ;;
    resync_source) $i ;;
    prepare_source) $i ;;
    x86_64_build) $i ;;
    x86_64_retry) $i ;;
    *) echo $i unknown 1>&2
      echo usage: $0 'install_packages|prepare_source|resync_source|fetch_source|$(uname -m)_build|$(uname -m)_retry' 1>&2
       exit 2
       ;;
  esac
done
