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
load("//build/bazel/rules/cc:cc_library_shared.bzl", "cc_library_shared")
load("//build/bazel/rules/cc:cc_library_static.bzl", "cc_library_static")
load("//build/bazel/rules/cc:cc_stub_library.bzl", "cc_stub_suite")
load(
    "//build/bazel/rules/cc/testing:transitions.bzl",
    "ActionArgsInfo",
    "compile_action_argv_aspect_generator",
)
load("//build/bazel/rules/test_common:flags.bzl", "action_flags_present_only_for_mnemonic_test")
load("//build/bazel/rules/test_common:paths.bzl", "get_package_dir_based_path")
load(":cc_library_common_test.bzl", "target_provides_androidmk_info_test")

def _cc_library_shared_suffix_test_impl(ctx):
    env = analysistest.begin(ctx)
    target = analysistest.target_under_test(env)
    info = target[DefaultInfo]
    suffix = ctx.attr.suffix

    # NB: There may be more than 1 output file (if e.g. including a TOC)
    outputs = [so.path for so in info.files.to_list() if so.path.endswith(".so")]
    asserts.true(
        env,
        len(outputs) == 1,
        "Expected only 1 output file; got %s" % outputs,
    )
    out = outputs[0]
    suffix_ = suffix + ".so"
    asserts.true(
        env,
        out.endswith(suffix_),
        "Expected output filename to end in `%s`; it was instead %s" % (suffix_, out),
    )

    return analysistest.end(env)

cc_library_shared_suffix_test = analysistest.make(
    _cc_library_shared_suffix_test_impl,
    attrs = {"suffix": attr.string()},
)

def _cc_library_shared_suffix():
    name = "cc_library_shared_suffix"
    test_name = name + "_test"
    suffix = "-suf"

    cc_library_shared(
        name,
        srcs = ["foo.cc"],
        tags = ["manual"],
        suffix = suffix,
    )
    cc_library_shared_suffix_test(
        name = test_name,
        target_under_test = name,
        suffix = suffix,
    )
    return test_name

def _cc_library_shared_empty_suffix():
    name = "cc_library_shared_empty_suffix"
    test_name = name + "_test"

    cc_library_shared(
        name,
        srcs = ["foo.cc"],
        tags = ["manual"],
    )
    cc_library_shared_suffix_test(
        name = test_name,
        target_under_test = name,
    )
    return test_name

def _cc_library_shared_propagating_compilation_context_test_impl(ctx):
    env = analysistest.begin(ctx)
    target = analysistest.target_under_test(env)
    cc_info = target[CcInfo]
    compilation_context = cc_info.compilation_context

    header_paths = [f.path for f in compilation_context.headers.to_list()]
    for hdr in ctx.files.expected_hdrs:
        asserts.true(
            env,
            hdr.path in header_paths,
            "Did not find {hdr} in includes: {hdrs}.".format(hdr = hdr, hdrs = compilation_context.headers),
        )

    for hdr in ctx.files.expected_absent_hdrs:
        asserts.true(
            env,
            hdr not in header_paths,
            "Found {hdr} in includes: {hdrs}, should not be present.".format(hdr = hdr, hdrs = compilation_context.headers),
        )

    for include in ctx.attr.expected_includes:
        absolute_include = get_package_dir_based_path(env, include)
        asserts.true(
            env,
            absolute_include in compilation_context.includes.to_list(),
            "Did not find {include} in includes: {includes}.".format(include = include, includes = compilation_context.includes),
        )

    for include in ctx.attr.expected_absent_includes:
        absolute_include = get_package_dir_based_path(env, include)
        asserts.true(
            env,
            absolute_include not in compilation_context.includes.to_list(),
            "Found {include} in includes: {includes}, was expected to be absent".format(include = include, includes = compilation_context.includes),
        )

    for include in ctx.attr.expected_system_includes:
        absolute_include = get_package_dir_based_path(env, include)
        asserts.true(
            env,
            absolute_include in compilation_context.system_includes.to_list(),
            "Did not find {include} in system includes: {includes}.".format(include = include, includes = compilation_context.system_includes),
        )

    for include in ctx.attr.expected_absent_system_includes:
        absolute_include = get_package_dir_based_path(env, include)
        asserts.true(
            env,
            absolute_include not in compilation_context.system_includes.to_list(),
            "Found {include} in system includes: {includes}, was expected to be absent".format(include = include, includes = compilation_context.system_includes),
        )

    return analysistest.end(env)

_cc_library_shared_propagating_compilation_context_test = analysistest.make(
    _cc_library_shared_propagating_compilation_context_test_impl,
    attrs = {
        "expected_hdrs": attr.label_list(),
        "expected_absent_hdrs": attr.label_list(),
        "expected_includes": attr.string_list(),
        "expected_absent_includes": attr.string_list(),
        "expected_system_includes": attr.string_list(),
        "expected_absent_system_includes": attr.string_list(),
    },
)

def _cc_library_shared_propagates_deps():
    name = "_cc_library_shared_propagates_deps"
    dep_name = name + "_dep"
    test_name = name + "_test"

    cc_library_static(
        name = dep_name,
        hdrs = [":cc_library_shared_hdr"],
        export_includes = ["a/b/c"],
        export_system_includes = ["d/e/f"],
        tags = ["manual"],
    )

    cc_library_shared(
        name = name,
        deps = [dep_name],
        tags = ["manual"],
    )

    _cc_library_shared_propagating_compilation_context_test(
        name = test_name,
        target_under_test = name,
        expected_hdrs = [":cc_library_shared_hdr"],
        expected_includes = ["a/b/c"],
        expected_system_includes = ["d/e/f"],
    )

    return test_name

def _cc_library_shared_propagates_whole_archive_deps():
    name = "_cc_library_shared_propagates_whole_archive_deps"
    dep_name = name + "_dep"
    test_name = name + "_test"

    cc_library_static(
        name = dep_name,
        hdrs = [":cc_library_shared_hdr"],
        export_includes = ["a/b/c"],
        export_system_includes = ["d/e/f"],
        tags = ["manual"],
    )

    cc_library_shared(
        name = name,
        whole_archive_deps = [dep_name],
        tags = ["manual"],
    )

    _cc_library_shared_propagating_compilation_context_test(
        name = test_name,
        target_under_test = name,
        expected_hdrs = [":cc_library_shared_hdr"],
        expected_includes = ["a/b/c"],
        expected_system_includes = ["d/e/f"],
    )

    return test_name

def _cc_library_shared_propagates_dynamic_deps():
    name = "_cc_library_shared_propagates_dynamic_deps"
    dep_name = name + "_dep"
    test_name = name + "_test"

    cc_library_shared(
        name = dep_name,
        hdrs = [":cc_library_shared_hdr"],
        export_includes = ["a/b/c"],
        export_system_includes = ["d/e/f"],
        tags = ["manual"],
    )

    cc_library_shared(
        name = name,
        dynamic_deps = [dep_name],
        tags = ["manual"],
    )

    _cc_library_shared_propagating_compilation_context_test(
        name = test_name,
        target_under_test = name,
        expected_hdrs = [":cc_library_shared_hdr"],
        expected_includes = ["a/b/c"],
        expected_system_includes = ["d/e/f"],
    )

    return test_name

def _cc_library_shared_does_not_propagate_implementation_deps():
    name = "_cc_library_shared_does_not_propagate_implementation_deps"
    dep_name = name + "_dep"
    test_name = name + "_test"

    cc_library_static(
        name = dep_name,
        hdrs = [":cc_library_shared_hdr"],
        export_includes = ["a/b/c"],
        export_system_includes = ["d/e/f"],
        tags = ["manual"],
    )

    cc_library_shared(
        name = name,
        implementation_deps = [dep_name],
        tags = ["manual"],
    )

    _cc_library_shared_propagating_compilation_context_test(
        name = test_name,
        target_under_test = name,
        expected_absent_hdrs = [":cc_library_shared_hdr"],
        expected_absent_includes = ["a/b/c"],
        expected_absent_system_includes = ["d/e/f"],
    )

    return test_name

def _cc_library_shared_does_not_propagate_implementation_whole_archive_deps():
    name = "_cc_library_shared_does_not_propagate_implementation_whole_archive_deps"
    dep_name = name + "_dep"
    test_name = name + "_test"

    cc_library_static(
        name = dep_name,
        hdrs = [":cc_library_shared_hdr"],
        export_includes = ["a/b/c"],
        export_system_includes = ["d/e/f"],
        tags = ["manual"],
    )

    cc_library_shared(
        name = name,
        implementation_whole_archive_deps = [dep_name],
        tags = ["manual"],
    )

    _cc_library_shared_propagating_compilation_context_test(
        name = test_name,
        target_under_test = name,
        expected_absent_hdrs = [":cc_library_shared_hdr"],
        expected_absent_includes = ["a/b/c"],
        expected_absent_system_includes = ["d/e/f"],
    )

    return test_name

def _cc_library_shared_does_not_propagate_implementation_dynamic_deps():
    name = "_cc_library_shared_does_not_propagate_implementation_dynamic_deps"
    dep_name = name + "_dep"
    test_name = name + "_test"

    cc_library_shared(
        name = dep_name,
        hdrs = [":cc_library_shared_hdr"],
        export_includes = ["a/b/c"],
        export_system_includes = ["d/e/f"],
        tags = ["manual"],
    )

    cc_library_shared(
        name = name,
        implementation_dynamic_deps = [dep_name],
        tags = ["manual"],
    )

    _cc_library_shared_propagating_compilation_context_test(
        name = test_name,
        target_under_test = name,
        expected_absent_hdrs = [":cc_library_shared_hdr"],
        expected_absent_includes = ["a/b/c"],
        expected_absent_system_includes = ["d/e/f"],
    )

    return test_name

def _cc_library_shared_propagating_fdo_profile_test_impl(ctx):
    env = analysistest.begin(ctx)
    target_under_test = analysistest.target_under_test(env)
    argv_map = target_under_test[ActionArgsInfo].argv_map
    for label in ctx.attr.deps_labels_to_check_fdo_profile:
        asserts.true(
            env,
            label in argv_map,
            "can't find {} in argv map".format(label),
        )
        argv = argv_map[label]
        asserts.true(
            env,
            _has_fdo_profile(argv, ctx.attr.fdo_profile_path_basename),
            "can't find {} in compile action of {}".format(
                ctx.attr.fdo_profile_path_basename,
                label,
            ),
        )
    for label in ctx.attr.deps_labels_to_check_no_fdo_profile:
        asserts.true(
            env,
            label in argv_map,
            "can't find {} in argv_map".format(label),
        )
        argv = argv_map[label]
        asserts.true(
            env,
            not _has_fdo_profile(argv, ctx.attr.fdo_profile_path_basename),
            "{} should not have {} in compile action".format(
                ctx.attr.fdo_profile_path_basename,
                label,
            ),
        )

    return analysistest.end(env)

_compile_action_argv_aspect = compile_action_argv_aspect_generator({
    "_cc_library_combiner": ["deps", "roots", "includes"],
    "_cc_includes": ["deps"],
    "_cc_library_shared_proxy": ["deps"],
})

cc_library_shared_propagating_fdo_profile_test = analysistest.make(
    _cc_library_shared_propagating_fdo_profile_test_impl,
    attrs = {
        # FdoProfileInfo isn't exposed to Starlark so we need to test against
        # the path basename directly
        "fdo_profile_path_basename": attr.string(),
        # This has to be a string_list() instead of label_list(). If the deps
        # are given as labels, the deps are analyzed because transition is attached
        "deps_labels_to_check_fdo_profile": attr.string_list(),
        "deps_labels_to_check_no_fdo_profile": attr.string_list(),
    },
    # We need to use aspect to examine the dependencies' actions of the apex
    # target as the result of the transition, checking the dependencies directly
    # using names will give you the info before the transition takes effect.
    extra_target_under_test_aspects = [_compile_action_argv_aspect],
)

# _has_fdo_profile checks whether afdo-specific flag is present in actions.argv
def _has_fdo_profile(argv, fdo_profile_path_basename):
    for arg in argv:
        if fdo_profile_path_basename in arg and "-fprofile-sample-use" in arg:
            return True

    return False

def _cc_libary_shared_propagate_fdo_profile_to_whole_archive_deps():
    name = "_cc_libary_shared_propagate_fdo_profile_to_whole_archive_deps"
    fdo_profile_name = name + "_fdo_profile"
    dep_name = name + "_dep"
    transitive_dep_name = name + "_transitive_dep"
    unexported_dep_name = name + "_exported_dep"
    transitive_unexported_dep_name = name + "_transitive_unexported_dep"
    test_name = name + "_test"

    native.fdo_profile(
        name = fdo_profile_name,
        profile = fdo_profile_name + ".afdo",
    )

    cc_library_static(
        name = transitive_dep_name,
        srcs = ["foo.cpp"],
        tags = ["manual"],
    )
    cc_library_static(
        name = transitive_unexported_dep_name,
        srcs = ["foo.cpp"],
        tags = ["manual"],
    )
    cc_library_static(
        name = dep_name,
        whole_archive_deps = [transitive_dep_name],
        implementation_whole_archive_deps = [transitive_unexported_dep_name],
        srcs = ["foo.cpp", "bar.cpp"],
        tags = ["manual"],
    )
    cc_library_static(
        name = unexported_dep_name,
        srcs = ["foo.cpp"],
        tags = ["manual"],
    )

    cc_library_shared(
        name = name,
        whole_archive_deps = [dep_name],
        implementation_whole_archive_deps = [unexported_dep_name],
        fdo_profile = ":" + fdo_profile_name,
        tags = ["manual"],
    )

    cc_library_shared_propagating_fdo_profile_test(
        name = test_name,
        target_under_test = name,
        deps_labels_to_check_fdo_profile = [
            dep_name + "_cpp",
            transitive_dep_name + "_cpp",
            unexported_dep_name + "_cpp",
            transitive_unexported_dep_name + "_cpp",
        ],
        fdo_profile_path_basename = fdo_profile_name + ".afdo",
    )

    return test_name

def _cc_library_shared_does_not_propagate_fdo_profile_to_dynamic_deps():
    name = "_cc_library_shared_does_not_propagate_fdo_profile_to_dynamic_deps"
    fdo_profile_name = name + "_fdo_profile"
    dep_name = name + "_dep"
    transitive_shared_dep_name = name + "_transitive_shared_dep"
    unexported_transitive_shared_dep_name = name + "_unexported_transitive_shared_dep"
    test_name = name + "_test"

    cc_library_shared(
        name = transitive_shared_dep_name,
        srcs = ["foo.cpp"],
        tags = ["manual"],
    )
    cc_library_shared(
        name = unexported_transitive_shared_dep_name,
        srcs = ["foo.cpp"],
        tags = ["manual"],
    )
    cc_library_static(
        name = dep_name,
        srcs = ["foo.cpp"],
        dynamic_deps = [transitive_shared_dep_name],
        implementation_dynamic_deps = [unexported_transitive_shared_dep_name],
        tags = ["manual"],
    )
    native.fdo_profile(
        name = fdo_profile_name,
        profile = fdo_profile_name + ".afdo",
    )
    cc_library_shared(
        name = name,
        whole_archive_deps = [dep_name],
        fdo_profile = fdo_profile_name,
        stl = "",
        tags = ["manual"],
    )

    cc_library_shared_propagating_fdo_profile_test(
        name = test_name,
        target_under_test = name,
        deps_labels_to_check_fdo_profile = [
            dep_name + "_cpp",
        ],
        # make sure dynamic deps don't build with afdo profiles from rdeps
        deps_labels_to_check_no_fdo_profile = [
            transitive_shared_dep_name + "__internal_root_cpp",
            unexported_transitive_shared_dep_name + "__internal_root_cpp",
        ],
        fdo_profile_path_basename = fdo_profile_name + ".afdo",
    )

    return test_name

def _fdo_profile_transition_correctly_set_and_unset_fdo_profile():
    name = "_fdo_profile_transition_set_and_unset_fdo_profile_correctly"
    fdo_profile_name = name + "_fdo_profile"
    dep_with_fdo_profile = name + "_dep_with_fdo_profile"
    transitive_dep_without_fdo_profile = name + "_transitive_dep_without_fdo_profile"
    test_name = name + "_test"

    native.fdo_profile(
        name = fdo_profile_name,
        profile = fdo_profile_name + ".afdo",
    )

    cc_library_shared(
        name = name,
        srcs = ["foo.cpp"],
        tags = ["manual"],
        dynamic_deps = [dep_with_fdo_profile],
    )

    cc_library_shared(
        name = dep_with_fdo_profile,
        fdo_profile = fdo_profile_name,
        srcs = ["foo.cpp"],
        tags = ["manual"],
        dynamic_deps = [transitive_dep_without_fdo_profile],
    )

    cc_library_shared(
        name = transitive_dep_without_fdo_profile,
        srcs = ["foo.cpp"],
        tags = ["manual"],
    )

    cc_library_shared_propagating_fdo_profile_test(
        name = test_name,
        target_under_test = name,
        deps_labels_to_check_fdo_profile = [
            dep_with_fdo_profile + "__internal_root_cpp",
        ],
        # make sure dynamic deps don't build with afdo profiles from rdeps
        deps_labels_to_check_no_fdo_profile = [
            name + "__internal_root_cpp",
            transitive_dep_without_fdo_profile + "__internal_root_cpp",
        ],
        fdo_profile_path_basename = fdo_profile_name + ".afdo",
    )

    return test_name

def _cc_library_link_flags_test_impl(ctx):
    env = analysistest.begin(ctx)
    target = analysistest.target_under_test(env)

    for action in target.actions:
        if action.mnemonic == "CppLink":
            for flag in ctx.attr.expected_link_flags:
                if flag not in action.argv:
                    fail("{} is not in list of flags for linking {}".format(flag, action.argv))

    return analysistest.end(env)

cc_library_link_flags_test = analysistest.make(
    _cc_library_link_flags_test_impl,
    attrs = {
        "expected_link_flags": attr.string_list(),
    },
)

def _cc_library_with_fdo_profile_link_flags():
    name = "_cc_library_with_fdo_profile_link_flags"
    test_name = name + "_test"
    cc_library_shared(
        name = name,
        fdo_profile = name + "_fdo_profile",
        tags = ["manual"],
    )
    cc_library_link_flags_test(
        name = test_name,
        target_under_test = name + "_unstripped",
        expected_link_flags = [
            "-funique-internal-linkage-names",
            "-fprofile-sample-accurate",
            "-fprofile-sample-use=build/bazel/rules/cc/_cc_library_with_fdo_profile_link_flags_fdo_profile.afdo",
            "-Wl,-mllvm,-no-warn-sample-unused=true",
        ],
    )
    return test_name

def _cc_library_disable_fdo_optimization_if_coverage_is_enabled_impl(ctx):
    env = analysistest.begin(ctx)
    target = analysistest.target_under_test(env)

    for action in target.actions:
        if action.mnemonic == "CppCompile":
            for arg in action.argv:
                if "-fprofile-sample-use" in arg:
                    fail("fdo optimization can not be enabled when coverage is enabled")

    return analysistest.end(env)

cc_library_disable_fdo_optimization_if_coverage_is_enabled_test = analysistest.make(
    _cc_library_disable_fdo_optimization_if_coverage_is_enabled_impl,
    config_settings = {
        "//command_line_option:collect_code_coverage": True,
    },
)

def _cc_library_disable_fdo_optimization_if_coverage_is_enabled_test():
    name = "_cc_library_disable_fdo_optimization_if_coverage_is_enabled_test"
    test_name = name + "_test"
    cc_library_shared(
        name = name,
        fdo_profile = name + "_fdo_profile",
        srcs = ["foo.cpp"],
        # Coverage will add an extra lib to all the shared libs, we try to avoid
        # that by clearing the system_dynamic_deps and stl.
        system_dynamic_deps = [],
        stl = "none",
        tags = ["manual"],
    )
    cc_library_disable_fdo_optimization_if_coverage_is_enabled_test(
        name = test_name,
        target_under_test = name + "__internal_root_cpp",
    )
    return test_name

def _cc_library_set_defines_for_stubs():
    name = "cc_library_set_defines_for_stubs"
    test_name = name + "_test"

    cc_library_shared(
        name = name + "_libfoo",
        system_dynamic_deps = [],
        stl = "none",
        tags = ["manual"],
        stubs_symbol_file = name + "_libfoo.map.txt",
    )

    cc_stub_suite(
        name = name + "_libfoo_stub_libs",
        soname = name + "_libfoo.so",
        source_library_label = ":" + name + "_libfoo",
        symbol_file = name + "_libfoo.map.txt",
        versions = ["30", "40"],
    )

    cc_library_shared(
        name = name + "_libbar",
        system_dynamic_deps = [],
        stl = "none",
        tags = ["manual"],
        stubs_symbol_file = name + "_libbar.map.txt",
    )

    cc_stub_suite(
        name = name + "_libbar_stub_libs",
        soname = name + "_libbar.so",
        source_library_label = ":" + name + "_libbar",
        symbol_file = name + "_libbar.map.txt",
        versions = ["current"],
    )

    cc_library_shared(
        name = name + "_libbaz",
        system_dynamic_deps = [],
        stl = "none",
        tags = ["manual"],
        stubs_symbol_file = name + "_libbaz.map.txt",
    )

    cc_stub_suite(
        name = name + "_libbaz_stub_libs",
        soname = name + "_libbaz.so",
        source_library_label = ":" + name + "_libbaz",
        symbol_file = name + "_libbaz.map.txt",
        versions = ["30"],
    )

    cc_library_shared(
        name = name + "_lib_with_stub_deps",
        srcs = ["foo.cpp"],
        implementation_dynamic_deps = [
            name + "_libfoo_stub_libs_current",
            name + "_libbar_stub_libs_current",
            name + "_libbaz_stub_libs-30",  # depend on an old version explicitly
        ],
        tags = ["manual"],
    )

    action_flags_present_only_for_mnemonic_test(
        name = test_name,
        target_under_test = name + "_lib_with_stub_deps__internal_root_cpp",
        mnemonics = ["CppCompile"],
        expected_flags = [
            "-D__CC_LIBRARY_SET_DEFINES_FOR_STUBS_LIBFOO_API__=10000",
            "-D__CC_LIBRARY_SET_DEFINES_FOR_STUBS_LIBBAR_API__=10000",
            "-D__CC_LIBRARY_SET_DEFINES_FOR_STUBS_LIBBAZ_API__=30",
        ],
    )
    return test_name

def _cc_library_shared_provides_androidmk_info():
    name = "cc_library_shared_provides_androidmk_info"
    dep_name = name + "_static_dep"
    whole_archive_dep_name = name + "_whole_archive_dep"
    dynamic_dep_name = name + "_dynamic_dep"
    test_name = name + "_test"

    cc_library_static(
        name = dep_name,
        srcs = ["foo.c"],
        tags = ["manual"],
    )
    cc_library_static(
        name = whole_archive_dep_name,
        srcs = ["foo.c"],
        tags = ["manual"],
    )
    cc_library_shared(
        name = dynamic_dep_name,
        srcs = ["foo.c"],
        tags = ["manual"],
    )
    cc_library_shared(
        name = name,
        srcs = ["foo.cc"],
        deps = [dep_name],
        whole_archive_deps = [whole_archive_dep_name],
        dynamic_deps = [dynamic_dep_name],
        tags = ["manual"],
    )
    android_test_name = test_name + "_android"
    linux_test_name = test_name + "_linux"
    target_provides_androidmk_info_test(
        name = android_test_name,
        target_under_test = name,
        expected_static_libs = [dep_name, "libc++demangle"],
        expected_whole_static_libs = [whole_archive_dep_name],
        expected_shared_libs = [dynamic_dep_name, "libc++", "libc", "libdl", "libm"],
        target_compatible_with = ["//build/bazel/platforms/os:android"],
    )
    target_provides_androidmk_info_test(
        name = linux_test_name,
        target_under_test = name,
        expected_static_libs = [dep_name],
        expected_whole_static_libs = [whole_archive_dep_name],
        expected_shared_libs = [dynamic_dep_name, "libc++"],
        target_compatible_with = ["//build/bazel/platforms/os:linux"],
    )
    return [
        android_test_name,
        linux_test_name,
    ]

def cc_library_shared_test_suite(name):
    native.genrule(name = "cc_library_shared_hdr", cmd = "null", outs = ["cc_shared_f.h"], tags = ["manual"])

    native.test_suite(
        name = name,
        tests = [
            _cc_library_shared_suffix(),
            _cc_library_shared_empty_suffix(),
            _cc_library_shared_propagates_deps(),
            _cc_library_shared_propagates_whole_archive_deps(),
            _cc_library_shared_propagates_dynamic_deps(),
            _cc_library_shared_does_not_propagate_implementation_deps(),
            _cc_library_shared_does_not_propagate_implementation_whole_archive_deps(),
            _cc_library_shared_does_not_propagate_implementation_dynamic_deps(),
            _cc_libary_shared_propagate_fdo_profile_to_whole_archive_deps(),
            _cc_library_shared_does_not_propagate_fdo_profile_to_dynamic_deps(),
            _fdo_profile_transition_correctly_set_and_unset_fdo_profile(),
            _cc_library_with_fdo_profile_link_flags(),
            _cc_library_disable_fdo_optimization_if_coverage_is_enabled_test(),
            _cc_library_set_defines_for_stubs(),
        ] + _cc_library_shared_provides_androidmk_info(),
    )
