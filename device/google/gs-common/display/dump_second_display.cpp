/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include <dump/pixel_dump.h>

int main() {
    dumpFileContent("DECON-1 counters /sys/class/drm/card0/device/decon1/counters", "/sys/class/drm/card0/device/decon1/counters");
    dumpFileContent("CRTC-1 event log", "/sys/kernel/debug/dri/0/crtc-1/event");
    dumpFileContent("Secondary panel name", "/sys/devices/platform/exynos-drm/secondary-panel/panel_name");
    dumpFileContent("Secondary panel extra info", "/sys/devices/platform/exynos-drm/secondary-panel/panel_extinfo");
    return 0;
}

