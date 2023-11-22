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

#include "code_gen/driver/HalHidlCodeGen.h"

#include <fstream>
#include <iostream>
#include <set>
#include <sstream>
#include <string>

#include "test/vts/proto/ComponentSpecificationMessage.pb.h"

#include "VtsCompilerUtils.h"
#include "utils/InterfaceSpecUtil.h"
#include "utils/StringUtil.h"

using namespace std;
using namespace android;

namespace android {
namespace vts {

const char* const HalHidlCodeGen::kInstanceVariableName = "hw_binder_proxy_";

void HalHidlCodeGen::GenerateCppBodyInterfaceImpl(
    Formatter& out, const ComponentSpecificationMessage& message,
    const string& /*fuzzer_extended_class_name*/) {
  out << "\n";
  FQName component_fq_name = GetFQName(message);
  for (const auto& api : message.interface().api()) {
    // Generate return statement.
    if (CanElideCallback(api)) {
      out << "::android::hardware::Return<"
          << GetCppVariableType(api.return_type_hidl(0), &message) << "> ";
    } else {
      out << "::android::hardware::Return<void> ";
    }
    // Generate function call.
    string full_method_name =
        "Vts_" + component_fq_name.tokenName() + "::" + api.name();
    out << full_method_name << "(\n";
    out.indent();
    for (int index = 0; index < api.arg_size(); index++) {
      const auto& arg = api.arg(index);
      if (!isConstType(arg.type())) {
        out << GetCppVariableType(arg, &message);
      } else {
        out << GetCppVariableType(arg, &message, true);
      }
      out << " arg" << index << " __attribute__((__unused__))";
      if (index != (api.arg_size() - 1)) out << ",\n";
    }
    if (api.return_type_hidl_size() == 0 || CanElideCallback(api)) {
      out << ") {\n";
    } else {  // handle the case of callbacks.
      out << (api.arg_size() != 0 ? ", " : "");
      out << "std::function<void(";
      for (int index = 0; index < api.return_type_hidl_size(); index++) {
        const auto& return_val = api.return_type_hidl(index);
        if (!isConstType(return_val.type())) {
          out << GetCppVariableType(return_val, &message);
        } else {
          out << GetCppVariableType(return_val, &message, true);
        }
        out << " arg" << index;
        if (index != (api.return_type_hidl_size() - 1)) {
          out << ",";
        }
      }
      out << ")>) {\n";
    }
    out << "cout << \"" << api.name() << " called\" << endl;\n";
    out << "AndroidSystemCallbackRequestMessage callback_message;\n";
    out << "callback_message.set_id(GetCallbackID(\"" << api.name()
        << "\"));\n";
    out << "callback_message.set_name(\"" << full_method_name << "\");\n";
    for (int index = 0; index < api.arg_size(); index++) {
      out << "VariableSpecificationMessage* var_msg" << index << " = "
          << "callback_message.add_arg();\n";
      GenerateSetResultCodeForTypedVariable(out, api.arg(index),
                                            "var_msg" + std::to_string(index),
                                            "arg" + std::to_string(index));
    }
    out << "RpcCallToAgent(callback_message, callback_socket_name_);\n";

    // TODO(zhuoyao): return the received results from host.
    if (CanElideCallback(api)) {
      const auto& return_val = api.return_type_hidl(0);
      const auto& type = return_val.type();
      if (type == TYPE_SCALAR) {
        out << "return static_cast<"
            << GetCppVariableType(return_val.scalar_type()) << ">(0);\n";
      } else if (type == TYPE_ENUM || type == TYPE_MASK) {
        if (return_val.has_predefined_type()) {
          std::string predefined_type_name = return_val.predefined_type();
          ReplaceSubString(predefined_type_name, "::", "__");
          if (type == TYPE_ENUM) {
            out << "return static_cast< " << GetCppVariableType(return_val)
                << ">(Random" << predefined_type_name << "());\n";
          } else {
            out << "return Random" << predefined_type_name << "();\n";
          }
        } else {
          cerr << __func__ << " ENUM doesn't have predefined type" << endl;
          exit(-1);
        }
      } else {
        out << "return nullptr;\n";
      }
    } else {
      out << "return ::android::hardware::Void();\n";
    }
    out.unindent();
    out << "}"
        << "\n";
    out << "\n";
  }

  string component_name_token = "Vts_" + component_fq_name.tokenName();
  out << "sp<" << component_fq_name.cppName() << "> VtsFuzzerCreate"
      << component_name_token << "(const string& callback_socket_name) {\n";
  out.indent();
  out << "static sp<" << component_fq_name.cppName() << "> result;\n";
  out << "result = new " << component_name_token << "(callback_socket_name);\n";
  out << "return result;\n";
  out.unindent();
  out << "}\n\n";
}

void HalHidlCodeGen::GenerateScalarTypeInC(Formatter& out, const string& type) {
  if (type == "bool_t") {
    out << "bool";
  } else if (type == "int8_t" ||
             type == "uint8_t" ||
             type == "int16_t" ||
             type == "uint16_t" ||
             type == "int32_t" ||
             type == "uint32_t" ||
             type == "int64_t" ||
             type == "uint64_t" ||
             type == "size_t") {
    out << type;
  } else if (type == "float_t") {
    out << "float";
  } else if (type == "double_t") {
    out << "double";
  } else if (type == "char_pointer") {
    out << "char*";
  } else if (type == "void_pointer") {
    out << "void*";
  } else {
    cerr << __func__ << ":" << __LINE__
         << " unsupported scalar type " << type << "\n";
    exit(-1);
  }
}

void HalHidlCodeGen::GenerateCppBodyFuzzFunction(
    Formatter& out, const ComponentSpecificationMessage& /*message*/,
    const string& fuzzer_extended_class_name) {
    out << "bool " << fuzzer_extended_class_name << "::Fuzz(" << "\n";
    out.indent();
    out << "FunctionSpecificationMessage* /*func_msg*/,"
        << "\n";
    out << "void** /*result*/, const string& /*callback_socket_name*/) {\n";
    out << "return true;\n";
    out.unindent();
    out << "}\n";
}

void HalHidlCodeGen::GenerateDriverFunctionImpl(Formatter& out,
    const ComponentSpecificationMessage& message,
    const string& fuzzer_extended_class_name) {
  if (message.component_name() != "types") {
    out << "bool " << fuzzer_extended_class_name << "::CallFunction("
        << "\n";
    out.indent();
    out << "const FunctionSpecificationMessage& func_msg,"
        << "\n";
    out << "const string& callback_socket_name __attribute__((__unused__)),"
        << "\n";
    out << "FunctionSpecificationMessage* result_msg) {\n";

    out << "const char* func_name = func_msg.name().c_str();" << "\n";
    out << "cout << \"Function: \" << __func__ << \" \" << func_name << endl;"
        << "\n";
    out << "cout << \"Callback socket name: \" << callback_socket_name << endl;"
        << "\n";

    out << "if (hw_binder_proxy_ == nullptr) {\n";
    out.indent();
    out << "cerr << \"" << kInstanceVariableName << " is null. \"<< endl;\n";
    out << "return false;\n";
    out.unindent();
    out << "}\n";
    for (auto const& api : message.interface().api()) {
      GenerateDriverImplForMethod(out, message, api);
    }

    GenerateDriverImplForReservedMethods(out);

    out << "return false;\n";
    out.unindent();
    out << "}\n";
  }
}

void HalHidlCodeGen::GenerateDriverImplForReservedMethods(Formatter& out) {
  // Generate call for reserved method: notifySyspropsChanged.
  out << "if (!strcmp(func_name, \"notifySyspropsChanged\")) {\n";
  out.indent();

  out << "cout << \"Call notifySyspropsChanged\" << endl;" << "\n";
  out << kInstanceVariableName << "->notifySyspropsChanged();\n";
  out << "result_msg->set_name(\"notifySyspropsChanged\");\n";
  out << "cout << \"called\" << endl;\n";
  out << "return true;\n";

  out.unindent();
  out << "}\n";
  // TODO(zhuoyao): Add generation code for other reserved method,
  // e.g interfaceChain
}

void HalHidlCodeGen::GenerateDriverImplForMethod(Formatter& out,
    const ComponentSpecificationMessage& message,
    const FunctionSpecificationMessage& func_msg) {
  out << "if (!strcmp(func_name, \"" << func_msg.name() << "\")) {\n";
  out.indent();
  // Process the arguments.
  for (int i = 0; i < func_msg.arg_size(); i++) {
    const auto& arg = func_msg.arg(i);
    string cur_arg_name = "arg" + std::to_string(i);
    string var_type;
    if (arg.type() == TYPE_ARRAY || arg.type() == TYPE_VECTOR) {
      var_type = GetCppVariableType(arg, &message, true);
      var_type = var_type.substr(5, var_type.length() - 6);
    } else {
      var_type = GetCppVariableType(arg, &message);
    }
    if (arg.type() == TYPE_POINTER ||
        (arg.type() == TYPE_SCALAR &&
         (arg.scalar_type() == "pointer" ||
          arg.scalar_type() == "void_pointer" ||
          arg.scalar_type() == "function_pointer"))) {
      out << var_type << " " << cur_arg_name << " = nullptr;\n";
    } else if (arg.type() == TYPE_SCALAR) {
      out << var_type << " " << cur_arg_name << " = 0;\n";
    } else if (arg.type() != TYPE_FMQ_SYNC && arg.type() != TYPE_FMQ_UNSYNC) {
      out << var_type << " " << cur_arg_name << ";\n";
    }

    GenerateDriverImplForTypedVariable(
        out, arg, cur_arg_name, "func_msg.arg(" + std::to_string(i) + ")");
  }

  GenerateCodeToStartMeasurement(out);
  // may need to check whether the function is actually defined.
  out << "cout << \"Call an API\" << endl;" << "\n";
  out << "cout << \"local_device = \" << " << kInstanceVariableName << ".get()"
      << " << endl;\n";

  // Define the return results and call the HAL function.
  for (int index = 0; index < func_msg.return_type_hidl_size(); index++) {
    const auto& return_val = func_msg.return_type_hidl(index);
    if (return_val.type() != TYPE_FMQ_SYNC &&
        return_val.type() != TYPE_FMQ_UNSYNC) {
      out << GetCppVariableType(return_val, &message) << " result" << index
          << ";\n";
    } else {
      // Use pointer to store return results with fmq type as copy assignment
      // is not allowed for fmq descriptor.
      out << "std::unique_ptr<" << GetCppVariableType(return_val, &message)
          << "> result" << index << ";\n";
    }
  }
  if (CanElideCallback(func_msg)) {
    out << "result0 = ";
    GenerateHalFunctionCall(out, message, func_msg);
  } else {
    GenerateHalFunctionCall(out, message, func_msg);
  }

  GenerateCodeToStopMeasurement(out);

  // Set the return results value to the proto message.
  out << "result_msg->set_name(\"" << func_msg.name() << "\");\n";
  for (int index = 0; index < func_msg.return_type_hidl_size(); index++) {
    out << "VariableSpecificationMessage* result_val_" << index << " = "
        << "result_msg->add_return_type_hidl();\n";
    GenerateSetResultCodeForTypedVariable(out, func_msg.return_type_hidl(index),
                                          "result_val_" + std::to_string(index),
                                          "result" + std::to_string(index));
  }

  out << "cout << \"called\" << endl;\n";
  out << "return true;\n";
  out.unindent();
  out << "}\n";
}

void HalHidlCodeGen::GenerateHalFunctionCall(Formatter& out,
    const ComponentSpecificationMessage& message,
    const FunctionSpecificationMessage& func_msg) {
  out << kInstanceVariableName << "->" << func_msg.name() << "(";
  for (int index = 0; index < func_msg.arg_size(); index++) {
    out << "arg" << index;
    if (index != (func_msg.arg_size() - 1)) out << ", ";
  }
  if (func_msg.return_type_hidl_size()== 0 || CanElideCallback(func_msg)) {
    out << ");\n";
  } else {
    out << (func_msg.arg_size() != 0 ? ", " : "");
    GenerateSyncCallbackFunctionImpl(out, message, func_msg);
    out << ");\n";
  }
}

void HalHidlCodeGen::GenerateSyncCallbackFunctionImpl(Formatter& out,
    const ComponentSpecificationMessage& message,
    const FunctionSpecificationMessage& func_msg) {
  out << "[&](";
  for (int index = 0; index < func_msg.return_type_hidl_size(); index++) {
    const auto& return_val = func_msg.return_type_hidl(index);
    if (!isConstType(return_val.type())) {
      out << GetCppVariableType(return_val, &message);
    } else {
      out << GetCppVariableType(return_val, &message, true);
    }
    out << " arg" << index;
    if (index != (func_msg.return_type_hidl_size() - 1)) out << ",";
  }
  out << "){\n";
  out.indent();
  out << "cout << \"callback " << func_msg.name() << " called\""
      << " << endl;\n";

  for (int index = 0; index < func_msg.return_type_hidl_size(); index++) {
    const auto& return_val = func_msg.return_type_hidl(index);
    if (return_val.type() != TYPE_FMQ_SYNC &&
        return_val.type() != TYPE_FMQ_UNSYNC) {
      out << "result" << index << " = arg" << index << ";\n";
    } else {
      out << "result" << index << ".reset(new (std::nothrow) "
          << GetCppVariableType(return_val, &message) << "(arg" << index
          << "));\n";
    }
  }
  out.unindent();
  out << "}";
}

void HalHidlCodeGen::GenerateCppBodyGetAttributeFunction(
    Formatter& out, const ComponentSpecificationMessage& message,
    const string& fuzzer_extended_class_name) {
  if (message.component_name() != "types") {
    out << "bool " << fuzzer_extended_class_name << "::GetAttribute(" << "\n";
    out.indent();
    out << "FunctionSpecificationMessage* /*func_msg*/,"
        << "\n";
    out << "void** /*result*/) {"
        << "\n";
    // TOOD: impl
    out << "cerr << \"attribute not found\" << endl;\n"
        << "return false;\n";
    out.unindent();
    out << "}" << "\n";
  }
}

void HalHidlCodeGen::GenerateClassConstructionFunction(Formatter& out,
    const ComponentSpecificationMessage& message,
    const string& fuzzer_extended_class_name) {
  out << fuzzer_extended_class_name << "() : DriverBase(";
  if (message.component_name() != "types") {
    out << "HAL_HIDL), " << kInstanceVariableName << "()";
  } else {
    out << "HAL_HIDL)";
  }
  out << " {}" << "\n";
  out << "\n";

  FQName fqname = GetFQName(message);
  out << "explicit " << fuzzer_extended_class_name << "(" << fqname.cppName()
      << "* hw_binder_proxy) : DriverBase("
      << "HAL_HIDL)";
  if (message.component_name() != "types") {
    out << ", " << kInstanceVariableName << "(hw_binder_proxy)";
  }
  out << " {}\n";
}

void HalHidlCodeGen::GenerateHeaderGlobalFunctionDeclarations(Formatter& out,
    const ComponentSpecificationMessage& message,
    const bool print_extern_block) {
  if (message.component_name() != "types") {
    if (print_extern_block) {
      out << "extern \"C\" {" << "\n";
    }
    DriverCodeGenBase::GenerateHeaderGlobalFunctionDeclarations(
        out, message, false);

    string function_name_prefix = GetFunctionNamePrefix(message);
    FQName fqname = GetFQName(message);
    out << "extern "
        << "android::vts::DriverBase* " << function_name_prefix
        << "with_arg(uint64_t hw_binder_proxy);\n";
    if (print_extern_block) {
      out << "}" << "\n";
    }
  }
}

void HalHidlCodeGen::GenerateCppBodyGlobalFunctions(Formatter& out,
    const ComponentSpecificationMessage& message,
    const string& fuzzer_extended_class_name, const bool print_extern_block) {
  if (message.component_name() != "types") {
    if (print_extern_block) {
      out << "extern \"C\" {" << "\n";
    }
    DriverCodeGenBase::GenerateCppBodyGlobalFunctions(
        out, message, fuzzer_extended_class_name, false);

    string function_name_prefix = GetFunctionNamePrefix(message);
    FQName fqname = GetFQName(message);
    out << "android::vts::DriverBase* " << function_name_prefix << "with_arg("
        << "uint64_t hw_binder_proxy) {\n";
    out.indent();
    out << fqname.cppName() << "* arg = nullptr;\n";
    out << "if (hw_binder_proxy) {\n";
    out.indent();
    out << "arg = reinterpret_cast<" << fqname.cppName()
        << "*>(hw_binder_proxy);\n";
    out.unindent();
    out << "} else {\n";
    out.indent();
    out << "cout << \" Creating DriverBase with null proxy.\" << endl;\n";
    out.unindent();
    out << "}\n";
    out << "android::vts::DriverBase* result ="
        << "\n"
        << "    new android::vts::" << fuzzer_extended_class_name << "(\n"
        << "        arg);\n";
    out << "if (arg != nullptr) {\n";
    out.indent();
    out << "arg->decStrong(arg);" << "\n";
    out.unindent();
    out << "}\n";
    out << "return result;" << "\n";
    out.unindent();
    out << "}\n\n";
    if (print_extern_block) {
      out << "}" << "\n";
    }
  }
}

void HalHidlCodeGen::GenerateClassHeader(Formatter& out,
    const ComponentSpecificationMessage& message,
    const string& fuzzer_extended_class_name) {
  if (message.component_name() != "types") {
    for (const auto attribute : message.interface().attribute()) {
      GenerateAllFunctionDeclForAttribute(out, attribute);
    }
    DriverCodeGenBase::GenerateClassHeader(out, message,
                                           fuzzer_extended_class_name);
  } else {
    for (const auto attribute : message.attribute()) {
      GenerateAllFunctionDeclForAttribute(out, attribute);
    };
  }
}

void HalHidlCodeGen::GenerateHeaderInterfaceImpl(
    Formatter& out, const ComponentSpecificationMessage& message) {
  out << "\n";
  FQName component_fq_name = GetFQName(message);
  string component_name_token = "Vts_" + component_fq_name.tokenName();
  out << "class " << component_name_token << " : public "
      << component_fq_name.cppName() << ", public DriverCallbackBase {\n";
  out << " public:\n";
  out.indent();
  out << component_name_token << "(const string& callback_socket_name)\n"
      << "    : callback_socket_name_(callback_socket_name) {};\n\n";
  out << "virtual ~" << component_name_token << "()"
      << " = default;\n\n";
  for (const auto& api : message.interface().api()) {
    // Generate return statement.
    if (CanElideCallback(api)) {
      out << "::android::hardware::Return<"
          << GetCppVariableType(api.return_type_hidl(0), &message) << "> ";
    } else {
      out << "::android::hardware::Return<void> ";
    }
    // Generate function call.
    out << api.name() << "(\n";
    out.indent();
    for (int index = 0; index < api.arg_size(); index++) {
      const auto& arg = api.arg(index);
      if (!isConstType(arg.type())) {
        out << GetCppVariableType(arg, &message);
      } else {
        out << GetCppVariableType(arg, &message, true);
      }
      out << " arg" << index;
      if (index != (api.arg_size() - 1)) out << ",\n";
    }
    if (api.return_type_hidl_size() == 0 || CanElideCallback(api)) {
      out << ") override;\n\n";
    } else {  // handle the case of callbacks.
      out << (api.arg_size() != 0 ? ", " : "");
      out << "std::function<void(";
      for (int index = 0; index < api.return_type_hidl_size(); index++) {
        const auto& return_val = api.return_type_hidl(index);
        if (!isConstType(return_val.type())) {
          out << GetCppVariableType(return_val, &message);
        } else {
          out << GetCppVariableType(return_val, &message, true);
        }
        out << " arg" << index;
        if (index != (api.return_type_hidl_size() - 1)) out << ",";
      }
      out << ")>) override;\n\n";
    }
    out.unindent();
  }
  out << "\n";
  out.unindent();
  out << " private:\n";
  out.indent();
  out << "string callback_socket_name_;\n";
  out.unindent();
  out << "};\n\n";

  out << "sp<" << component_fq_name.cppName() << "> VtsFuzzerCreate"
      << component_name_token << "(const string& callback_socket_name);\n\n";
}

void HalHidlCodeGen::GenerateClassImpl(Formatter& out,
    const ComponentSpecificationMessage& message,
    const string& fuzzer_extended_class_name) {
  if (message.component_name() != "types") {
    for (auto attribute : message.interface().attribute()) {
      GenerateAllFunctionImplForAttribute(out, attribute);
    }
    GenerateGetServiceImpl(out, message, fuzzer_extended_class_name);
    DriverCodeGenBase::GenerateClassImpl(out, message,
                                         fuzzer_extended_class_name);
  } else {
    for (auto attribute : message.attribute()) {
      GenerateAllFunctionImplForAttribute(out, attribute);
    }
  }
}

void HalHidlCodeGen::GenerateHeaderIncludeFiles(Formatter& out,
    const ComponentSpecificationMessage& message,
    const string& fuzzer_extended_class_name) {
  DriverCodeGenBase::GenerateHeaderIncludeFiles(out, message,
                                                fuzzer_extended_class_name);
  out << "#include <" << GetPackagePath(message) << "/" << GetVersion(message)
      << "/" << GetComponentName(message) << ".h>"
      << "\n";
  out << "#include <hidl/HidlSupport.h>" << "\n";

  for (const auto& import : message.import()) {
    FQName import_name = FQName(import);
    string import_package_path = import_name.package();
    string import_package_version = import_name.version();
    string import_component_name = import_name.name();
    ReplaceSubString(import_package_path, ".", "/");

    out << "#include <" << import_package_path << "/" << import_package_version
        << "/" << import_component_name << ".h>\n";
    if (import_package_path.find("android/hardware") != std::string::npos) {
      if (import_component_name[0] == 'I') {
        import_component_name = import_component_name.substr(1);
      }
      out << "#include <" << import_package_path << "/"
          << import_package_version << "/" << import_component_name
          << ".vts.h>\n";
    }
  }
  out << "\n\n";
}

void HalHidlCodeGen::GenerateSourceIncludeFiles(Formatter& out,
    const ComponentSpecificationMessage& message,
    const string& fuzzer_extended_class_name) {
  DriverCodeGenBase::GenerateSourceIncludeFiles(out, message,
                                                fuzzer_extended_class_name);
  out << "#include <hidl/HidlSupport.h>\n";
  out << "#include <" << GetPackagePath(message) << "/" << GetVersion(message)
      << "/" << GetComponentName(message) << ".h>"
      << "\n";
  for (const auto& import : message.import()) {
    FQName import_name = FQName(import);
    string import_package_name = import_name.package();
    string import_package_version = import_name.version();
    string import_component_name = import_name.name();
    string import_package_path = import_package_name;
    ReplaceSubString(import_package_path, ".", "/");
    if (import_package_name == GetPackageName(message) &&
        import_package_version == GetVersion(message)) {
      out << "#include \"" << import_package_path << "/"
          << import_package_version << "/"
          << (import_component_name == "types"
                  ? "types"
                  : import_component_name.substr(1))
          << ".vts.h\"\n";
    } else {
      out << "#include <" << import_package_path << "/"
          << import_package_version << "/" << import_component_name << ".h>\n";
    }
  }
  out << "#include <android/hidl/allocator/1.0/IAllocator.h>\n";
  out << "#include <fmq/MessageQueue.h>\n";
  out << "#include <sys/stat.h>\n";
  out << "#include <unistd.h>\n";
}

void HalHidlCodeGen::GenerateAdditionalFuctionDeclarations(Formatter& out,
    const ComponentSpecificationMessage& message,
    const string& /*fuzzer_extended_class_name*/) {
  if (message.component_name() != "types") {
    out << "bool GetService(bool get_stub, const char* service_name);"
        << "\n\n";
  }
}

void HalHidlCodeGen::GeneratePublicFunctionDeclarations(
    Formatter& out, const ComponentSpecificationMessage& /*message*/) {
  out << "uint64_t GetHidlInterfaceProxy() const {\n";
  out.indent();
  out << "return reinterpret_cast<uintptr_t>(" << kInstanceVariableName
      << ".get());\n";
  out.unindent();
  out << "}\n";
}

void HalHidlCodeGen::GeneratePrivateMemberDeclarations(Formatter& out,
    const ComponentSpecificationMessage& message) {
  FQName fqname = GetFQName(message);
  out << "sp<" << fqname.cppName() << "> " << kInstanceVariableName << ";\n";
}

void HalHidlCodeGen::GenerateRandomFunctionDeclForAttribute(Formatter& out,
    const VariableSpecificationMessage& attribute) {
  if (attribute.type() == TYPE_ENUM) {
    if (attribute.enum_value().enumerator_size() == 0) {
      // empty enum without any actual enumerator.
      return;
    }
    string attribute_name = ClearStringWithNameSpaceAccess(attribute.name());
    out << attribute.enum_value().scalar_type() << " "
        << "Random" << attribute_name << "();\n";
  }
}

void HalHidlCodeGen::GenerateRandomFunctionImplForAttribute(Formatter& out,
    const VariableSpecificationMessage& attribute) {
  // Random value generator
  if (attribute.type() == TYPE_ENUM) {
    if (attribute.enum_value().enumerator_size() == 0) {
      // empty enum without any actual enumerator.
      return;
    }
    string attribute_name = ClearStringWithNameSpaceAccess(attribute.name());
    out << attribute.enum_value().scalar_type() << " Random" << attribute_name
        << "() {\n";
    out.indent();
    out << attribute.enum_value().scalar_type() << " choice = " << "("
        << attribute.enum_value().scalar_type() << ") " << "rand() / "
        << attribute.enum_value().enumerator().size() << ";" << "\n";
    if (attribute.enum_value().scalar_type().find("u") != 0) {
      out << "if (choice < 0) choice *= -1;" << "\n";
    }
    for (int index = 0; index < attribute.enum_value().enumerator().size();
        index++) {
      out << "if (choice == ";
      out << "(" << attribute.enum_value().scalar_type() << ") ";
      if (attribute.enum_value().scalar_type() == "int8_t") {
        out << attribute.enum_value().scalar_value(index).int8_t();
      } else if (attribute.enum_value().scalar_type() == "uint8_t") {
        out << attribute.enum_value().scalar_value(index).uint8_t();
      } else if (attribute.enum_value().scalar_type() == "int16_t") {
        out << attribute.enum_value().scalar_value(index).int16_t();
      } else if (attribute.enum_value().scalar_type() == "uint16_t") {
        out << attribute.enum_value().scalar_value(index).uint16_t();
      } else if (attribute.enum_value().scalar_type() == "int32_t") {
        out << attribute.enum_value().scalar_value(index).int32_t();
      } else if (attribute.enum_value().scalar_type() == "uint32_t") {
        out << attribute.enum_value().scalar_value(index).uint32_t();
      } else if (attribute.enum_value().scalar_type() == "int64_t") {
        out << attribute.enum_value().scalar_value(index).int64_t();
      } else if (attribute.enum_value().scalar_type() == "uint64_t") {
        out << attribute.enum_value().scalar_value(index).uint64_t();
      } else {
        cerr << __func__ << ":" << __LINE__ << " ERROR unsupported enum type "
            << attribute.enum_value().scalar_type() << "\n";
        exit(-1);
      }
      out << ") return static_cast<" << attribute.enum_value().scalar_type()
          << ">(" << attribute.name()
          << "::" << attribute.enum_value().enumerator(index) << ");\n";
    }
    out << "return static_cast<" << attribute.enum_value().scalar_type() << ">("
        << attribute.name() << "::" << attribute.enum_value().enumerator(0)
        << ");\n";
    out.unindent();
    out << "}" << "\n";
  }
}

void HalHidlCodeGen::GenerateDriverDeclForAttribute(Formatter& out,
    const VariableSpecificationMessage& attribute) {
  if (attribute.type() == TYPE_STRUCT || attribute.type() == TYPE_UNION) {
    // Recursively generate SetResult method implementation for all sub_types.
    for (const auto sub_struct : attribute.sub_struct()) {
      GenerateDriverDeclForAttribute(out, sub_struct);
    }
    for (const auto sub_union : attribute.sub_union()) {
      GenerateDriverDeclForAttribute(out, sub_union);
    }
    string func_name = "MessageTo"
        + ClearStringWithNameSpaceAccess(attribute.name());
    out << "void " << func_name
        << "(const VariableSpecificationMessage& var_msg, " << attribute.name()
        << "* arg);\n";
  } else if (attribute.type() == TYPE_ENUM) {
    string func_name = "EnumValue"
            + ClearStringWithNameSpaceAccess(attribute.name());
    // Message to value converter
    out << attribute.name() << " " << func_name
        << "(const ScalarDataValueMessage& arg);\n";
  } else {
    cerr << __func__ << " unsupported attribute type " << attribute.type()
         << "\n";
    exit(-1);
  }
}

void HalHidlCodeGen::GenerateDriverImplForAttribute(Formatter& out,
    const VariableSpecificationMessage& attribute) {
  switch (attribute.type()) {
    case TYPE_ENUM:
    {
      string func_name = "EnumValue"
          + ClearStringWithNameSpaceAccess(attribute.name());
      // Message to value converter
      out << attribute.name() << " " << func_name
          << "(const ScalarDataValueMessage& arg) {\n";
      out.indent();
      out << "return (" << attribute.name() << ") arg."
          << attribute.enum_value().scalar_type() << "();\n";
      out.unindent();
      out << "}" << "\n";
      break;
    }
    case TYPE_STRUCT:
    {
      // Recursively generate driver implementation method for all sub_types.
      for (const auto sub_struct : attribute.sub_struct()) {
        GenerateDriverImplForAttribute(out, sub_struct);
      }
      string func_name = "MessageTo"
          + ClearStringWithNameSpaceAccess(attribute.name());
      out << "void " << func_name << "(const VariableSpecificationMessage& "
                                     "var_msg __attribute__((__unused__)), "
          << attribute.name() << "* arg __attribute__((__unused__))) {"
          << "\n";
      out.indent();
      int struct_index = 0;
      for (const auto& struct_value : attribute.struct_value()) {
        GenerateDriverImplForTypedVariable(
            out, struct_value, "arg->" + struct_value.name(),
            "var_msg.struct_value(" + std::to_string(struct_index) + ")");
        struct_index++;
      }
      out.unindent();
      out << "}\n";
      break;
    }
    case TYPE_UNION:
    {
      // Recursively generate driver implementation method for all sub_types.
      for (const auto sub_union : attribute.sub_union()) {
        GenerateDriverImplForAttribute(out, sub_union);
      }
      string func_name = "MessageTo"
          + ClearStringWithNameSpaceAccess(attribute.name());
      out << "void " << func_name
          << "(const VariableSpecificationMessage& var_msg, "
          << attribute.name() << "* arg) {" << "\n";
      out.indent();
      int union_index = 0;
      for (const auto& union_value : attribute.union_value()) {
        out << "if (var_msg.union_value(" << union_index << ").name() == \""
            << union_value.name() << "\") {" << "\n";
        out.indent();
        GenerateDriverImplForTypedVariable(
            out, union_value, "arg->" + union_value.name(),
            "var_msg.union_value(" + std::to_string(union_index) + ")");
        union_index++;
        out.unindent();
        out << "}" << "\n";
      }
      out.unindent();
      out << "}\n";
      break;
    }
    default:
    {
      cerr << __func__ << " unsupported attribute type " << attribute.type()
           << "\n";
      exit(-1);
    }
  }
}

void HalHidlCodeGen::GenerateGetServiceImpl(Formatter& out,
    const ComponentSpecificationMessage& message,
    const string& fuzzer_extended_class_name) {
  out << "bool " << fuzzer_extended_class_name
      << "::GetService(bool get_stub, const char* service_name) {" << "\n";
  out.indent();
  out << "static bool initialized = false;" << "\n";
  out << "if (!initialized) {" << "\n";
  out.indent();
  out << "cout << \"[agent:hal] HIDL getService\" << endl;" << "\n";
  out << "if (service_name) {\n"
      << "  cout << \"  - service name: \" << service_name << endl;" << "\n"
      << "}\n";
  FQName fqname = GetFQName(message);
  out << kInstanceVariableName << " = " << fqname.cppName() << "::getService("
      << "service_name, get_stub);" << "\n";
  out << "if (" << kInstanceVariableName << " == nullptr) {\n";
  out.indent();
  out << "cerr << \"getService() returned a null pointer.\" << endl;\n";
  out << "return false;\n";
  out.unindent();
  out << "}\n";
  out << "cout << \"[agent:hal] " << kInstanceVariableName << " = \" << "
      << kInstanceVariableName << ".get() << endl;" << "\n";
  out << "initialized = true;" << "\n";
  out.unindent();
  out << "}" << "\n";
  out << "return true;" << "\n";
  out.unindent();
  out << "}" << "\n" << "\n";
}

void HalHidlCodeGen::GenerateDriverImplForTypedVariable(Formatter& out,
    const VariableSpecificationMessage& val, const string& arg_name,
    const string& arg_value_name) {
  switch (val.type()) {
    case TYPE_SCALAR:
    {
      out << arg_name << " = " << arg_value_name << ".scalar_value()."
          << val.scalar_type() << "();\n";
      break;
    }
    case TYPE_STRING:
    {
      out << arg_name << " = ::android::hardware::hidl_string("
          << arg_value_name << ".string_value().message());\n";
      break;
    }
    case TYPE_ENUM:
    {
      if (val.has_predefined_type()) {
        string func_name = "EnumValue"
            + ClearStringWithNameSpaceAccess(val.predefined_type());
        out << arg_name << " = " << func_name << "(" << arg_value_name
            << ".scalar_value());\n";
      } else {
        out << arg_name << " = (" << val.name() << ")" << arg_value_name << "."
            << "enum_value().scalar_value(0)." << val.enum_value().scalar_type()
            << "();\n";
      }
      break;
    }
    case TYPE_MASK:
    {
      out << arg_name << " = " << arg_value_name << ".scalar_value()."
          << val.scalar_type() << "();\n";
      break;
    }
    case TYPE_VECTOR:
    {
      out << arg_name << ".resize(" << arg_value_name
          << ".vector_value_size());\n";
      out << "for (int i = 0; i <" << arg_value_name
          << ".vector_value_size(); i++) {\n";
      out.indent();
      GenerateDriverImplForTypedVariable(out, val.vector_value(0),
                                         arg_name + "[i]",
                                         arg_value_name + ".vector_value(i)");
      out.unindent();
      out << "}\n";
      break;
    }
    case TYPE_ARRAY:
    {
      out << "for (int i = 0; i < " << arg_value_name
          << ".vector_value_size(); i++) {\n";
      out.indent();
      GenerateDriverImplForTypedVariable(out, val.vector_value(0),
                                         arg_name + "[i]",
                                         arg_value_name + ".vector_value(i)");
      out.unindent();
      out << "}\n";
      break;
    }
    case TYPE_STRUCT:
    {
      if (val.has_predefined_type()) {
        string func_name = "MessageTo"
            + ClearStringWithNameSpaceAccess(val.predefined_type());
        out << func_name << "(" << arg_value_name << ", &("
            << arg_name << "));\n";
      } else {
        int struct_index = 0;
        for (const auto struct_field : val.struct_value()) {
          string struct_field_name = arg_name + "." + struct_field.name();
          string struct_field_value_name = arg_value_name + ".struct_value("
              + std::to_string(struct_index) + ")";
          GenerateDriverImplForTypedVariable(out, struct_field,
                                             struct_field_name,
                                             struct_field_value_name);
          struct_index++;
        }
      }
      break;
    }
    case TYPE_UNION:
    {
      if (val.has_predefined_type()) {
        string func_name = "MessageTo"
            + ClearStringWithNameSpaceAccess(val.predefined_type());
        out << func_name << "(" << arg_value_name << ", &(" << arg_name
            << "));\n";
      } else {
        int union_index = 0;
        for (const auto union_field : val.union_value()) {
          string union_field_name = arg_name + "." + union_field.name();
          string union_field_value_name = arg_value_name + ".union_value("
              + std::to_string(union_index) + ")";
          GenerateDriverImplForTypedVariable(out, union_field, union_field_name,
                                             union_field_value_name);
          union_index++;
        }
      }
      break;
    }
    case TYPE_HIDL_CALLBACK:
    {
      string type_name = val.predefined_type();
      ReplaceSubString(type_name, "::", "_");

      out << arg_name << " = VtsFuzzerCreateVts" << type_name
          << "(callback_socket_name);\n";
      out << "static_cast<" << "Vts" + type_name << "*>(" << arg_name
          << ".get())->Register(" << arg_value_name << ");\n";
      break;
    }
    case TYPE_HANDLE:
    {
      out << "if (" << arg_value_name << ".has_handle_value()) {\n";
      out.indent();
      out << "native_handle_t* handle = native_handle_create(" << arg_value_name
          << ".handle_value().num_fds(), " << arg_value_name
          << ".handle_value().num_ints());\n";
      out << "if (!handle) {\n";
      out.indent();
      out << "cerr << \"Failed to create handle. \" << endl;\n";
      out << "exit(-1);\n";
      out.unindent();
      out << "}\n";
      out << "for (int fd_index = 0; fd_index < " << arg_value_name
          << ".handle_value().num_fds() + " << arg_value_name
          << ".handle_value().num_ints(); fd_index++) {\n";
      out.indent();
      out << "if (fd_index < " << arg_value_name
          << ".handle_value().num_fds()) {\n";
      out.indent();
      out << "FdMessage fd_val = " << arg_value_name
          << ".handle_value().fd_val(fd_index);\n";
      out << "string file_name = fd_val.file_name();\n";
      out << "switch (fd_val.type()) {\n";
      out.indent();
      out << "case FdType::FILE_TYPE:\n";
      out << "{\n";
      out.indent();
      // Create the parent path recursively if not exist.
      out << "size_t pre = 0; size_t pos = 0;\n";
      out << "string dir;\n";
      out << "struct stat st;\n";
      out << "while((pos=file_name.find_first_of('/', pre)) "
          << "!= string::npos){\n";
      out.indent();
      out << "dir = file_name.substr(0, pos++);\n";
      out << "pre = pos;\n";
      out << "if(dir.size() == 0) continue; // ignore leading /\n";
      out << "if (stat(dir.c_str(), &st) == -1) {\n";
      out << "cout << \" Creating dir: \" << dir << endl;\n";
      out.indent();
      out << "mkdir(dir.c_str(), 0700);\n";
      out.unindent();
      out << "}\n";
      out.unindent();
      out << "}\n";
      out << "int fd = open(file_name.c_str(), "
          << "fd_val.flags() | O_CREAT, fd_val.mode());\n";
      out << "if (fd == -1) {\n";
      out.indent();
      out << "cout << \"Failed to open file: \" << file_name << \" error: \" "
          << "<< errno << endl;\n";
      out << "exit (-1);\n";
      out.unindent();
      out << "}\n";
      out << "handle->data[fd_index] = fd;\n";
      out << "break;\n";
      out.unindent();
      out << "}\n";
      out << "case FdType::DIR_TYPE:\n";
      out << "{\n";
      out.indent();
      out << "struct stat st;\n";
      out << "if (!stat(file_name.c_str(), &st)) {\n";
      out.indent();
      out << "mkdir(file_name.c_str(), fd_val.mode());\n";
      out.unindent();
      out << "}\n";
      out << "handle->data[fd_index] = open(file_name.c_str(), O_DIRECTORY, "
          << "fd_val.mode());\n";
      out << "break;\n";
      out.unindent();
      out << "}\n";
      out << "case FdType::DEV_TYPE:\n";
      out << "{\n";
      out.indent();
      out << "if(file_name == \"/dev/ashmem\") {\n";
      out.indent();
      out << "handle->data[fd_index] = ashmem_create_region(\"SharedMemory\", "
          << "fd_val.memory().size());\n";
      out.unindent();
      out << "}\n";
      out << "break;\n";
      out.unindent();
      out << "}\n";
      out << "case FdType::PIPE_TYPE:\n";
      out << "case FdType::SOCKET_TYPE:\n";
      out << "case FdType::LINK_TYPE:\n";
      out << "{\n";
      out.indent();
      out << "cout << \"Not supported yet. \" << endl;\n";
      out << "break;\n";
      out.unindent();
      out << "}\n";
      out.unindent();
      out << "}\n";
      out.unindent();
      out << "} else {\n";
      out.indent();
      out << "handle->data[fd_index] = " << arg_value_name
          << ".handle_value().int_val(fd_index -" << arg_value_name
          << ".handle_value().num_fds());\n";
      out.unindent();
      out << "}\n";
      out.unindent();
      out << "}\n";
      out << arg_name << " = handle;\n";
      out.unindent();
      out << "} else {\n";
      out.indent();
      out << arg_name << " = nullptr;\n";
      out.unindent();
      out << "}\n";
      break;
    }
    case TYPE_HIDL_INTERFACE:
    {
      out << "/* ERROR: TYPE_HIDL_INTERFACE is not supported yet. */\n";
      break;
    }
    case TYPE_HIDL_MEMORY:
    {
      out << "sp<::android::hidl::allocator::V1_0::IAllocator> ashmemAllocator"
          << " = ::android::hidl::allocator::V1_0::IAllocator::getService(\""
          << "ashmem\");\n";
      out << "if (ashmemAllocator == nullptr) {\n";
      out.indent();
      out << "cerr << \"Failed to get ashmemAllocator! \" << endl;\n";
      out << "exit(-1);\n";
      out.unindent();
      out << "}\n";
      // TODO(zhuoyao): initialize memory with recorded contents.
      out << "auto res = ashmemAllocator->allocate(" << arg_value_name
          << ".hidl_memory_value().size(), [&](bool success, "
          << "const hardware::hidl_memory& memory) {\n";
      out.indent();
      out << "if (!success) {\n";
      out.indent();
      out << "cerr << \"Failed to allocate memory! \" << endl;\n";
      out << arg_name << " = ::android::hardware::hidl_memory();\n";
      out << "return;\n";
      out.unindent();
      out << "}\n";
      out << arg_name << " = memory;\n";
      out.unindent();
      out << "});\n";
      break;
    }
    case TYPE_POINTER:
    {
      out << "/* ERROR: TYPE_POINTER is not supported yet. */\n";
      break;
    }
    case TYPE_FMQ_SYNC:
    {
      if (arg_name.find("->") != std::string::npos) {
        cout << "Nested structure with fmq is not supported yet." << endl;
      } else {
        std::string element_type =
            GetCppVariableType(val.fmq_value(0), nullptr);
        std::string queue_name = arg_name + "_sync_q";
        // TODO(zhuoyao): consider record and use the queue capacity.
        out << "::android::hardware::MessageQueue<" << element_type
            << ", ::android::hardware::kSynchronizedReadWrite> " << queue_name
            << "(1024);\n";
        out << "for (int i = 0; i < (int)" << arg_value_name
            << ".fmq_value_size(); i++) {\n";
        out.indent();
        std::string fmq_item_name = queue_name + "_item";
        out << element_type << " " << fmq_item_name << ";\n";
        GenerateDriverImplForTypedVariable(out, val.fmq_value(0), fmq_item_name,
                                           arg_value_name + ".fmq_value(i)");
        out << queue_name << ".write(&" << fmq_item_name << ");\n";
        out.unindent();
        out << "}\n";
        out << GetCppVariableType(val, nullptr) << " " << arg_name << "(*"
            << queue_name << ".getDesc());\n";
      }
      break;
    }
    case TYPE_FMQ_UNSYNC:
    {
      if (arg_name.find("->") != std::string::npos) {
        cout << "Nested structure with fmq is not supported yet." << endl;
      } else {
        std::string element_type =
            GetCppVariableType(val.fmq_value(0), nullptr);
        std::string queue_name = arg_name + "_unsync_q";
        // TODO(zhuoyao): consider record and use the queue capacity.
        out << "::android::hardware::MessageQueue<" << element_type << ", "
            << "::android::hardware::kUnsynchronizedWrite> " << queue_name
            << "(1024);\n";
        out << "for (int i = 0; i < (int)" << arg_value_name
            << ".fmq_value_size(); i++) {\n";
        out.indent();
        std::string fmq_item_name = queue_name + "_item";
        out << element_type << " " << fmq_item_name << ";\n";
        GenerateDriverImplForTypedVariable(out, val.fmq_value(0), fmq_item_name,
                                           arg_value_name + ".fmq_value(i)");
        out << queue_name << ".write(&" << fmq_item_name << ");\n";
        out.unindent();
        out << "}\n";
        out << GetCppVariableType(val, nullptr) << " " << arg_name << "(*"
            << queue_name << ".getDesc());\n";
      }
      break;
    }
    case TYPE_REF:
    {
      out << "/* ERROR: TYPE_REF is not supported yet. */\n";
      break;
    }
    default:
    {
      cerr << __func__ << " ERROR: unsupported type " << val.type() << ".\n";
      exit(-1);
    }
  }
}

// TODO(zhuoyao): Verify results based on verification rules instead of perform
// an exact match.
void HalHidlCodeGen::GenerateVerificationFunctionImpl(Formatter& out,
    const ComponentSpecificationMessage& message,
    const string& fuzzer_extended_class_name) {
  if (message.component_name() != "types") {
    // Generate the main profiler function.
    out << "\nbool " << fuzzer_extended_class_name;
    out.indent();
    out << "::VerifyResults(const FunctionSpecificationMessage& "
           "expected_result __attribute__((__unused__)),"
        << "\n";
    out << "const FunctionSpecificationMessage& actual_result "
           "__attribute__((__unused__))) {\n";
    for (const FunctionSpecificationMessage api : message.interface().api()) {
      out << "if (!strcmp(actual_result.name().c_str(), \"" << api.name()
          << "\")) {\n";
      out.indent();
      out << "if (actual_result.return_type_hidl_size() != "
          << "expected_result.return_type_hidl_size() "
          << ") { return false; }\n";
      for (int i = 0; i < api.return_type_hidl_size(); i++) {
        std::string expected_result = "expected_result.return_type_hidl("
            + std::to_string(i) + ")";
        std::string actual_result = "actual_result.return_type_hidl("
            + std::to_string(i) + ")";
        GenerateVerificationCodeForTypedVariable(out, api.return_type_hidl(i),
                                                 expected_result,
                                                 actual_result);
      }
      out << "return true;\n";
      out.unindent();
      out << "}\n";
    }
    out << "return false;\n";
    out.unindent();
    out << "}\n\n";
  }
}

void HalHidlCodeGen::GenerateVerificationCodeForTypedVariable(Formatter& out,
    const VariableSpecificationMessage& val, const string& expected_result,
    const string& actual_result) {
  switch (val.type()) {
    case TYPE_SCALAR:
    {
      out << "if (" << actual_result << ".scalar_value()." << val.scalar_type()
          << "() != " << expected_result << ".scalar_value()."
          << val.scalar_type() << "()) { return false; }\n";
      break;
    }
    case TYPE_STRING:
    {
      out << "if (strcmp(" << actual_result
          << ".string_value().message().c_str(), " << expected_result
          << ".string_value().message().c_str())!= 0)" << "{ return false; }\n";
      break;
    }
    case TYPE_ENUM:
    {
      if (val.has_predefined_type()) {
        string func_name = "Verify"
            + ClearStringWithNameSpaceAccess(val.predefined_type());
        out << "if(!" << func_name << "(" << expected_result << ", "
            << actual_result << ")) { return false; }\n";
      } else {
        out << "if (" << actual_result << ".scalar_value()."
            << val.enum_value().scalar_type() << "() != " << expected_result
            << ".scalar_value()." << val.enum_value().scalar_type()
            << "()) { return false; }\n";
      }
      break;
    }
    case TYPE_MASK:
    {
      out << "if (" << actual_result << ".scalar_value()." << val.scalar_type()
          << "() != " << expected_result << ".scalar_value()."
          << val.scalar_type() << "()) { return false; }\n";
      break;
    }
    case TYPE_VECTOR:
    {
      out << "if (" << actual_result << ".vector_value_size() != "
          << expected_result << ".vector_value_size()) {\n";
      out.indent();
      out << "cerr << \"Verification failed for vector size. expected: \" << "
             << expected_result << ".vector_value_size() << \" actual: \" << "
             << actual_result << ".vector_value_size();\n";
      out << "return false;\n";
      out.unindent();
      out << "}\n";
      out << "for (int i = 0; i <" << expected_result
          << ".vector_value_size(); i++) {\n";
      out.indent();
      GenerateVerificationCodeForTypedVariable(
          out, val.vector_value(0), expected_result + ".vector_value(i)",
          actual_result + ".vector_value(i)");
      out.unindent();
      out << "}\n";
      break;
    }
    case TYPE_ARRAY:
    {
      out << "if (" << actual_result << ".vector_value_size() != "
          << expected_result << ".vector_value_size()) {\n";
      out.indent();
      out << "cerr << \"Verification failed for vector size. expected: \" << "
          << expected_result << ".vector_value_size() << \" actual: \" << "
          << actual_result << ".vector_value_size();\n";
      out << "return false;\n";
      out.unindent();
      out << "}\n";
      out << "for (int i = 0; i < " << expected_result
          << ".vector_value_size(); i++) {\n";
      out.indent();
      GenerateVerificationCodeForTypedVariable(
          out, val.vector_value(0), expected_result + ".vector_value(i)",
          actual_result + ".vector_value(i)");
      out.unindent();
      out << "}\n";
      break;
    }
    case TYPE_STRUCT:
    {
      if (val.has_predefined_type()) {
        string func_name = "Verify"
            + ClearStringWithNameSpaceAccess(val.predefined_type());
        out << "if (!" << func_name << "(" << expected_result << ", "
            << actual_result << ")) { return false; }\n";
      } else {
        for (int i = 0; i < val.struct_value_size(); i++) {
          string struct_field_actual_result = actual_result + ".struct_value("
              + std::to_string(i) + ")";
          string struct_field_expected_result = expected_result
              + ".struct_value(" + std::to_string(i) + ")";
          GenerateVerificationCodeForTypedVariable(out, val.struct_value(i),
                                                   struct_field_expected_result,
                                                   struct_field_actual_result);
        }
      }
      break;
    }
    case TYPE_UNION:
    {
      if (val.has_predefined_type()) {
        string func_name = "Verify"
            + ClearStringWithNameSpaceAccess(val.predefined_type());
        out << "if (!" << func_name << "(" << expected_result << ", "
            << actual_result << ")) {return false; }\n";
      } else {
        for (int i = 0; i < val.union_value_size(); i++) {
          string union_field_actual_result = actual_result + ".union_value("
              + std::to_string(i) + ")";
          string union_field_expected_result = expected_result + ".union_value("
              + std::to_string(i) + ")";
          GenerateVerificationCodeForTypedVariable(out, val.union_value(i),
                                                   union_field_expected_result,
                                                   union_field_actual_result);
        }
      }
      break;
    }
    case TYPE_HIDL_CALLBACK:
    {
      out << "/* ERROR: TYPE_HIDL_CALLBACK is not supported yet. */\n";
      break;
    }
    case TYPE_HANDLE:
    {
      out << "/* ERROR: TYPE_HANDLE is not supported yet. */\n";
      break;
    }
    case TYPE_HIDL_INTERFACE:
    {
      out << "/* ERROR: TYPE_HIDL_INTERFACE is not supported yet. */\n";
      break;
    }
    case TYPE_HIDL_MEMORY:
    {
      out << "/* ERROR: TYPE_HIDL_MEMORY is not supported yet. */\n";
      break;
    }
    case TYPE_POINTER:
    {
      out << "/* ERROR: TYPE_POINTER is not supported yet. */\n";
      break;
    }
    case TYPE_FMQ_SYNC:
    {
      out << "/* ERROR: TYPE_FMQ_SYNC is not supported yet. */\n";
      break;
    }
    case TYPE_FMQ_UNSYNC:
    {
      out << "/* ERROR: TYPE_FMQ_UNSYNC is not supported yet. */\n";
      break;
    }
    case TYPE_REF:
    {
      out << "/* ERROR: TYPE_REF is not supported yet. */\n";
      break;
    }
    default:
    {
      cerr << __func__ << " ERROR: unsupported type " << val.type() << ".\n";
      exit(-1);
    }
  }
}

void HalHidlCodeGen::GenerateVerificationDeclForAttribute(Formatter& out,
    const VariableSpecificationMessage& attribute) {
  if (attribute.type() == TYPE_STRUCT || attribute.type() == TYPE_UNION) {
    // Recursively generate verification method implementation for all sub_types.
    for (const auto sub_struct : attribute.sub_struct()) {
      GenerateVerificationDeclForAttribute(out, sub_struct);
    }
    for (const auto sub_union : attribute.sub_union()) {
      GenerateVerificationDeclForAttribute(out, sub_union);
    }
  }
  std::string func_name = "bool Verify"
      + ClearStringWithNameSpaceAccess(attribute.name());
  out << func_name << "(const VariableSpecificationMessage& expected_result, "
      << "const VariableSpecificationMessage& actual_result);\n";
}

void HalHidlCodeGen::GenerateVerificationImplForAttribute(Formatter& out,
    const VariableSpecificationMessage& attribute) {
  if (attribute.type() == TYPE_STRUCT || attribute.type() == TYPE_UNION) {
    // Recursively generate verification method implementation for all sub_types.
    for (const auto sub_struct : attribute.sub_struct()) {
      GenerateVerificationImplForAttribute(out, sub_struct);
    }
    for (const auto sub_union : attribute.sub_union()) {
      GenerateVerificationImplForAttribute(out, sub_union);
    }
  }
  std::string func_name = "bool Verify"
      + ClearStringWithNameSpaceAccess(attribute.name());
  out << func_name << "(const VariableSpecificationMessage& expected_result "
                      "__attribute__((__unused__)), "
      << "const VariableSpecificationMessage& actual_result "
         "__attribute__((__unused__))){\n";
  out.indent();
  GenerateVerificationCodeForTypedVariable(out, attribute, "expected_result",
                                           "actual_result");
  out << "return true;\n";
  out.unindent();
  out << "}\n\n";
}

// TODO(zhuoyao): consider to generalize the pattern for
// Verification/SetResult/DriverImpl.
void HalHidlCodeGen::GenerateSetResultCodeForTypedVariable(Formatter& out,
    const VariableSpecificationMessage& val, const string& result_msg,
    const string& result_value) {
  switch (val.type()) {
    case TYPE_SCALAR:
    {
      out << result_msg << "->set_type(TYPE_SCALAR);\n";
      out << result_msg << "->set_scalar_type(\"" << val.scalar_type()
          << "\");\n";
      out << result_msg << "->mutable_scalar_value()->set_" << val.scalar_type()
          << "(" << result_value << ");\n";
      break;
    }
    case TYPE_STRING:
    {
      out << result_msg << "->set_type(TYPE_STRING);\n";
      out << result_msg << "->mutable_string_value()->set_message" << "("
          << result_value << ".c_str());\n";
      out << result_msg << "->mutable_string_value()->set_length" << "("
          << result_value << ".size());\n";
      break;
    }
    case TYPE_ENUM:
    {
      out << result_msg << "->set_type(TYPE_ENUM);\n";
      if (val.has_predefined_type()) {
        string func_name = "SetResult"
            + ClearStringWithNameSpaceAccess(val.predefined_type());
        out << func_name << "(" << result_msg << ", " << result_value << ");\n";
      } else {
        const string scalar_type = val.enum_value().scalar_type();
        out << result_msg << "->set_scalar_type(\"" << scalar_type << "\");\n";
        out << result_msg << "->mutable_scalar_value()->set_" << scalar_type
            << "(static_cast<" << scalar_type << ">(" << result_value
            << "));\n";
      }
      break;
    }
    case TYPE_MASK:
    {
      out << result_msg << "->set_type(TYPE_MASK);\n";
      out << result_msg << "->set_scalar_type(\"" << val.scalar_type()
          << "\");\n";
      out << result_msg << "->mutable_scalar_value()->set_" << val.scalar_type()
          << "(" << result_value << ");\n";
      break;
    }
    case TYPE_VECTOR:
    {
      out << result_msg << "->set_type(TYPE_VECTOR);\n";
      out << result_msg << "->set_vector_size(" << result_value
          << ".size());\n";
      out << "for (int i = 0; i < (int)" << result_value << ".size(); i++) {\n";
      out.indent();
      string vector_element_name = result_msg + "_vector_i";
      out << "auto *" << vector_element_name << " = " << result_msg
          << "->add_vector_value();\n";
      GenerateSetResultCodeForTypedVariable(out, val.vector_value(0),
                                            vector_element_name,
                                            result_value + "[i]");
      out.unindent();
      out << "}\n";
      break;
    }
    case TYPE_ARRAY:
    {
      out << result_msg << "->set_type(TYPE_ARRAY);\n";
      out << result_msg << "->set_vector_size(" << val.vector_value_size()
          << ");\n";
      out << "for (int i = 0; i < " << val.vector_value_size() << "; i++) {\n";
      out.indent();
      string array_element_name = result_msg + "_array_i";
      out << "auto *" << array_element_name << " = " << result_msg
          << "->add_vector_value();\n";
      GenerateSetResultCodeForTypedVariable(out, val.vector_value(0),
                                            array_element_name,
                                            result_value + "[i]");
      out.unindent();
      out << "}\n";
      break;
    }
    case TYPE_STRUCT:
    {
      out << result_msg << "->set_type(TYPE_STRUCT);\n";
      if (val.has_predefined_type()) {
        string func_name = "SetResult"
            + ClearStringWithNameSpaceAccess(val.predefined_type());
        out << func_name << "(" << result_msg << ", " << result_value << ");\n";
      } else {
        for (const auto struct_field : val.struct_value()) {
          string struct_field_name = result_msg + "_" + struct_field.name();
          out << "auto *" << struct_field_name << " = " << result_msg
              << "->add_struct_value();\n";
          GenerateSetResultCodeForTypedVariable(
              out, struct_field, struct_field_name,
              result_value + "." + struct_field.name());
          if (struct_field.has_name()) {
            out << struct_field_name << "->set_name(\""
                << struct_field.name() << "\");\n";
          }
        }
      }
      break;
    }
    case TYPE_UNION:
    {
      out << result_msg << "->set_type(TYPE_UNION);\n";
      if (val.has_predefined_type()) {
        string func_name = "SetResult"
            + ClearStringWithNameSpaceAccess(val.predefined_type());
        out << func_name << "(" << result_msg << ", " << result_value << ");\n";
      } else {
        for (const auto union_field : val.union_value()) {
          string union_field_name = result_msg + "_" + union_field.name();
          out << "auto *" << union_field_name << " = " << result_msg
              << "->add_union_value();\n";
          GenerateSetResultCodeForTypedVariable(
              out, union_field, union_field_name,
              result_value + "." + union_field.name());
        }
      }
      break;
    }
    case TYPE_HIDL_CALLBACK:
    {
      out << result_msg << "->set_type(TYPE_HIDL_CALLBACK);\n";
      out << "/* ERROR: TYPE_HIDL_CALLBACK is not supported yet. */\n";
      break;
    }
    case TYPE_HANDLE:
    {
      out << result_msg << "->set_type(TYPE_HANDLE);\n";
      out << "/* ERROR: TYPE_HANDLE is not supported yet. */\n";
      break;
    }
    case TYPE_HIDL_INTERFACE:
    {
      out << result_msg << "->set_type(TYPE_HIDL_INTERFACE);\n";
      if (!val.has_predefined_type()) {
        cerr << __func__ << ":" << __LINE__
             << " HIDL interface is a return type"
             << "but predefined_type is unset." << endl;
        exit(-1);
      }
      out << result_msg << "->set_predefined_type(\"" << val.predefined_type()
          << "\");\n";
      out << "if (" << result_value << " != nullptr) {\n";
      out.indent();
      out << result_value << "->incStrong(" << result_value << ".get());\n";
      out << result_msg << "->set_hidl_interface_pointer("
          << "reinterpret_cast<uintptr_t>(" << result_value << ".get()));\n";
      out.unindent();
      out << "} else {\n";
      out.indent();
      out << result_msg << "->set_hidl_interface_pointer(0);\n";
      out.unindent();
      out << "}\n";
      break;
    }
    case TYPE_HIDL_MEMORY:
    {
      out << result_msg << "->set_type(TYPE_HIDL_MEMORY);\n";
      out << "/* ERROR: TYPE_HIDL_MEMORY is not supported yet. */\n";
      break;
    }
    case TYPE_POINTER:
    {
      out << result_msg << "->set_type(TYPE_POINTER);\n";
      out << "/* ERROR: TYPE_POINTER is not supported yet. */\n";
      break;
    }
    case TYPE_FMQ_SYNC:
    {
      out << result_msg << "->set_type(TYPE_FMQ_SYNC);\n";
      out << "/* ERROR: TYPE_FMQ_SYNC is not supported yet. */\n";
      break;
    }
    case TYPE_FMQ_UNSYNC:
    {
      out << result_msg << "->set_type(TYPE_FMQ_UNSYNC);\n";
      out << "/* ERROR: TYPE_FMQ_UNSYNC is not supported yet. */\n";
      break;
    }
    case TYPE_REF:
    {
      out << result_msg << "->set_type(TYPE_REF);\n";
      out << "/* ERROR: TYPE_REF is not supported yet. */\n";
      break;
    }
    default:
    {
      cerr << __func__ << " ERROR: unsupported type " << val.type() << ".\n";
      exit(-1);
    }
  }
}

void HalHidlCodeGen::GenerateSetResultDeclForAttribute(Formatter& out,
    const VariableSpecificationMessage& attribute) {
  if (attribute.type() == TYPE_STRUCT || attribute.type() == TYPE_UNION) {
    // Recursively generate SetResult method implementation for all sub_types.
    for (const auto sub_struct : attribute.sub_struct()) {
      GenerateSetResultDeclForAttribute(out, sub_struct);
    }
    for (const auto sub_union : attribute.sub_union()) {
      GenerateSetResultDeclForAttribute(out, sub_union);
    }
  }
  string func_name = "void SetResult"
      + ClearStringWithNameSpaceAccess(attribute.name());
  out << func_name << "(VariableSpecificationMessage* result_msg, "
      << attribute.name() << " result_value);\n";
}

void HalHidlCodeGen::GenerateSetResultImplForAttribute(Formatter& out,
    const VariableSpecificationMessage& attribute) {
  if (attribute.type() == TYPE_STRUCT || attribute.type() == TYPE_UNION) {
    // Recursively generate SetResult method implementation for all sub_types.
    for (const auto sub_struct : attribute.sub_struct()) {
      GenerateSetResultImplForAttribute(out, sub_struct);
    }
    for (const auto sub_union : attribute.sub_union()) {
      GenerateSetResultImplForAttribute(out, sub_union);
    }
  }
  string func_name = "void SetResult"
      + ClearStringWithNameSpaceAccess(attribute.name());
  out << func_name << "(VariableSpecificationMessage* result_msg, "
      << attribute.name() << " result_value __attribute__((__unused__))){\n";
  out.indent();
  GenerateSetResultCodeForTypedVariable(out, attribute, "result_msg",
                                        "result_value");
  out.unindent();
  out << "}\n\n";
}

void HalHidlCodeGen::GenerateAllFunctionDeclForAttribute(Formatter& out,
    const VariableSpecificationMessage& attribute) {
  GenerateDriverDeclForAttribute(out, attribute);
  GenerateRandomFunctionDeclForAttribute(out, attribute);
  GenerateVerificationDeclForAttribute(out, attribute);
  GenerateSetResultDeclForAttribute(out, attribute);
}

void HalHidlCodeGen::GenerateAllFunctionImplForAttribute(Formatter& out,
    const VariableSpecificationMessage& attribute) {
  GenerateDriverImplForAttribute(out, attribute);
  GenerateRandomFunctionImplForAttribute(out, attribute);
  GenerateVerificationImplForAttribute(out, attribute);
  GenerateSetResultImplForAttribute(out, attribute);
}

bool HalHidlCodeGen::CanElideCallback(
    const FunctionSpecificationMessage& func_msg) {
  // Can't elide callback for void or tuple-returning methods
  if (func_msg.return_type_hidl_size() != 1) {
    return false;
  }
  const VariableType& type = func_msg.return_type_hidl(0).type();
  if (type == TYPE_ARRAY || type == TYPE_VECTOR || type == TYPE_REF) {
    return false;
  }
  return isElidableType(type);
}

bool HalHidlCodeGen::isElidableType(const VariableType& type) {
  if (type == TYPE_SCALAR || type == TYPE_ENUM || type == TYPE_MASK
      || type == TYPE_POINTER || type == TYPE_HIDL_INTERFACE
      || type == TYPE_VOID) {
    return true;
  }
  return false;
}

bool HalHidlCodeGen::isConstType(const VariableType& type) {
  if (type == TYPE_ARRAY || type == TYPE_VECTOR || type == TYPE_REF ||
      type == TYPE_HIDL_INTERFACE) {
    return true;
  }
  if (isElidableType(type)) {
    return false;
  }
  return true;
}

}  // namespace vts
}  // namespace android
