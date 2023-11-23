message(STATUS "Adding rust ${CMAKE_CURRENT_LIST_DIR}/Cargo.toml")

# This will automatically register all the tests as well.
corrosion_import_crate(MANIFEST_PATH Cargo.toml FLAGS --offline --verbose)
corrosion_set_env_vars(netsim-common CARGO_HOME=${Rust_CARGO_HOME})
corrosion_set_env_vars(netsim-cxx CARGO_HOME=${Rust_CARGO_HOME})
corrosion_set_env_vars(netsim-cli CARGO_HOME=${Rust_CARGO_HOME})
corrosion_set_env_vars(frontend-proto CARGO_HOME=${Rust_CARGO_HOME})
corrosion_set_env_vars(frontend-client-cxx CARGO_HOME=${Rust_CARGO_HOME})

# cxx crates
if(WIN32)
  # 1. Set CMAKE_CXX_FLAGS, as it is not set in Rust crates imported by
  #    corrosion.
  # 2. Set RUST_CXX_NO_EXCEPTIONS preprocesor definition for cxx crate to disable
  #    compilation error message of "cannot use 'throw' with exceptions".
  # 3. MSVC uses environment variable "CL" to prepend arguments to the
  #    command-line arguments. However, we verified MSVC also supports CXXFLAGS,
  #    which is used by cc crate.
  # 4. This isn't needed in mac and Linux because corrosion doesn't pass
  #    -fno-exception flag.
  set(CXXFLAGS "${CMAKE_CXX_FLAGS} /DRUST_CXX_NO_EXCEPTIONS")
  corrosion_set_env_vars(frontend-client-cxx CXXFLAGS=${CXXFLAGS})
  corrosion_set_env_vars(netsim-cli CXXFLAGS=${CXXFLAGS})
  corrosion_set_env_vars(netsim-cxx CXXFLAGS=${CXXFLAGS})
endif()

if(LINUX)
  # b/260271048
  set(RUSTFLAGS "-Clink-args=-Wl,-rpath,${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
  corrosion_add_target_rustflags(frontend-client-cxx ${RUSTFLAGS})
  corrosion_add_target_rustflags(netsim-cli ${RUSTFLAGS})
  corrosion_add_target_rustflags(netsim-cxx ${RUSTFLAGS})
endif()

add_subdirectory(frontend-proto)
# Steps to generate rust files are as follows:
#
# 1. sudo apt-get install protobuf-compiler
# 2. Uncomment `build = "build_cargo.rs"` in rust/frontend-proto/Cargo.toml.
# 3. cargo build
# 4. bash scripts/format_code.sh

android_license(TARGET "netsim-cxx" LIBNAME None SPDX None LICENSE None
                LOCAL None)
android_license(TARGET "backend-proto" LIBNAME None SPDX None LICENSE None
                LOCAL None)
android_license(TARGET "frontend-proto" LIBNAME None SPDX None LICENSE None
                LOCAL None)
android_license(TARGET "frontend-client-cxx" LIBNAME None SPDX None LICENSE None
                LOCAL None)

add_library(netsim-cli-rust-lib INTERFACE)

target_link_libraries(netsim-cli-rust-lib
                      INTERFACE frontend-proto frontend-client-cxx netsim-cli)

android_license(TARGET netsim-cli-rust-lib LIBNAME None SPDX None LICENSE None
                LOCAL None)
