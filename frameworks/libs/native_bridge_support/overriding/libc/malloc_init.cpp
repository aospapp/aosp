/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include <private/bionic_config.h>
#include <private/bionic_globals.h>

#include <stdio.h>

#include <async_safe/log.h>

#include "native_bridge_support/vdso/vdso.h"

#if !defined(LIBC_STATIC)
static int malloc_info_impl(int options, FILE* fp) {
  // FILE objects cannot cross architecture boundary!
  // HACK: extract underlying file descriptor and use it instead.
  // TODO(b/146494184): at the moment malloc_info succeeds but writes nothing to memory streams!
  fflush(fp);
  int fd = fileno(fp);
  if (fd == -1) {
    return 0;
  }

  typedef int (*fn_t)(int options, int fd);
  static fn_t fn = reinterpret_cast<fn_t>(
      native_bridge_find_proxy_library_symbol("libc.so", "native_bridge_malloc_info"));
  return fn(options, fd);
}

static void malloc_init_impl(libc_globals* globals) {
  static const MallocDispatch malloc_default_dispatch __attribute__((unused)) = {
    reinterpret_cast<MallocCalloc>(native_bridge_find_proxy_library_symbol("libc.so", "calloc")),
    reinterpret_cast<MallocFree>(native_bridge_find_proxy_library_symbol("libc.so", "free")),
    reinterpret_cast<MallocMallinfo>(
        native_bridge_find_proxy_library_symbol("libc.so", "mallinfo")),
    reinterpret_cast<MallocMalloc>(native_bridge_find_proxy_library_symbol("libc.so", "malloc")),
    reinterpret_cast<MallocMallocUsableSize>(
        native_bridge_find_proxy_library_symbol("libc.so", "malloc_usable_size")),
    reinterpret_cast<MallocMemalign>(
        native_bridge_find_proxy_library_symbol("libc.so", "memalign")),
    reinterpret_cast<MallocPosixMemalign>(
        native_bridge_find_proxy_library_symbol("libc.so", "posix_memalign")),
#if defined(HAVE_DEPRECATED_MALLOC_FUNCS)
    reinterpret_cast<MallocPvalloc>(native_bridge_find_proxy_library_symbol("libc.so", "pvalloc")),
#endif
    reinterpret_cast<MallocRealloc>(native_bridge_find_proxy_library_symbol("libc.so", "realloc")),
#if defined(HAVE_DEPRECATED_MALLOC_FUNCS)
    reinterpret_cast<MallocValloc>(native_bridge_find_proxy_library_symbol("libc.so", "valloc")),
#endif
    reinterpret_cast<MallocIterate>(
        native_bridge_find_proxy_library_symbol("libc.so", "malloc_iterate")),
    reinterpret_cast<MallocMallocDisable>(
        native_bridge_find_proxy_library_symbol("libc.so", "malloc_disable")),
    reinterpret_cast<MallocMallocEnable>(
        native_bridge_find_proxy_library_symbol("libc.so", "malloc_enable")),
    reinterpret_cast<MallocMallopt>(native_bridge_find_proxy_library_symbol("libc.so", "mallopt")),
    reinterpret_cast<MallocAlignedAlloc>(
        native_bridge_find_proxy_library_symbol("libc.so", "aligned_alloc")),
    malloc_info_impl,
  };
  globals->malloc_dispatch_table = malloc_default_dispatch;
  globals->current_dispatch_table = &globals->malloc_dispatch_table;
}

// Initializes memory allocation framework.
// This routine is called from __libc_init routines in libc_init_dynamic.cpp.
__LIBC_HIDDEN__ void __libc_init_malloc(libc_globals* globals) {
  malloc_init_impl(globals);
}
#endif  // !LIBC_STATIC
