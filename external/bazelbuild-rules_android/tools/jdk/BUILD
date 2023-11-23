load("@bazel_tools//tools/jdk:default_java_toolchain.bzl", "default_java_toolchain")

# AOSP-only change
alias(
    name = "toolchain_android_only",
    actual = "@bazel_tools//tools/jdk:current_java_toolchain",
    visibility = ["//visibility:public"],
)

alias(
    name = "current_java_runtime",
    actual = "@bazel_tools//tools/jdk:current_java_runtime",
    visibility = ["//visibility:public"],
)
