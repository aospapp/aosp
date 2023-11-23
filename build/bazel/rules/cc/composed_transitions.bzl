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

load(
    ":fdo_profile_transitions.bzl",
    "CLI_CODECOV_KEY",
    "CLI_FDO_KEY",
    "FDO_PROFILE_ATTR_KEY",
    "apply_fdo_profile",
)
load(":lto_transitions.bzl", "CLI_FEATURES_KEY", "apply_drop_lto")

# Both LTO and FDO require an incoming transition on cc_library_shared
def _lto_and_fdo_profile_incoming_transition_impl(settings, attr):
    new_fdo_settings = apply_fdo_profile(
        settings[CLI_CODECOV_KEY],
        getattr(attr, FDO_PROFILE_ATTR_KEY),
    )

    new_lto_settings = apply_drop_lto(settings[CLI_FEATURES_KEY])

    if new_fdo_settings == None:
        new_fdo_settings = {}
    if new_lto_settings == None:
        new_lto_settings = {}
    return new_fdo_settings | new_lto_settings

lto_and_fdo_profile_incoming_transition = transition(
    implementation = _lto_and_fdo_profile_incoming_transition_impl,
    inputs = [
        CLI_CODECOV_KEY,
        CLI_FEATURES_KEY,
    ],
    outputs = [
        CLI_FDO_KEY,
        CLI_FEATURES_KEY,
    ],
)
