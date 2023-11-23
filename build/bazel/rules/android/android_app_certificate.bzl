# Copyright (C) 2021 The Android Open Source Project
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
load("@bazel_skylib//rules:common_settings.bzl", "BuildSettingInfo")
load("//build/bazel/product_config:product_variables_providing_rule.bzl", "ProductVariablesDepsInfo", "ProductVariablesInfo")

AndroidAppCertificateInfo = provider(
    "Info needed for Android app certificates",
    fields = {
        "pem": "Certificate .pem file",
        "pk8": "Certificate .pk8 file",
        "key_name": "Key name",
    },
)

def _search_cert_files(cert_name, cert_files_to_search):
    pk8 = None
    pem = None
    for file in cert_files_to_search:
        if file.basename == cert_name + ".pk8":
            pk8 = file
        elif file.basename == cert_name + ".x509.pem":
            pem = file
    if not pk8 or not pem:
        fail("Could not find .x509.pem and/or .pk8 file with name '%s' in the following files: %s" % (cert_name, cert_files_to_search))
    return pk8, pem

def _maybe_override(ctx, cert_name):
    if not cert_name:
        fail("cert_name cannot be None")

    cert_overrides = ctx.attr._product_variables[ProductVariablesInfo].CertificateOverrides
    if not cert_overrides:
        return cert_name, False

    apex_name = ctx.attr._apex_name[BuildSettingInfo].value
    if not apex_name:
        # Only override in the apex configuration, because the apex module name is used as the key for overriding
        return cert_name, False

    matches = [o for o in cert_overrides if o.split(":")[0] == apex_name]

    if not matches:
        # no matches, no override.
        return cert_name, False

    if len(matches) > 1:
        fail("unexpected multiple certificate overrides for %s in: %s" % (apex_name, matches))

    # e.g. test1_com.android.tzdata:com.google.android.tzdata5.certificate
    new_cert_name = matches[0].split(":")[1]
    return new_cert_name.removesuffix(".certificate"), True

def _android_app_certificate_rule_impl(ctx):
    cert_name = ctx.attr.certificate
    pk8 = ctx.file.pk8
    pem = ctx.file.pem

    # Only override if the override mapping exists, otherwise we wouldn't be
    # able to find the new certs.
    overridden_cert_name, overridden = _maybe_override(ctx, cert_name)
    if overridden:
        cert_name = overridden_cert_name
        cert_files_to_search = ctx.attr._product_variables[ProductVariablesDepsInfo].OverridingCertificateFiles
        pk8, pem = _search_cert_files(cert_name, cert_files_to_search)

    return [
        AndroidAppCertificateInfo(pem = pem, pk8 = pk8, key_name = cert_name),
    ]

_android_app_certificate = rule(
    implementation = _android_app_certificate_rule_impl,
    attrs = {
        "pem": attr.label(mandatory = True, allow_single_file = [".pem"]),
        "pk8": attr.label(mandatory = True, allow_single_file = [".pk8"]),
        "certificate": attr.string(mandatory = True),
        "_apex_name": attr.label(default = "//build/bazel/rules/apex:apex_name"),
        "_product_variables": attr.label(
            default = "//build/bazel/product_config:product_vars",
        ),
        "_hardcoded_certs": attr.label(
            default = "//build/make/target/product/security:android_certificate_directory",
        ),
    },
)

def android_app_certificate(
        name,
        certificate,
        **kwargs):
    "Bazel macro to correspond with the Android app certificate Soong module."

    _android_app_certificate(
        name = name,
        pem = certificate + ".x509.pem",
        pk8 = certificate + ".pk8",
        certificate = certificate,
        **kwargs
    )

default_cert_directory = "build/make/target/product/security"

def _android_app_certificate_with_default_cert_impl(ctx):
    product_var_cert = ctx.attr._product_variables[ProductVariablesInfo].DefaultAppCertificate

    cert_name = ctx.attr.cert_name

    if cert_name and product_var_cert:
        cert_dir = paths.dirname(product_var_cert)
    elif cert_name:
        cert_dir = default_cert_directory
    elif product_var_cert:
        cert_name = paths.basename(product_var_cert)
        cert_dir = paths.dirname(product_var_cert)
    else:
        cert_name = "testkey"
        cert_dir = default_cert_directory

    if cert_dir != default_cert_directory:
        cert_files_to_search = ctx.attr._product_variables[ProductVariablesDepsInfo].DefaultAppCertificateFiles
    else:
        cert_files_to_search = ctx.files._hardcoded_certs

    cert_name, overridden = _maybe_override(ctx, cert_name)
    if overridden:
        cert_files_to_search = ctx.attr._product_variables[ProductVariablesDepsInfo].OverridingCertificateFiles
    pk8, pem = _search_cert_files(cert_name, cert_files_to_search)

    return [
        AndroidAppCertificateInfo(
            pk8 = pk8,
            pem = pem,
            key_name = "//" + cert_dir + ":" + cert_name,
        ),
    ]

android_app_certificate_with_default_cert = rule(
    doc = """
    This rule is the equivalent of an android_app_certificate, but uses the
    certificate with the given name from a certain folder, or the default
    certificate.

    Modules can give a simple name of a certificate instead of a full label to
    an android_app_certificate. This certificate will be looked for either in
    the package determined by the DefaultAppCertificate product config variable,
    or the hardcoded default directory. (build/make/target/product/security)

    If a name is not given, it will fall back to using the certificate termined
    by DefaultAppCertificate. (DefaultAppCertificate can function as both the
    default certificate to use if none is specified, and the folder to look for
    certificates in)

    If neither the name nor DefaultAppCertificate is given,
    build/make/target/product/security/testkey.{pem,pk8} will be used.

    Since this rule is intended to be used from other macros, it's common to have
    multiple android_app_certificate targets pointing to the same pem/pk8 files.
    """,
    implementation = _android_app_certificate_with_default_cert_impl,
    attrs = {
        "cert_name": attr.string(),
        "_product_variables": attr.label(
            default = "//build/bazel/product_config:product_vars",
        ),
        "_hardcoded_certs": attr.label(
            default = "//build/make/target/product/security:android_certificate_directory",
        ),
    },
)
