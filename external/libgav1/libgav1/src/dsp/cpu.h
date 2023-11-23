/*
 * Copyright 2019 The libgav1 Authors
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

#ifndef LIBGAV1_SRC_DSP_CPU_H_
#define LIBGAV1_SRC_DSP_CPU_H_

#include <cstdint>

namespace libgav1 {
namespace dsp {

enum CpuFeatures : uint8_t {
  kSSE2 = 1 << 0,
#define LIBGAV1_DSP_SSE2 (1 << 0)
  kSSSE3 = 1 << 1,
#define LIBGAV1_DSP_SSSE3 (1 << 1)
  kSSE4_1 = 1 << 2,
#define LIBGAV1_DSP_SSE4_1 (1 << 2)
  kAVX = 1 << 3,
#define LIBGAV1_DSP_AVX (1 << 3)
  kAVX2 = 1 << 4,
#define LIBGAV1_DSP_AVX2 (1 << 4)
  kNEON = 1 << 5,
#define LIBGAV1_DSP_NEON (1 << 5)
};

// Returns a bit-wise OR of CpuFeatures supported by this platform.
uint32_t GetCpuInfo();

}  // namespace dsp
}  // namespace libgav1

#endif  // LIBGAV1_SRC_DSP_CPU_H_
