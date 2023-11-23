# Copyright 2022 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License. You may obtain a copy of
# the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations under
# the License.

# Netsim - a network simulator for discovery, ranging and communication

set(INSTALL_NETSIM_UI_DIR "${CMAKE_INSTALL_PREFIX}/netsim-ui"
    CACHE PATH "The directory the netsim web UI is installed in")

install(DIRECTORY dist/ DESTINATION ${INSTALL_NETSIM_UI_DIR})
