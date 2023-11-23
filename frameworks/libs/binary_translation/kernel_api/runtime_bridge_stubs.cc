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

#include "runtime_bridge.h"

#include <cstdlib>

#include "berberis/base/checks.h"

namespace berberis {

long RunGuestSyscall___NR_rt_sigaction(long, long, long, long) {
  FATAL("unimplemented syscall rt_sigaction");
  return -1;
}

long RunGuestSyscall___NR_sigaltstack(long, long) {
  FATAL("unimplemented syscall sigaltstack");
  return -1;
}

long RunGuestSyscall___NR_timer_create(long, long, long) {
  FATAL("unimplemented syscall create");
  return -1;
}

long RunGuestSyscall___NR_exit(long code) {
  _exit(code);
  return 0;
}

long RunGuestSyscall___NR_clone(long, long, long, long, long) {
  FATAL("unimplemented syscall clone");
  return -1;
}

long RunGuestSyscall___NR_mmap(long, long, long, long, long, long) {
  FATAL("unimplemented syscall mmap");
  return -1;
}

long RunGuestSyscall___NR_mmap2(long, long, long, long, long, long) {
  FATAL("unimplemented syscall mmap2");
  return -1;
}

long RunGuestSyscall___NR_munmap(long, long) {
  FATAL("unimplemented syscall munmap");
  return -1;
}

long RunGuestSyscall___NR_mprotect(long, long, long) {
  FATAL("unimplemented syscall mprotect");
  return -1;
}

long RunGuestSyscall___NR_mremap(long, long, long, long, long) {
  FATAL("unimplemented syscall mremap");
  return -1;
}

}  // namespace berberis
