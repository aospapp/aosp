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

load("@bazel_skylib//lib:paths.bzl", "paths")

def _py_proto_sources_gen_rule_impl(ctx):
    imports = []
    all_outputs = []
    for dep in ctx.attr.deps:
        proto_info = dep[ProtoInfo]

        outputs = []
        for name in proto_info.direct_sources:
            outputs.append(ctx.actions.declare_file(paths.replace_extension(name.basename, "_pb2.py"), sibling = name))

        args = ctx.actions.args()
        args.add("--python_out=" + proto_info.proto_source_root)
        args.add_all(["-I", proto_info.proto_source_root])
        args.add_all(proto_info.direct_sources)

        if proto_info.proto_source_root != ".":
            imports.append(paths.join("__main__", paths.relativize(proto_info.proto_source_root, ctx.bin_dir.path)))

        # It's not clear what to do with transititve imports/sources
        if len(proto_info.transitive_imports.to_list()) > len(proto_info.direct_sources) or len(proto_info.transitive_sources.to_list()) > len(proto_info.direct_sources):
            fail("TODO: Transitive imports/sources of python protos")

        ctx.actions.run(
            inputs = depset(
                direct = proto_info.direct_sources,
                transitive = [proto_info.transitive_imports],
            ),
            executable = ctx.executable._protoc,
            outputs = outputs,
            arguments = [args],
            mnemonic = "PyProtoGen",
        )

        all_outputs.extend(outputs)

    output_depset = depset(direct = all_outputs)
    return [
        DefaultInfo(files = output_depset),
        PyInfo(
            transitive_sources = output_depset,
            # If proto_source_root is set to something other than the root of the workspace, import the current package.
            # It's always the current package because it's the path to where we generated the python sources, not to where
            # the proto sources are.
            imports = depset(direct = imports),
        ),
    ]

_py_proto_sources_gen = rule(
    implementation = _py_proto_sources_gen_rule_impl,
    attrs = {
        "deps": attr.label_list(
            providers = [ProtoInfo],
            doc = "proto_library or any other target exposing ProtoInfo provider with *.proto files",
            mandatory = True,
        ),
        "_protoc": attr.label(
            default = Label("//external/protobuf:aprotoc"),
            executable = True,
            cfg = "exec",
        ),
    },
)

def py_proto_library(
        name,
        deps = [],
        target_compatible_with = [],
        **kwargs):
    proto_lib_name = name + "_proto_gen"

    _py_proto_sources_gen(
        name = proto_lib_name,
        deps = deps,
        **kwargs
    )

    # There may be a better way to do this, but proto_lib_name appears in both srcs
    # and deps because it must appear in srcs to cause the protobuf files to
    # actually be compiled, and it must appear in deps for the PyInfo provider to
    # be respected and the "imports" path to be included in this library.
    native.py_library(
        name = name,
        srcs = [":" + proto_lib_name],
        deps = [":" + proto_lib_name] + (["//external/protobuf:libprotobuf-python"] if "libprotobuf-python" not in name else []),
        target_compatible_with = target_compatible_with,
        **kwargs
    )
