/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include <dlfcn.h>
#include <cctype>
#include <cmath>
#include <cstring>

#include "chre/platform/shared/nanoapp_loader.h"

#include "chre.h"
#include "chre/platform/assert.h"
#include "chre/platform/fatal_error.h"
#include "chre/platform/shared/debug_dump.h"
#include "chre/platform/shared/memory.h"
#include "chre/target_platform/platform_cache_management.h"
#include "chre/util/dynamic_vector.h"
#include "chre/util/macros.h"

#ifdef CHREX_SYMBOL_EXTENSIONS
#include "chre/extensions/platform/symbol_list.h"
#endif

#ifndef CHRE_LOADER_ARCH
#define CHRE_LOADER_ARCH EM_ARM
#endif  // CHRE_LOADER_ARCH

namespace chre {
namespace {

using ElfHeader = ElfW(Ehdr);
using ProgramHeader = ElfW(Phdr);

struct ExportedData {
  void *data;
  const char *dataName;
};

//! If non-null, a nanoapp is currently being loaded. This allows certain C
//! functions to access the nanoapp if called during static init.
NanoappLoader *gCurrentlyLoadingNanoapp = nullptr;
//! Indicates whether a failure occurred during static initialization.
bool gStaticInitFailure = false;

int atexitInternal(struct AtExitCallback &cb) {
  if (gCurrentlyLoadingNanoapp == nullptr) {
    CHRE_ASSERT_LOG(false,
                    "atexit is only supported during static initialization.");
    return -1;
  }

  gCurrentlyLoadingNanoapp->registerAtexitFunction(cb);
  return 0;
}

// atexit is used to register functions that must be called when a binary is
// removed from the system. The call back function has an arg (void *)
int cxaAtexitOverride(void (*func)(void *), void *arg, void *dso) {
  LOGV("__cxa_atexit invoked with %p, %p, %p", func, arg, dso);
  struct AtExitCallback cb(func, arg);
  atexitInternal(cb);
  return 0;
}

// The call back function has no arg.
int atexitOverride(void (*func)(void)) {
  LOGV("atexit invoked with %p", func);
  struct AtExitCallback cb(func);
  atexitInternal(cb);
  return 0;
}

// The following functions from the cmath header need to be overridden, since
// they're overloaded functions, and we need to specify explicit types of the
// template for the compiler.
double frexpOverride(double value, int *exp) {
  return frexp(value, exp);
}

double fmaxOverride(double x, double y) {
  return fmax(x, y);
}

double fminOverride(double x, double y) {
  return fmin(x, y);
}

double floorOverride(double value) {
  return floor(value);
}

double sinOverride(double rad) {
  return sin(rad);
}

double asinOverride(double val) {
  return asin(val);
}

double atan2Override(double y, double x) {
  return atan2(y, x);
}

double cosOverride(double rad) {
  return cos(rad);
}

float sqrtOverride(float val) {
  return sqrt(val);
}

double roundOverride(double val) {
  return round(val);
}

// This function is required to be exposed to nanoapps to handle errors from
// invoking virtual functions.
void __cxa_pure_virtual(void) {
  chreAbort(CHRE_ERROR /* abortCode */);
}

// TODO(karthikmb/stange): While this array was hand-coded for simple
// "hello-world" prototyping, the list of exported symbols must be
// generated to minimize runtime errors and build breaks.
// clang-format off
// Disable deprecation warning so that deprecated symbols in the array
// can be exported for older nanoapps and tests.
CHRE_DEPRECATED_PREAMBLE
const ExportedData kExportedData[] = {
    /* libmath overrides and symbols */
    ADD_EXPORTED_SYMBOL(asinOverride, "asin"),
    ADD_EXPORTED_SYMBOL(atan2Override, "atan2"),
    ADD_EXPORTED_SYMBOL(cosOverride, "cos"),
    ADD_EXPORTED_SYMBOL(floorOverride, "floor"),
    ADD_EXPORTED_SYMBOL(fmaxOverride, "fmax"),
    ADD_EXPORTED_SYMBOL(fminOverride, "fmin"),
    ADD_EXPORTED_SYMBOL(frexpOverride, "frexp"),
    ADD_EXPORTED_SYMBOL(roundOverride, "round"),
    ADD_EXPORTED_SYMBOL(sinOverride, "sin"),
    ADD_EXPORTED_SYMBOL(sqrtOverride, "sqrt"),
    ADD_EXPORTED_C_SYMBOL(acosf),
    ADD_EXPORTED_C_SYMBOL(asinf),
    ADD_EXPORTED_C_SYMBOL(atan2f),
    ADD_EXPORTED_C_SYMBOL(ceilf),
    ADD_EXPORTED_C_SYMBOL(cosf),
    ADD_EXPORTED_C_SYMBOL(expf),
    ADD_EXPORTED_C_SYMBOL(fabsf),
    ADD_EXPORTED_C_SYMBOL(floorf),
    ADD_EXPORTED_C_SYMBOL(fmaxf),
    ADD_EXPORTED_C_SYMBOL(fminf),
    ADD_EXPORTED_C_SYMBOL(fmodf),
    ADD_EXPORTED_C_SYMBOL(log10f),
    ADD_EXPORTED_C_SYMBOL(log1pf),
    ADD_EXPORTED_C_SYMBOL(log2f),
    ADD_EXPORTED_C_SYMBOL(logf),
    ADD_EXPORTED_C_SYMBOL(lrintf),
    ADD_EXPORTED_C_SYMBOL(lroundf),
    ADD_EXPORTED_C_SYMBOL(powf),
    ADD_EXPORTED_C_SYMBOL(remainderf),
    ADD_EXPORTED_C_SYMBOL(roundf),
    ADD_EXPORTED_C_SYMBOL(sinf),
    ADD_EXPORTED_C_SYMBOL(sqrtf),
    ADD_EXPORTED_C_SYMBOL(tanf),
    ADD_EXPORTED_C_SYMBOL(tanhf),
    /* libc overrides and symbols */
    ADD_EXPORTED_C_SYMBOL(__cxa_pure_virtual),
    ADD_EXPORTED_SYMBOL(cxaAtexitOverride, "__cxa_atexit"),
    ADD_EXPORTED_SYMBOL(atexitOverride, "atexit"),
    ADD_EXPORTED_C_SYMBOL(dlsym),
    ADD_EXPORTED_C_SYMBOL(isgraph),
    ADD_EXPORTED_C_SYMBOL(memcmp),
    ADD_EXPORTED_C_SYMBOL(memcpy),
    ADD_EXPORTED_C_SYMBOL(memmove),
    ADD_EXPORTED_C_SYMBOL(memset),
    ADD_EXPORTED_C_SYMBOL(snprintf),
    ADD_EXPORTED_C_SYMBOL(strcmp),
    ADD_EXPORTED_C_SYMBOL(strlen),
    ADD_EXPORTED_C_SYMBOL(strncmp),
    ADD_EXPORTED_C_SYMBOL(tolower),
    /* CHRE symbols */
    ADD_EXPORTED_C_SYMBOL(chreAbort),
    ADD_EXPORTED_C_SYMBOL(chreAudioConfigureSource),
    ADD_EXPORTED_C_SYMBOL(chreAudioGetSource),
    ADD_EXPORTED_C_SYMBOL(chreBleGetCapabilities),
    ADD_EXPORTED_C_SYMBOL(chreBleGetFilterCapabilities),
    ADD_EXPORTED_C_SYMBOL(chreBleFlushAsync),
    ADD_EXPORTED_C_SYMBOL(chreBleStartScanAsync),
    ADD_EXPORTED_C_SYMBOL(chreBleStopScanAsync),
    ADD_EXPORTED_C_SYMBOL(chreBleReadRssiAsync),
    ADD_EXPORTED_C_SYMBOL(chreBleGetScanStatus),
    ADD_EXPORTED_C_SYMBOL(chreConfigureDebugDumpEvent),
    ADD_EXPORTED_C_SYMBOL(chreConfigureHostSleepStateEvents),
    ADD_EXPORTED_C_SYMBOL(chreConfigureNanoappInfoEvents),
    ADD_EXPORTED_C_SYMBOL(chreDebugDumpLog),
    ADD_EXPORTED_C_SYMBOL(chreGetApiVersion),
    ADD_EXPORTED_C_SYMBOL(chreGetAppId),
    ADD_EXPORTED_C_SYMBOL(chreGetInstanceId),
    ADD_EXPORTED_C_SYMBOL(chreGetEstimatedHostTimeOffset),
    ADD_EXPORTED_C_SYMBOL(chreGetNanoappInfoByAppId),
    ADD_EXPORTED_C_SYMBOL(chreGetNanoappInfoByInstanceId),
    ADD_EXPORTED_C_SYMBOL(chreGetPlatformId),
    ADD_EXPORTED_C_SYMBOL(chreGetSensorInfo),
    ADD_EXPORTED_C_SYMBOL(chreGetSensorSamplingStatus),
    ADD_EXPORTED_C_SYMBOL(chreGetTime),
    ADD_EXPORTED_C_SYMBOL(chreGetVersion),
    ADD_EXPORTED_C_SYMBOL(chreGnssConfigurePassiveLocationListener),
    ADD_EXPORTED_C_SYMBOL(chreGnssGetCapabilities),
    ADD_EXPORTED_C_SYMBOL(chreGnssLocationSessionStartAsync),
    ADD_EXPORTED_C_SYMBOL(chreGnssLocationSessionStopAsync),
    ADD_EXPORTED_C_SYMBOL(chreGnssMeasurementSessionStartAsync),
    ADD_EXPORTED_C_SYMBOL(chreGnssMeasurementSessionStopAsync),
    ADD_EXPORTED_C_SYMBOL(chreHeapAlloc),
    ADD_EXPORTED_C_SYMBOL(chreHeapFree),
    ADD_EXPORTED_C_SYMBOL(chreIsHostAwake),
    ADD_EXPORTED_C_SYMBOL(chreLog),
    ADD_EXPORTED_C_SYMBOL(chreSendEvent),
    ADD_EXPORTED_C_SYMBOL(chreSendMessageToHost),
    ADD_EXPORTED_C_SYMBOL(chreSendMessageToHostEndpoint),
    ADD_EXPORTED_C_SYMBOL(chreSendMessageWithPermissions),
    ADD_EXPORTED_C_SYMBOL(chreSensorConfigure),
    ADD_EXPORTED_C_SYMBOL(chreSensorConfigureBiasEvents),
    ADD_EXPORTED_C_SYMBOL(chreSensorFind),
    ADD_EXPORTED_C_SYMBOL(chreSensorFindDefault),
    ADD_EXPORTED_C_SYMBOL(chreSensorFlushAsync),
    ADD_EXPORTED_C_SYMBOL(chreSensorGetThreeAxisBias),
    ADD_EXPORTED_C_SYMBOL(chreTimerCancel),
    ADD_EXPORTED_C_SYMBOL(chreTimerSet),
    ADD_EXPORTED_C_SYMBOL(chreUserSettingConfigureEvents),
    ADD_EXPORTED_C_SYMBOL(chreUserSettingGetState),
    ADD_EXPORTED_C_SYMBOL(chreWifiConfigureScanMonitorAsync),
    ADD_EXPORTED_C_SYMBOL(chreWifiGetCapabilities),
    ADD_EXPORTED_C_SYMBOL(chreWifiRequestScanAsync),
    ADD_EXPORTED_C_SYMBOL(chreWifiRequestRangingAsync),
    ADD_EXPORTED_C_SYMBOL(chreWifiNanRequestRangingAsync),
    ADD_EXPORTED_C_SYMBOL(chreWifiNanSubscribe),
    ADD_EXPORTED_C_SYMBOL(chreWifiNanSubscribeCancel),
    ADD_EXPORTED_C_SYMBOL(chreWwanGetCapabilities),
    ADD_EXPORTED_C_SYMBOL(chreWwanGetCellInfoAsync),
    ADD_EXPORTED_C_SYMBOL(platform_chreDebugDumpVaLog),
    ADD_EXPORTED_C_SYMBOL(chreConfigureHostEndpointNotifications),
    ADD_EXPORTED_C_SYMBOL(chrePublishRpcServices),
    ADD_EXPORTED_C_SYMBOL(chreGetHostEndpointInfo),
};
CHRE_DEPRECATED_EPILOGUE
// clang-format on

}  // namespace

void *NanoappLoader::create(void *elfInput, bool mapIntoTcm) {
  void *instance = nullptr;
  NanoappLoader *loader = memoryAllocDram<NanoappLoader>(elfInput, mapIntoTcm);
  if (loader != nullptr) {
    if (loader->open()) {
      instance = loader;
    } else {
      memoryFreeDram(loader);
    }
  } else {
    LOG_OOM();
  }

  return instance;
}

void NanoappLoader::destroy(NanoappLoader *loader) {
  loader->close();
  // TODO(b/151847750): Modify utilities to support free'ing from regions other
  // than SRAM.
  loader->~NanoappLoader();
  memoryFreeDram(loader);
}

void *NanoappLoader::findExportedSymbol(const char *name) {
  size_t nameLen = strlen(name);
  for (size_t i = 0; i < ARRAY_SIZE(kExportedData); i++) {
    if (nameLen == strlen(kExportedData[i].dataName) &&
        strncmp(name, kExportedData[i].dataName, nameLen) == 0) {
      return kExportedData[i].data;
    }
  }

#ifdef CHREX_SYMBOL_EXTENSIONS
  for (size_t i = 0; i < ARRAY_SIZE(kVendorExportedData); i++) {
    if (nameLen == strlen(kVendorExportedData[i].dataName) &&
        strncmp(name, kVendorExportedData[i].dataName, nameLen) == 0) {
      return kVendorExportedData[i].data;
    }
  }
#endif

  return nullptr;
}

bool NanoappLoader::open() {
  bool success = false;
  if (mBinary != nullptr) {
    if (!copyAndVerifyHeaders()) {
      LOGE("Failed to verify headers");
    } else if (!createMappings()) {
      LOGE("Failed to create mappings");
    } else if (!fixRelocations()) {
      LOGE("Failed to fix relocations");
    } else if (!resolveGot()) {
      LOGE("Failed to resolve GOT");
    } else {
      // Wipe caches before calling init array to ensure initializers are not in
      // the data cache.
      wipeSystemCaches(reinterpret_cast<uintptr_t>(mMapping), mMemorySpan);
      if (!callInitArray()) {
        LOGE("Failed to perform static init");
      } else {
        success = true;
      }
    }
  }

  if (!success) {
    freeAllocatedData();
  }

  return success;
}

void NanoappLoader::close() {
  callAtexitFunctions();
  callTerminatorArray();
  freeAllocatedData();
}

void *NanoappLoader::findSymbolByName(const char *name) {
  for (size_t offset = 0; offset < mSymbolTableSize; offset += sizeof(ElfSym)) {
    ElfSym *currSym = reinterpret_cast<ElfSym *>(mSymbolTablePtr + offset);
    const char *symbolName = &mStringTablePtr[currSym->st_name];

    if (strncmp(symbolName, name, strlen(name)) == 0) {
      return getSymbolTarget(currSym);
    }
  }
  return nullptr;
}

void NanoappLoader::registerAtexitFunction(struct AtExitCallback &cb) {
  if (!mAtexitFunctions.push_back(cb)) {
    LOG_OOM();
    gStaticInitFailure = true;
  }
}

void NanoappLoader::mapBss(const ProgramHeader *hdr) {
  // if the memory size of this segment exceeds the file size zero fill the
  // difference.
  LOGV("Program Hdr mem sz: %u file size: %u", hdr->p_memsz, hdr->p_filesz);
  if (hdr->p_memsz > hdr->p_filesz) {
    ElfAddr endOfFile = hdr->p_vaddr + hdr->p_filesz + mLoadBias;
    ElfAddr endOfMem = hdr->p_vaddr + hdr->p_memsz + mLoadBias;
    if (endOfMem > endOfFile) {
      auto deltaMem = endOfMem - endOfFile;
      LOGV("Zeroing out %u from page %x", deltaMem, endOfFile);
      memset(reinterpret_cast<void *>(endOfFile), 0, deltaMem);
    }
  }
}

bool NanoappLoader::callInitArray() {
  bool success = true;
  // Sets global variable used by atexit in case it's invoked as part of
  // initializing static data.
  gCurrentlyLoadingNanoapp = this;

  // TODO(b/151847750): ELF can have other sections like .init, .preinit, .fini
  // etc. Be sure to look for those if they end up being something that should
  // be supported for nanoapps.
  for (size_t i = 0; i < mNumSectionHeaders; ++i) {
    const char *name = getSectionHeaderName(mSectionHeadersPtr[i].sh_name);
    if (strncmp(name, kInitArrayName, strlen(kInitArrayName)) == 0) {
      LOGV("Invoking init function");
      uintptr_t initArray =
          static_cast<uintptr_t>(mLoadBias + mSectionHeadersPtr[i].sh_addr);
      uintptr_t offset = 0;
      while (offset < mSectionHeadersPtr[i].sh_size) {
        ElfAddr *funcPtr = reinterpret_cast<ElfAddr *>(initArray + offset);
        uintptr_t initFunction = static_cast<uintptr_t>(*funcPtr);
        ((void (*)())initFunction)();
        offset += sizeof(initFunction);
        if (gStaticInitFailure) {
          success = false;
          break;
        }
      }
      break;
    }
  }

  //! Reset global state so it doesn't leak into the next load.
  gCurrentlyLoadingNanoapp = nullptr;
  gStaticInitFailure = false;
  return success;
}

uintptr_t NanoappLoader::roundDownToAlign(uintptr_t virtualAddr,
                                          size_t alignment) {
  return alignment == 0 ? virtualAddr : virtualAddr & -alignment;
}

void NanoappLoader::freeAllocatedData() {
  if (mIsTcmBinary) {
    nanoappBinaryFree(mMapping);
  } else {
    nanoappBinaryDramFree(mMapping);
  }
  memoryFreeDram(mSectionHeadersPtr);
  memoryFreeDram(mSectionNamesPtr);
  memoryFreeDram(mSymbolTablePtr);
  memoryFreeDram(mStringTablePtr);
}

bool NanoappLoader::verifyElfHeader() {
  bool success = false;
  ElfHeader *elfHeader = getElfHeader();
  if (elfHeader != nullptr && (elfHeader->e_ident[EI_MAG0] == ELFMAG0) &&
      (elfHeader->e_ident[EI_MAG1] == ELFMAG1) &&
      (elfHeader->e_ident[EI_MAG2] == ELFMAG2) &&
      (elfHeader->e_ident[EI_MAG3] == ELFMAG3) &&
      (elfHeader->e_ehsize == sizeof(ElfHeader)) &&
      (elfHeader->e_phentsize == sizeof(ProgramHeader)) &&
      (elfHeader->e_shentsize == sizeof(SectionHeader)) &&
      (elfHeader->e_shstrndx < elfHeader->e_shnum) &&
      (elfHeader->e_version == EV_CURRENT) &&
      (elfHeader->e_machine == CHRE_LOADER_ARCH) &&
      (elfHeader->e_type == ET_DYN)) {
    success = true;
  }
  return success;
}

bool NanoappLoader::verifyProgramHeaders() {
  // This is a minimal check for now -
  // there should be at least one load segment.
  bool success = false;
  for (size_t i = 0; i < getProgramHeaderArraySize(); ++i) {
    if (getProgramHeaderArray()[i].p_type == PT_LOAD) {
      success = true;
      break;
    }
  }
  return success;
}

const char *NanoappLoader::getSectionHeaderName(size_t headerOffset) {
  if (headerOffset == 0) {
    return "";
  }

  return &mSectionNamesPtr[headerOffset];
}

NanoappLoader::SectionHeader *NanoappLoader::getSectionHeader(
    const char *headerName) {
  SectionHeader *rv = nullptr;
  for (size_t i = 0; i < mNumSectionHeaders; ++i) {
    const char *name = getSectionHeaderName(mSectionHeadersPtr[i].sh_name);
    if (strncmp(name, headerName, strlen(headerName)) == 0) {
      rv = &mSectionHeadersPtr[i];
      break;
    }
  }
  return rv;
}

ElfHeader *NanoappLoader::getElfHeader() {
  return reinterpret_cast<ElfHeader *>(mBinary);
}

ProgramHeader *NanoappLoader::getProgramHeaderArray() {
  ElfHeader *elfHeader = getElfHeader();
  ProgramHeader *programHeader = nullptr;
  if (elfHeader != nullptr) {
    programHeader =
        reinterpret_cast<ProgramHeader *>(mBinary + elfHeader->e_phoff);
  }

  return programHeader;
}

size_t NanoappLoader::getProgramHeaderArraySize() {
  ElfHeader *elfHeader = getElfHeader();
  size_t arraySize = 0;
  if (elfHeader != nullptr) {
    arraySize = elfHeader->e_phnum;
  }

  return arraySize;
}

char *NanoappLoader::getDynamicStringTable() {
  char *table = nullptr;

  SectionHeader *dynamicStringTablePtr = getSectionHeader(".dynstr");
  CHRE_ASSERT(dynamicStringTablePtr != nullptr);
  if (dynamicStringTablePtr != nullptr && mBinary != nullptr) {
    table =
        reinterpret_cast<char *>(mBinary + dynamicStringTablePtr->sh_offset);
  }

  return table;
}

uint8_t *NanoappLoader::getDynamicSymbolTable() {
  uint8_t *table = nullptr;

  SectionHeader *dynamicSymbolTablePtr = getSectionHeader(".dynsym");
  CHRE_ASSERT(dynamicSymbolTablePtr != nullptr);
  if (dynamicSymbolTablePtr != nullptr && mBinary != nullptr) {
    table = (mBinary + dynamicSymbolTablePtr->sh_offset);
  }

  return table;
}

size_t NanoappLoader::getDynamicSymbolTableSize() {
  size_t tableSize = 0;

  SectionHeader *dynamicSymbolTablePtr = getSectionHeader(".dynsym");
  CHRE_ASSERT(dynamicSymbolTablePtr != nullptr);
  if (dynamicSymbolTablePtr != nullptr) {
    tableSize = dynamicSymbolTablePtr->sh_size;
  }

  return tableSize;
}

bool NanoappLoader::verifySectionHeaders() {
  bool foundSymbolTableHeader = false;
  bool foundStringTableHeader = false;

  for (size_t i = 0; i < mNumSectionHeaders; ++i) {
    const char *name = getSectionHeaderName(mSectionHeadersPtr[i].sh_name);

    if (strncmp(name, kSymTableName, strlen(kSymTableName)) == 0) {
      foundSymbolTableHeader = true;
    } else if (strncmp(name, kStrTableName, strlen(kStrTableName)) == 0) {
      foundStringTableHeader = true;
    }
  }

  return foundSymbolTableHeader && foundStringTableHeader;
}

bool NanoappLoader::copyAndVerifyHeaders() {
  size_t offset = 0;
  bool success = false;
  uint8_t *pDataBytes = mBinary;

  // Verify the ELF Header
  ElfHeader *elfHeader = getElfHeader();
  success = verifyElfHeader();

  LOGV("Verified ELF header %d", success);

  // Verify Program Headers
  if (success) {
    success = verifyProgramHeaders();
  }

  LOGV("Verified Program headers %d", success);

  // Load Section Headers
  if (success) {
    offset = elfHeader->e_shoff;
    size_t sectionHeaderSizeBytes = sizeof(SectionHeader) * elfHeader->e_shnum;
    mSectionHeadersPtr =
        static_cast<SectionHeader *>(memoryAllocDram(sectionHeaderSizeBytes));
    if (mSectionHeadersPtr == nullptr) {
      success = false;
      LOG_OOM();
    } else {
      memcpy(mSectionHeadersPtr, (pDataBytes + offset), sectionHeaderSizeBytes);
      mNumSectionHeaders = elfHeader->e_shnum;
    }
  }

  LOGV("Loaded section headers %d", success);

  // Load section header names
  if (success) {
    SectionHeader &stringSection = mSectionHeadersPtr[elfHeader->e_shstrndx];
    size_t sectionSize = stringSection.sh_size;
    mSectionNamesPtr = static_cast<char *>(memoryAllocDram(sectionSize));
    if (mSectionNamesPtr == nullptr) {
      LOG_OOM();
      success = false;
    } else {
      memcpy(mSectionNamesPtr, mBinary + stringSection.sh_offset, sectionSize);
    }
  }

  LOGV("Loaded section header names %d", success);

  success = verifySectionHeaders();
  LOGV("Verified Section headers %d", success);

  // Load symbol table
  if (success) {
    SectionHeader *symbolTableHeader = getSectionHeader(kSymTableName);
    mSymbolTableSize = symbolTableHeader->sh_size;
    if (mSymbolTableSize == 0) {
      LOGE("No symbols to resolve");
      success = false;
    } else {
      mSymbolTablePtr =
          static_cast<uint8_t *>(memoryAllocDram(mSymbolTableSize));
      if (mSymbolTablePtr == nullptr) {
        LOG_OOM();
        success = false;
      } else {
        memcpy(mSymbolTablePtr, mBinary + symbolTableHeader->sh_offset,
               mSymbolTableSize);
      }
    }
  }

  LOGV("Loaded symbol table %d", success);

  // Load string table
  if (success) {
    SectionHeader *stringTableHeader = getSectionHeader(kStrTableName);
    size_t stringTableSize = stringTableHeader->sh_size;
    if (mSymbolTableSize == 0) {
      LOGE("No string table corresponding to symbols");
      success = false;
    } else {
      mStringTablePtr = static_cast<char *>(memoryAllocDram(stringTableSize));
      if (mStringTablePtr == nullptr) {
        LOG_OOM();
        success = false;
      } else {
        memcpy(mStringTablePtr, mBinary + stringTableHeader->sh_offset,
               stringTableSize);
      }
    }
  }

  LOGV("Loaded string table %d", success);

  return success;
}

bool NanoappLoader::createMappings() {
  // ELF needs pt_load segments to be in contiguous ascending order of
  // virtual addresses. So the first and last segs can be used to
  // calculate the entire address span of the image.
  ElfHeader *elfHeader = getElfHeader();
  ProgramHeader *programHeaderArray = getProgramHeaderArray();
  size_t numProgramHeaders = getProgramHeaderArraySize();
  const ProgramHeader *first = &programHeaderArray[0];
  const ProgramHeader *last = &programHeaderArray[numProgramHeaders - 1];

  // Find first load segment
  while (first->p_type != PT_LOAD && first <= last) {
    ++first;
  }

  bool success = false;
  if (first->p_type != PT_LOAD) {
    LOGE("Unable to find any load segments in the binary");
  } else {
    // Verify that the first load segment has a program header
    // first byte of a valid load segment can't be greater than the
    // program header offset
    bool valid =
        (first->p_offset < elfHeader->e_phoff) &&
        (first->p_filesz >=
         (elfHeader->e_phoff + (numProgramHeaders * sizeof(ProgramHeader))));
    if (!valid) {
      LOGE("Load segment program header validation failed");
    } else {
      // Get the last load segment
      while (last > first && last->p_type != PT_LOAD) --last;

      size_t alignment = first->p_align;
      size_t memorySpan = last->p_vaddr + last->p_memsz - first->p_vaddr;
      LOGV("Nanoapp image Memory Span: %zu", memorySpan);

      if (mIsTcmBinary) {
        mMapping =
            static_cast<uint8_t *>(nanoappBinaryAlloc(memorySpan, alignment));
      } else {
        mMapping = static_cast<uint8_t *>(
            nanoappBinaryDramAlloc(memorySpan, alignment));
      }

      if (mMapping == nullptr) {
        LOG_OOM();
      } else {
        LOGV("Starting location of mappings %p", mMapping);
        mMemorySpan = memorySpan;

        // Calculate the load bias using the first load segment.
        uintptr_t adjustedFirstLoadSegAddr =
            roundDownToAlign(first->p_vaddr, alignment);
        mLoadBias =
            reinterpret_cast<uintptr_t>(mMapping) - adjustedFirstLoadSegAddr;
        LOGV("Load bias is %lu", static_cast<long unsigned int>(mLoadBias));

        success = true;
      }
    }
  }

  if (success) {
    // Map the remaining segments
    for (const ProgramHeader *ph = first; ph <= last; ++ph) {
      if (ph->p_type == PT_LOAD) {
        ElfAddr segStart = ph->p_vaddr + mLoadBias;
        void *startPage = reinterpret_cast<void *>(segStart);
        void *binaryStartPage = mBinary + ph->p_offset;
        size_t segmentLen = ph->p_filesz;

        LOGV("Mapping start page %p from %p with length %zu", startPage,
             binaryStartPage, segmentLen);
        memcpy(startPage, binaryStartPage, segmentLen);
        mapBss(ph);
      } else {
        LOGE("Non-load segment found between load segments");
        success = false;
        break;
      }
    }
  }

  return success;
}

NanoappLoader::ElfSym *NanoappLoader::getDynamicSymbol(
    size_t posInSymbolTable) {
  size_t sectionSize = getDynamicSymbolTableSize();
  uint8_t *dynamicSymbolTable = getDynamicSymbolTable();
  size_t numElements = sectionSize / sizeof(ElfSym);
  CHRE_ASSERT(posInSymbolTable < numElements);
  if (posInSymbolTable < numElements) {
    return reinterpret_cast<ElfSym *>(
        &dynamicSymbolTable[posInSymbolTable * sizeof(ElfSym)]);
  }
  return nullptr;
}

const char *NanoappLoader::getDataName(const ElfSym *symbol) {
  return symbol == nullptr ? nullptr
                           : &getDynamicStringTable()[symbol->st_name];
}

void *NanoappLoader::getSymbolTarget(const ElfSym *symbol) {
  if (symbol == nullptr || symbol->st_shndx == SHN_UNDEF) {
    return nullptr;
  }

  return mMapping + symbol->st_value;
}

void *NanoappLoader::resolveData(size_t posInSymbolTable) {
  const ElfSym *symbol = getDynamicSymbol(posInSymbolTable);
  const char *dataName = getDataName(symbol);
  void *target = nullptr;

  if (dataName != nullptr) {
    LOGV("Resolving %s", dataName);
    target = findExportedSymbol(dataName);
    if (target == nullptr) {
      target = getSymbolTarget(symbol);
    }
    if (target == nullptr) {
      LOGE("Unable to find %s", dataName);
    }
  }

  return target;
}

NanoappLoader::DynamicHeader *NanoappLoader::getDynamicHeader() {
  DynamicHeader *dyn = nullptr;
  ProgramHeader *programHeaders = getProgramHeaderArray();
  for (size_t i = 0; i < getProgramHeaderArraySize(); ++i) {
    if (programHeaders[i].p_type == PT_DYNAMIC) {
      dyn = reinterpret_cast<DynamicHeader *>(programHeaders[i].p_offset +
                                              mBinary);
      break;
    }
  }
  return dyn;
}

NanoappLoader::ProgramHeader *NanoappLoader::getFirstRoSegHeader() {
  // return the first read only segment found
  ProgramHeader *ro = nullptr;
  ProgramHeader *programHeaders = getProgramHeaderArray();
  for (size_t i = 0; i < getProgramHeaderArraySize(); ++i) {
    if (!(programHeaders[i].p_flags & PF_W)) {
      ro = &programHeaders[i];
      break;
    }
  }
  return ro;
}

NanoappLoader::ElfWord NanoappLoader::getDynEntry(DynamicHeader *dyn,
                                                  int field) {
  ElfWord rv = 0;

  while (dyn->d_tag != DT_NULL) {
    if (dyn->d_tag == field) {
      rv = dyn->d_un.d_val;
      break;
    }
    ++dyn;
  }

  return rv;
}

bool NanoappLoader::fixRelocations() {
  DynamicHeader *dyn = getDynamicHeader();
  ProgramHeader *roSeg = getFirstRoSegHeader();

  bool success = false;
  if ((dyn == nullptr) || (roSeg == nullptr)) {
    LOGE("Mandatory headers missing from shared object, aborting load");
  }

  // Must return true if it table is not required or is empty. If
  // the entry is present when not expected, this must return false.
  success = relocateTable(dyn, DT_RELA);
  if (success) {
    success = relocateTable(dyn, DT_REL);
  }

  if (!success) {
    LOGE("Unable to resolve all symbols in the binary");
  }

  return success;
}

void NanoappLoader::callAtexitFunctions() {
  while (!mAtexitFunctions.empty()) {
    struct AtExitCallback cb = mAtexitFunctions.back();
    if (cb.arg.has_value()) {
      LOGV("Calling __cxa_atexit at %p, arg %p", cb.func1, cb.arg.value());
      cb.func1(cb.arg.value());
    } else {
      LOGV("Calling atexit at %p", cb.func0);
      cb.func0();
    }
    mAtexitFunctions.pop_back();
  }
}

void NanoappLoader::callTerminatorArray() {
  for (size_t i = 0; i < mNumSectionHeaders; ++i) {
    const char *name = getSectionHeaderName(mSectionHeadersPtr[i].sh_name);
    if (strncmp(name, kFiniArrayName, strlen(kFiniArrayName)) == 0) {
      uintptr_t finiArray =
          static_cast<uintptr_t>(mLoadBias + mSectionHeadersPtr[i].sh_addr);
      uintptr_t offset = 0;
      while (offset < mSectionHeadersPtr[i].sh_size) {
        ElfAddr *funcPtr = reinterpret_cast<ElfAddr *>(finiArray + offset);
        uintptr_t finiFunction = static_cast<uintptr_t>(*funcPtr);
        ((void (*)())finiFunction)();
        offset += sizeof(finiFunction);
      }
      break;
    }
  }
}

}  // namespace chre
