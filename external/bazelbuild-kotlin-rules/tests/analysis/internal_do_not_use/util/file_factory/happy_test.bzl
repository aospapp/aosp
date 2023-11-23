# Copyright 2022 Google LLC. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the License);
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

"""file_factory_happy_test"""

load("//kotlin/jvm/internal_do_not_use/util:file_factory.bzl", "FileFactory")
load("//:visibility.bzl", "RULES_KOTLIN")

def _test_base_from_file(ctx, pkg_path):
    base_file = ctx.actions.declare_file("file/base.txt")
    factory = FileFactory(ctx, base_file)

    _assert_equals(pkg_path + "/file/base", factory.base_as_path)

    return [base_file]

def _test_declare(ctx, pkg_path):
    factory = FileFactory(ctx, "string/base")

    _assert_equals(pkg_path + "/string/base", factory.base_as_path)

    a_file = factory.declare_file("a.txt")
    _assert_equals(pkg_path + "/string/basea.txt", a_file.path)

    b_dir = factory.declare_directory("b_dir")
    _assert_equals(pkg_path + "/string/baseb_dir", b_dir.path)

    return [a_file, b_dir]

def _test_derive(ctx, pkg_path):
    factory = FileFactory(ctx, "")

    # Once
    factory_once = factory.derive("once")
    _assert_equals(pkg_path + "/once", factory_once.base_as_path)

    # Twice
    factory_twice = factory_once.derive("/twice")
    _assert_equals(pkg_path + "/once/twice", factory_twice.base_as_path)

def _assert_equals(expected, actual):
    if expected != actual:
        fail("Expected '%s' but was '%s'" % (expected, actual))

def _file_factory_happy_test_impl(ctx):
    pkg_path = ctx.bin_dir.path + "/" + ctx.label.package
    all_files = []

    all_files.extend(_test_base_from_file(ctx, pkg_path))
    all_files.extend(_test_declare(ctx, pkg_path))
    _test_derive(ctx, pkg_path)

    ctx.actions.run_shell(
        outputs = all_files,
        command = "exit 1",
    )

    test_script = ctx.actions.declare_file(ctx.label.name + "_test.sh")
    ctx.actions.write(test_script, "#!/bin/bash", True)
    return [
        DefaultInfo(executable = test_script),
    ]

file_factory_happy_test = rule(
    implementation = _file_factory_happy_test_impl,
    test = True,
)
