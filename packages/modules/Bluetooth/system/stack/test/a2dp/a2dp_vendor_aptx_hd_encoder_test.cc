/*
 * Copyright 2022 The Android Open Source Project
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

#define LOG_TAG "aptx_encoder_test"

#include "a2dp_vendor_aptx_hd_encoder.h"

#include <base/logging.h>
#include <gtest/gtest.h>
#include <stdio.h>

#include <cstdint>

#include "osi/include/allocator.h"
#include "osi/include/log.h"
#include "osi/test/AllocationTestHarness.h"

extern void allocation_tracker_uninit(void);

class A2dpAptxHdTest : public AllocationTestHarness {
 protected:
  void SetUp() override { AllocationTestHarness::SetUp(); }

  void TearDown() override { AllocationTestHarness::TearDown(); }
};

TEST_F(A2dpAptxHdTest, CheckLoadLibrary) {
  tLOADING_CODEC_STATUS aptx_support = A2DP_VendorLoadEncoderAptxHd();
  if (aptx_support == LOAD_ERROR_MISSING_CODEC) {
    LOG_WARN("Aptx Hd library not found, ignored test");
    return;
  }
  // Loading is either success or missing library. Version mismatch is not
  // allowed
  ASSERT_EQ(aptx_support, LOAD_SUCCESS);
}

TEST_F(A2dpAptxHdTest, EncodePacket) {
  tLOADING_CODEC_STATUS aptx_support = A2DP_VendorLoadEncoderAptxHd();
  if (aptx_support == LOAD_ERROR_MISSING_CODEC) {
    LOG_WARN("Aptx Hd library not found, ignored test");
    return;
  }
  // Loading is either success or missing library. Wrong symbol is not allowed
  ASSERT_EQ(aptx_support, LOAD_SUCCESS);

  tAPTX_HD_API aptx_hd_api;
  ASSERT_TRUE(A2DP_VendorCopyAptxHdApi(aptx_hd_api));

  ASSERT_EQ(aptx_hd_api.sizeof_params_func(), 5256);
  void* handle = osi_malloc(aptx_hd_api.sizeof_params_func());
  ASSERT_TRUE(handle != NULL);
  aptx_hd_api.init_func(handle, 0);

  size_t pcm_bytes_encoded = 0;
  const uint32_t *data32_in = (uint32_t *)"012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789";
  const uint8_t* p = (const uint8_t*)(data32_in);
  uint8_t data_out[30];
  const uint8_t expected_data_out[30] = {115, 190, 255, 115, 190, 255, 0,   127,
                                         255, 0,   127, 255, 8,   127, 255, 8,
                                         127, 227, 115, 193, 57,  115, 193, 61,
                                         148, 192, 176, 164, 64,  158};

  size_t data_out_index = 0;

  for (size_t samples = 0;
       samples < strlen((char*)data32_in) / 24;  // 24 bit encode
       samples++) {
    uint32_t pcmL[4];
    uint32_t pcmR[4];
    uint32_t encoded_sample[2];
    // Expand from AUDIO_FORMAT_PCM_24_BIT_PACKED data (3 bytes per sample)
    // into AUDIO_FORMAT_PCM_8_24_BIT (4 bytes per sample).
    for (size_t i = 0; i < 4; i++) {
      pcmL[i] = ((p[0] << 0) | (p[1] << 8) | (((int8_t)p[2]) << 16));
      p += 3;
      pcmR[i] = ((p[0] << 0) | (p[1] << 8) | (((int8_t)p[2]) << 16));
      p += 3;
    }

    aptx_hd_api.encode_stereo_func(handle, &pcmL, &pcmR, &encoded_sample);

    uint8_t* encoded_ptr = (uint8_t*)&encoded_sample[0];
    data_out[data_out_index + 0] = *(encoded_ptr + 2);
    data_out[data_out_index + 1] = *(encoded_ptr + 1);
    data_out[data_out_index + 2] = *(encoded_ptr + 0);
    data_out[data_out_index + 3] = *(encoded_ptr + 6);
    data_out[data_out_index + 4] = *(encoded_ptr + 5);
    data_out[data_out_index + 5] = *(encoded_ptr + 4);

    pcm_bytes_encoded += 24;
    data_out_index += 6;
  }

  // for (size_t i =0; i < data_out_index; i++) {
  //   LOG_ERROR("DATA %zu is %hu", i, data_out[i]);
  // }

  ASSERT_EQ(sizeof(expected_data_out), data_out_index);
  ASSERT_EQ(0, memcmp(data_out, expected_data_out, sizeof(expected_data_out)));

  osi_free(handle);
}
