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
#include <android-base/file.h>
#include <android-base/properties.h>
#include <dump/pixel_dump.h>

namespace {

constexpr std::string_view kCameraLogDir = "/data/vendor/camera/profiler";
constexpr std::string_view kGraphStateDumpDir = "/data/vendor/camera";

}  // namespace

int main() {
  if (!::android::base::GetBoolProperty(
          "vendor.camera.debug.camera_performance_analyzer.attach_to_bugreport",
          true)) {
    return 0;
  }

  const std::string cameraDestDir =
      concatenatePath(BUGREPORT_PACKING_DIR, "camera");

  if (mkdir(cameraDestDir.c_str(), 0777) == -1) {
    printf("Unable to create folder: %s\n", cameraDestDir.c_str());
    return 0;
  }

  // Attach multiple latest sessions (in case the user is running concurrent
  // sessions or starts a new session after the one with performance issues).
  dumpLogs(kCameraLogDir.data(), cameraDestDir.c_str(), 10, "session-ended-");
  dumpLogs(kCameraLogDir.data(), cameraDestDir.c_str(), 5, "high-drop-rate-");
  dumpLogs(kCameraLogDir.data(), cameraDestDir.c_str(), 5, "watchdog-");
  dumpLogs(kCameraLogDir.data(), cameraDestDir.c_str(), 5, "camera-ended-");
  dumpLogs(kGraphStateDumpDir.data(), cameraDestDir.c_str(), 5,
           "hal_graph_state_");

  return 0;
}
