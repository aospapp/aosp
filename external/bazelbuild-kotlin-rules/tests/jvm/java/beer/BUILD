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

licenses(["notice"])

kt_jvm_library(
    name = "beer_lib",
    srcs = ["Beer.kt"],
    deps = [
        "@dagger",
        "@maven//:javax_inject_jsr330_api",
    ],
)

kt_jvm_test(
    name = "beer",
    main_class = "beer.BeerKt",
    tags = ["darwin_x86_64_compatible"],
    runtime_deps = [":beer_lib"],
)

# Regression test for b/123767247
sh_test(
    name = "build_data_test",
    srcs = ["build_data_test.sh"],
    data = [
        ":libbeer_lib.jar",
    ],
    env = {
        "TEST_JAR": "$(location :libbeer_lib.jar)",
    },
)
