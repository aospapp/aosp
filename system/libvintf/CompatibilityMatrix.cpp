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

#include "CompatibilityMatrix.h"

#include "parse_string.h"
#include "utils.h"

namespace android {
namespace vintf {

constexpr Version CompatibilityMatrix::kVersion;

bool CompatibilityMatrix::add(MatrixHal &&hal) {
    return HalGroup<MatrixHal>::add(std::move(hal));
}

bool CompatibilityMatrix::add(MatrixKernel &&kernel) {
    if (mType != SchemaType::FRAMEWORK) {
        return false;
    }
    framework.mKernels.push_back(std::move(kernel));
    return true;
}

SchemaType CompatibilityMatrix::type() const {
    return mType;
}


status_t CompatibilityMatrix::fetchAllInformation(const std::string &path) {
    return details::fetchAllInformation(path, gCompatibilityMatrixConverter, this);
}

std::string CompatibilityMatrix::getXmlSchemaPath(const std::string& xmlFileName,
                                                  const Version& version) const {
    using std::literals::string_literals::operator""s;
    auto range = getXmlFiles(xmlFileName);
    for (auto it = range.first; it != range.second; ++it) {
        const MatrixXmlFile& matrixXmlFile = it->second;
        if (matrixXmlFile.versionRange().contains(version)) {
            if (!matrixXmlFile.overriddenPath().empty()) {
                return matrixXmlFile.overriddenPath();
            }
            return "/"s + (type() == SchemaType::DEVICE ? "vendor" : "system") + "/etc/" +
                   xmlFileName + "_V" + std::to_string(matrixXmlFile.versionRange().majorVer) +
                   "_" + std::to_string(matrixXmlFile.versionRange().maxMinor) + "." +
                   to_string(matrixXmlFile.format());
        }
    }
    return "";
}

bool operator==(const CompatibilityMatrix &lft, const CompatibilityMatrix &rgt) {
    return lft.mType == rgt.mType && lft.mHals == rgt.mHals && lft.mXmlFiles == rgt.mXmlFiles &&
           (lft.mType != SchemaType::DEVICE || (lft.device.mVndk == rgt.device.mVndk)) &&
           (lft.mType != SchemaType::FRAMEWORK ||
            (lft.framework.mKernels == rgt.framework.mKernels &&
             lft.framework.mSepolicy == rgt.framework.mSepolicy &&
             lft.framework.mAvbMetaVersion == rgt.framework.mAvbMetaVersion));
}

} // namespace vintf
} // namespace android
