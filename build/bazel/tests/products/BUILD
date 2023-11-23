load("@//build/bazel/product_config:android_product.bzl", "android_product")
load("@//build/bazel/tests/products:aosp_arm.variables.bzl", _soong_variables_arm = "variables")
load("@//build/bazel/tests/products:aosp_arm64.variables.bzl", _soong_variables_arm64 = "variables")
load("@//build/bazel/tests/products:aosp_x86.variables.bzl", _soong_variables_x86 = "variables")
load("@//build/bazel/tests/products:aosp_x86_64.variables.bzl", _soong_variables_x86_64 = "variables")
load("@bazel_skylib//lib:dicts.bzl", "dicts")

package(default_visibility = [
    "@//build/bazel/product_config:__subpackages__",
])

# This package contains pregenerated soong.variables files for the aosp_<arch> products, used to
# make platforms for testing. This is an optimization, we could generate these directly from source
# at build time but it would add time to every `m nothing`. Converting the product config makefiles
# to starlark and checking them in would also solve this performance issue.
#
# This is also where we can define platforms that have set product config variables to certain
# values for testing. Unfortunately we cannot just transition on a single product config variable
# due to limitations in bazel.

android_product(
    name = "aosp_arm_for_testing",
    soong_variables = _soong_variables_arm,
)

android_product(
    name = "aosp_arm_for_testing_custom_linker_alignment",
    soong_variables = dicts.add(
        _soong_variables_arm,
        {"DeviceMaxPageSizeSupported": "65536"},
    ),
)

android_product(
    name = "aosp_arm64_for_testing",
    soong_variables = _soong_variables_arm64,
)

android_product(
    name = "aosp_arm64_for_testing_no_compression",
    soong_variables = dicts.add(
        _soong_variables_arm64,
        {"CompressedApex": False},
    ),
)

android_product(
    name = "aosp_arm64_for_testing_unbundled_build",
    soong_variables = dicts.add(
        _soong_variables_arm64,
        {"Unbundled_build": True},
    ),
)

android_product(
    name = "aosp_arm64_for_testing_with_overrides_and_app_cert",
    soong_variables = dicts.add(
        _soong_variables_arm64,
        {
            "ManifestPackageNameOverrides": [
                "apex_certificate_label_with_overrides:another",
                "package_name_override_from_config:another.package",
            ],
            "CertificateOverrides": [
                "apex_certificate_label_with_overrides:another.certificate",
            ],
            "DefaultAppCertificate": "build/bazel/rules/apex/testdata/devkey",
        },
    ),
)

android_product(
    name = "aosp_arm64_for_testing_custom_linker_alignment",
    soong_variables = dicts.add(
        _soong_variables_arm64,
        {"DeviceMaxPageSizeSupported": "16384"},
    ),
)

android_product(
    name = "aosp_x86_for_testing",
    soong_variables = _soong_variables_x86,
)

android_product(
    name = "aosp_x86_64_for_testing",
    soong_variables = _soong_variables_x86_64,
)
