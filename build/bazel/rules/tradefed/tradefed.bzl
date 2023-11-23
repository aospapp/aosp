# Copyright (C) 2022 The Android Open Source Project
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

TRADEFED_TEST_ATTRIBUTES = {
    "test": attr.label(
        providers = [[CcInfo]],
        doc = "Test target to run in tradefed.",
    ),
    "test_identifier": attr.string(),
    "host_driven": attr.bool(
        default = True,
        doc = "Is a host driven test",
    ),
    "_tradefed_test_sh_template": attr.label(
        default = ":tradefed.sh.tpl",
        allow_single_file = True,
        doc = "Template script to launch tradefed.",
    ),
    "_tradefed_dependencies": attr.label_list(
        default = [
            "//prebuilts/runtime:prebuilt-runtime-adb",
            "//tools/tradefederation/prebuilts/filegroups/tradefed:bp2build_all_srcs",
            "//tools/tradefederation/prebuilts/filegroups/suite:compatibility-host-util-prebuilt",
            "//tools/tradefederation/prebuilts/filegroups/suite:compatibility-tradefed-prebuilt",
            "//tools/asuite/atest:atest-tradefed",
            "//tools/asuite/atest/bazel/reporter:bazel-result-reporter",
        ],
        doc = "Files needed on the PATH to run tradefed",
        cfg = "exec",
    ),

    # Test config and if test config generation attributes.
    "test_config": attr.label(
        allow_single_file = True,
        doc = "Test/Tradefed config.",
    ),
    "template_test_config": attr.label(
        allow_single_file = True,
        doc = "Template to generate test config.",
    ),
    "template_configs": attr.string_list(
        doc = "Extra tradefed config options to extend into generated test config.",
    ),
    "template_install_base": attr.string(
        default = "/data/local/tmp",
        doc = "Directory to install tests onto the device for generated config",
    ),
}

# Get test config if specified or generate test config from template.
def _get_or_generate_test_config(ctx):
    # Validate input
    c = ctx.file.test_config
    c_template = ctx.file.template_test_config
    if c and c_template:
        fail("Both test_config and test_config_template were provided, please use only 1 of them")
    if not c and not c_template:
        fail("Either test_config or test_config_template should be provided")

    # Check for existing tradefed config - and add a symlink with test_identifier.
    out = ctx.actions.declare_file(ctx.attr.test_identifier + ".config")
    if c:
        ctx.actions.symlink(
            output = out,
            target_file = c,
        )
        return out

    # No test config found, generate config from template.
    # Join extra configs together and add xml spacing indent.
    extra_configs = "\n    ".join(ctx.attr.template_configs)
    ctx.actions.expand_template(
        template = c_template,
        output = out,
        substitutions = {
            "{MODULE}": ctx.attr.test_identifier,
            "{EXTRA_CONFIGS}": extra_configs,
            "{TEST_INSTALL_BASE}": ctx.attr.template_install_base,
        },
    )
    return out

# Generate tradefed result reporter config.
def _create_result_reporter_config(ctx):
    result_reporters_config_file = ctx.actions.declare_file("result-reporters.xml")
    config_lines = [
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
        "<configuration>",
    ]

    result_reporters = [
        "com.android.tradefed.result.BazelExitCodeResultReporter",
        "com.android.tradefed.result.BazelXmlResultReporter",
    ]
    for result_reporter in result_reporters:
        config_lines.append("    <result_reporter class=\"%s\" />" % result_reporter)
    config_lines.append("</configuration>")

    ctx.actions.write(result_reporters_config_file, "\n".join(config_lines))
    return result_reporters_config_file

# Generate and run tradefed bash script.
def _tradefed_test_impl(ctx):
    # Get or generate test config.
    test_config = _get_or_generate_test_config(ctx)

    # Generate result reporter config file.
    report_config = _create_result_reporter_config(ctx)

    # Symlink file names if `__test_binary` was appended in a previous rule.
    targets = []
    for f in ctx.attr.test.files.to_list():
        if "__test_binary" not in f.basename:
            targets.append(f)
        else:
            file_name = f.basename.replace("__test_binary", "")
            out = ctx.actions.declare_file(file_name)
            ctx.actions.symlink(
                output = out,
                target_file = f,
            )
            targets.append(out)

    # Symlink tradefed dependencies.
    for f in ctx.files._tradefed_dependencies:
        out = ctx.actions.declare_file(f.basename)
        ctx.actions.symlink(
            output = out,
            target_file = f,
        )
        targets.append(out)

    # Gather runfiles.
    runfiles = ctx.runfiles()
    runfiles = runfiles.merge_all([
        ctx.attr.test.default_runfiles,
        ctx.runfiles(files = targets + [test_config, report_config]),
    ])

    # Generate script to run tradefed.
    script = ctx.actions.declare_file("tradefed_test_%s.sh" % ctx.label.name)
    ctx.actions.expand_template(
        template = ctx.file._tradefed_test_sh_template,
        output = script,
        is_executable = True,
        substitutions = {
            "{MODULE}": ctx.attr.test_identifier,
        },
    )

    return [DefaultInfo(
        executable = script,
        runfiles = runfiles,
    )]

# Generate and run tradefed bash script for deviceless (host) tests.
_tradefed_test = rule(
    doc = "A rule used to run tests using Tradefed",
    attrs = TRADEFED_TEST_ATTRIBUTES,
    test = True,
    implementation = _tradefed_test_impl,
)

def tradefed_host_driven_test(**kwargs):
    _tradefed_test(
        **kwargs
    )

def tradefed_device_test(**kwargs):
    _tradefed_test(
        host_driven = False,
        **kwargs
    )
