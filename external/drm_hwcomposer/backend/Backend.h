/*
 * Copyright (C) 2020 The Android Open Source Project
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

#ifndef ANDROID_BACKEND_H
#define ANDROID_BACKEND_H

#include "DrmHwcTwo.h"

namespace android {

class Backend {
 public:
  virtual ~Backend() = default;
  virtual HWC2::Error ValidateDisplay(DrmHwcTwo::HwcDisplay *display,
                                      uint32_t *num_types,
                                      uint32_t *num_requests);
  virtual std::tuple<int, size_t> GetClientLayers(
      DrmHwcTwo::HwcDisplay *display,
      const std::vector<DrmHwcTwo::HwcLayer *> &layers);
  virtual bool IsClientLayer(DrmHwcTwo::HwcDisplay *display,
                             DrmHwcTwo::HwcLayer *layer);

 protected:
  bool HardwareSupportsLayerType(HWC2::Composition comp_type);
  uint32_t CalcPixOps(const std::vector<DrmHwcTwo::HwcLayer *> &layers,
                      size_t first_z, size_t size);
  void MarkValidated(std::vector<DrmHwcTwo::HwcLayer *> &layers,
                     size_t client_first_z, size_t client_size);
  std::tuple<int, int> GetExtraClientRange(
      DrmHwcTwo::HwcDisplay *display,
      const std::vector<DrmHwcTwo::HwcLayer *> &layers, int client_start,
      size_t client_size);
};
}  // namespace android

#endif
