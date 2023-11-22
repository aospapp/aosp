#!/bin/bash

ARCH="$(uname -m)"
pushd "$(dirname "$0")" > /dev/null 2>&1
OUT_DIR="$(pwd)/${ARCH}"
popd > /dev/null 2>&1
LIB_PATH="${OUT_DIR}/lib64/crosvm"
mkdir -p "${LIB_PATH}"

BUILD_DIR=${HOME}/build
export THIRD_PARTY_ROOT="${BUILD_DIR}/third_party"
export PATH="${PATH}:${HOME}/bin"
mkdir -p "${THIRD_PARTY_ROOT}"

set -o errexit
set -x

sudo apt-get update
sudo apt-get install -y \
    autoconf \
    automake \
    curl \
    gcc \
    g++ \
    git \
    libcap-dev \
    libdrm-dev \
    libfdt-dev \
    libegl1-mesa-dev \
    libgl1-mesa-dev \
    libgles1-mesa-dev \
    libgles2-mesa-dev \
    libssl1.0-dev \
    libtool \
    libusb-1.0-0-dev \
    libwayland-dev \
    make \
    nasm \
    ninja-build \
    pkg-config \
    protobuf-compiler \
    python3 \
    xutils-dev

export RUST_VERSION=1.32.0 RUSTFLAGS='--cfg hermetic'

curl -LO "https://static.rust-lang.org/rustup/archive/1.14.0/x86_64-unknown-linux-gnu/rustup-init"
echo "0077ff9c19f722e2be202698c037413099e1188c0c233c12a2297bf18e9ff6e7 *rustup-init" | sha256sum -c -
chmod +x rustup-init
./rustup-init -y --no-modify-path --default-toolchain $RUST_VERSION
source $HOME/.cargo/env
rustup component add rustfmt-preview
rm rustup-init

cd "${THIRD_PARTY_ROOT}"
# minijail does not exist in upstream linux distros.
git clone https://android.googlesource.com/platform/external/minijail
cd minijail
make -j
mkdir -p "${HOME}/lib"
cp libminijail.so "${HOME}/lib/"
cp libminijail.so "${LIB_PATH}/"

cd "${THIRD_PARTY_ROOT}"
# The gbm used by upstream linux distros is not compatible with crosvm, which must use Chrome OS's
# minigbm.
git clone https://android.googlesource.com/platform/external/minigbm \
  -b upstream-master
cd minigbm
sed 's/-Wall/-Wno-maybe-uninitialized/g' -i Makefile
ln -s "${HOME}" "${HOME}/usr"
DESTDIR="${HOME}" make -j install
cp ${HOME}/lib/libgbm.so.1 "${LIB_PATH}/"

cd "${THIRD_PARTY_ROOT}"
set -x
# New libepoxy has EGL_KHR_DEBUG entry points needed by crosvm.
git clone https://android.googlesource.com/platform/external/libepoxy
cd libepoxy
git checkout 707f50e680ab4f1861b1e54ca6e2907aaca56c12
./autogen.sh --prefix="${HOME}"
make -j install
cp "${HOME}"/lib/libepoxy.so.0 "${LIB_PATH}"/

# Note: depends on libepoxy
cd "${THIRD_PARTY_ROOT}"
git clone https://android.googlesource.com/platform/external/virglrenderer \
  -b upstream-master
cd virglrenderer
./autogen.sh --prefix=${HOME} PKG_CONFIG_PATH=${HOME}/lib/pkgconfig
make -j install
cp "${HOME}/lib/libvirglrenderer.so.0" "${LIB_PATH}"/

cd "${THIRD_PARTY_ROOT}"
git clone https://android.googlesource.com/platform/external/adhd \
  -b upstream-master

#cd "${THIRD_PARTY_ROOT}"
# Install libtpm2 so that tpm2-sys/build.rs does not try to build it in place in
# the read-only source directory.
#git clone https://chromium.googlesource.com/chromiumos/third_party/tpm2 \
#    && cd tpm2 \
#    && git checkout 15260c8cd98eb10b4976d2161cd5cb9bc0c3adac \
#    && make -j24

# Install librendernodehost
#RUN git clone https://chromium.googlesource.com/chromiumos/platform2 \
#    && cd platform2 \
#    && git checkout 226fc35730a430344a68c34d7fe7d613f758f417 \
#    && cd rendernodehost \
#    && gcc -c src.c -o src.o \
#    && ar rcs librendernodehost.a src.o \
#    && cp librendernodehost.a /lib

# Inform pkg-config where libraries we install are placed.
#COPY pkgconfig/* /usr/lib/pkgconfig

# Reduces image size and prevents accidentally using /scratch files
#RUN rm -r /scratch /usr/bin/meson

# The manual installation of shared objects requires an ld.so.cache refresh.
#RUN ldconfig

# Pull down repositories that crosvm depends on to cros checkout-like locations.
#ENV CROS_ROOT=/
#ENV THIRD_PARTY_ROOT=$CROS_ROOT/third_party
#RUN mkdir -p $THIRD_PARTY_ROOT
#ENV PLATFORM_ROOT=$CROS_ROOT/platform
#RUN mkdir -p $PLATFORM_ROOT



mkdir -p "${BUILD_DIR}/platform"
cd "${BUILD_DIR}/platform"
git clone https://android.googlesource.com/platform/external/crosvm \
  -b upstream-master

cd "${BUILD_DIR}/platform/crosvm"

RUSTFLAGS="-C link-arg=-Wl,-rpath,\$ORIGIN/../lib64/crosvm -C link-arg=-L${HOME}/lib" \
  cargo build --features gpu

# Save the outputs
mkdir -p "${OUT_DIR}"
cp Cargo.lock "${OUT_DIR}"
mkdir -p "${OUT_DIR}/bin/"
cp target/debug/crosvm "${OUT_DIR}/bin/"


cargo --version --verbose > "${OUT_DIR}/cargo_version.txt"
rustup show > "${OUT_DIR}/rustup_show.txt"
dpkg-query -W > "${OUT_DIR}/builder-packages.txt"

cd "${HOME}"
for i in $(find . -name .git -type d -print); do
  dir="$(dirname "$i")"
  pushd "${dir}" > /dev/null 2>&1
  echo "${dir}" \
    "$(git remote get-url "$(git remote show)")" \
    "$(git rev-parse HEAD)"
  popd > /dev/null 2>&1
done > "${OUT_DIR}/BUILD_INFO"

echo Results in ${OUT_DIR}
