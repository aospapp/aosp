# Copyright (C) 2023 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

load("@//build/bazel/tests/products:product_labels.bzl", _test_product_labels = "product_labels")
load("@soong_injection//product_config_platforms:product_labels.bzl", _product_labels = "product_labels")
load("//build/bazel/platforms/arch/variants:constants.bzl", _arch_constants = "constants")
load("//build/bazel/product_variables:constants.bzl", "constants")
load(
    "//prebuilts/clang/host/linux-x86:cc_toolchain_constants.bzl",
    "arch_to_variants",
    "variant_constraints",
    "variant_name",
)
load(":product_variables_providing_rule.bzl", "product_variables_providing_rule")

all_android_product_labels = _product_labels + _test_product_labels

def _is_variant_default(arch, variant):
    return variant == None or variant in (arch, "generic")

def _soong_arch_config_to_struct(soong_arch_config):
    return struct(
        arch = soong_arch_config["arch"],
        arch_variant = soong_arch_config["arch_variant"],
        cpu_variant = soong_arch_config["cpu_variant"],
    )

def _determine_target_arches_from_config(config):
    arches = []

    # ndk_abis and aml_abis explicitly get handled first as they override any setting
    # for DeviceArch, DeviceSecondaryArch in Soong:
    # https://cs.android.com/android/platform/superproject/+/master:build/soong/android/config.go;l=455-468;drc=b45a2ea782074944f79fc388df20b06e01f265f7
    if config.get("Ndk_abis"):
        for arch_config in _arch_constants.ndk_arches:
            arches.append(_soong_arch_config_to_struct(arch_config))
        return arches
    elif config.get("Aml_abis"):
        for arch_config in _arch_constants.aml_arches:
            arches.append(_soong_arch_config_to_struct(arch_config))
        return arches

    arch = config.get("DeviceArch")
    arch_variant = config.get("DeviceArchVariant")
    cpu_variant = config.get("DeviceCpuVariant")

    if _is_variant_default(arch, arch_variant):
        arch_variant = ""
    if _is_variant_default(arch, cpu_variant):
        cpu_variant = ""

    if not arch:
        # TODO(b/258839711): determine how to better id whether a config is actually host only or we're just missing the target config
        if "DeviceArch" in config:
            fail("No architecture was specified in the product config, expected one of Ndk_abis, Aml_abis, or DeviceArch to be set:\n%s" % config)
        else:
            return arches

    arches.append(struct(
        arch = arch,
        arch_variant = arch_variant,
        cpu_variant = cpu_variant,
    ))

    arch = config.get("DeviceSecondaryArch")
    arch_variant = config.get("DeviceSecondaryArchVariant")
    cpu_variant = config.get("DeviceSecondaryCpuVariant")

    if _is_variant_default(arch, arch_variant):
        arch_variant = ""
    if _is_variant_default(arch, cpu_variant):
        cpu_variant = ""

    if arch:
        arches.append(struct(
            arch = arch,
            arch_variant = arch_variant,
            cpu_variant = cpu_variant,
        ))
    return arches

def _product_variable_constraint_settings(variables):
    constraints = []

    local_vars = dict(variables)

    # Native_coverage is not set within soong.variables, but is hardcoded
    # within config.go NewConfig
    local_vars["Native_coverage"] = (
        local_vars.get("ClangCoverage", False) or
        local_vars.get("GcovCoverage", False)
    )

    # Some attributes on rules are able to access the values of product
    # variables via make-style expansion (like $(foo)). We collect the values
    # of the relevant product variables here so that it can be passed to
    # product_variables_providing_rule, which exports a
    # platform_common.TemplateVariableInfo provider to allow the substitution.
    attribute_vars = {}

    def add_attribute_var(typ, var, value):
        if typ == "bool":
            attribute_vars[var] = "1" if value else "0"
        elif typ == "list":
            attribute_vars[var] = ",".join(value)
        elif typ == "int":
            attribute_vars[var] = str(value)
        elif typ == "string":
            attribute_vars[var] = value

    # Generate constraints for Soong config variables (bool, value, string typed).
    vendor_vars = local_vars.pop("VendorVars", default = {})
    for (namespace, variables) in vendor_vars.items():
        for (var, value) in variables.items():
            # All vendor vars are Starlark string-typed, even though they may be
            # boxed bools/strings/arbitrary printf'd values, like numbers, so
            # we'll need to do some translation work here by referring to
            # soong_injection's generated data.

            if value == "":
                # Variable is not set so skip adding this as a constraint.
                continue

            # Create the identifier for the constraint var (or select key)
            config_var = namespace + "__" + var

            # List of all soong_config_module_type variables.
            if not config_var in constants.SoongConfigVariables:
                continue

            # Normalize all constraint vars (i.e. select keys) to be lowercased.
            constraint_var = config_var.lower()

            if config_var in constants.SoongConfigBoolVariables:
                constraints.append("@//build/bazel/product_variables:" + constraint_var)
            elif config_var in constants.SoongConfigStringVariables:
                # The string value is part of the the select key.
                constraints.append("@//build/bazel/product_variables:" + constraint_var + "__" + value.lower())
            elif config_var in constants.SoongConfigValueVariables:
                # For value variables, providing_vars add support for substituting
                # the value using TemplateVariableInfo.
                constraints.append("@//build/bazel/product_variables:" + constraint_var)
                add_attribute_var("string", constraint_var, value)

    for (var, value) in local_vars.items():
        # TODO(b/187323817): determine how to handle remaining product
        # variables not used in product_variables
        constraint_var = var.lower()
        if not constants.ProductVariables.get(constraint_var):
            continue

        # variable.go excludes nil values
        add_constraint = (value != None)
        add_attribute_var(type(value), var, value)
        if type(value) == "bool":
            # variable.go special cases bools
            add_constraint = value

        if add_constraint:
            constraints.append("@//build/bazel/product_variables:" + constraint_var)

    return constraints, attribute_vars

def _define_platform_for_arch(name, common_constraints, arch, secondary_arch = None):
    if secondary_arch == None:
        # When there is no secondary arch, we'll pretend it exists but is the same as the primary arch
        secondary_arch = arch
    native.platform(
        name = name,
        constraint_values = common_constraints + [
            "@//build/bazel/platforms/arch:" + arch.arch,
            "@//build/bazel/platforms/arch:secondary_" + secondary_arch.arch,
            "@//build/bazel/platforms/os:android",
        ] + ["@" + v for v in variant_constraints(
            arch,
            _arch_constants.AndroidArchToVariantToFeatures[arch.arch],
        )],
    )

def _define_platform_for_arch_with_secondary(name, common_constraints, arch, secondary_arch = None):
    if secondary_arch != None:
        _define_platform_for_arch(name, common_constraints, arch, secondary_arch)
        _define_platform_for_arch(name + "_secondary", common_constraints, secondary_arch)
    else:
        _define_platform_for_arch(name, common_constraints, arch)
        native.alias(
            name = name + "_secondary",
            actual = ":" + name,
        )

def _verify_product_is_registered(name):
    """
    Verifies that this android_product() is listed in all_android_product_labels.

    all_android_product_labels is used to build a select statement from each product to its
    _product_vars rule. This is because we store most product configuration in a rule instead of
    constraint settings or build settings due to limitations in bazel. (constraint settings can't
    be unbounded, typed, or have dependencies, build settings can't be set with --platforms)
    """
    my_label = native.repository_name() + "//" + native.package_name() + ":" + name
    for label in all_android_product_labels:
        if my_label == label:
            return
    fail("All android_product() instances must be listed in all_android_product_labels in " +
         "//build/bazel/product_config/android_product.bzl. By default the products generated " +
         "from legacy android product configurations are included, additional platforms (like " +
         "testing-specific platforms) must be manually listed in " +
         "//build/bazel/tests/products/product_labels.bzl.")

def android_product(name, soong_variables):
    """
    android_product integrates product variables into Bazel platforms.

    This uses soong.variables to create constraints and platforms used by the
    bazel build. The soong.variables file itself contains a post-processed list of
    variables derived from Make variables, through soong_config.mk, generated
    during the product config step.

    Some constraints used here are handcrafted in
    //build/bazel/platforms/{arch,os}. The rest are dynamically generated.

    If you're looking for what --config=android, --config=linux_x86_64 or most
    select statements in the BUILD files (ultimately) refer to, they're all
    created here.
    """
    _verify_product_is_registered(name)

    product_var_constraints, attribute_vars = _product_variable_constraint_settings(soong_variables)
    arch_configs = _determine_target_arches_from_config(soong_variables)

    product_variables_providing_rule(
        name = name + "_product_vars",
        attribute_vars = attribute_vars,
        product_vars = soong_variables,
    )

    native.constraint_value(
        name = name + "_constraint_value",
        constraint_setting = "@//build/bazel/product_config:current_product",
    )

    common_constraints = product_var_constraints + [name + "_constraint_value"]

    # TODO(b/258802089): figure out how to deal with multiple arches for target
    if len(arch_configs) > 0:
        arch = arch_configs[0]
        secondary_arch = None
        if len(arch_configs) > 1:
            secondary_arch = arch_configs[1]

        _define_platform_for_arch_with_secondary(name, common_constraints, arch, secondary_arch)

        # These variants are mostly for mixed builds, which may request a
        # module with a certain arch
        for arch, variants in arch_to_variants.items():
            for variant in variants:
                native.platform(
                    name = name + "_android_" + arch + variant_name(variant),
                    constraint_values = common_constraints + [
                        "@//build/bazel/platforms/arch:" + arch,
                        "@//build/bazel/platforms/arch:secondary_" + arch,
                        "@//build/bazel/platforms/os:android",
                    ] + ["@" + v for v in variant_constraints(
                        variant,
                        _arch_constants.AndroidArchToVariantToFeatures[arch],
                    )],
                )

        arch_transitions = [
            struct(
                name = "arm",
                arch = struct(
                    arch = "arm",
                    arch_variant = "armv7-a-neon",
                    cpu_variant = "",
                ),
                secondary_arch = None,
            ),
            struct(
                name = "arm64",
                arch = struct(
                    arch = "arm64",
                    arch_variant = "armv8-a",
                    cpu_variant = "",
                ),
                secondary_arch = struct(
                    arch = "arm",
                    arch_variant = "armv7-a-neon",
                    cpu_variant = "",
                ),
            ),
            struct(
                name = "arm64only",
                arch = struct(
                    arch = "arm64",
                    arch_variant = "armv8-a",
                    cpu_variant = "",
                ),
                secondary_arch = None,
            ),
            struct(
                name = "x86",
                arch = struct(
                    arch = "x86",
                    arch_variant = "",
                    cpu_variant = "",
                ),
                secondary_arch = None,
            ),
            struct(
                name = "x86_64",
                arch = struct(
                    arch = "x86_64",
                    arch_variant = "",
                    cpu_variant = "",
                ),
                secondary_arch = struct(
                    arch = "x86",
                    arch_variant = "",
                    cpu_variant = "",
                ),
            ),
            struct(
                name = "x86_64only",
                arch = struct(
                    arch = "x86_64",
                    arch_variant = "",
                    cpu_variant = "",
                ),
                secondary_arch = None,
            ),
        ]

        # TODO(b/249685973): Remove this, this is currently just for aabs
        # to build each architecture
        for arch in arch_transitions:
            _define_platform_for_arch_with_secondary(name + "__internal_" + arch.name, common_constraints, arch.arch, arch.secondary_arch)

    # Now define the host platforms. We need a host platform per product because
    # the host platforms still use the product variables.
    # TODO(b/262753134): Investigate making the host platforms product-independant
    native.platform(
        name = name + "_linux_x86",
        constraint_values = common_constraints + [
            "@//build/bazel/platforms/arch:x86",
            "@//build/bazel/platforms/os:linux",
        ],
    )

    native.platform(
        name = name + "_linux_x86_64",
        constraint_values = common_constraints + [
            "@//build/bazel/platforms/arch:x86_64",
            "@//build/bazel/platforms/os:linux",
        ],
    )

    native.platform(
        name = name + "_linux_musl_x86",
        constraint_values = common_constraints + [
            "@//build/bazel/platforms/arch:x86",
            "@//build/bazel/platforms/os:linux_musl",
        ],
    )

    native.platform(
        name = name + "_linux_musl_x86_64",
        constraint_values = common_constraints + [
            "@//build/bazel/platforms/arch:x86_64",
            "@//build/bazel/platforms/os:linux_musl",
        ],
    )

    # linux_bionic is the OS for the Linux kernel plus the Bionic libc runtime, but
    # without the rest of Android.
    native.platform(
        name = name + "_linux_bionic_arm64",
        constraint_values = common_constraints + [
            "@//build/bazel/platforms/arch:arm64",
            "@//build/bazel/platforms/os:linux_bionic",
        ],
    )

    native.platform(
        name = name + "_linux_bionic_x86_64",
        constraint_values = common_constraints + [
            "@//build/bazel/platforms/arch:x86_64",
            "@//build/bazel/platforms/os:linux_bionic",
        ],
    )

    native.platform(
        name = name + "_darwin_arm64",
        constraint_values = common_constraints + [
            "@//build/bazel/platforms/arch:arm64",
            "@//build/bazel/platforms/os:darwin",
        ],
    )

    native.platform(
        name = name + "_darwin_x86_64",
        constraint_values = common_constraints + [
            "@//build/bazel/platforms/arch:x86_64",
            "@//build/bazel/platforms/os:darwin",
        ],
    )

    native.platform(
        name = name + "_windows_x86",
        constraint_values = common_constraints + [
            "@//build/bazel/platforms/arch:x86",
            "@//build/bazel/platforms/os:windows",
        ],
    )

    native.platform(
        name = name + "_windows_x86_64",
        constraint_values = common_constraints + [
            "@//build/bazel/platforms/arch:x86_64",
            "@//build/bazel/platforms/os:windows",
        ],
    )
