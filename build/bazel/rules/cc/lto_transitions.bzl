# Copyright (C) 2023 The Android Open Source Project
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

FEATURES_ATTR_KEY = "features"
LTO_FEATURE = "android_thin_lto"
CLI_FEATURES_KEY = "//command_line_option:features"

# This propagates LTO enablement down the dependency tree for modules that
# enable it explicitly
# TODO(b/270418352): Move this logic to the incoming transition when incoming
#                    transitions support select statements
def lto_deps_transition_impl(settings, attr):
    features = getattr(attr, FEATURES_ATTR_KEY)
    new_cli_features = list(settings[CLI_FEATURES_KEY])
    if LTO_FEATURE in features and LTO_FEATURE not in new_cli_features:
        new_cli_features.append(LTO_FEATURE)

    return {
        CLI_FEATURES_KEY: new_cli_features,
    }

lto_deps_transition = transition(
    implementation = lto_deps_transition_impl,
    inputs = [
        CLI_FEATURES_KEY,
    ],
    outputs = [
        CLI_FEATURES_KEY,
    ],
)

# This un-propagates LTO enablement for shared deps, as LTO should only
# propagate down static deps. This approach avoids an error where we end up with
# two config variants of the same dependency
def apply_drop_lto(old_cli_features):
    new_cli_features = list(old_cli_features)
    if LTO_FEATURE in old_cli_features:
        new_cli_features.remove(LTO_FEATURE)

    return {
        CLI_FEATURES_KEY: new_cli_features,
    }

def drop_lto_transition_impl(settings, _):
    return apply_drop_lto(settings[CLI_FEATURES_KEY])

drop_lto_transition = transition(
    implementation = drop_lto_transition_impl,
    inputs = [CLI_FEATURES_KEY],
    outputs = [CLI_FEATURES_KEY],
)
