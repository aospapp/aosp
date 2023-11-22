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

#ifndef ANDROID_VINTF_HAL_GROUP_H
#define ANDROID_VINTF_HAL_GROUP_H

#include <map>

#include "MapValueIterator.h"

namespace android {
namespace vintf {

// A HalGroup is a wrapped multimap from name to Hal.
// Hal.getName() must return a string indicating the name.
template <typename Hal>
struct HalGroup {
   public:
    virtual ~HalGroup() {}
    // Move all hals from another HalGroup to this.
    bool addAll(HalGroup&& other) {
        for (auto& pair : other.mHals) {
            if (!add(std::move(pair.second))) {
                return false;
            }
        }
        return true;
    }

    // Add an hal to this HalGroup so that it can be constructed programatically.
    bool add(Hal&& hal) {
        if (!shouldAdd(hal)) {
            return false;
        }
        std::string name = hal.getName();
        mHals.emplace(std::move(name), std::move(hal));  // always succeed
        return true;
    }

   protected:
    // sorted map from component name to the component.
    // The component name looks like: android.hardware.foo
    std::multimap<std::string, Hal> mHals;

    // override this to filter for add.
    virtual bool shouldAdd(const Hal&) const { return true; }

    // Return an iterable to all ManifestHal objects. Call it as follows:
    // for (const auto& e : vm.getHals()) { }
    ConstMultiMapValueIterable<std::string, Hal> getHals() const {
        return ConstMultiMapValueIterable<std::string, Hal>(mHals);
    }

    // Get any HAL component based on the component name. Return any one
    // if multiple. Return nullptr if the component does not exist. This is only
    // for creating objects programatically.
    // The component name looks like:
    // android.hardware.foo
    Hal* getAnyHal(const std::string& name) {
        auto it = mHals.find(name);
        if (it == mHals.end()) {
            return nullptr;
        }
        return &(it->second);
    }
};

}  // namespace vintf
}  // namespace android

#endif  // ANDROID_VINTF_HAL_GROUP_H
