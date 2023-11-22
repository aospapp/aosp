/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "AST.h"

#include "Annotation.h"
#include "Coordinator.h"
#include "Interface.h"
#include "Method.h"
#include "Scope.h"

#include <hidl-util/Formatter.h>
#include <android-base/logging.h>
#include <string>
#include <vector>

namespace android {

status_t AST::emitVtsTypeDeclarations(Formatter &out) const {
    if (AST::isInterface()) {
        const Interface* iface = mRootScope.getInterface();
        return iface->emitVtsAttributeDeclaration(out);
    }

    for (const auto& type : mRootScope.getSubTypes()) {
        // Skip for TypeDef as it is just an alias of a defined type.
        if (type->isTypeDef()) {
            continue;
        }
        out << "attribute: {\n";
        out.indent();
        status_t status = type->emitVtsTypeDeclarations(out);
        if (status != OK) {
            return status;
        }
        out.unindent();
        out << "}\n\n";
    }

    return OK;
}

status_t AST::generateVts(const std::string &outputPath) const {
    std::string baseName = AST::getBaseName();
    const Interface *iface = AST::getInterface();

    std::string path = outputPath;
    path.append(mCoordinator->convertPackageRootToPath(mPackage));
    path.append(mCoordinator->getPackagePath(mPackage, true /* relative */));
    path.append(baseName);
    path.append(".vts");

    CHECK(Coordinator::MakeParentHierarchy(path));
    FILE *file = fopen(path.c_str(), "w");

    if (file == NULL) {
        return -errno;
    }

    Formatter out(file);

    out << "component_class: HAL_HIDL\n";
    out << "component_type_version: " << mPackage.version()
        << "\n";
    out << "component_name: \""
        << (iface ? iface->localName() : "types")
        << "\"\n\n";

    out << "package: \"" << mPackage.package() << "\"\n\n";

    // Generate import statement for all imported interface/types.
    std::set<FQName> allImportedNames;
    getAllImportedNames(&allImportedNames);
    for (const auto &name : allImportedNames) {
        // ignore IBase.
        if (name != gIBaseFqName) {
            out << "import: \"" << name.string() << "\"\n";
        }
    }

    out << "\n";

    if (isInterface()) {
        const Interface* iface = mRootScope.getInterface();
        out << "interface: {\n";
        out.indent();

        std::vector<const Interface *> chain = iface->typeChain();

        // Generate all the attribute declarations first.
        status_t status = emitVtsTypeDeclarations(out);
        if (status != OK) {
            return status;
        }
        // Generate all the method declarations.
        for (auto it = chain.rbegin(); it != chain.rend(); ++it) {
            const Interface *superInterface = *it;
            status_t status = superInterface->emitVtsMethodDeclaration(out);
            if (status != OK) {
                return status;
            }
        }

        out.unindent();
        out << "}\n";
    } else {
        status_t status = emitVtsTypeDeclarations(out);
        if (status != OK) {
            return status;
        }
    }
    return OK;
}

}  // namespace android
