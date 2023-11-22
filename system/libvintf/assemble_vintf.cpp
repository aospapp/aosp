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

#include <getopt.h>
#include <stdlib.h>
#include <unistd.h>

#include <fstream>
#include <iostream>
#include <unordered_map>
#include <sstream>
#include <string>

#include <android-base/file.h>

#include <vintf/KernelConfigParser.h>
#include <vintf/parse_string.h>
#include <vintf/parse_xml.h>

#define BUFFER_SIZE sysconf(_SC_PAGESIZE)

namespace android {
namespace vintf {

static const std::string gConfigPrefix = "android-base-";
static const std::string gConfigSuffix = ".cfg";
static const std::string gBaseConfig = "android-base.cfg";

/**
 * Slurps the device manifest file and add build time flag to it.
 */
class AssembleVintf {
    using Condition = std::unique_ptr<KernelConfig>;
    using ConditionedConfig = std::pair<Condition, std::vector<KernelConfig> /* configs */>;

   public:
    template<typename T>
    static bool getFlag(const std::string& key, T* value) {
        const char *envValue = getenv(key.c_str());
        if (envValue == NULL) {
            std::cerr << "Warning: " << key << " is missing, defaulted to " << (*value)
                      << std::endl;
            return true;
        }

        if (!parse(envValue, value)) {
            std::cerr << "Cannot parse " << envValue << "." << std::endl;
            return false;
        }
        return true;
    }

    static std::string read(std::basic_istream<char>& is) {
        std::stringstream ss;
        ss << is.rdbuf();
        return ss.str();
    }

    static bool isCommonConfig(const std::string& path) {
        return ::android::base::Basename(path) == gBaseConfig;
    }

    // nullptr on any error, otherwise the condition.
    static Condition generateCondition(const std::string& path) {
        std::string fname = ::android::base::Basename(path);
        if (fname.size() <= gConfigPrefix.size() + gConfigSuffix.size() ||
            !std::equal(gConfigPrefix.begin(), gConfigPrefix.end(), fname.begin()) ||
            !std::equal(gConfigSuffix.rbegin(), gConfigSuffix.rend(), fname.rbegin())) {
            return nullptr;
        }

        std::string sub = fname.substr(gConfigPrefix.size(),
                                       fname.size() - gConfigPrefix.size() - gConfigSuffix.size());
        if (sub.empty()) {
            return nullptr;  // should not happen
        }
        for (size_t i = 0; i < sub.size(); ++i) {
            if (sub[i] == '-') {
                sub[i] = '_';
                continue;
            }
            if (isalnum(sub[i])) {
                sub[i] = toupper(sub[i]);
                continue;
            }
            std::cerr << "'" << fname << "' (in " << path
                      << ") is not a valid kernel config file name. Must match regex: "
                      << "android-base(-[0-9a-zA-Z-]+)?\\.cfg" << std::endl;
            return nullptr;
        }
        sub.insert(0, "CONFIG_");
        return std::make_unique<KernelConfig>(std::move(sub), Tristate::YES);
    }

    static bool parseFileForKernelConfigs(const std::string& path, std::vector<KernelConfig>* out) {
        std::ifstream ifs{path};
        if (!ifs.is_open()) {
            std::cerr << "File '" << path << "' does not exist or cannot be read." << std::endl;
            return false;
        }
        KernelConfigParser parser(true /* processComments */, true /* relaxedFormat */);
        std::string content = read(ifs);
        status_t err = parser.process(content.c_str(), content.size());
        if (err != OK) {
            std::cerr << parser.error();
            return false;
        }
        err = parser.finish();
        if (err != OK) {
            std::cerr << parser.error();
            return false;
        }

        for (auto& configPair : parser.configs()) {
            out->push_back({});
            KernelConfig& config = out->back();
            config.first = std::move(configPair.first);
            if (!parseKernelConfigTypedValue(configPair.second, &config.second)) {
                std::cerr << "Unknown value type for key = '" << config.first << "', value = '"
                          << configPair.second << "'\n";
                return false;
            }
        }
        return true;
    }

    static bool parseFilesForKernelConfigs(const std::string& path,
                                           std::vector<ConditionedConfig>* out) {
        out->clear();
        ConditionedConfig commonConfig;
        bool foundCommonConfig = false;
        bool ret = true;
        char *pathIter;
        char *modPath = new char[path.length() + 1];
        strcpy(modPath, path.c_str());
        pathIter = strtok(modPath, ":");
        while (ret && pathIter != NULL) {
            if (isCommonConfig(pathIter)) {
                ret &= parseFileForKernelConfigs(pathIter, &commonConfig.second);
                foundCommonConfig = true;
            } else {
                Condition condition = generateCondition(pathIter);
                ret &= (condition != nullptr);

                std::vector<KernelConfig> kernelConfigs;
                if ((ret &= parseFileForKernelConfigs(pathIter, &kernelConfigs)))
                    out->emplace_back(std::move(condition), std::move(kernelConfigs));
            }
            pathIter = strtok(NULL, ":");
        }
        delete[] modPath;

        if (!foundCommonConfig) {
            std::cerr << "No android-base.cfg is found in these paths: '" << path << "'"
                      << std::endl;
        }
        ret &= foundCommonConfig;
        // first element is always common configs (no conditions).
        out->insert(out->begin(), std::move(commonConfig));
        return ret;
    }

    std::basic_ostream<char>& out() const {
        return mOutFileRef == nullptr ? std::cout : *mOutFileRef;
    }

    bool assembleHalManifest(HalManifest* halManifest) {
        std::string error;

        if (halManifest->mType == SchemaType::DEVICE) {
            if (!getFlag("BOARD_SEPOLICY_VERS", &halManifest->device.mSepolicyVersion)) {
                return false;
            }
        }

        if (mOutputMatrix) {
            CompatibilityMatrix generatedMatrix = halManifest->generateCompatibleMatrix();
            if (!halManifest->checkCompatibility(generatedMatrix, &error)) {
                std::cerr << "FATAL ERROR: cannot generate a compatible matrix: " << error
                          << std::endl;
            }
            out() << "<!-- \n"
                     "    Autogenerated skeleton compatibility matrix. \n"
                     "    Use with caution. Modify it to suit your needs.\n"
                     "    All HALs are set to optional.\n"
                     "    Many entries other than HALs are zero-filled and\n"
                     "    require human attention. \n"
                     "-->\n"
                  << gCompatibilityMatrixConverter(generatedMatrix);
        } else {
            out() << gHalManifestConverter(*halManifest);
        }
        out().flush();

        if (mCheckFile.is_open()) {
            CompatibilityMatrix checkMatrix;
            if (!gCompatibilityMatrixConverter(&checkMatrix, read(mCheckFile))) {
                std::cerr << "Cannot parse check file as a compatibility matrix: "
                          << gCompatibilityMatrixConverter.lastError() << std::endl;
                return false;
            }
            if (!halManifest->checkCompatibility(checkMatrix, &error)) {
                std::cerr << "Not compatible: " << error << std::endl;
                return false;
            }
        }

        return true;
    }

    bool assembleFrameworkCompatibilityMatrixKernels(CompatibilityMatrix* matrix) {
        if (!matrix->framework.mKernels.empty()) {
            // Remove hard-coded <kernel version="x.y.z" /> in legacy files.
            std::cerr << "WARNING: framework compatibility matrix has hard-coded kernel"
                      << " requirements for version";
            for (const auto& kernel : matrix->framework.mKernels) {
                std::cerr << " " << kernel.minLts();
            }
            std::cerr << ". Hard-coded requirements are removed." << std::endl;
            matrix->framework.mKernels.clear();
        }
        for (const auto& pair : mKernels) {
            std::vector<ConditionedConfig> conditionedConfigs;
            if (!parseFilesForKernelConfigs(pair.second, &conditionedConfigs)) {
                return false;
            }
            for (ConditionedConfig& conditionedConfig : conditionedConfigs) {
                MatrixKernel kernel(KernelVersion{pair.first.majorVer, pair.first.minorVer, 0u},
                                    std::move(conditionedConfig.second));
                if (conditionedConfig.first != nullptr)
                    kernel.mConditions.push_back(std::move(*conditionedConfig.first));
                matrix->framework.mKernels.push_back(std::move(kernel));
            }
        }
        return true;
    }

    bool assembleCompatibilityMatrix(CompatibilityMatrix* matrix) {
        std::string error;

        KernelSepolicyVersion kernelSepolicyVers;
        Version sepolicyVers;
        if (matrix->mType == SchemaType::FRAMEWORK) {
            if (!getFlag("BOARD_SEPOLICY_VERS", &sepolicyVers)) {
                return false;
            }
            if (!getFlag("POLICYVERS", &kernelSepolicyVers)) {
                return false;
            }

            if (!assembleFrameworkCompatibilityMatrixKernels(matrix)) {
                return false;
            }

            matrix->framework.mSepolicy =
                Sepolicy(kernelSepolicyVers, {{sepolicyVers.majorVer, sepolicyVers.minorVer}});

            Version avbMetaVersion;
            if (!getFlag("FRAMEWORK_VBMETA_VERSION", &avbMetaVersion)) {
                return false;
            }
            matrix->framework.mAvbMetaVersion = avbMetaVersion;
        }
        out() << gCompatibilityMatrixConverter(*matrix);
        out().flush();

        if (mCheckFile.is_open()) {
            HalManifest checkManifest;
            if (!gHalManifestConverter(&checkManifest, read(mCheckFile))) {
                std::cerr << "Cannot parse check file as a HAL manifest: "
                          << gHalManifestConverter.lastError() << std::endl;
                return false;
            }
            if (!checkManifest.checkCompatibility(*matrix, &error)) {
                std::cerr << "Not compatible: " << error << std::endl;
                return false;
            }
        }

        return true;
    }

    enum AssembleStatus { SUCCESS, FAIL_AND_EXIT, TRY_NEXT };
    template <typename Schema, typename AssembleFunc>
    AssembleStatus tryAssemble(const XmlConverter<Schema>& converter, const std::string& schemaName,
                               AssembleFunc assemble) {
        Schema schema;
        if (!converter(&schema, read(mInFiles.front()))) {
            return TRY_NEXT;
        }
        auto firstType = schema.type();
        for (auto it = mInFiles.begin() + 1; it != mInFiles.end(); ++it) {
            Schema additionalSchema;
            if (!converter(&additionalSchema, read(*it))) {
                std::cerr << "File \"" << mInFilePaths[std::distance(mInFiles.begin(), it)]
                          << "\" is not a valid " << firstType << " " << schemaName
                          << " (but the first file is a valid " << firstType << " " << schemaName
                          << "). Error: " << converter.lastError() << std::endl;
                return FAIL_AND_EXIT;
            }
            if (additionalSchema.type() != firstType) {
                std::cerr << "File \"" << mInFilePaths[std::distance(mInFiles.begin(), it)]
                          << "\" is a " << additionalSchema.type() << " " << schemaName
                          << " (but a " << firstType << " " << schemaName << " is expected)."
                          << std::endl;
                return FAIL_AND_EXIT;
            }
            schema.addAll(std::move(additionalSchema));
        }
        return assemble(&schema) ? SUCCESS : FAIL_AND_EXIT;
    }

    bool assemble() {
        using std::placeholders::_1;
        if (mInFiles.empty()) {
            std::cerr << "Missing input file." << std::endl;
            return false;
        }

        auto status = tryAssemble(gHalManifestConverter, "manifest",
                                  std::bind(&AssembleVintf::assembleHalManifest, this, _1));
        if (status == SUCCESS) return true;
        if (status == FAIL_AND_EXIT) return false;

        resetInFiles();

        status = tryAssemble(gCompatibilityMatrixConverter, "compatibility matrix",
                             std::bind(&AssembleVintf::assembleCompatibilityMatrix, this, _1));
        if (status == SUCCESS) return true;
        if (status == FAIL_AND_EXIT) return false;

        std::cerr << "Input file has unknown format." << std::endl
                  << "Error when attempting to convert to manifest: "
                  << gHalManifestConverter.lastError() << std::endl
                  << "Error when attempting to convert to compatibility matrix: "
                  << gCompatibilityMatrixConverter.lastError() << std::endl;
        return false;
    }

    bool openOutFile(const char* path) {
        mOutFileRef = std::make_unique<std::ofstream>();
        mOutFileRef->open(path);
        return mOutFileRef->is_open();
    }

    bool openInFile(const char* path) {
        mInFilePaths.push_back(path);
        mInFiles.push_back({});
        mInFiles.back().open(path);
        return mInFiles.back().is_open();
    }

    bool openCheckFile(const char* path) {
        mCheckFile.open(path);
        return mCheckFile.is_open();
    }

    void resetInFiles() {
        for (auto& inFile : mInFiles) {
            inFile.clear();
            inFile.seekg(0);
        }
    }

    void setOutputMatrix() { mOutputMatrix = true; }

    bool addKernel(const std::string& kernelArg) {
        auto ind = kernelArg.find(':');
        if (ind == std::string::npos) {
            std::cerr << "Unrecognized --kernel option '" << kernelArg << "'" << std::endl;
            return false;
        }
        std::string kernelVerStr{kernelArg.begin(), kernelArg.begin() + ind};
        std::string kernelConfigPath{kernelArg.begin() + ind + 1, kernelArg.end()};
        Version kernelVer;
        if (!parse(kernelVerStr, &kernelVer)) {
            std::cerr << "Unrecognized kernel version '" << kernelVerStr << "'" << std::endl;
            return false;
        }
        if (mKernels.find(kernelVer) != mKernels.end()) {
            std::cerr << "Multiple --kernel for " << kernelVer << " is specified." << std::endl;
            return false;
        }
        mKernels[kernelVer] = kernelConfigPath;
        return true;
    }

   private:
    std::vector<std::string> mInFilePaths;
    std::vector<std::ifstream> mInFiles;
    std::unique_ptr<std::ofstream> mOutFileRef;
    std::ifstream mCheckFile;
    bool mOutputMatrix = false;
    std::map<Version, std::string> mKernels;
};

}  // namespace vintf
}  // namespace android

void help() {
    std::cerr << "assemble_vintf: Checks if a given manifest / matrix file is valid and \n"
                 "    fill in build-time flags into the given file.\n"
                 "assemble_vintf -h\n"
                 "               Display this help text.\n"
                 "assemble_vintf -i <input file>[:<input file>[...]] [-o <output file>] [-m]\n"
                 "               [-c [<check file>]]\n"
                 "               Fill in build-time flags into the given file.\n"
                 "    -i <input file>[:<input file>[...]]\n"
                 "               A list of input files. Format is automatically detected for the\n"
                 "               first file, and the remaining files must have the same format.\n"
                 "               Files other than the first file should only have <hal> defined;\n"
                 "               other entries are ignored.\n"
                 "    -o <output file>\n"
                 "               Optional output file. If not specified, write to stdout.\n"
                 "    -m\n"
                 "               a compatible compatibility matrix is\n"
                 "               generated instead; for example, given a device manifest,\n"
                 "               a framework compatibility matrix is generated. This flag\n"
                 "               is ignored when input is a compatibility matrix.\n"
                 "    -c [<check file>]\n"
                 "               After writing the output file, check compatibility between\n"
                 "               output file and check file.\n"
                 "               If -c is set but the check file is not specified, a warning\n"
                 "               message is written to stderr. Return 0.\n"
                 "               If the check file is specified but is not compatible, an error\n"
                 "               message is written to stderr. Return 1.\n"
                 "    --kernel=<version>:<android-base.cfg>[:<android-base-arch.cfg>[...]]\n"
                 "               Add a kernel entry to framework compatibility matrix.\n"
                 "               Ignored for other input format.\n"
                 "               <version> has format: 3.18\n"
                 "               <android-base.cfg> is the location of android-base.cfg\n"
                 "               <android-base-arch.cfg> is the location of an optional\n"
                 "               arch-specific config fragment, more than one may be specified\n";
}

int main(int argc, char **argv) {
    const struct option longopts[] = {{"kernel", required_argument, NULL, 'k'}, {0, 0, 0, 0}};

    std::string outFilePath;
    ::android::vintf::AssembleVintf assembleVintf;
    int res;
    int optind;
    while ((res = getopt_long(argc, argv, "hi:o:mc:", longopts, &optind)) >= 0) {
        switch (res) {
            case 'i': {
                char* inFilePath = strtok(optarg, ":");
                while (inFilePath != NULL) {
                    if (!assembleVintf.openInFile(inFilePath)) {
                        std::cerr << "Failed to open " << optarg << std::endl;
                        return 1;
                    }
                    inFilePath = strtok(NULL, ":");
                }
            } break;

            case 'o': {
                outFilePath = optarg;
                if (!assembleVintf.openOutFile(optarg)) {
                    std::cerr << "Failed to open " << optarg << std::endl;
                    return 1;
                }
            } break;

            case 'm': {
                assembleVintf.setOutputMatrix();
            } break;

            case 'c': {
                if (strlen(optarg) != 0) {
                    if (!assembleVintf.openCheckFile(optarg)) {
                        std::cerr << "Failed to open " << optarg << std::endl;
                        return 1;
                    }
                } else {
                    std::cerr << "WARNING: no compatibility check is done on "
                              << (outFilePath.empty() ? "output" : outFilePath) << std::endl;
                }
            } break;

            case 'k': {
                if (!assembleVintf.addKernel(optarg)) {
                    std::cerr << "ERROR: Unrecognized --kernel argument." << std::endl;
                    return 1;
                }
            } break;

            case 'h':
            default: {
                help();
                return 1;
            } break;
        }
    }

    bool success = assembleVintf.assemble();

    return success ? 0 : 1;
}
