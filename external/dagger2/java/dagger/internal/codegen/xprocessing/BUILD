# Copyright (C) 2021 The Dagger Authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Description:
#   Import for including XProcessing in Dagger.

load("@rules_java//java:defs.bzl", "java_import")

package(default_visibility = ["//:src"])

java_library(
    name = "xprocessing",
    # TODO(b/181056551): Ideally, all of the methods in these utility classes
    # will move directly into XProcessing, and we can then remove these classes.
    srcs = glob(["*.java"]),
    exports = [
        ":xprocessing-lib",
    ],
    deps = [
        ":xprocessing-lib",
        "//java/dagger/internal/codegen/extension",
        "//third_party/java/auto:common",
        "//third_party/java/guava/base",
        "//third_party/java/guava/collect",
        "//third_party/java/javapoet",
        "@maven//:org_jetbrains_kotlin_kotlin_stdlib",
        "@maven//:org_jetbrains_kotlin_kotlin_stdlib_jdk8",
    ],
)

alias(
    name = "xprocessing-lib",
    actual = ":xprocessing-jar",
    visibility = ["//visibility:private"],
)

java_import(
    name = "xprocessing-jar",
    jars = ["xprocessing.jar"],
)
