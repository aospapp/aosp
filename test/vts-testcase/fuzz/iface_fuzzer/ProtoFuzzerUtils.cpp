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

#include "ProtoFuzzerUtils.h"

#include <dirent.h>
#include <getopt.h>
#include <algorithm>
#include <sstream>

#include "utils/InterfaceSpecUtil.h"

using std::cout;
using std::cerr;
using std::string;
using std::unordered_map;
using std::vector;

namespace android {
namespace vts {
namespace fuzzer {

static void usage() {
  fprintf(
      stdout,
      "Usage:\n"
      "\n"
      "./vts_proto_fuzzer <vts flags> -- <libfuzzer flags>\n"
      "\n"
      "VTS flags (strictly in form --flag=value):\n"
      "\n"
      "\tvts_binder_mode: if set, fuzzer will open the HAL in binder mode.\n"
      "\tvts_exec_size: number of function calls per 1 run of "
      "LLVMFuzzerTestOneInput.\n"
      "\tvts_spec_dir: \":\"-separated list of directories on the target "
      "containing .vts spec files.\n"
      "\tvts_target_iface: name of interface targeted for fuzz, e.g. "
      "\"INfc\".\n"
      "\n"
      "libfuzzer flags (strictly in form -flag=value):\n"
      "\tUse -help=1 to see libfuzzer flags\n"
      "\n");
}

static struct option long_options[] = {
    {"help", no_argument, 0, 'h'},
    {"vts_binder_mode", no_argument, 0, 'b'},
    {"vts_spec_dir", required_argument, 0, 'd'},
    {"vts_exec_size", required_argument, 0, 'e'},
    {"vts_target_iface", required_argument, 0, 't'}};

// Removes information from CompSpec not needed by fuzzer.
static void TrimCompSpec(CompSpec *comp_spec) {
  if (comp_spec == nullptr) {
    cerr << __func__ << ": empty CompSpec." << endl;
    return;
  }
  if (comp_spec->has_interface()) {
    auto *iface_spec = comp_spec->mutable_interface();
    for (auto i = 0; i < iface_spec->api_size(); ++i) {
      iface_spec->mutable_api(i)->clear_callflow();
    }
  }
}

static vector<CompSpec> ExtractCompSpecs(string arg) {
  vector<CompSpec> result{};
  string dir_path;
  std::istringstream iss(arg);

  while (std::getline(iss, dir_path, ':')) {
    DIR *dir;
    struct dirent *ent;
    if (!(dir = opendir(dir_path.c_str()))) {
      cerr << "Could not open directory: " << dir_path << endl;
      exit(1);
    }
    while ((ent = readdir(dir))) {
      string vts_spec_name{ent->d_name};
      if (vts_spec_name.find(".vts") != string::npos) {
        cout << "Loading: " << vts_spec_name << endl;
        string vts_spec_path = dir_path + "/" + vts_spec_name;
        CompSpec comp_spec{};
        ParseInterfaceSpec(vts_spec_path.c_str(), &comp_spec);
        TrimCompSpec(&comp_spec);
        result.emplace_back(std::move(comp_spec));
      }
    }
  }
  return result;
}

static void ExtractPredefinedTypesFromVar(
    const TypeSpec &var_spec,
    unordered_map<string, TypeSpec> &predefined_types) {
  predefined_types[var_spec.name()] = var_spec;
  for (const auto &sub_var_spec : var_spec.sub_struct()) {
    ExtractPredefinedTypesFromVar(sub_var_spec, predefined_types);
  }
}

ProtoFuzzerParams ExtractProtoFuzzerParams(int argc, char **argv) {
  ProtoFuzzerParams params;
  int opt = 0;
  int index = 0;
  while ((opt = getopt_long_only(argc, argv, "", long_options, &index)) != -1) {
    switch (opt) {
      case 'h':
        usage();
        exit(0);
      case 'b':
        params.binder_mode_ = true;
        break;
      case 'd':
        params.comp_specs_ = ExtractCompSpecs(optarg);
        break;
      case 'e':
        params.exec_size_ = atoi(optarg);
        break;
      case 't':
        params.target_iface_ = optarg;
        break;
      default:
        // Ignore. This option will be handled by libfuzzer.
        break;
    }
  }
  return params;
}

unordered_map<string, TypeSpec> ExtractPredefinedTypes(
    const vector<CompSpec> &specs) {
  unordered_map<string, TypeSpec> predefined_types;
  for (const auto &comp_spec : specs) {
    for (const auto &var_spec : comp_spec.attribute()) {
      ExtractPredefinedTypesFromVar(var_spec, predefined_types);
    }
    for (const auto &var_spec : comp_spec.interface().attribute()) {
      ExtractPredefinedTypesFromVar(var_spec, predefined_types);
    }
  }
  return predefined_types;
}

bool FromArray(const uint8_t *data, size_t size, ExecSpec *exec_spec) {
  // TODO(b/63136690): Use checksum to validate exec_spec more reliably.
  return exec_spec->ParseFromArray(data, size) && exec_spec->has_valid() &&
         exec_spec->valid();
}

size_t ToArray(uint8_t *data, size_t size, ExecSpec *exec_spec) {
  exec_spec->set_valid(true);
  size_t exec_size = exec_spec->ByteSize();
  exec_spec->SerializeToArray(data, exec_size);
  return exec_size;
}

}  // namespace fuzzer
}  // namespace vts
}  // namespace android
