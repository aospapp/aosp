/*
 * Copyright (C) 2023 The Android Open Source Project
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

#include <unistd.h>

#include <cstddef>
#include <cstdio>
#include <string>
#include <tuple>

#include "berberis/base/bit_util.h"
#include "berberis/base/checks.h"
#include "berberis/base/macros.h"
#include "berberis/guest_state/guest_addr.h"
#include "berberis/guest_state/guest_state.h"
#include "berberis/runtime/execute_guest.h"
#include "berberis/tiny_loader/loaded_elf_file.h"
#include "berberis/tiny_loader/tiny_loader.h"

namespace berberis {

namespace {

void Usage(const char* argv_0) {
  printf(
      "Usage: %s [-h] [-a start_addr] guest_executable [arg1 [arg2 ...]]\n"
      "  -h             - print this message\n"
      "  -a start_addr  - start execution at start_addr\n"
      "  guest_executable - path to the guest executable\n",
      argv_0);
}

std::tuple<GuestAddr, bool> ParseGuestAddr(const char* addr_cstr) {
  char* end_ptr = nullptr;
  errno = 0;
  GuestAddr addr = bit_cast<GuestAddr>(strtoull(addr_cstr, &end_ptr, 16));

  // Warning: setting errno on failure is implementation defined. So we also use extra heuristics.
  if (errno != 0 || (*end_ptr != '\n' && *end_ptr != '\0')) {
    printf("Cannot convert \"%s\" to integer: %s\n", addr_cstr,
           errno != 0 ? strerror(errno) : "unexpected end of string");
    return {kNullGuestAddr, false};
  }
  return {addr, true};
}

struct Options {
  const char* guest_executable;
  GuestAddr start_addr;
  bool print_help_and_exit;
};

Options ParseArgs(int argc, char* argv[]) {
  CHECK_GE(argc, 1);

  Options opts{};

  while (true) {
    int c = getopt(argc, argv, "ha:");
    if (c < 0) {
      break;
    }
    switch (c) {
      case 'a': {
        auto [addr, success] = ParseGuestAddr(optarg);
        if (!success) {
          return Options{.print_help_and_exit = true};
        }
        opts.start_addr = addr;
        break;
      }
      case 'h':
        return Options{.print_help_and_exit = true};
      default:
        UNREACHABLE();
    }
  }

  if (optind >= argc) {
    return Options{.print_help_and_exit = true};
  }

  opts.guest_executable = argv[optind];
  opts.print_help_and_exit = false;
  return opts;
}

}  // namespace

}  // namespace berberis

int main(int argc, char* argv[]) {
  berberis::Options opts = berberis::ParseArgs(argc, argv);

  if (opts.print_help_and_exit) {
    berberis::Usage(argv[0]);
    return -1;
  }

  LoadedElfFile elf_file;
  std::string error_msg;
  if (!TinyLoader::LoadFromFile(opts.guest_executable, &elf_file, &error_msg)) {
    printf("%s\n", error_msg.c_str());
    return -1;
  }

  berberis::ThreadState state{};
  state.cpu.insn_addr = opts.start_addr;
  ExecuteGuest(&state, berberis::kNullGuestAddr);

  return 0;
}
