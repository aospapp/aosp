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
#include "driver_manager/VtsHalDriverManager.h"

#include <iostream>
#include <string>

#include <google/protobuf/text_format.h>

#include "utils/InterfaceSpecUtil.h"
#include "utils/StringUtil.h"

static constexpr const char* kErrorString = "error";
static constexpr const char* kVoidString = "void";
static constexpr const int kInvalidDriverId = -1;

namespace android {
namespace vts {

VtsHalDriverManager::VtsHalDriverManager(const string& spec_dir,
                                         const int epoch_count,
                                         const string& callback_socket_name)
    : callback_socket_name_(callback_socket_name),
      default_driver_lib_name_(""),
      hal_driver_loader_(
          HalDriverLoader(spec_dir, epoch_count, callback_socket_name)) {}

DriverId VtsHalDriverManager::LoadTargetComponent(
    const string& dll_file_name, const string& spec_lib_file_path,
    const int component_class, const int component_type, const float version,
    const string& package_name, const string& component_name,
    const string& hw_binder_service_name, const string& submodule_name) {
  cout << __func__ << " entry dll_file_name = " << dll_file_name << endl;
  ComponentSpecificationMessage spec_message;
  if (!hal_driver_loader_.FindComponentSpecification(
          component_class, package_name, version, component_name,
          component_type, submodule_name, &spec_message)) {
    cerr << __func__ << ": Faild to load specification for component with "
         << "class: " << component_class << " type: " << component_type
         << " version: " << version << endl;
    return kInvalidDriverId;
  }
  cout << "loaded specification for component with class: " << component_class
       << " type: " << component_type << " version: " << version << endl;

  string driver_lib_path = "";
  if (component_class == HAL_HIDL) {
    driver_lib_path = GetHidlHalDriverLibName(package_name, version);
  } else {
    driver_lib_path = spec_lib_file_path;
    default_driver_lib_name_ = driver_lib_path;
  }

  cout << __func__ << " driver lib path " << driver_lib_path << endl;

  std::unique_ptr<DriverBase> hal_driver = nullptr;
  hal_driver.reset(hal_driver_loader_.GetDriver(driver_lib_path, spec_message,
                                                hw_binder_service_name, 0,
                                                false, dll_file_name, ""));
  if (!hal_driver) {
    cerr << "can't load driver for component with class: " << component_class
         << " type: " << component_type << " version: " << version << endl;
    return kInvalidDriverId;
  }

  // TODO (zhuoyao): get hidl_proxy_pointer for loaded hidl hal dirver.
  uint64_t interface_pt = 0;
  return RegisterDriver(std::move(hal_driver), spec_message, "", interface_pt);
}

string VtsHalDriverManager::CallFunction(FunctionCallMessage* call_msg) {
  string output = "";
  DriverBase* driver = GetDriverWithCallMsg(*call_msg);
  if (!driver) {
    cerr << "can't find driver for package: " << call_msg->package_name()
         << " version: " << call_msg->component_type_version() << endl;
    return kErrorString;
  }

  // Special process to open conventional hal.
  FunctionSpecificationMessage* api = call_msg->mutable_api();
  if (call_msg->component_class() != HAL_HIDL && api->name() == "#Open") {
    cout << __func__ << ":" << __LINE__ << " #Open" << endl;
    if (api->arg().size() > 0) {
      cout << __func__ << " open conventional hal with arg:"
           << api->arg(0).string_value().message() << endl;
      driver->OpenConventionalHal(api->arg(0).string_value().message().c_str());
    } else {
      cout << __func__ << " open conventional hal with no arg" << endl;
      driver->OpenConventionalHal();
    }
    // return the return value from open;
    if (api->return_type().has_type()) {
      cout << __func__ << " return_type exists" << endl;
      // TODO handle when the size > 1.
      if (!strcmp(api->return_type().scalar_type().c_str(), "int32_t")) {
        cout << __func__ << " return_type is int32_t" << endl;
        api->mutable_return_type()->mutable_scalar_value()->set_int32_t(0);
        cout << "result " << endl;
        // todo handle more types;
        google::protobuf::TextFormat::PrintToString(*api, &output);
        return output;
      }
    }
    cerr << __func__ << " return_type unknown" << endl;
    google::protobuf::TextFormat::PrintToString(*api, &output);
    return output;
  }

  void* result;
  FunctionSpecificationMessage result_msg;
  driver->FunctionCallBegin();
  cout << __func__ << " Call Function " << api->name() << " parent_path("
       << api->parent_path() << ")" << endl;
  // For Hidl HAL, use CallFunction method.
  if (call_msg->component_class() == HAL_HIDL) {
    if (!driver->CallFunction(*api, callback_socket_name_, &result_msg)) {
      cerr << __func__ << " Failed to call function: " << api->DebugString()
           << endl;
      return kErrorString;
    }
  } else {
    if (!driver->Fuzz(api, &result, callback_socket_name_)) {
      cerr << __func__ << " Failed to call function: " << api->DebugString()
           << endl;
      return kErrorString;
    }
  }
  cout << __func__ << ": called function " << api->name() << endl;

  // set coverage data.
  driver->FunctionCallEnd(api);

  if (call_msg->component_class() == HAL_HIDL) {
    for (int index = 0; index < result_msg.return_type_hidl_size(); index++) {
      auto* return_val = result_msg.mutable_return_type_hidl(index);
      if (return_val->type() == TYPE_HIDL_INTERFACE &&
          return_val->hidl_interface_pointer() != 0) {
        string type_name = return_val->predefined_type();
        uint64_t interface_pt = return_val->hidl_interface_pointer();
        std::unique_ptr<DriverBase> driver;
        ComponentSpecificationMessage spec_msg;
        string package_name = GetPackageName(type_name);
        float version = GetVersion(type_name);
        string component_name = GetComponentName(type_name);
        if (!hal_driver_loader_.FindComponentSpecification(
                HAL_HIDL, package_name, version, component_name, 0, "",
                &spec_msg)) {
          cerr << __func__
               << " Failed to load specification for gnerated interface :"
               << type_name << endl;
          return kErrorString;
        }
        string driver_lib_path = GetHidlHalDriverLibName(package_name, version);
        // TODO(zhuoyao): figure out a way to get the service_name.
        string hw_binder_service_name = "default";
        driver.reset(hal_driver_loader_.GetDriver(driver_lib_path, spec_msg,
                                                  hw_binder_service_name,
                                                  interface_pt, true, "", ""));
        int32_t driver_id =
            RegisterDriver(std::move(driver), spec_msg, "", interface_pt);
        return_val->set_hidl_interface_id(driver_id);
      }
    }
    google::protobuf::TextFormat::PrintToString(result_msg, &output);
    return output;
  } else {
    return ProcessFuncResultsForConventionalHal(api, result);
  }
  return kVoidString;
}

string VtsHalDriverManager::GetAttribute(FunctionCallMessage* call_msg) {
  string output = "";
  DriverBase* driver = GetDriverWithCallMsg(*call_msg);
  if (!driver) {
    cerr << "can't find driver for package: " << call_msg->package_name()
         << " version: " << call_msg->component_type_version() << endl;
    return kErrorString;
  }

  void* result;
  FunctionSpecificationMessage* api = call_msg->mutable_api();
  cout << __func__ << " Get Atrribute " << api->name() << " parent_path("
       << api->parent_path() << ")" << endl;
  if (!driver->GetAttribute(api, &result)) {
    cerr << __func__ << " attribute not found - todo handle more explicitly"
         << endl;
    return kErrorString;
  }
  cout << __func__ << ": called" << endl;

  if (call_msg->component_class() == HAL_HIDL) {
    cout << __func__ << ": for a HIDL HAL" << endl;
    api->mutable_return_type()->set_type(TYPE_STRING);
    api->mutable_return_type()->mutable_string_value()->set_message(
        *(string*)result);
    api->mutable_return_type()->mutable_string_value()->set_length(
        ((string*)result)->size());
    free(result);
    string* output = new string();
    google::protobuf::TextFormat::PrintToString(*api, output);
    return *output;
  } else {
    return ProcessFuncResultsForConventionalHal(api, result);
  }
  return kVoidString;
}

DriverId VtsHalDriverManager::RegisterDriver(
    std::unique_ptr<DriverBase> driver,
    const ComponentSpecificationMessage& spec_msg, const string& submodule_name,
    const uint64_t interface_pt) {
  DriverId driver_id =
      FindDriverIdInternal(spec_msg, submodule_name, interface_pt, true);
  if (driver_id == kInvalidDriverId) {
    driver_id = hal_driver_map_.size();
    hal_driver_map_.insert(
        make_pair(driver_id, HalDriverInfo(spec_msg, submodule_name,
                                           interface_pt, std::move(driver))));
  } else {
    cout << __func__ << " Driver already exists. ";
  }

  return driver_id;
}

DriverBase* VtsHalDriverManager::GetDriverById(const int32_t id) {
  auto res = hal_driver_map_.find(id);
  if (res == hal_driver_map_.end()) {
    cerr << "Failed to find driver info with id: " << id << endl;
    return nullptr;
  }
  cout << __func__ << " found driver info with id: " << id << endl;
  return res->second.driver.get();
}

ComponentSpecificationMessage* VtsHalDriverManager::GetComponentSpecById(
    const int32_t id) {
  auto res = hal_driver_map_.find(id);
  if (res == hal_driver_map_.end()) {
    cerr << "Failed to find driver info with id: " << id << endl;
    return nullptr;
  }
  cout << __func__ << " found driver info with id: " << id << endl;
  return &(res->second.spec_msg);
}

DriverBase* VtsHalDriverManager::GetDriverForHidlHalInterface(
    const string& package_name, const float version,
    const string& interface_name, const string& hal_service_name) {
  ComponentSpecificationMessage spec_msg;
  spec_msg.set_component_class(HAL_HIDL);
  spec_msg.set_package(package_name);
  spec_msg.set_component_type_version(version);
  spec_msg.set_component_name(interface_name);
  int32_t driver_id = FindDriverIdInternal(spec_msg);
  if (driver_id == kInvalidDriverId) {
    string driver_lib_path = GetHidlHalDriverLibName(package_name, version);
    driver_id =
        LoadTargetComponent("", driver_lib_path, HAL_HIDL, 0, version,
                            package_name, interface_name, hal_service_name, "");
  }
  return GetDriverById(driver_id);
}

bool VtsHalDriverManager::FindComponentSpecification(
    const int component_class, const int component_type, const float version,
    const string& submodule_name, const string& package_name,
    const string& component_name, ComponentSpecificationMessage* spec_msg) {
  return hal_driver_loader_.FindComponentSpecification(
      component_class, package_name, version, component_name, component_type,
      submodule_name, spec_msg);
}

ComponentSpecificationMessage*
VtsHalDriverManager::GetComponentSpecification() {
  if (hal_driver_map_.empty()) {
    return nullptr;
  } else {
    return &(hal_driver_map_.find(0)->second.spec_msg);
  }
}

DriverId VtsHalDriverManager::FindDriverIdInternal(
    const ComponentSpecificationMessage& spec_msg, const string& submodule_name,
    const uint64_t interface_pt, bool with_interface_pointer) {
  if (!spec_msg.has_component_class()) {
    cerr << __func__ << " Component class not specified. " << endl;
    return kInvalidDriverId;
  }
  if (spec_msg.component_class() == HAL_HIDL) {
    if (!spec_msg.has_package() || spec_msg.package().empty()) {
      cerr << __func__ << " Package name is requried but not specified. "
           << endl;
      return kInvalidDriverId;
    }
    if (!spec_msg.has_component_type_version()) {
      cerr << __func__ << " Package version is requried but not specified. "
           << endl;
      return kInvalidDriverId;
    }
    if (!spec_msg.has_component_name() || spec_msg.component_name().empty()) {
      cerr << __func__ << " Component name is requried but not specified. "
           << endl;
      return kInvalidDriverId;
    }
  } else {
    if (submodule_name.empty()) {
      cerr << __func__ << " Submodule name is requried but not specified. "
           << endl;
      return kInvalidDriverId;
    }
  }
  for (auto it = hal_driver_map_.begin(); it != hal_driver_map_.end(); ++it) {
    ComponentSpecificationMessage cur_spec_msg = it->second.spec_msg;
    if (cur_spec_msg.component_class() != spec_msg.component_class()) {
      continue;
    }
    // If package name is specified, match package name.
    if (spec_msg.has_package()) {
      if (!cur_spec_msg.has_package() ||
          cur_spec_msg.package() != spec_msg.package()) {
        continue;
      }
    }
    // If version is specified, match version.
    if (spec_msg.has_component_type_version()) {
      if (!cur_spec_msg.has_component_type_version() ||
          cur_spec_msg.component_type_version() !=
              spec_msg.component_type_version()) {
        continue;
      }
    }
    if (spec_msg.component_class() == HAL_HIDL) {
      if (cur_spec_msg.component_name() != spec_msg.component_name()) {
        continue;
      }
      if (with_interface_pointer &&
          it->second.hidl_hal_proxy_pt != interface_pt) {
        continue;
      }
      cout << __func__ << " Found hidl hal driver with id: " << it->first
           << endl;
      return it->first;
    } else {
      if ((!spec_msg.has_component_type() ||
           cur_spec_msg.component_type() == spec_msg.component_type()) &&
          it->second.submodule_name == submodule_name) {
        cout << __func__
             << " Found conventional hal driver with id: " << it->first << endl;
        return it->first;
      }
    }
  }
  return kInvalidDriverId;
}

DriverBase* VtsHalDriverManager::GetDriverWithCallMsg(
    const FunctionCallMessage& call_msg) {
  DriverId driver_id = kInvalidDriverId;
  // If call_mag contains driver_id, use that given driver id.
  if (call_msg.has_hal_driver_id() &&
      call_msg.hal_driver_id() != kInvalidDriverId) {
    driver_id = call_msg.hal_driver_id();
  } else {
    // Otherwise, try to find a registed driver matches the given info. e.g.,
    // submodule_name, package_name, version etc.
    FunctionSpecificationMessage api = call_msg.api();
    if (api.submodule_name().size() > 0) {
      string submodule_name = api.submodule_name();
      cout << __func__ << " submodule name " << submodule_name << endl;
      DriverId driver_id = FindDriverIdWithSubModuleName(submodule_name);
      if (driver_id != kInvalidDriverId) {
        cout << __func__ << " call is for a submodule" << endl;
      } else {
        cerr << __func__ << " called an API of a non-loaded submodule." << endl;
        return nullptr;
      }
    } else {
      ComponentSpecificationMessage spec_msg;
      spec_msg.set_component_class(call_msg.component_class());
      spec_msg.set_package(call_msg.package_name());
      spec_msg.set_component_type_version(
          stof(call_msg.component_type_version()));
      spec_msg.set_component_name(call_msg.component_name());
      driver_id = FindDriverIdInternal(spec_msg);
    }
  }

  if (driver_id == kInvalidDriverId) {
    cerr << "can't find driver ID for package: " << call_msg.package_name()
         << " version: " << call_msg.component_type_version() << endl;
    return nullptr;
  } else {
    return GetDriverById(driver_id);
  }
}

DriverId VtsHalDriverManager::FindDriverIdWithSubModuleName(
    const string& submodule_name) {
  ComponentSpecificationMessage spec_msg;
  spec_msg.set_component_class(HAL_CONVENTIONAL);
  return FindDriverIdInternal(spec_msg, submodule_name);
}

string VtsHalDriverManager::ProcessFuncResultsForConventionalHal(
    FunctionSpecificationMessage* func_msg, void* result) {
  string output = "";
  if (func_msg->return_type().type() == TYPE_PREDEFINED) {
    // TODO: actually handle this case.
    if (result != NULL) {
      // loads that interface spec and enqueues all functions.
      cout << __func__ << " return type: " << func_msg->return_type().type()
           << endl;
    } else {
      cout << __func__ << " return value = NULL" << endl;
    }
    cerr << __func__ << " todo: support aggregate" << endl;
    google::protobuf::TextFormat::PrintToString(*func_msg, &output);
    return output;
  } else if (func_msg->return_type().type() == TYPE_SCALAR) {
    // TODO handle when the size > 1.
    // todo handle more types;
    if (!strcmp(func_msg->return_type().scalar_type().c_str(), "int32_t")) {
      func_msg->mutable_return_type()->mutable_scalar_value()->set_int32_t(
          *((int*)(&result)));
      google::protobuf::TextFormat::PrintToString(*func_msg, &output);
      return output;
    } else if (!strcmp(func_msg->return_type().scalar_type().c_str(),
                       "uint32_t")) {
      func_msg->mutable_return_type()->mutable_scalar_value()->set_uint32_t(
          *((int*)(&result)));
      google::protobuf::TextFormat::PrintToString(*func_msg, &output);
      return output;
    } else if (!strcmp(func_msg->return_type().scalar_type().c_str(),
                       "int16_t")) {
      func_msg->mutable_return_type()->mutable_scalar_value()->set_int16_t(
          *((int*)(&result)));
      google::protobuf::TextFormat::PrintToString(*func_msg, &output);
      return output;
    } else if (!strcmp(func_msg->return_type().scalar_type().c_str(),
                       "uint16_t")) {
      google::protobuf::TextFormat::PrintToString(*func_msg, &output);
      return output;
    }
  } else if (func_msg->return_type().type() == TYPE_SUBMODULE) {
    cerr << __func__ << "[driver:hal] return type TYPE_SUBMODULE" << endl;
    if (result != NULL) {
      // loads that interface spec and enqueues all functions.
      cout << __func__ << " return type: " << func_msg->return_type().type()
           << endl;
    } else {
      cout << __func__ << " return value = NULL" << endl;
    }
    // find a VTS spec for that module
    string submodule_name = func_msg->return_type().predefined_type().substr(
        0, func_msg->return_type().predefined_type().size() - 1);
    ComponentSpecificationMessage submodule_iface_spec_msg;
    DriverId driver_id = FindDriverIdWithSubModuleName(submodule_name);
    if (driver_id != kInvalidDriverId) {
      cout << __func__ << " submodule InterfaceSpecification already loaded"
           << endl;
      ComponentSpecificationMessage* spec_msg = GetComponentSpecById(driver_id);
      func_msg->set_allocated_return_type_submodule_spec(spec_msg);
    } else {
      // TODO(zhuoyao): under the current assumption, driver_id = 0 means the
      // Hal the initially loaded, need to change then when we support
      // multi-hal testing.
      ComponentSpecificationMessage* spec_msg = GetComponentSpecById(0);
      if (hal_driver_loader_.FindComponentSpecification(
              spec_msg->component_class(), spec_msg->package(),
              spec_msg->component_type_version(), spec_msg->component_name(),
              spec_msg->component_type(), submodule_name,
              &submodule_iface_spec_msg)) {
        cout << __func__ << " submodule InterfaceSpecification found" << endl;
        func_msg->set_allocated_return_type_submodule_spec(
            &submodule_iface_spec_msg);
        std::unique_ptr<DriverBase> driver = nullptr;
        driver.reset(hal_driver_loader_.GetDriverForSubModule(
            default_driver_lib_name_, submodule_iface_spec_msg, result));
        RegisterDriver(std::move(driver), submodule_iface_spec_msg,
                       submodule_name, 0);
      } else {
        cerr << __func__ << " submodule InterfaceSpecification not found"
             << endl;
      }
    }
    google::protobuf::TextFormat::PrintToString(*func_msg, &output);
    return output;
  }
  return kVoidString;
}

}  // namespace vts
}  // namespace android
