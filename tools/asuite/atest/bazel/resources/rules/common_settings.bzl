# Copyright (C) 2021 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Build setting rule.

The rule returns a BuildSettingInfo with the value of the build setting.
More documentation on how to use build settings at
https://docs.bazel.build/versions/master/skylark/config.html#user-defined-build-settings
"""

BuildSettingInfo = provider(
    doc = "A singleton provider that contains the raw value of a build setting",
    fields = {
        "value": "The value of the build setting in the current configuration. " +
                 "This value may come from the command line or an upstream transition, " +
                 "or else it will be the build setting's default.",
    },
)

def _string_impl(ctx):
    allowed_values = ctx.attr.values
    value = ctx.build_setting_value

    if len(allowed_values) == 0 or value in ctx.attr.values:
        return BuildSettingInfo(value = value)
    fail("Error setting " + str(ctx.label) + ": invalid value '" + value + "'. Allowed values are " + str(allowed_values))

string_flag = rule(
    implementation = _string_impl,
    build_setting = config.string(flag = True),
    attrs = {
        "values": attr.string_list(
            doc = "The list of allowed values for this setting. An error is raised if any other value is given.",
        ),
    },
    doc = "A string-typed build setting that can be set on the command line",
)

def _impl(ctx):
    return BuildSettingInfo(value = ctx.build_setting_value)

string_list_flag = rule(
    implementation = _impl,
    build_setting = config.string_list(flag = True),
    doc = "A string list-typed build setting that can be set on the command line",
)
