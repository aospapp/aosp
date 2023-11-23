cc=/usr/bin/clang
cxx=/usr/bin/clang++
rm -rf build
mkdir build
cd build
CC=$cc CXX=$cxx cmake . ../
#cmake . ../ -DCMAKE_TOOLCHAIN_FILE=../toolchain/toolchain-linux-x86_64.cmake
make -j24
