message(STATUS "Enabling bluetooth LMP module.")

# TODO(b/279494407): migrate hci packets to new generator
android_bluetooth_packet_gen(
    GENERATED hci_packets_rs INCLUDES packets SRC hci/hci_packets.pdl
    SOURCE_DIR ${ROOTCANAL_ROOT} LANG rust)

pdl_gen(
    NAME lmp_packets_rs
    INPUT ${ROOTCANAL_ROOT}/rust/lmp_packets.pdl
    OUTPUT lmp_packets.rs
    LANG rust)

# This will automatically register all the tests as well.
corrosion_import_crate(MANIFEST_PATH ${ROOTCANAL_ROOT}/rust/Cargo.toml FLAGS
                       --offline --verbose --verbose)
corrosion_set_env_vars(rootcanal-rs
  LMP_PACKETS_PREBUILT=${lmp_packets_rs}
  HCI_PACKETS_PREBUILT=${hci_packets_rs}
  CARGO_HOME=${Rust_CARGO_HOME})

android_license(TARGET rootcanal-rs LIBNAME None SPDX None LICENSE None LOCAL None)

add_custom_target(lmp_rust_packets DEPENDS ${lmp_packets_rs}
                                           ${hci_packets_rs})

# Make sure we have the rust packets generated before we build them.
add_dependencies(cargo-build_rootcanal-rs lmp_rust_packets)

# Enable lmp in rootcanal.
target_link_libraries(libbt-rootcanal PUBLIC rootcanal-rs)
target_include_directories(libbt-rootcanal
    PUBLIC ${ROOTCANAL_ROOT}/rust/include)
