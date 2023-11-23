load(":toolchain.bzl", "partition_toolchain")

toolchain_type(name = "partition_toolchain_type")

partition_toolchain(
    name = "partition_toolchain",
    build_image = "@make_injection//:host/linux-x86/bin/build_image",
    mkuserimg_mke2fs = "@make_injection//:host/linux-x86/bin/mkuserimg_mke2fs",
    tags = ["manual"],
)

toolchain(
    name = "partition_toolchain_def",
    exec_compatible_with = [
        "//build/bazel/platforms/arch:x86_64",
        "//build/bazel/platforms/os:linux",
    ],
    tags = ["manual"],
    target_compatible_with = [
        "//build/bazel/platforms/os:android",
    ],
    toolchain = ":partition_toolchain",
    toolchain_type = ":partition_toolchain_type",
)
