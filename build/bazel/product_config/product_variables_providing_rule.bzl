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
load("@bazel_skylib//lib:paths.bzl", "paths")
load("@soong_injection//metrics:converted_modules_path_map.bzl", "modules")

ProductVariablesInfo = provider(
    "ProductVariablesInfo provides the android product config variables.",
    fields = {
        "Always_use_prebuilt_sdks": "Boolean to always use a prebuilt sdk instead of source-built.",
        "CompressedApex": "Boolean indicating if apexes are compressed or not.",
        "DefaultAppCertificate": "The default certificate to sign APKs and APEXes with. The $(dirname) of this certificate will also be used to find additional certificates when modules only give their names.",
        "TidyChecks": "List of clang tidy checks to enable.",
        "Unbundled_apps": "List of apps to build as unbundled.",
        "Unbundled_build": "True if this is an unbundled build",
        "ManifestPackageNameOverrides": "A list of string:string mapping from APEX/APK name to package name to override the AndroidManifest.xml package of the module.",
        "CertificateOverrides": "A list of string:string mapping from APEX/APK name to the certificate name to override the certificate used to sign the APEX/APK container.",
        "DeviceMaxPageSizeSupported": "String indicating the max-page-size supported by the device.",
        "DeviceProduct": "Device product",
        "DeviceName": "Device name",
        "Platform_version_name": "Platform version name",
        "BuildId": "Build ID",
        "ProductManufacturer": "Product manufacturer",
        "ProductBrand": "Product brand",
        "TargetBuildVariant": "Target build variant",
        "BuildVersionTags": "Build version tags",
    },
)

ProductVariablesDepsInfo = provider(
    "ProductVariablesDepsInfo provides fields that are not regular product config variables, but rather the concrete files that other product config vars reference.",
    fields = {
        "DefaultAppCertificateFiles": "All the .pk8, .pem, and .avbpubkey files in the DefaultAppCertificate directory.",
        "OverridingCertificateFiles": "All the android_certificate_directory filegroups referenced by certificates in the CertificateOverrides mapping. Superset of DefaultAppCertificateFiles.",
    },
)

def _product_variables_providing_rule_impl(ctx):
    vars = json.decode(ctx.attr.product_vars)

    tidy_checks = vars.get("TidyChecks", "")
    tidy_checks = tidy_checks.split(",") if tidy_checks else []
    target_build_variant = "user"
    if vars.get("Eng"):
        target_build_variant = "eng"
    elif vars.get("Debuggable"):
        target_build_variant = "userdebug"

    return [
        platform_common.TemplateVariableInfo(ctx.attr.attribute_vars),
        ProductVariablesInfo(
            Always_use_prebuilt_sdks = vars.get("Always_use_prebuilt_sdks", False),
            CompressedApex = vars.get("CompressedApex", False),
            DefaultAppCertificate = vars.get("DefaultAppCertificate", None),
            TidyChecks = tidy_checks,
            Unbundled_apps = vars.get("Unbundled_apps", []),
            Unbundled_build = vars.get("Unbundled_build", False),
            ManifestPackageNameOverrides = vars.get("ManifestPackageNameOverrides", []),
            CertificateOverrides = vars.get("CertificateOverrides", []),
            DeviceMaxPageSizeSupported = vars.get("DeviceMaxPageSizeSupported", ""),
            DeviceProduct = vars.get("DeviceProduct", ""),
            DeviceName = vars.get("DeviceName", ""),
            Platform_version_name = vars.get("Platform_version_name", ""),
            BuildId = vars.get("BuildId", ""),
            ProductManufacturer = vars.get("ProductManufacturer", ""),
            ProductBrand = vars.get("ProductBrand", ""),
            TargetBuildVariant = target_build_variant,
            BuildVersionTags = vars.get("BuildVersionTags", []),
        ),
        ProductVariablesDepsInfo(
            DefaultAppCertificateFiles = ctx.files.default_app_certificate_filegroup,
            OverridingCertificateFiles = ctx.files.overriding_cert_filegroups,
        ),
    ]

# Provides product variables for templated string replacement.
_product_variables_providing_rule = rule(
    implementation = _product_variables_providing_rule_impl,
    attrs = {
        "attribute_vars": attr.string_dict(doc = "Variables that can be expanded using make-style syntax in attributes"),
        "product_vars": attr.string(doc = "Regular android product variables, a copy of the soong.variables file. Unfortunately this needs to be a json-encoded string because bazel attributes can only be simple types."),
        "default_app_certificate_filegroup": attr.label(doc = "The filegroup that contains all the .pem, .pk8, and .avbpubkey files in $(dirname product_vars.DefaultAppCertificate)"),
        "overriding_cert_filegroups": attr.label_list(doc = "All certificates that are used to override an android_app_certificate using the CertificatesOverride product variable."),
    },
)

def product_variables_providing_rule(
        name,
        attribute_vars,
        product_vars):
    default_app_certificate_filegroup = None
    default_app_certificate = product_vars.get("DefaultAppCertificate", None)
    if default_app_certificate:
        default_app_certificate_filegroup = "@//" + paths.dirname(default_app_certificate) + ":android_certificate_directory"

    # Overriding certificates can be from anywhere, and may not always be in the
    # same directory as DefaultAppCertificate / PRODUCT_DEFAULT_DEV_CERTIFICATE.
    # Collect their additional 'android_certificate_directory' filegroups here.
    #
    # e.g. if CertificateOverrides is [m1:c1, m2:c2, ..., mn:cn], then collect
    # //pkg(c1):android_certificate_directory,
    # //pkg(c2):android_certificate_directory, and so on.
    #
    # We cannot add directory dependencies on c1, c2, etc because that would
    # form a cyclic dependency graph from product_vars to
    # android_app_certificate (where the override happens) and back to
    # product_vars again. So reference the filegroups instead.
    #
    # Note that this relies on a global bzl mapping of android_app_certificate
    # module names to the packages they belong to.  This is currently generated
    # by bp2build, but may need to be maintained in a different approach in the
    # future when the android_app_certificate modules are no longer auto converted.
    cert_overrides = product_vars.get("CertificateOverrides", [])
    cert_filegroups = {}
    if default_app_certificate_filegroup:
        cert_filegroups[default_app_certificate_filegroup] = True
    if cert_overrides:
        for c in cert_overrides:
            module_name = c.split(":")[1]
            pkg = modules.get(module_name)  # use the global mapping of module names to their enclosing package.
            if pkg:
                # not everything is converted.
                cert_filegroups["@" + pkg + ":android_certificate_directory"] = True

    _product_variables_providing_rule(
        name = name,
        attribute_vars = attribute_vars,
        product_vars = json.encode(product_vars),
        default_app_certificate_filegroup = default_app_certificate_filegroup,
        overriding_cert_filegroups = cert_filegroups.keys(),
    )
