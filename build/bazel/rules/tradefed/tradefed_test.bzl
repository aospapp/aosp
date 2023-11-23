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

load("@bazel_skylib//lib:unittest.bzl", "analysistest", "asserts")
load("//build/bazel/rules/cc:cc_library_static.bzl", "cc_library_static")
load(
    "//build/bazel/rules/test_common:paths.bzl",
    "get_output_and_package_dir_based_path",
)
load(":tradefed.bzl", "tradefed_device_test", "tradefed_host_driven_test")

tradefed_dependencies = [
    "atest_tradefed.sh",
    "libatest-tradefed.jar",
    "libbazel-result-reporter.jar",
    "tradefed.jar",
]

def _test_tradefed_config_generation_impl(ctx):
    env = analysistest.begin(ctx)
    actions = analysistest.target_actions(env)

    actual_outputs = []
    for action in actions:
        for output in action.outputs.to_list():
            actual_outputs.append(output.path)

    for expected_output in ctx.attr.expected_outputs:
        expected_output = get_output_and_package_dir_based_path(env, expected_output)
        asserts.true(
            env,
            expected_output in actual_outputs,
            "Expected: " + expected_output +
            " in outputs: " + str(actual_outputs),
        )
    return analysistest.end(env)

tradefed_config_generation_test = analysistest.make(
    _test_tradefed_config_generation_impl,
    attrs = {
        "expected_outputs": attr.string_list(),
    },
)

def tradefed_cc_outputs():
    name = "cc"
    target = "cc_target"

    cc_library_static(
        name = target,
        srcs = ["foo.c"],
        tags = ["manual"],
    )
    tradefed_device_test(
        name = name,
        test_identifier = target,
        tags = ["manual"],
        test = target,
        test_config = "//build/bazel/rules/tradefed/test:example_config.xml",
        target_compatible_with = ["//build/bazel/platforms/os:linux"],
    )

    # check for expected output files (.config file  and .sh script)
    tradefed_config_generation_test(
        name = name + "_test",
        target_under_test = name,
        expected_outputs = [
            "tradefed_test_" + name + ".sh",
            "result-reporters.xml",
            target + ".config",
        ] + tradefed_dependencies,
        target_compatible_with = ["//build/bazel/platforms/os:linux"],
    )
    return name + "_test"

def tradefed_cc_host_outputs():
    name = "cc_host"
    target = "cc_host_target"

    cc_library_static(
        name = target,
        tags = ["manual"],
    )
    tradefed_host_driven_test(
        name = name,
        test_identifier = target,
        tags = ["manual"],
        test = target,
        test_config = "//build/bazel/rules/tradefed/test:example_config.xml",
        target_compatible_with = ["//build/bazel/platforms/os:linux"],
    )

    # check for expected output files (.config file  and .sh script)
    tradefed_config_generation_test(
        name = name + "_test",
        target_under_test = name,
        expected_outputs = [
            "tradefed_test_" + name + ".sh",
            "result-reporters.xml",
            target + ".config",
        ] + tradefed_dependencies,
        target_compatible_with = ["//build/bazel/platforms/os:linux"],
    )
    return name + "_test"

def tradefed_cc_host_outputs_generate_test_config():
    name = "cc_host_generate_config"
    target = "cc_host_target_generate_config"

    cc_library_static(
        name = target,
        tags = ["manual"],
    )
    tradefed_host_driven_test(
        name = name,
        test_identifier = target,
        tags = ["manual"],
        test = target,
        template_test_config = "//build/make/core:native_host_test_config_template.xml",
        template_configs = [
            "<option name=\"config-descriptor:metadata\" key=\"parameter\" value=\"not_multi_abi\" />",
            "<option name=\"config-descriptor:metadata\" key=\"parameter\" value=\"secondary_user\" />",
        ],
        target_compatible_with = ["//build/bazel/platforms/os:linux"],
    )

    # check for expected output files (.config file  and .sh script)
    tradefed_config_generation_test(
        name = name + "_test",
        target_under_test = name,
        expected_outputs = [
            "tradefed_test_" + name + ".sh",
            "result-reporters.xml",
            target + ".config",
        ] + tradefed_dependencies,
        target_compatible_with = ["//build/bazel/platforms/os:linux"],
    )
    return name + "_test"

def tradefed_test_suite(name):
    native.test_suite(
        name = name,
        tests = [
            tradefed_cc_outputs(),
            tradefed_cc_host_outputs(),
            tradefed_cc_host_outputs_generate_test_config(),
        ],
    )
