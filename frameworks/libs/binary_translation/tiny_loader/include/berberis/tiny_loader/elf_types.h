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

#ifndef BERBERIS_TINY_LOADER_ELF_TYPES_H_
#define BERBERIS_TINY_LOADER_ELF_TYPES_H_

#include <elf.h>

// glibc has this defined in link.h - remove the definition to avoid conflicts
#if defined(ElfW)
#undef ElfW
#endif

#if defined(__LP64__)
#define ElfW(type) Elf64_##type
constexpr int kSupportedElfClass = ELFCLASS64;
#else
#define ElfW(type) Elf32_##type
constexpr int kSupportedElfClass = ELFCLASS32;
#endif

typedef ElfW(Addr) ElfAddr;
typedef ElfW(Dyn) ElfDyn;
typedef ElfW(Ehdr) ElfEhdr;
typedef ElfW(Half) ElfHalf;
typedef ElfW(Phdr) ElfPhdr;
typedef ElfW(Shdr) ElfShdr;
typedef ElfW(Sym) ElfSym;
typedef ElfW(Word) ElfWord;

#endif  // BERBERIS_TINY_LOADER_ELF_TYPES_H_
