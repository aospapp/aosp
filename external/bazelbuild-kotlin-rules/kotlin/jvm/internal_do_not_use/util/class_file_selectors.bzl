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

"""Selects bytecode class files with a matched source file in srcjars."""

load("//:visibility.bzl", "RULES_KOTLIN")

def gen_java_info_generated_class_jar(ctx, file_factory, kt_toolchain, input_jars, srcjars):
    """Generates a class jar with class file jar entries matching files in the give srcjars.

    Args:
      ctx: A rule context.
      file_factory: A file factory in responsible for file creation under the current context.
      kt_toolchain: The toolchain for kotlin builds.
      input_jars: A sequence of jar files from which class files are selected.
      srcjars: A sequence of source jar files that the selection references to.
    Returns:
      The output jar file, i.e. output_jar.
    """
    output_jar = file_factory.declare_file("-java_info_generated_class_jar.jar")
    input_jars = depset(input_jars)
    transformer_env_files = depset(srcjars)

    transformer_entry_point = "com.google.devtools.jar.transformation.ClassFileSelectorBySourceFile"
    transformer_jars = kt_toolchain.class_file_selector_by_source_file[JavaInfo].transitive_runtime_jars
    jar_transformer = kt_toolchain.jar_transformer[DefaultInfo].files_to_run

    args = ctx.actions.args()
    args.add_joined("--input_jars", input_jars, join_with = ",")
    args.add_joined("--transformer_jars", transformer_jars, join_with = ",")
    args.add("--transformer_entry_point", transformer_entry_point)
    args.add_joined("--transformer_env_files", transformer_env_files, join_with = ",")
    args.add("--result", output_jar)
    ctx.actions.run(
        inputs = depset(transitive = [
            input_jars,
            transformer_jars,
            transformer_env_files,
        ]),
        outputs = [output_jar],
        arguments = [args],
        progress_message = "Generating JavaInfo.generated_class_jar into %{output}",
        mnemonic = "ClassFileSelectorBySourceFile",
        executable = jar_transformer,
    )
    return output_jar
