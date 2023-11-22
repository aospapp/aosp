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
#include "Coordinator.h"
#include "Scope.h"

#include <hidl-hash/Hash.h>
#include <hidl-util/Formatter.h>
#include <hidl-util/FQName.h>
#include <hidl-util/StringHelper.h>
#include <android-base/logging.h>
#include <set>
#include <stdio.h>
#include <string>
#include <unistd.h>
#include <vector>

using namespace android;

struct OutputHandler {
    std::string mKey;
    std::string mDescription;
    enum OutputMode {
        NEEDS_DIR,
        NEEDS_FILE,
        NEEDS_SRC, // for changes inside the source tree itself
        NOT_NEEDED
    } mOutputMode;

    const std::string& name() { return mKey; }
    const std::string& description() { return mDescription; }

    using ValidationFunction = std::function<bool(const FQName &, const std::string &language)>;
    using GenerationFunction = std::function<status_t(const FQName &fqName,
                                                      const char *hidl_gen,
                                                      Coordinator *coordinator,
                                                      const std::string &outputDir)>;

    ValidationFunction validate;
    GenerationFunction generate;
};

static bool generateForTest = false;

static status_t generateSourcesForFile(
        const FQName &fqName,
        const char *,
        Coordinator *coordinator,
        const std::string &outputDir,
        const std::string &lang) {
    CHECK(fqName.isFullyQualified());

    AST *ast;
    std::string limitToType;

    if (fqName.name().find("types.") == 0) {
        CHECK(lang == "java");  // Already verified in validate().

        limitToType = fqName.name().substr(strlen("types."));

        FQName typesName = fqName.getTypesForPackage();
        ast = coordinator->parse(typesName);
    } else {
        ast = coordinator->parse(fqName);
    }

    if (ast == NULL) {
        fprintf(stderr,
                "ERROR: Could not parse %s. Aborting.\n",
                fqName.string().c_str());

        return UNKNOWN_ERROR;
    }

    if (lang == "check") {
        return OK; // only parsing, not generating
    }
    if (lang == "c++") {
        return ast->generateCpp(outputDir);
    }
    if (lang == "c++-headers") {
        return ast->generateCppHeaders(outputDir);
    }
    if (lang == "c++-sources") {
        return ast->generateCppSources(outputDir);
    }
    if (lang == "c++-impl") {
        return ast->generateCppImpl(outputDir);
    }
    if (lang == "c++-impl-headers") {
        return ast->generateStubImplHeader(outputDir);
    }
    if (lang == "c++-impl-sources") {
        return ast->generateStubImplSource(outputDir);
    }
    if (lang == "java") {
        return ast->generateJava(outputDir, limitToType);
    }
    if (lang == "vts") {
        return ast->generateVts(outputDir);
    }
    // Unknown language.
    return UNKNOWN_ERROR;
}

static status_t generateSourcesForPackage(
        const FQName &packageFQName,
        const char *hidl_gen,
        Coordinator *coordinator,
        const std::string &outputDir,
        const std::string &lang) {
    CHECK(packageFQName.isValid() &&
        !packageFQName.isFullyQualified() &&
        packageFQName.name().empty());

    std::vector<FQName> packageInterfaces;

    status_t err =
        coordinator->appendPackageInterfacesToVector(packageFQName,
                                                     &packageInterfaces);

    if (err != OK) {
        return err;
    }

    for (const auto &fqName : packageInterfaces) {
        err = generateSourcesForFile(
                fqName, hidl_gen, coordinator, outputDir, lang);
        if (err != OK) {
            return err;
        }
    }

    return OK;
}

OutputHandler::GenerationFunction generationFunctionForFileOrPackage(const std::string &language) {
    return [language](const FQName &fqName,
              const char *hidl_gen, Coordinator *coordinator,
              const std::string &outputDir) -> status_t {
        if (fqName.isFullyQualified()) {
                    return generateSourcesForFile(fqName,
                                                  hidl_gen,
                                                  coordinator,
                                                  outputDir,
                                                  language);
        } else {
                    return generateSourcesForPackage(fqName,
                                                     hidl_gen,
                                                     coordinator,
                                                     outputDir,
                                                     language);
        }
    };
}

static std::string makeLibraryName(const FQName &packageFQName) {
    return packageFQName.string();
}

static std::string makeJavaLibraryName(const FQName &packageFQName) {
    std::string out;
    out = packageFQName.package();
    out += "-V";
    out += packageFQName.version();
    return out;
}

static void generatePackagePathsSection(
        Formatter &out,
        Coordinator *coordinator,
        const FQName &packageFQName,
        const std::set<FQName> &importedPackages,
        bool forMakefiles = false) {
    std::set<std::string> options{};
    for (const auto &interface : importedPackages) {
        options.insert(coordinator->getPackageRootOption(interface));
    }
    options.insert(coordinator->getPackageRootOption(packageFQName));
    options.insert(coordinator->getPackageRootOption(gIBaseFqName));
    for (const auto &option : options) {
        out << "-r"
            << option
            << " ";
        if (forMakefiles) {
            out << "\\\n";
        }
    }
}

static void generateMakefileSectionForType(
        Formatter &out,
        Coordinator *coordinator,
        const FQName &packageFQName,
        const FQName &fqName,
        const std::set<FQName> &importedPackages,
        const char *typeName) {
    out << "\n"
        << "\n#"
        << "\n# Build " << fqName.name() << ".hal";

    if (typeName != nullptr) {
        out << " (" << typeName << ")";
    }

    out << "\n#"
        << "\nGEN := $(intermediates)/"
        << coordinator->convertPackageRootToPath(packageFQName)
        << coordinator->getPackagePath(packageFQName, true /* relative */,
                true /* sanitized */);
    if (typeName == nullptr) {
        out << fqName.name() << ".java";
    } else {
        out << typeName << ".java";
    }

    out << "\n$(GEN): $(HIDL)";
    out << "\n$(GEN): PRIVATE_HIDL := $(HIDL)";
    out << "\n$(GEN): PRIVATE_DEPS := $(LOCAL_PATH)/"
        << fqName.name() << ".hal";

    {
        AST *ast = coordinator->parse(fqName);
        CHECK(ast != nullptr);
        const std::set<FQName>& refs = ast->getImportedNames();
        for (auto depFQName : refs) {
            // If the package of depFQName is the same as this fqName's package,
            // then add it explicitly as a .hal dependency within the same
            // package.
            if (fqName.package() == depFQName.package() &&
                fqName.version() == depFQName.version()) {
                    // PRIVATE_DEPS is not actually being used in the
                    // auto-generated file, but is necessary if the build rule
                    // ever needs to use the dependency information, since the
                    // built-in Make variables are not supported in the Android
                    // build system.
                    out << "\n$(GEN): PRIVATE_DEPS += $(LOCAL_PATH)/"
                        << depFQName.name() << ".hal";
                    // This is the actual dependency.
                    out << "\n$(GEN): $(LOCAL_PATH)/"
                        << depFQName.name() << ".hal";
            }
        }
    }

    out << "\n$(GEN): PRIVATE_OUTPUT_DIR := $(intermediates)"
        << "\n$(GEN): PRIVATE_CUSTOM_TOOL = \\";
    out.indent();
    out.indent();
    out << "\n$(PRIVATE_HIDL) -o $(PRIVATE_OUTPUT_DIR) \\"
        << "\n-Ljava \\\n";

    generatePackagePathsSection(out, coordinator, packageFQName, importedPackages, true /* forJava */);

    out << packageFQName.string()
        << "::"
        << fqName.name();

    if (typeName != nullptr) {
        out << "." << typeName;
    }

    out << "\n";

    out.unindent();
    out.unindent();

    out << "\n$(GEN): $(LOCAL_PATH)/" << fqName.name() << ".hal";
    out << "\n\t$(transform-generated-source)";
    out << "\nLOCAL_GENERATED_SOURCES += $(GEN)";
}

static void generateMakefileSection(
        Formatter &out,
        Coordinator *coordinator,
        const FQName &packageFQName,
        const std::vector<FQName> &packageInterfaces,
        const std::set<FQName> &importedPackages,
        AST *typesAST) {
    for (const auto &fqName : packageInterfaces) {
        if (fqName.name() == "types") {
            CHECK(typesAST != nullptr);

            Scope* rootScope = typesAST->getRootScope();

            std::vector<NamedType *> subTypes = rootScope->getSubTypes();
            std::sort(
                    subTypes.begin(),
                    subTypes.end(),
                    [](const NamedType *a, const NamedType *b) -> bool {
                        return a->fqName() < b->fqName();
                    });

            for (const auto &type : subTypes) {
                if (type->isTypeDef()) {
                    continue;
                }

                generateMakefileSectionForType(
                        out,
                        coordinator,
                        packageFQName,
                        fqName,
                        importedPackages,
                        type->localName().c_str());
            }

            continue;
        }

        generateMakefileSectionForType(
                out,
                coordinator,
                packageFQName,
                fqName,
                importedPackages,
                nullptr /* typeName */);
    }
}

static status_t isPackageJavaCompatible(
        const FQName &packageFQName,
        Coordinator *coordinator,
        bool *compatible) {
    std::vector<FQName> todo;
    status_t err =
        coordinator->appendPackageInterfacesToVector(packageFQName, &todo);

    if (err != OK) {
        return err;
    }

    std::set<FQName> seen;
    for (const auto &iface : todo) {
        seen.insert(iface);
    }

    // Form the transitive closure of all imported interfaces (and types.hal-s)
    // If any one of them is not java compatible, this package isn't either.
    while (!todo.empty()) {
        const FQName fqName = todo.back();
        todo.pop_back();

        AST *ast = coordinator->parse(fqName);

        if (ast == nullptr) {
            return UNKNOWN_ERROR;
        }

        if (!ast->isJavaCompatible()) {
            *compatible = false;
            return OK;
        }

        std::set<FQName> importedPackages;
        ast->getImportedPackages(&importedPackages);

        for (const auto &package : importedPackages) {
            std::vector<FQName> packageInterfaces;
            status_t err = coordinator->appendPackageInterfacesToVector(
                    package, &packageInterfaces);

            if (err != OK) {
                return err;
            }

            for (const auto &iface : packageInterfaces) {
                if (seen.find(iface) != seen.end()) {
                    continue;
                }

                todo.push_back(iface);
                seen.insert(iface);
            }
        }
    }

    *compatible = true;
    return OK;
}

static bool packageNeedsJavaCode(
        const std::vector<FQName> &packageInterfaces, AST *typesAST) {
    // If there is more than just a types.hal file to this package we'll
    // definitely need to generate Java code.
    if (packageInterfaces.size() > 1
            || packageInterfaces[0].name() != "types") {
        return true;
    }

    CHECK(typesAST != nullptr);

    // We'll have to generate Java code if types.hal contains any non-typedef
    // type declarations.

    Scope* rootScope = typesAST->getRootScope();
    std::vector<NamedType *> subTypes = rootScope->getSubTypes();

    for (const auto &subType : subTypes) {
        if (!subType->isTypeDef()) {
            return true;
        }
    }

    return false;
}

static void generateMakefileSectionForJavaConstants(
        Formatter &out,
        Coordinator *coordinator,
        const FQName &packageFQName,
        const std::vector<FQName> &packageInterfaces,
        const std::set<FQName> &importedPackages) {
    out << "\n#"
        << "\nGEN := $(intermediates)/"
        << coordinator->convertPackageRootToPath(packageFQName)
        << coordinator->getPackagePath(packageFQName, true /* relative */, true /* sanitized */)
        << "Constants.java";

    out << "\n$(GEN): $(HIDL)\n";
    for (const auto &iface : packageInterfaces) {
        out << "$(GEN): $(LOCAL_PATH)/" << iface.name() << ".hal\n";
    }
    out << "\n$(GEN): PRIVATE_HIDL := $(HIDL)";
    out << "\n$(GEN): PRIVATE_OUTPUT_DIR := $(intermediates)"
        << "\n$(GEN): PRIVATE_CUSTOM_TOOL = \\";
    out.indent();
    out.indent();
    out << "\n$(PRIVATE_HIDL) -o $(PRIVATE_OUTPUT_DIR) \\"
        << "\n-Ljava-constants \\\n";

    generatePackagePathsSection(out, coordinator, packageFQName, importedPackages, true /* forJava */);

    out << packageFQName.string();
    out << "\n";

    out.unindent();
    out.unindent();

    out << "\n$(GEN):";
    out << "\n\t$(transform-generated-source)";
    out << "\nLOCAL_GENERATED_SOURCES += $(GEN)";
}

static status_t generateMakefileForPackage(
        const FQName &packageFQName,
        const char *hidl_gen,
        Coordinator *coordinator,
        const std::string &outputPath) {

    CHECK(packageFQName.isValid() &&
          !packageFQName.isFullyQualified() &&
          packageFQName.name().empty());

    std::vector<FQName> packageInterfaces;

    status_t err =
        coordinator->appendPackageInterfacesToVector(packageFQName,
                                                     &packageInterfaces);

    if (err != OK) {
        return err;
    }

    std::set<FQName> importedPackages;
    AST *typesAST = nullptr;
    std::vector<const Type *> exportedTypes;

    for (const auto &fqName : packageInterfaces) {
        AST *ast = coordinator->parse(fqName);

        if (ast == NULL) {
            fprintf(stderr,
                    "ERROR: Could not parse %s. Aborting.\n",
                    fqName.string().c_str());

            return UNKNOWN_ERROR;
        }

        if (fqName.name() == "types") {
            typesAST = ast;
        }

        ast->getImportedPackagesHierarchy(&importedPackages);
        ast->appendToExportedTypesVector(&exportedTypes);
    }

    bool packageIsJavaCompatible;
    err = isPackageJavaCompatible(
            packageFQName, coordinator, &packageIsJavaCompatible);

    if (err != OK) {
        return err;
    }

    bool haveJavaConstants = !exportedTypes.empty();

    if (!packageIsJavaCompatible && !haveJavaConstants) {
        // TODO(b/33420795)
        fprintf(stderr,
                "WARNING: %s is not java compatible. No java makefile created.\n",
                packageFQName.string().c_str());
        return OK;
    }

    if (!packageNeedsJavaCode(packageInterfaces, typesAST)) {
        return OK;
    }

    std::string path = outputPath;
    path.append(coordinator->getPackagePath(packageFQName, false /* relative */));
    path.append("Android.mk");

    CHECK(Coordinator::MakeParentHierarchy(path));
    FILE *file = fopen(path.c_str(), "w");

    if (file == NULL) {
        return -errno;
    }

    const std::string libraryName = makeJavaLibraryName(packageFQName);

    Formatter out(file);

    out << "# This file is autogenerated by hidl-gen. Do not edit manually.\n\n";
    out << "LOCAL_PATH := $(call my-dir)\n";

    enum LibraryStyle {
        LIBRARY_STYLE_REGULAR,
        LIBRARY_STYLE_STATIC,
        LIBRARY_STYLE_END,
    };

    for (int style = LIBRARY_STYLE_REGULAR;
            (packageIsJavaCompatible && style != LIBRARY_STYLE_END);
            ++style) {
        const std::string staticSuffix =
            (style == LIBRARY_STYLE_STATIC) ? "-static" : "";

        out << "\n"
            << "########################################"
            << "########################################\n\n";

        out << "include $(CLEAR_VARS)\n"
            << "LOCAL_MODULE := "
            << libraryName
            << "-java"
            << staticSuffix
            << "\nLOCAL_MODULE_CLASS := JAVA_LIBRARIES\n\n"
            << "intermediates := $(call local-generated-sources-dir, COMMON)"
            << "\n\n"
            << "HIDL := $(HOST_OUT_EXECUTABLES)/"
            << hidl_gen
            << "$(HOST_EXECUTABLE_SUFFIX)";

        if (!importedPackages.empty()) {
            out << "\n"
                << "\nLOCAL_"
                << ((style == LIBRARY_STYLE_STATIC) ? "STATIC_" : "")
                << "JAVA_LIBRARIES := \\";

            out.indent();
            for (const auto &importedPackage : importedPackages) {
                out << "\n"
                    << makeJavaLibraryName(importedPackage)
                    << "-java"
                    << staticSuffix
                    << " \\";
            }
            out << "\n";
            out.unindent();
        }

        generateMakefileSection(
                out,
                coordinator,
                packageFQName,
                packageInterfaces,
                importedPackages,
                typesAST);

        out << "\ninclude $(BUILD_"
            << ((style == LIBRARY_STYLE_STATIC) ? "STATIC_" : "")
            << "JAVA_LIBRARY)\n\n";
    }

    if (haveJavaConstants) {
        out << "\n"
            << "########################################"
            << "########################################\n\n";

        out << "include $(CLEAR_VARS)\n"
            << "LOCAL_MODULE := "
            << libraryName
            << "-java-constants"
            << "\nLOCAL_MODULE_CLASS := JAVA_LIBRARIES\n\n"
            << "intermediates := $(call local-generated-sources-dir, COMMON)"
            << "\n\n"
            << "HIDL := $(HOST_OUT_EXECUTABLES)/"
            << hidl_gen
            << "$(HOST_EXECUTABLE_SUFFIX)";

        generateMakefileSectionForJavaConstants(
                out, coordinator, packageFQName, packageInterfaces, importedPackages);

        out << "\n# Avoid dependency cycle of framework.jar -> this-library "
            << "-> framework.jar\n"
            << "LOCAL_NO_STANDARD_LIBRARIES := true\n"
            << "LOCAL_JAVA_LIBRARIES := core-oj\n\n"
            << "include $(BUILD_STATIC_JAVA_LIBRARY)\n\n";
    }

    out << "\n\n"
        << "include $(call all-makefiles-under,$(LOCAL_PATH))\n";

    return OK;
}

bool validateIsPackage(
        const FQName &fqName, const std::string & /* language */) {
    if (fqName.package().empty()) {
        fprintf(stderr, "ERROR: Expecting package name\n");
        return false;
    }

    if (fqName.version().empty()) {
        fprintf(stderr, "ERROR: Expecting package version\n");
        return false;
    }

    if (!fqName.name().empty()) {
        fprintf(stderr,
                "ERROR: Expecting only package name and version.\n");
        return false;
    }

    return true;
}

bool isHidlTransportPackage(const FQName& fqName) {
    return fqName.package() == gIBasePackageFqName.string() ||
           fqName.package() == gIManagerPackageFqName.string();
}

bool isSystemProcessSupportedPackage(const FQName& fqName) {
    // Technically, so is hidl IBase + IServiceManager, but
    // these are part of libhidltransport.
    return fqName.string() == "android.hardware.graphics.allocator@2.0" ||
           fqName.string() == "android.hardware.graphics.common@1.0" ||
           fqName.string() == "android.hardware.graphics.mapper@2.0" ||
           fqName.string() == "android.hardware.renderscript@1.0" ||
           fqName.string() == "android.hidl.memory@1.0";
}

bool isSystemPackage(const FQName &package) {
    return package.inPackage("android.hidl") ||
           package.inPackage("android.system") ||
           package.inPackage("android.frameworks") ||
           package.inPackage("android.hardware");
}

static void generateAndroidBpGenSection(
        Formatter &out,
        const FQName &packageFQName,
        const char *hidl_gen,
        Coordinator *coordinator,
        const std::string &halFilegroupName,
        const std::string &genName,
        const char *language,
        const std::vector<FQName> &packageInterfaces,
        const std::set<FQName> &importedPackages,
        const std::function<void(Formatter&, const FQName)> outputFn) {

    out << "genrule {\n";
    out.indent();
    out << "name: \"" << genName << "\",\n"
        << "tools: [\"" << hidl_gen << "\"],\n";

    out << "cmd: \"$(location " << hidl_gen << ") -o $(genDir)"
        << " -L" << language << " ";

    generatePackagePathsSection(out, coordinator, packageFQName, importedPackages);

    out << packageFQName.string() << "\",\n";

    out << "srcs: [\n";
    out.indent();
    out << "\":" << halFilegroupName << "\",\n";
    out.unindent();
    out << "],\n";

    out << "out: [\n";
    out.indent();
    for (const auto &fqName : packageInterfaces) {
        outputFn(out, fqName);
    }
    out.unindent();
    out << "],\n";

    out.unindent();
    out << "}\n\n";
}

static void generateAndroidBpDependencyList(
        Formatter &out,
        const std::set<FQName> &importedPackagesHierarchy,
        bool generateVendor) {
    for (const auto &importedPackage : importedPackagesHierarchy) {
        if (isHidlTransportPackage(importedPackage)) {
            continue;
        }

        out << "\"" << makeLibraryName(importedPackage);
        if (generateVendor && !isSystemPackage(importedPackage)) {
            out << "_vendor";
        }
        out << "\",\n";
    }
}

static void generateAndroidBpLibSection(
        Formatter &out,
        bool generateVendor,
        const FQName &packageFQName,
        const std::string &libraryName,
        const std::string &genSourceName,
        const std::string &genHeaderName,
        const std::set<FQName> &importedPackagesHierarchy) {

    // C++ library definition
    out << "cc_library {\n";
    out.indent();
    out << "name: \"" << libraryName << (generateVendor ? "_vendor" : "") << "\",\n"
        << "defaults: [\"hidl-module-defaults\"],\n"
        << "generated_sources: [\"" << genSourceName << "\"],\n"
        << "generated_headers: [\"" << genHeaderName << "\"],\n"
        << "export_generated_headers: [\"" << genHeaderName << "\"],\n";

    if (generateVendor) {
        out << "vendor: true,\n";
    } else {
        out << "vendor_available: true,\n";
        if (!generateForTest) {
            out << "vndk: ";
            out.block([&]() {
                out << "enabled: true,\n";
                if (isSystemProcessSupportedPackage(packageFQName)) {
                    out << "support_system_process: true,\n";
                }
            }) << ",\n";
        }
    }
    out << "shared_libs: [\n";

    out.indent();
    out << "\"libhidlbase\",\n"
        << "\"libhidltransport\",\n"
        << "\"libhwbinder\",\n"
        << "\"liblog\",\n"
        << "\"libutils\",\n"
        << "\"libcutils\",\n";
    generateAndroidBpDependencyList(out, importedPackagesHierarchy, generateVendor);
    out.unindent();

    out << "],\n";

    out << "export_shared_lib_headers: [\n";
    out.indent();
    out << "\"libhidlbase\",\n"
        << "\"libhidltransport\",\n"
        << "\"libhwbinder\",\n"
        << "\"libutils\",\n";
    generateAndroidBpDependencyList(out, importedPackagesHierarchy, generateVendor);
    out.unindent();
    out << "],\n";
    out.unindent();

    out << "}\n";
}

static status_t generateAndroidBpForPackage(
        const FQName &packageFQName,
        const char *hidl_gen,
        Coordinator *coordinator,
        const std::string &outputPath) {

    CHECK(packageFQName.isValid() &&
          !packageFQName.isFullyQualified() &&
          packageFQName.name().empty());

    std::vector<FQName> packageInterfaces;

    status_t err =
        coordinator->appendPackageInterfacesToVector(packageFQName,
                                                     &packageInterfaces);

    if (err != OK) {
        return err;
    }

    std::set<FQName> importedPackagesHierarchy;
    AST *typesAST = nullptr;

    for (const auto &fqName : packageInterfaces) {
        AST *ast = coordinator->parse(fqName);

        if (ast == NULL) {
            fprintf(stderr,
                    "ERROR: Could not parse %s. Aborting.\n",
                    fqName.string().c_str());

            return UNKNOWN_ERROR;
        }

        if (fqName.name() == "types") {
            typesAST = ast;
        }

        ast->getImportedPackagesHierarchy(&importedPackagesHierarchy);
    }

    std::string path = outputPath;
    path.append(coordinator->getPackagePath(packageFQName, false /* relative */));
    path.append("Android.bp");

    CHECK(Coordinator::MakeParentHierarchy(path));
    FILE *file = fopen(path.c_str(), "w");

    if (file == NULL) {
        return -errno;
    }

    const std::string libraryName = makeLibraryName(packageFQName);
    const std::string halFilegroupName = libraryName + "_hal";
    const std::string genSourceName = libraryName + "_genc++";
    const std::string genHeaderName = libraryName + "_genc++_headers";
    const std::string pathPrefix =
        coordinator->convertPackageRootToPath(packageFQName) +
        coordinator->getPackagePath(packageFQName, true /* relative */);

    Formatter out(file);

    out << "// This file is autogenerated by hidl-gen. Do not edit manually.\n\n";

    // Rule to generate .hal filegroup
    out << "filegroup {\n";
    out.indent();
    out << "name: \"" << halFilegroupName << "\",\n";
    out << "srcs: [\n";
    out.indent();
    for (const auto &fqName : packageInterfaces) {
      out << "\"" << fqName.name() << ".hal\",\n";
    }
    out.unindent();
    out << "],\n";
    out.unindent();
    out << "}\n\n";

    // Rule to generate the C++ source files
    generateAndroidBpGenSection(
            out,
            packageFQName,
            hidl_gen,
            coordinator,
            halFilegroupName,
            genSourceName,
            "c++-sources",
            packageInterfaces,
            importedPackagesHierarchy,
            [&pathPrefix](Formatter &out, const FQName &fqName) {
                if (fqName.name() == "types") {
                    out << "\"" << pathPrefix << "types.cpp\",\n";
                } else {
                    out << "\"" << pathPrefix << fqName.name().substr(1) << "All.cpp\",\n";
                }
            });

    // Rule to generate the C++ header files
    generateAndroidBpGenSection(
            out,
            packageFQName,
            hidl_gen,
            coordinator,
            halFilegroupName,
            genHeaderName,
            "c++-headers",
            packageInterfaces,
            importedPackagesHierarchy,
            [&pathPrefix](Formatter &out, const FQName &fqName) {
                out << "\"" << pathPrefix << fqName.name() << ".h\",\n";
                if (fqName.name() != "types") {
                    out << "\"" << pathPrefix << fqName.getInterfaceHwName() << ".h\",\n";
                    out << "\"" << pathPrefix << fqName.getInterfaceStubName() << ".h\",\n";
                    out << "\"" << pathPrefix << fqName.getInterfaceProxyName() << ".h\",\n";
                    out << "\"" << pathPrefix << fqName.getInterfacePassthroughName() << ".h\",\n";
                } else {
                    out << "\"" << pathPrefix << "hwtypes.h\",\n";
                }
            });

    if (isHidlTransportPackage(packageFQName)) {
        out << "// " << packageFQName.string() << " is exported from libhidltransport\n";
    } else {
        generateAndroidBpLibSection(
            out,
            false /* generateVendor */,
            packageFQName,
            libraryName,
            genSourceName,
            genHeaderName,
            importedPackagesHierarchy);

        // TODO(b/35813011): make all libraries vendor_available
        // Explicitly create '_vendor' copies of libraries so that
        // vendor code can link against the extensions. When this is
        // used, framework code should link against vendor.awesome.foo@1.0
        // and code on the vendor image should link against
        // vendor.awesome.foo@1.0_vendor. For libraries with the below extensions,
        // they will be available even on the generic system image.
        // Because of this, they should always be referenced without the
        // '_vendor' name suffix.
        if (!isSystemPackage(packageFQName)) {

            // Note, not using cc_defaults here since it's already not used and
            // because generating this libraries will be removed when the VNDK
            // is enabled (done by the build system itself).
            out.endl();
            generateAndroidBpLibSection(
                out,
                true /* generateVendor */,
                packageFQName,
                libraryName,
                genSourceName,
                genHeaderName,
                importedPackagesHierarchy);
        }
    }

    return OK;
}

static status_t generateAndroidBpImplForPackage(
        const FQName &packageFQName,
        const char *,
        Coordinator *coordinator,
        const std::string &outputDir) {

    const std::string libraryName = makeLibraryName(packageFQName) + "-impl";

    std::vector<FQName> packageInterfaces;

    status_t err =
        coordinator->appendPackageInterfacesToVector(packageFQName,
                                                     &packageInterfaces);

    if (err != OK) {
        return err;
    }

    std::set<FQName> importedPackages;

    for (const auto &fqName : packageInterfaces) {
        AST *ast = coordinator->parse(fqName);

        if (ast == NULL) {
            fprintf(stderr,
                    "ERROR: Could not parse %s. Aborting.\n",
                    fqName.string().c_str());

            return UNKNOWN_ERROR;
        }

        ast->getImportedPackages(&importedPackages);
    }

    std::string path = outputDir + "Android.bp";

    CHECK(Coordinator::MakeParentHierarchy(path));
    FILE *file = fopen(path.c_str(), "w");

    if (file == NULL) {
        return -errno;
    }

    Formatter out(file);

    out << "cc_library_shared {\n";
    out.indent([&] {
        out << "name: \"" << libraryName << "\",\n"
            << "relative_install_path: \"hw\",\n"
            << "proprietary: true,\n"
            << "srcs: [\n";
        out.indent([&] {
            for (const auto &fqName : packageInterfaces) {
                if (fqName.name() == "types") {
                    continue;
                }
                out << "\"" << fqName.getInterfaceBaseName() << ".cpp\",\n";
            }
        });
        out << "],\n"
            << "shared_libs: [\n";
        out.indent([&] {
            out << "\"libhidlbase\",\n"
                << "\"libhidltransport\",\n"
                << "\"libutils\",\n"
                << "\"" << makeLibraryName(packageFQName) << "\",\n";

            for (const auto &importedPackage : importedPackages) {
                if (isHidlTransportPackage(importedPackage)) {
                    continue;
                }

                out << "\"" << makeLibraryName(importedPackage) << "\",\n";
            }
        });
        out << "],\n";
    });
    out << "}\n";

    return OK;
}

bool validateForSource(
        const FQName &fqName, const std::string &language) {
    if (fqName.package().empty()) {
        fprintf(stderr, "ERROR: Expecting package name\n");
        return false;
    }

    if (fqName.version().empty()) {
        fprintf(stderr, "ERROR: Expecting package version\n");
        return false;
    }

    const std::string &name = fqName.name();
    if (!name.empty()) {
        if (name.find('.') == std::string::npos) {
            return true;
        }

        if (language != "java" || name.find("types.") != 0) {
            // When generating java sources for "types.hal", output can be
            // constrained to just one of the top-level types declared
            // by using the extended syntax
            // android.hardware.Foo@1.0::types.TopLevelTypeName.
            // In all other cases (different language, not 'types') the dot
            // notation in the name is illegal in this context.
            return false;
        }

        return true;
    }

    return true;
}

OutputHandler::GenerationFunction generateExportHeaderForPackage(bool forJava) {
    return [forJava](const FQName &packageFQName,
                     const char * /* hidl_gen */,
                     Coordinator *coordinator,
                     const std::string &outputPath) -> status_t {
        CHECK(packageFQName.isValid()
                && !packageFQName.package().empty()
                && !packageFQName.version().empty()
                && packageFQName.name().empty());

        std::vector<FQName> packageInterfaces;

        status_t err = coordinator->appendPackageInterfacesToVector(
                packageFQName, &packageInterfaces);

        if (err != OK) {
            return err;
        }

        std::vector<const Type *> exportedTypes;

        for (const auto &fqName : packageInterfaces) {
            AST *ast = coordinator->parse(fqName);

            if (ast == NULL) {
                fprintf(stderr,
                        "ERROR: Could not parse %s. Aborting.\n",
                        fqName.string().c_str());

                return UNKNOWN_ERROR;
            }

            ast->appendToExportedTypesVector(&exportedTypes);
        }

        if (exportedTypes.empty()) {
            return OK;
        }

        std::string path = outputPath;

        if (forJava) {
            path.append(coordinator->convertPackageRootToPath(packageFQName));

            path.append(coordinator->getPackagePath(
                        packageFQName, true /* relative */, true /* sanitized */));

            path.append("Constants.java");
        }

        CHECK(Coordinator::MakeParentHierarchy(path));
        FILE *file = fopen(path.c_str(), "w");

        if (file == nullptr) {
            return -errno;
        }

        Formatter out(file);

        out << "// This file is autogenerated by hidl-gen. Do not edit manually.\n"
            << "// Source: " << packageFQName.string() << "\n"
            << "// Root: " << coordinator->getPackageRootOption(packageFQName) << "\n\n";

        std::string guard;
        if (forJava) {
            out << "package " << packageFQName.javaPackage() << ";\n\n";
            out << "public class Constants {\n";
            out.indent();
        } else {
            guard = "HIDL_GENERATED_";
            guard += StringHelper::Uppercase(packageFQName.tokenName());
            guard += "_";
            guard += "EXPORTED_CONSTANTS_H_";

            out << "#ifndef "
                << guard
                << "\n#define "
                << guard
                << "\n\n#ifdef __cplusplus\nextern \"C\" {\n#endif\n\n";
        }

        for (const auto &type : exportedTypes) {
            type->emitExportedHeader(out, forJava);
        }

        if (forJava) {
            out.unindent();
            out << "}\n";
        } else {
            out << "#ifdef __cplusplus\n}\n#endif\n\n#endif  // "
                << guard
                << "\n";
        }

        return OK;
    };
}

static status_t generateHashOutput(const FQName &fqName,
        const char* /*hidl_gen*/,
        Coordinator *coordinator,
        const std::string & /*outputDir*/) {

    status_t err;
    std::vector<FQName> packageInterfaces;

    if (fqName.isFullyQualified()) {
        packageInterfaces = {fqName};
    } else {
        err = coordinator->appendPackageInterfacesToVector(
                fqName, &packageInterfaces);
        if (err != OK) {
            return err;
        }
    }

    for (const auto &currentFqName : packageInterfaces) {
        AST* ast = coordinator->parse(currentFqName, {} /* parsed */,
                                      Coordinator::Enforce::NO_HASH /* enforcement */);

        if (ast == NULL) {
            fprintf(stderr,
                    "ERROR: Could not parse %s. Aborting.\n",
                    currentFqName.string().c_str());

            return UNKNOWN_ERROR;
        }

        printf("%s %s\n",
                Hash::getHash(ast->getFilename()).hexString().c_str(),
                currentFqName.string().c_str());
    }

    return OK;
}

static std::vector<OutputHandler> formats = {
    {"check",
     "Parses the interface to see if valid but doesn't write any files.",
     OutputHandler::NOT_NEEDED /* mOutputMode */,
     validateForSource,
     generationFunctionForFileOrPackage("check")
    },

    {"c++",
     "(internal) (deprecated) Generates C++ interface files for talking to HIDL interfaces.",
     OutputHandler::NEEDS_DIR /* mOutputMode */,
     validateForSource,
     generationFunctionForFileOrPackage("c++")
    },

    {"c++-headers",
     "(internal) Generates C++ headers for interface files for talking to HIDL interfaces.",
     OutputHandler::NEEDS_DIR /* mOutputMode */,
     validateForSource,
     generationFunctionForFileOrPackage("c++-headers")
    },

    {"c++-sources",
     "(internal) Generates C++ sources for interface files for talking to HIDL interfaces.",
     OutputHandler::NEEDS_DIR /* mOutputMode */,
     validateForSource,
     generationFunctionForFileOrPackage("c++-sources")
    },

    {"export-header",
     "Generates a header file from @export enumerations to help maintain legacy code.",
     OutputHandler::NEEDS_FILE /* mOutputMode */,
     validateIsPackage,
     generateExportHeaderForPackage(false /* forJava */)
    },

    {"c++-impl",
     "Generates boilerplate implementation of a hidl interface in C++ (for convenience).",
     OutputHandler::NEEDS_DIR /* mOutputMode */,
     validateForSource,
     generationFunctionForFileOrPackage("c++-impl")
    },
    {"c++-impl-headers",
     "c++-impl but headers only",
     OutputHandler::NEEDS_DIR /* mOutputMode */,
     validateForSource,
     generationFunctionForFileOrPackage("c++-impl-headers")
    },
    {"c++-impl-sources",
     "c++-impl but sources only",
     OutputHandler::NEEDS_DIR /* mOutputMode */,
     validateForSource,
     generationFunctionForFileOrPackage("c++-impl-sources")
    },

    {"java",
     "(internal) Generates Java library for talking to HIDL interfaces in Java.",
     OutputHandler::NEEDS_DIR /* mOutputMode */,
     validateForSource,
     generationFunctionForFileOrPackage("java")
    },

    {"java-constants",
     "(internal) Like export-header but for Java (always created by -Lmakefile if @export exists).",
     OutputHandler::NEEDS_DIR /* mOutputMode */,
     validateIsPackage,
     generateExportHeaderForPackage(true /* forJava */)
    },

    {"vts",
     "(internal) Generates vts proto files for use in vtsd.",
     OutputHandler::NEEDS_DIR /* mOutputMode */,
     validateForSource,
     generationFunctionForFileOrPackage("vts")
    },

    {"makefile",
     "(internal) Generates makefiles for -Ljava and -Ljava-constants.",
     OutputHandler::NEEDS_SRC /* mOutputMode */,
     validateIsPackage,
     generateMakefileForPackage,
    },

    {"androidbp",
     "(internal) Generates Soong bp files for -Lc++-headers and -Lc++-sources.",
     OutputHandler::NEEDS_SRC /* mOutputMode */,
     validateIsPackage,
     generateAndroidBpForPackage,
    },

    {"androidbp-impl",
     "Generates boilerplate bp files for implementation created with -Lc++-impl.",
     OutputHandler::NEEDS_DIR /* mOutputMode */,
     validateIsPackage,
     generateAndroidBpImplForPackage,
    },

    {"hash",
     "Prints hashes of interface in `current.txt` format to standard out.",
     OutputHandler::NOT_NEEDED /* mOutputMode */,
     validateForSource,
     generateHashOutput,
    },
};

static void usage(const char *me) {
    fprintf(stderr,
            "usage: %s [-p <root path>] -o <output path> -L <language> (-r <interface root>)+ [-t] "
            "fqname+\n",
            me);

    fprintf(stderr, "         -h: Prints this menu.\n");
    fprintf(stderr, "         -L <language>: The following options are available:\n");
    for (auto &e : formats) {
        fprintf(stderr, "            %-16s: %s\n", e.name().c_str(), e.description().c_str());
    }
    fprintf(stderr, "         -o <output path>: Location to output files.\n");
    fprintf(stderr, "         -p <root path>: Android build root, defaults to $ANDROID_BUILD_TOP or pwd.\n");
    fprintf(stderr, "         -r <package:path root>: E.g., android.hardware:hardware/interfaces.\n");
    fprintf(stderr, "         -t: generate build scripts (Android.bp) for tests.\n");
}

// hidl is intentionally leaky. Turn off LeakSanitizer by default.
extern "C" const char *__asan_default_options() {
    return "detect_leaks=0";
}

int main(int argc, char **argv) {
    std::string outputPath;
    std::string rootPath;
    std::vector<std::string> packageRootPaths;
    std::vector<std::string> packageRoots;

    const char *me = argv[0];
    OutputHandler *outputFormat = nullptr;

    if (argc == 1) {
        usage(me);
        exit(1);
    }

    int res;
    while ((res = getopt(argc, argv, "hp:o:r:L:t")) >= 0) {
        switch (res) {
            case 'p':
            {
                rootPath = optarg;
                break;
            }

            case 'o':
            {
                outputPath = optarg;
                break;
            }

            case 'r':
            {
                std::string val(optarg);
                auto index = val.find_first_of(':');
                if (index == std::string::npos) {
                    fprintf(stderr, "ERROR: -r option must contain ':': %s\n", val.c_str());
                    exit(1);
                }

                auto package = val.substr(0, index);
                auto path = val.substr(index + 1);
                packageRootPaths.push_back(path);
                packageRoots.push_back(package);
                break;
            }

            case 'L':
            {
                if (outputFormat != nullptr) {
                    fprintf(stderr,
                            "ERROR: only one -L option allowed. \"%s\" already specified.\n",
                            outputFormat->name().c_str());
                    exit(1);
                }
                for (auto &e : formats) {
                    if (e.name() == optarg) {
                        outputFormat = &e;
                        break;
                    }
                }
                if (outputFormat == nullptr) {
                    fprintf(stderr,
                            "ERROR: unrecognized -L option: \"%s\".\n",
                            optarg);
                    exit(1);
                }
                break;
            }

            case 't': {
                generateForTest = true;
                break;
            }

            case '?':
            case 'h':
            default:
            {
                usage(me);
                exit(1);
                break;
            }
        }
    }

    if (outputFormat == nullptr) {
        fprintf(stderr,
            "ERROR: no -L option provided.\n");
        exit(1);
    }

    if (generateForTest && outputFormat->name() != "androidbp") {
        fprintf(stderr, "ERROR: -t option is for -Landroidbp only.\n");
        exit(1);
    }

    argc -= optind;
    argv += optind;

    if (argc == 0) {
        fprintf(stderr, "ERROR: no fqname specified.\n");
        usage(me);
        exit(1);
    }

    if (rootPath.empty()) {
        const char *ANDROID_BUILD_TOP = getenv("ANDROID_BUILD_TOP");

        if (ANDROID_BUILD_TOP != nullptr) {
            rootPath = ANDROID_BUILD_TOP;
        }

        // else default to pwd
    }

    if (!rootPath.empty() && !StringHelper::EndsWith(rootPath, "/")) {
        rootPath += "/";
    }

    // Valid options are now in argv[0] .. argv[argc - 1].

    switch (outputFormat->mOutputMode) {
        case OutputHandler::NEEDS_DIR:
        case OutputHandler::NEEDS_FILE:
        {
            if (outputPath.empty()) {
                usage(me);
                exit(1);
            }

            if (outputFormat->mOutputMode == OutputHandler::NEEDS_DIR) {
                const size_t len = outputPath.size();
                if (outputPath[len - 1] != '/') {
                    outputPath += "/";
                }
            }
            break;
        }
        case OutputHandler::NEEDS_SRC:
        {
            if (outputPath.empty()) {
                outputPath = rootPath;
            }

            break;
        }

        default:
            outputPath.clear();  // Unused.
            break;
    }

    Coordinator coordinator(packageRootPaths, packageRoots, rootPath);
    coordinator.addDefaultPackagePath("android.hardware", "hardware/interfaces");
    coordinator.addDefaultPackagePath("android.hidl", "system/libhidl/transport");
    coordinator.addDefaultPackagePath("android.frameworks", "frameworks/hardware/interfaces");
    coordinator.addDefaultPackagePath("android.system", "system/hardware/interfaces");

    for (int i = 0; i < argc; ++i) {
        FQName fqName(argv[i]);

        if (!fqName.isValid()) {
            fprintf(stderr,
                    "ERROR: Invalid fully-qualified name.\n");
            exit(1);
        }

        if (!outputFormat->validate(fqName, outputFormat->name())) {
            fprintf(stderr,
                    "ERROR: output handler failed.\n");
            exit(1);
        }

        status_t err =
            outputFormat->generate(fqName, me, &coordinator, outputPath);

        if (err != OK) {
            exit(1);
        }
    }

    return 0;
}
