/*
 * Copyright 2016 The Android Open Source Project
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

#ifndef ANDROID_UI_HDR_CAPABILTIES_H
#define ANDROID_UI_HDR_CAPABILTIES_H

#include <stdint.h>

#include <vector>

#include <ui/GraphicTypes.h>

namespace android {

class HdrCapabilities {
public:
    HdrCapabilities(const std::vector<ui::Hdr>& types,
            float maxLuminance, float maxAverageLuminance, float minLuminance)
      : mSupportedHdrTypes(types),
        mMaxLuminance(maxLuminance),
        mMaxAverageLuminance(maxAverageLuminance),
        mMinLuminance(minLuminance) {}

    HdrCapabilities()
      : mSupportedHdrTypes(),
        mMaxLuminance(-1.0f),
        mMaxAverageLuminance(-1.0f),
        mMinLuminance(-1.0f) {}

    const std::vector<ui::Hdr>& getSupportedHdrTypes() const {
        return mSupportedHdrTypes;
    }
    float getDesiredMaxLuminance() const { return mMaxLuminance; }
    float getDesiredMaxAverageLuminance() const { return mMaxAverageLuminance; }
    float getDesiredMinLuminance() const { return mMinLuminance; }

private:
    std::vector<ui::Hdr> mSupportedHdrTypes;
    float mMaxLuminance;
    float mMaxAverageLuminance;
    float mMinLuminance;
};

} // namespace android

#endif
