/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "chre/platform/slpi/preloaded_nanoapps.h"

#include "chre/core/nanoapp.h"
#include "chre/platform/fatal_error.h"
#include "chre/util/macros.h"
#include "chre/util/unique_ptr.h"

namespace chre {

/**
 * Loads nanoapps that are standalone .so file (i.e. not static nanoapps), but
 * are pre-loaded in the system image.
 *
 * @param eventLoop The event loop where these nanoapps should run
 */
void loadPreloadedNanoapps(EventLoop *eventLoop) {
  struct PreloadedNanoappDescriptor {
    const uint64_t appId;
    const char *filename;
    UniquePtr<Nanoapp> nanoapp;
  };

  // The list of nanoapps to be loaded from the filesystem of the device.
  // TODO: allow these to be overridden by target-specific build configuration
  static PreloadedNanoappDescriptor preloadedNanoapps[] = {
    { 0x476f6f676c00100b, "activity.so", MakeUnique<Nanoapp>() },
    { 0x476f6f676c001004, "geofence.so", MakeUnique<Nanoapp>() },
    { 0x476f6f676c00100c, "wifi_offload.so", MakeUnique<Nanoapp>() },
  };

  for (size_t i = 0; i < ARRAY_SIZE(preloadedNanoapps); i++) {
    if (preloadedNanoapps[i].nanoapp.isNull()) {
      FATAL_ERROR("Couldn't allocate memory for preloaded nanoapp");
    } else {
      preloadedNanoapps[i].nanoapp->loadFromFile(preloadedNanoapps[i].appId,
                                                 preloadedNanoapps[i].filename);
      eventLoop->startNanoapp(preloadedNanoapps[i].nanoapp);
    }
  }
}

}  // namespace chre
