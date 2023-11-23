message(STATUS "Adding rust ${CMAKE_CURRENT_LIST_DIR}/Cargo.toml")

# This will automatically register all the tests as well.
corrosion_import_crate(MANIFEST_PATH Cargo.toml FLAGS --offline --verbose)

corrosion_set_env_vars(backend-proto
                       PROTOBUF_INCLUDE_DIR=${NATIVE_PROTOBUF_INCLUDE_DIR})
# Set CMake executable filepath because it's not found by cmake Rust crate. From
# https://github.com/rust-lang/cmake-rs, the CMake executable is assumed to be
# cmake unless the CMAKE environmental variable is set.
corrosion_set_env_vars(backend-proto CMAKE=${CMAKE_COMMAND})

if(LINUX)
  # b/260271048
  corrosion_add_target_rustflags(
    backend-proto "-Clink-args=-Wl,-rpath,${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
endif()
