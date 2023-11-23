/*
 * Copyright (C) 2023, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "native_writer_vendor.h"

#include "utils.h"

namespace android {
namespace stats_log_api_gen {

static int write_native_create_vendor_atom_methods(FILE* out,
                                                  const SignatureInfoMap& signatureInfoMap,
                                                  const AtomDecl& attributionDecl) {
    fprintf(out, "\n");
    for (const auto& [signature, fieldNumberToAtomDeclSet] : signatureInfoMap) {
        // TODO (b/264922532): provide vendor implementation to skip arg1 for reverseDomainName
        write_native_method_signature(out, "VendorAtom createVendorAtom(", signature,
                                      attributionDecl, " {", /*isVendorAtomLogging=*/true);

        fprintf(out, "    VendorAtom atom;\n");

        // Write method body.
        fprintf(out, "    atom.atomId = code;\n");
        fprintf(out, "    atom.reverseDomainName = arg1;\n");

        FieldNumberToAtomDeclSet::const_iterator fieldNumberToAtomDeclSetIt =
                fieldNumberToAtomDeclSet.find(ATOM_ID_FIELD_NUMBER);
        if (fieldNumberToAtomDeclSetIt != fieldNumberToAtomDeclSet.end()) {
            // TODO (b/264922532): provide support to pass annotations information
            fprintf(stderr, "Encountered field level annotation - skip\n");
        }

        // Exclude first field - which is reverseDomainName
        const int vendorAtomValuesCount = signature.size() - 1;
        fprintf(out, "    vector<VendorAtomValue> values(%d);\n", vendorAtomValuesCount);

        // Use 1-based index to access signature arguments
        for (int argIndex = 2; argIndex <= signature.size(); argIndex++) {
            const java_type_t& argType = signature[argIndex - 1];

            const int atomValueIndex = argIndex - 2;

            switch (argType) {
                case JAVA_TYPE_ATTRIBUTION_CHAIN: {
                    fprintf(stderr, "Found attribution chain - not supported.\n");
                    return 1;
                }
                case JAVA_TYPE_BYTE_ARRAY:
                    fprintf(out,
                            "    "
                            "values[%d].set<VendorAtomValue::byteArrayValue>(arg%d);\n",
                            atomValueIndex, argIndex);
                    break;
                case JAVA_TYPE_BOOLEAN:
                    fprintf(out, "    values[%d].set<VendorAtomValue::boolValue>(arg%d);\n",
                            atomValueIndex, argIndex);
                    break;
                case JAVA_TYPE_INT:
                    [[fallthrough]];
                case JAVA_TYPE_ENUM:
                    fprintf(out, "    values[%d].set<VendorAtomValue::intValue>(arg%d);\n",
                            atomValueIndex, argIndex);
                    break;
                case JAVA_TYPE_FLOAT:
                    fprintf(out, "    values[%d].set<VendorAtomValue::floatValue>(arg%d);\n",
                            atomValueIndex, argIndex);
                    break;
                case JAVA_TYPE_LONG:
                    fprintf(out, "    values[%d].set<VendorAtomValue::longValue>(arg%d);\n",
                            atomValueIndex, argIndex);
                    break;
                case JAVA_TYPE_STRING:
                    fprintf(out, "    values[%d].set<VendorAtomValue::stringValue>(arg%d);\n",
                            atomValueIndex, argIndex);
                    break;
                case JAVA_TYPE_BOOLEAN_ARRAY:
                    fprintf(out, "    values[%d].set<VendorAtomValue::repeatedBoolValue>(arg%d);\n",
                            atomValueIndex, argIndex);
                    break;
                case JAVA_TYPE_INT_ARRAY:
                    [[fallthrough]];
                case JAVA_TYPE_ENUM_ARRAY:
                    fprintf(out, "    values[%d].set<VendorAtomValue::repeatedIntValue>(arg%d);\n",
                            atomValueIndex, argIndex);
                    break;
                case JAVA_TYPE_FLOAT_ARRAY:
                    fprintf(out,
                            "    values[%d].set<VendorAtomValue::repeatedFloatValue>(arg%d);\n",
                            atomValueIndex, argIndex);
                    break;
                case JAVA_TYPE_LONG_ARRAY:
                    fprintf(out, "    values[%d].set<VendorAtomValue::repeatedLongValue>(arg%d);\n",
                            atomValueIndex, argIndex);
                    break;
                case JAVA_TYPE_STRING_ARRAY:
                    fprintf(out, "    {\n");
                    fprintf(out, "    vector<optional<string>> arrayValue(\n");
                    fprintf(out, "        arg%d.begin(), arg%d.end());\n", argIndex, argIndex);
                    fprintf(out,
                            "    "
                            "values[%d].set<VendorAtomValue::repeatedStringValue>(std::move("
                            "arrayValue));\n",
                            atomValueIndex);
                    fprintf(out, "    }\n");
                    break;
                default:
                    // Unsupported types: OBJECT, DOUBLE
                    fprintf(stderr, "Encountered unsupported type.\n");
                    return 1;
            }
            FieldNumberToAtomDeclSet::const_iterator fieldNumberToAtomDeclSetIt =
                    fieldNumberToAtomDeclSet.find(argIndex);
            if (fieldNumberToAtomDeclSetIt != fieldNumberToAtomDeclSet.end()) {
                // TODO (b/264922532): provide support to pass annotations information
                fprintf(stderr, "Encountered field level annotation - skip\n");
            }
        }

        fprintf(out, "    atom.values = std::move(values);\n");  // end method body.
        fprintf(out, "    // elision of copy operations is permitted on return\n");
        fprintf(out, "    return atom;\n");
        fprintf(out, "}\n\n");  // end method.
    }
    return 0;
}

int write_stats_log_cpp_vendor(FILE* out, const Atoms& atoms, const AtomDecl& attributionDecl,
                               const string& cppNamespace, const string& importHeader) {
    // Print prelude
    fprintf(out, "// This file is autogenerated\n");
    fprintf(out, "\n");

    fprintf(out, "#include <%s>\n", importHeader.c_str());
    fprintf(out, "#include <aidl/android/frameworks/stats/VendorAtom.h>\n");

    fprintf(out, "\n");
    write_namespace(out, cppNamespace);
    fprintf(out, "\n");
    fprintf(out, "using namespace aidl::android::frameworks::stats;\n");
    fprintf(out, "using std::make_optional;\n");
    fprintf(out, "using std::optional;\n");
    fprintf(out, "using std::vector;\n");
    fprintf(out, "using std::string;\n");

    int ret = write_native_create_vendor_atom_methods(out, atoms.signatureInfoMap, attributionDecl);
    if (ret != 0) {
        return ret;
    }
    // Print footer
    fprintf(out, "\n");
    write_closing_namespace(out, cppNamespace);

    return 0;
}

int write_stats_log_header_vendor(FILE* out, const Atoms& atoms, const AtomDecl& attributionDecl,
                                  const string& cppNamespace) {
    write_native_header_preamble(out, cppNamespace, false, /*isVendorAtomLogging=*/true);
    write_native_atom_constants(out, atoms, attributionDecl, "createVendorAtom(",
                                /*isVendorAtomLogging=*/true);

    for (AtomDeclSet::const_iterator atomIt = atoms.decls.begin(); atomIt != atoms.decls.end();
         atomIt++) {
        set<string> processedEnums;

        for (vector<AtomField>::const_iterator field = (*atomIt)->fields.begin();
             field != (*atomIt)->fields.end(); field++) {
            if (field->javaType == JAVA_TYPE_ENUM || field->javaType == JAVA_TYPE_ENUM_ARRAY) {
                // There might be N fields with the same enum type
                // avoid duplication definitions
                if (processedEnums.find(field->enumTypeName) != processedEnums.end()) {
                    continue;
                }

                if (processedEnums.empty()) {
                    fprintf(out, "class %s final {\n", (*atomIt)->message.c_str());
                    fprintf(out, "public:\n\n");
                }

                processedEnums.insert(field->enumTypeName);

                fprintf(out, "enum %s {\n", field->enumTypeName.c_str());
                size_t i = 0;
                for (map<int, string>::const_iterator value = field->enumValues.begin();
                     value != field->enumValues.end(); value++) {
                    fprintf(out, "    %s = %d", make_constant_name(value->second).c_str(),
                            value->first);
                    char const* const comma = (i == field->enumValues.size() - 1) ? "" : ",";
                    fprintf(out, "%s\n", comma);
                    i++;
                }

                fprintf(out, "};\n");
            }
        }
        if (!processedEnums.empty()) {
            fprintf(out, "};\n\n");
        }
    }

    fprintf(out, "using ::aidl::android::frameworks::stats::VendorAtom;\n");

    // Print write methods
    fprintf(out, "//\n");
    fprintf(out, "// Write methods\n");
    fprintf(out, "//\n");
    write_native_method_header(out, "VendorAtom createVendorAtom(", atoms.signatureInfoMap,
                               attributionDecl,
                               /*isVendorAtomLogging=*/true);
    fprintf(out, "\n");

    write_native_header_epilogue(out, cppNamespace);

    return 0;
}

}  // namespace stats_log_api_gen
}  // namespace android
