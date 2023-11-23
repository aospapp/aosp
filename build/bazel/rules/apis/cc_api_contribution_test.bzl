"""Copyright (C) 2022 The Android Open Source Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
"""

load("@bazel_skylib//lib:paths.bzl", "paths")
load("@bazel_skylib//lib:unittest.bzl", "analysistest", "asserts")
load(":cc_api_contribution.bzl", "CcApiContributionInfo", "CcApiHeaderInfo", "CcApiHeaderInfoList", "cc_api_contribution", "cc_api_headers", "cc_api_library_headers")

def _empty_include_dir_test_impl(ctx):
    env = analysistest.begin(ctx)
    target_under_test = analysistest.target_under_test(env)
    asserts.equals(env, paths.dirname(ctx.build_file_path), target_under_test[CcApiHeaderInfo].root)
    return analysistest.end(env)

empty_include_dir_test = analysistest.make(_empty_include_dir_test_impl)

def _empty_include_dir_test():
    test_name = "empty_include_dir_test"
    subject_name = test_name + "_subject"
    cc_api_headers(
        name = subject_name,
        hdrs = ["hdr.h"],
        tags = ["manual"],
    )
    empty_include_dir_test(
        name = test_name,
        target_under_test = subject_name,
    )
    return test_name

def _nonempty_include_dir_test_impl(ctx):
    env = analysistest.begin(ctx)
    target_under_test = analysistest.target_under_test(env)
    expected_root = paths.join(paths.dirname(ctx.build_file_path), ctx.attr.expected_include_dir)
    asserts.equals(env, expected_root, target_under_test[CcApiHeaderInfo].root)
    return analysistest.end(env)

nonempty_include_dir_test = analysistest.make(
    impl = _nonempty_include_dir_test_impl,
    attrs = {
        "expected_include_dir": attr.string(),
    },
)

def _nonempty_include_dir_test():
    test_name = "nonempty_include_dir_test"
    subject_name = test_name + "_subject"
    include_dir = "my/include"
    cc_api_headers(
        name = subject_name,
        include_dir = include_dir,
        hdrs = ["my/include/hdr.h"],
        tags = ["manual"],
    )
    nonempty_include_dir_test(
        name = test_name,
        target_under_test = subject_name,
        expected_include_dir = include_dir,
    )
    return test_name

def _api_library_headers_test_impl(ctx):
    env = analysistest.begin(ctx)
    target_under_test = analysistest.target_under_test(env)
    asserts.true(env, CcApiHeaderInfoList in target_under_test)
    headers_list = target_under_test[CcApiHeaderInfoList].headers_list
    actual_includes = sorted([headers.root for headers in headers_list if not headers.system])
    actual_system_includes = sorted([headers.root for headers in headers_list if headers.system])
    asserts.equals(env, ctx.attr.expected_includes, actual_includes)
    asserts.equals(env, ctx.attr.expected_system_includes, actual_system_includes)
    return analysistest.end(env)

api_library_headers_test = analysistest.make(
    impl = _api_library_headers_test_impl,
    attrs = {
        "expected_includes": attr.string_list(),
        "expected_system_includes": attr.string_list(),
    },
)

def _api_library_headers_test():
    test_name = "api_library_headers_test"
    subject_name = test_name + "_subject"
    cc_api_library_headers(
        name = subject_name,
        hdrs = [],
        export_includes = ["include1", "include2"],
        export_system_includes = ["system_include1"],
        deps = [":other_api_library_headers", "other_api_headers"],
        tags = ["manual"],
    )
    cc_api_library_headers(
        name = "other_api_library_headers",
        hdrs = [],
        export_includes = ["otherinclude1"],
        tags = ["manual"],
    )
    cc_api_headers(
        name = "other_api_headers",
        hdrs = [],
        include_dir = "otherinclude2",
        tags = ["manual"],
    )
    api_library_headers_test(
        name = test_name,
        target_under_test = subject_name,
        expected_includes = ["build/bazel/rules/apis/include1", "build/bazel/rules/apis/include2", "build/bazel/rules/apis/otherinclude1", "build/bazel/rules/apis/otherinclude2"],
        expected_system_includes = ["build/bazel/rules/apis/system_include1"],
    )
    return test_name

def _api_path_is_relative_to_workspace_root_test_impl(ctx):
    env = analysistest.begin(ctx)
    target_under_test = analysistest.target_under_test(env)
    expected_path = paths.join(paths.dirname(ctx.build_file_path), ctx.attr.expected_symbolfile)
    asserts.equals(env, expected_path, target_under_test[CcApiContributionInfo].api)
    return analysistest.end(env)

api_path_is_relative_to_workspace_root_test = analysistest.make(
    impl = _api_path_is_relative_to_workspace_root_test_impl,
    attrs = {
        "expected_symbolfile": attr.string(),
    },
)

def _api_path_is_relative_to_workspace_root_test():
    test_name = "api_path_is_relative_workspace_root"
    subject_name = test_name + "_subject"
    symbolfile = "libfoo.map.txt"
    cc_api_contribution(
        name = subject_name,
        api = symbolfile,
        tags = ["manual"],
    )
    api_path_is_relative_to_workspace_root_test(
        name = test_name,
        target_under_test = subject_name,
        expected_symbolfile = symbolfile,
    )
    return test_name

def _empty_library_name_gets_label_name_impl(ctx):
    env = analysistest.begin(ctx)
    target_under_test = analysistest.target_under_test(env)
    asserts.equals(env, target_under_test.label.name, target_under_test[CcApiContributionInfo].name)
    return analysistest.end(env)

empty_library_name_gets_label_name_test = analysistest.make(_empty_library_name_gets_label_name_impl)

def _empty_library_name_gets_label_name_test():
    test_name = "empty_library_name_gets_label_name"
    subject_name = test_name + "_subject"
    cc_api_contribution(
        name = subject_name,
        api = ":libfoo.map.txt",
        tags = ["manual"],
    )
    empty_library_name_gets_label_name_test(
        name = test_name,
        target_under_test = subject_name,
    )
    return test_name

def _nonempty_library_name_preferred_impl(ctx):
    env = analysistest.begin(ctx)
    target_under_test = analysistest.target_under_test(env)
    asserts.equals(env, ctx.attr.expected_library_name, target_under_test[CcApiContributionInfo].name)
    return analysistest.end(env)

nonempty_library_name_preferred_test = analysistest.make(
    impl = _nonempty_library_name_preferred_impl,
    attrs = {
        "expected_library_name": attr.string(),
    },
)

def _nonempty_library_name_preferred_test():
    test_name = "nonempty_library_name_preferred_test"
    subject_name = test_name + "_subject"
    library_name = "mylibrary"
    cc_api_contribution(
        name = subject_name,
        library_name = library_name,
        api = ":libfoo.map.txt",
        tags = ["manual"],
    )
    nonempty_library_name_preferred_test(
        name = test_name,
        target_under_test = subject_name,
        expected_library_name = library_name,
    )
    return test_name

def _api_surfaces_attr_test_impl(ctx):
    env = analysistest.begin(ctx)
    target_under_test = analysistest.target_under_test(env)
    asserts.equals(env, ctx.attr.expected_api_surfaces, target_under_test[CcApiContributionInfo].api_surfaces)
    return analysistest.end(env)

api_surfaces_attr_test = analysistest.make(
    impl = _api_surfaces_attr_test_impl,
    attrs = {
        "expected_api_surfaces": attr.string_list(),
    },
)

def _api_surfaces_attr_test():
    test_name = "api_surfaces_attr_test"
    subject_name = test_name + "_subject"
    cc_api_contribution(
        name = subject_name,
        api = "libfoo.map.txt",
        api_surfaces = ["publicapi", "module-libapi"],
        tags = ["manual"],
    )
    api_surfaces_attr_test(
        name = test_name,
        target_under_test = subject_name,
        expected_api_surfaces = ["publicapi", "module-libapi"],
    )
    return test_name

def _api_headers_contribution_test_impl(ctx):
    env = analysistest.begin(ctx)
    target_under_test = analysistest.target_under_test(env)
    asserts.equals(env, ctx.attr.expected_include_dirs, [hdr_info.root for hdr_info in target_under_test[CcApiContributionInfo].headers])
    return analysistest.end(env)

api_headers_contribution_test = analysistest.make(
    impl = _api_headers_contribution_test_impl,
    attrs = {
        "expected_include_dirs": attr.string_list(),
    },
)

def _api_headers_contribution_test():
    test_name = "api_headers_contribution_test"
    subject_name = test_name + "_subject"
    cc_api_contribution(
        name = subject_name,
        api = ":libfoo.map.txt",
        hdrs = [
            subject_name + "_headers",
            subject_name + "_library_headers",
        ],
        tags = ["manual"],
    )
    cc_api_headers(
        name = subject_name + "_headers",
        hdrs = [],
        include_dir = "dir1",
        tags = ["manual"],
    )
    cc_api_library_headers(
        name = subject_name + "_library_headers",
        export_includes = ["dir2", "dir3"],
        tags = ["manual"],
    )
    api_headers_contribution_test(
        name = test_name,
        target_under_test = subject_name,
        expected_include_dirs = [
            "build/bazel/rules/apis/dir1",
            "build/bazel/rules/apis/dir2",
            "build/bazel/rules/apis/dir3",
        ],
    )
    return test_name

def cc_api_test_suite(name):
    native.test_suite(
        name = name,
        tests = [
            _empty_include_dir_test(),
            _nonempty_include_dir_test(),
            _api_library_headers_test(),
            _api_path_is_relative_to_workspace_root_test(),
            _empty_library_name_gets_label_name_test(),
            _nonempty_library_name_preferred_test(),
            _api_surfaces_attr_test(),
            _api_headers_contribution_test(),
        ],
    )
