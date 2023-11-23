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

#include "elf_reader.h"

#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <berberis/base/bit_util.h>
#include <berberis/base/checks.h>
#include <berberis/base/macros.h>
#include <berberis/base/mapped_file_fragment.h>
#include <berberis/base/stringprintf.h>

#include "string_offset_table.h"
#include "string_table.h"

namespace nogrod {

using berberis::bit_cast;
using berberis::StringPrintf;

namespace {

[[nodiscard]] constexpr uint8_t ElfStType(uint32_t info) {
  return info & 0xf;
}

class Elf32 {
 public:
  using Off = Elf32_Off;
  using Word = Elf32_Word;

  using Ehdr = Elf32_Ehdr;
  using Shdr = Elf32_Shdr;
  using Sym = Elf32_Sym;

  Elf32() = delete;
  Elf32(const Elf32&) = delete;
  const Elf32& operator=(const Elf32&) = delete;
};

class Elf64 {
 public:
  using Off = Elf64_Off;
  using Word = Elf64_Word;

  using Ehdr = Elf64_Ehdr;
  using Shdr = Elf64_Shdr;
  using Sym = Elf64_Sym;

  Elf64() = delete;
  Elf64(const Elf64&) = delete;
  const Elf64& operator=(const Elf64&) = delete;
};

template <typename ElfT>
class ElfFileImpl : public ElfFile {
 public:
  ~ElfFileImpl() override;

  static std::unique_ptr<ElfFileImpl<ElfT>> Create(const char* path,
                                                   int fd,
                                                   std::string* error_msg);

  [[nodiscard]] bool ReadExportedSymbols(std::vector<std::string>* symbols,
                                         std::string* error_msg) override;
  [[nodiscard]] std::unique_ptr<DwarfInfo> ReadDwarfInfo(std::string* error_msg) override;

 private:
  explicit ElfFileImpl(const char* path, int fd);
  [[nodiscard]] bool Init(std::string* error_msg);
  [[nodiscard]] bool ValidateShdrTable(std::string* error_msg);

  const typename ElfT::Shdr* FindSectionHeaderByType(typename ElfT::Word sh_type);
  const typename ElfT::Shdr* FindSectionHeaderByName(const char* name);

  template <typename T>
  [[nodiscard]] const T* OffsetToAddr(typename ElfT::Off offset) const;

  template <typename T>
  [[nodiscard]] const T* ShdrOffsetToAddr(const typename ElfT::Shdr* shdr) const;

  std::string path_;
  int fd_;

  MappedFileFragment mapped_file_;

  const typename ElfT::Ehdr* header_;

  const typename ElfT::Shdr* shdr_table_;
  size_t shdr_num_;

  StringTable strtab_;
};

template <typename ElfT>
ElfFileImpl<ElfT>::ElfFileImpl(const char* path, int fd)
    : path_(path), fd_(fd), header_(nullptr), shdr_table_(nullptr), shdr_num_(0) {}

template <typename ElfT>
ElfFileImpl<ElfT>::~ElfFileImpl() {
  close(fd_);
}

template <typename ElfT>
bool ElfFileImpl<ElfT>::ValidateShdrTable(std::string* error_msg) {
  size_t file_size = mapped_file_.size();
  for (size_t i = 0; i < shdr_num_; ++i) {
    const typename ElfT::Shdr* shdr = shdr_table_ + i;

    if (shdr->sh_link >= shdr_num_) {
      *error_msg = StringPrintf(
          "section %zd: sh_link (%d) is out of bounds (shnum=%zd)", i, shdr->sh_link, shdr_num_);
      return false;
    }

    // Skip boundary checks for SHT_NOBIT section headers.
    if (shdr->sh_type == SHT_NOBITS) {
      continue;
    }

    if (shdr->sh_offset >= file_size) {
      *error_msg = StringPrintf("section %zd: offset (%zd) is out of bounds (file_size=%zd)",
                                i,
                                static_cast<size_t>(shdr->sh_offset),
                                file_size);
      return false;
    }

    size_t section_end = shdr->sh_offset + shdr->sh_size;
    if (section_end > file_size) {
      *error_msg = StringPrintf("section %zd: offset+size (%zd) is out of bounds (file_size=%zd)",
                                i,
                                section_end,
                                file_size);
      return false;
    }
  }

  return true;
}

template <typename ElfT>
template <typename T>
const T* ElfFileImpl<ElfT>::OffsetToAddr(typename ElfT::Off offset) const {
  auto start = bit_cast<uintptr_t>(mapped_file_.data());
  return bit_cast<const T*>(start + offset);
}

template <typename ElfT>
template <typename T>
const T* ElfFileImpl<ElfT>::ShdrOffsetToAddr(const typename ElfT::Shdr* shdr) const {
  CHECK(shdr->sh_type != SHT_NOBITS);
  return OffsetToAddr<T>(shdr->sh_offset);
}

template <typename ElfT>
bool ElfFileImpl<ElfT>::Init(std::string* error_msg) {
  struct stat st {};
  if (fstat(fd_, &st) == -1) {
    *error_msg = StringPrintf("unable to stat \"%s\": %s", path_.c_str(), strerror(errno));
    return false;
  }

  size_t size = st.st_size;

  if (!mapped_file_.Map(fd_, 0, 0, size)) {
    *error_msg = StringPrintf("unable to map the file \"%s\"", path_.c_str());
    return false;
  }

  if (size < sizeof(typename ElfT::Ehdr)) {
    *error_msg = StringPrintf(
        "file \"%s\" is too small(%zd), there is not enough space for an ELF header(%zd)",
        path_.c_str(),
        size,
        sizeof(typename ElfT::Ehdr));
    return false;
  }

  header_ = OffsetToAddr<const typename ElfT::Ehdr>(0);

  uintptr_t shdr_offset = header_->e_shoff;
  size_t shdr_num = header_->e_shnum;

  if (header_->e_shentsize != sizeof(typename ElfT::Shdr)) {
    *error_msg = StringPrintf("invalid e_shentsize: %d, expected: %zd",
                              header_->e_shentsize,
                              sizeof(typename ElfT::Shdr));
    return false;
  }

  if (shdr_offset >= size) {
    *error_msg = StringPrintf("file \"%s\" is too small, e_shoff(%zd) is out of bounds (%zd)",
                              path_.c_str(),
                              shdr_offset,
                              size);
    return false;
  }

  if (shdr_offset + (shdr_num * sizeof(typename ElfT::Shdr)) > size) {
    *error_msg =
        StringPrintf("file \"%s\" is too small, e_shoff + shdr_size (%zd) is out of bounds (%zd)",
                     path_.c_str(),
                     shdr_offset + (shdr_num * sizeof(typename ElfT::Shdr)),
                     size);
    return false;
  }

  shdr_table_ = OffsetToAddr<const typename ElfT::Shdr>(shdr_offset);
  shdr_num_ = shdr_num;

  if (!ValidateShdrTable(error_msg)) {
    return false;
  }

  if (header_->e_shstrndx == SHN_UNDEF) {
    *error_msg = StringPrintf(
        "\"%s\": e_shstrndx is not defined, this is not good because "
        "section names are needed to extract dwarf_info",
        path_.c_str());
    return false;
  }

  if (header_->e_shstrndx >= shdr_num) {
    *error_msg = StringPrintf("\"%s\" invalid e_shstrndx (%d) - out of bounds (e_shnum=%zd)",
                              path_.c_str(),
                              header_->e_shstrndx,
                              shdr_num);
    return false;
  }

  const typename ElfT::Shdr* strtab_shdr = &shdr_table_[header_->e_shstrndx];

  strtab_ = StringTable(ShdrOffsetToAddr<const char>(strtab_shdr), strtab_shdr->sh_size);

  return true;
}

template <typename ElfT>
std::unique_ptr<ElfFileImpl<ElfT>> ElfFileImpl<ElfT>::Create(const char* path,
                                                             int fd,
                                                             std::string* error_msg) {
  std::unique_ptr<ElfFileImpl<ElfT>> result(new ElfFileImpl<ElfT>(path, fd));
  if (!result->Init(error_msg)) {
    return nullptr;
  }

  return result;
}

template <typename ElfT>
const typename ElfT::Shdr* ElfFileImpl<ElfT>::FindSectionHeaderByType(typename ElfT::Word sh_type) {
  for (size_t i = 0; i < shdr_num_; ++i) {
    if (shdr_table_[i].sh_type == sh_type) {
      return shdr_table_ + i;
    }
  }

  return nullptr;
}

template <typename ElfT>
const typename ElfT::Shdr* ElfFileImpl<ElfT>::FindSectionHeaderByName(const char* name) {
  for (size_t i = 0; i < shdr_num_; ++i) {
    if (strcmp(name, strtab_.GetString(shdr_table_[i].sh_name)) == 0) {
      return shdr_table_ + i;
    }
  }

  return nullptr;
}

template <typename ElfT>
bool ElfFileImpl<ElfT>::ReadExportedSymbols(std::vector<std::string>* symbols,
                                            std::string* error_msg) {
  const typename ElfT::Shdr* dynsym_shdr = FindSectionHeaderByType(SHT_DYNSYM);

  if (dynsym_shdr == nullptr) {
    *error_msg = "dynamic symbol section was not found";
    return false;
  }

  if (dynsym_shdr->sh_size % sizeof(typename ElfT::Sym) != 0) {
    *error_msg = StringPrintf("invalid SHT_DYNSYM section size(%zd): should be divisible by %zd",
                              static_cast<size_t>(dynsym_shdr->sh_size),
                              sizeof(typename ElfT::Sym));
    return false;
  }

  size_t dynsym_num = dynsym_shdr->sh_size / sizeof(typename ElfT::Sym);
  const auto* dynsyms = ShdrOffsetToAddr<const typename ElfT::Sym>(dynsym_shdr);

  const typename ElfT::Shdr* strtab_shdr = shdr_table_ + dynsym_shdr->sh_link;

  const StringTable strtab(ShdrOffsetToAddr<const char>(strtab_shdr), strtab_shdr->sh_size);

  for (size_t i = 0; i < dynsym_num; ++i) {
    const typename ElfT::Sym* sym = dynsyms + i;
    // skip undefined symbols
    if (sym->st_shndx == SHN_UNDEF) {
      continue;
    }

    // We are interested only in functions and variables.
    // This is a bit strange but the fact of the matter is that ld.gold generates OBJECT
    // of size 0 for version labels - we need to skip them as well.
    uint8_t st_type = ElfStType(sym->st_info);
    if (st_type == STT_FUNC || (st_type == STT_OBJECT && sym->st_size != 0)) {
      symbols->push_back(strtab.GetString(sym->st_name));
    }
  }

  return true;
}

template <typename ElfT>
std::unique_ptr<DwarfInfo> ElfFileImpl<ElfT>::ReadDwarfInfo(std::string* error_msg) {
  const typename ElfT::Shdr* dwarf_abbrev_shdr = FindSectionHeaderByName(".debug_abbrev");
  if (dwarf_abbrev_shdr == nullptr) {
    *error_msg = "couldn't find .debug_abbrev section";
    return nullptr;
  }

  const typename ElfT::Shdr* dwarf_info_shdr = FindSectionHeaderByName(".debug_info");
  if (dwarf_info_shdr == nullptr) {
    *error_msg = "couldn't find .debug_info section";
    return nullptr;
  }

  const typename ElfT::Shdr* dwarf_str_shdr = FindSectionHeaderByName(".debug_str");
  if (dwarf_str_shdr == nullptr) {
    *error_msg = "couldn't find .debug_str section";
    return nullptr;
  }

  StringTable string_table{ShdrOffsetToAddr<const char>(dwarf_str_shdr), dwarf_str_shdr->sh_size};

  // This section is optional (at least as of now)
  const typename ElfT::Shdr* debug_str_offsets_shdr = FindSectionHeaderByName(".debug_str_offsets");
  std::optional<StringOffsetTable> string_offsets_table;
  if (debug_str_offsets_shdr != nullptr) {
    string_offsets_table.emplace(ShdrOffsetToAddr<const uint8_t>(debug_str_offsets_shdr),
                                 debug_str_offsets_shdr->sh_size);
  }

  std::unique_ptr<DwarfInfo> dwarf_info(
      new DwarfInfo(ShdrOffsetToAddr<const uint8_t>(dwarf_abbrev_shdr),
                    dwarf_abbrev_shdr->sh_size,
                    ShdrOffsetToAddr<const uint8_t>(dwarf_info_shdr),
                    dwarf_info_shdr->sh_size,
                    string_table,
                    string_offsets_table));

  if (!dwarf_info->Parse(error_msg)) {
    return nullptr;
  }

  return dwarf_info;
}

}  // namespace

std::unique_ptr<ElfFile> ElfFile::Load(const char* path, std::string* error_msg) {
  int fd = TEMP_FAILURE_RETRY(open(path, O_RDONLY | O_CLOEXEC));
  if (fd == -1) {
    *error_msg = strerror(errno);
    return nullptr;
  }

  // Read header in order verify the file and detect bitness

  uint8_t e_ident[EI_NIDENT];
  ssize_t res = TEMP_FAILURE_RETRY(pread64(fd, e_ident, sizeof(e_ident), 0));
  if (res < 0) {
    *error_msg = strerror(errno);
    return nullptr;
  }

  if (res != sizeof(e_ident)) {
    *error_msg = "file is too small for an ELF file";
    return nullptr;
  }

  if (memcmp(e_ident, ELFMAG, SELFMAG) != 0) {
    *error_msg = "bad ELF magic";
    return nullptr;
  }

  std::unique_ptr<ElfFile> result;

  if (e_ident[EI_CLASS] == ELFCLASS32) {
    result = ElfFileImpl<Elf32>::Create(path, fd, error_msg);
  } else if (e_ident[EI_CLASS] == ELFCLASS64) {
    result = ElfFileImpl<Elf64>::Create(path, fd, error_msg);
  } else {
    *error_msg = StringPrintf("bad EI_CLASS: %d", e_ident[EI_CLASS]);
  }

  return result;
}

}  // namespace nogrod
