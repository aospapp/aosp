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

load("//kotlin:rules.bzl", "kt_jvm_library", "kt_jvm_test")
load("//tests/analysis:util.bzl", "create_dir", "create_file")
load("@bazel_skylib//rules:build_test.bzl", "build_test")

package(default_testonly = 1)

licenses(["notice"])

kt_jvm_test(
    name = "srcartifacts",
    srcs = [
        "JavaSrc.java",
        "KtSrc.kt",
        "SrcArtifactsTest.kt",
        ":JavaSrcjar_gen",
        ":dir/java",
        ":dir/kotlin",
    ],
    resources = [
        "dir/empty",
        "dir/resources",
    ],
    test_class = "srcartifacts.SrcArtifactsTest",
    deps = [
        "@maven//:com_google_truth_truth",
        "@maven//:junit_junit",
    ],
)

create_dir(
    name = "dir/java",
    srcs = [
        "JavaInJavaDir.java",
    ],
    subdir = "srcartifacts",
)

create_dir(
    name = "dir/kotlin",
    srcs = [
        "KtInKotlinDir.kt",
    ],
    subdir = "srcartifacts",
)

create_dir(
    name = "dir/empty",
    srcs = [],
    subdir = "srcartifacts/empty",
)

create_dir(
    name = "dir/resources",
    srcs = [
        create_file(
            name = "resources_in_resources_dir.txt",
            content = "Test resource content.",
        ),
    ],
    subdir = "resources",
)

genrule(
    name = "JavaSrcjar_gen",
    srcs = [":libJavaSrcjar_lib-src.jar"],
    outs = ["JavaSrcjar.srcjar"],
    cmd = "cp $(location :libJavaSrcjar_lib-src.jar) $(location :JavaSrcjar.srcjar)",
)

java_library(
    name = "JavaSrcjar_lib",
    srcs = ["JavaSrcjar.java"],
    tags = ["manual"],
)

kt_jvm_library(
    name = "empty_java_tree_artifact",
    srcs = [
        create_dir(
            name = "dir/empty_java/java",
            srcs = [],
            subdir = "srcartifacts",
        ),
    ],
)

kt_jvm_library(
    name = "empty_kotlin_tree_artifact",
    srcs = [
        create_dir(
            name = "dir/empty_kotlin/kotlin",
            srcs = [],
            subdir = "srcartifacts",
        ),
    ],
)

build_test(
    name = "empty_dirs_build_test",
    targets = [
        "empty_java_tree_artifact",
        "empty_kotlin_tree_artifact",
    ],
)
