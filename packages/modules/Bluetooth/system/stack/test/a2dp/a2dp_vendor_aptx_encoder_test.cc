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

#include "a2dp_vendor_aptx_encoder.h"

#include <base/logging.h>
#include <gtest/gtest.h>
#include <stdio.h>

#include <cstdint>

#include "osi/include/allocator.h"
#include "osi/include/log.h"
#include "osi/test/AllocationTestHarness.h"

extern void allocation_tracker_uninit(void);

class A2dpAptxTest : public AllocationTestHarness {
 protected:
  void SetUp() override { AllocationTestHarness::SetUp(); }

  void TearDown() override { AllocationTestHarness::TearDown(); }
};

TEST_F(A2dpAptxTest, CheckLoadLibrary) {
  tLOADING_CODEC_STATUS aptx_support = A2DP_VendorLoadEncoderAptx();
  if (aptx_support == LOAD_ERROR_MISSING_CODEC) {
    LOG_WARN("Aptx library not found, ignored test");
    return;
  }
  // Loading is either success or missing library. Version mismatch is not
  // allowed
  ASSERT_EQ(aptx_support, LOAD_SUCCESS);
}

TEST_F(A2dpAptxTest, EncodePacket) {
  tLOADING_CODEC_STATUS aptx_support = A2DP_VendorLoadEncoderAptx();
  if (aptx_support == LOAD_ERROR_MISSING_CODEC) {
    LOG_WARN("Aptx library not found, ignored test");
    return;
  }
  // Loading is either success or missing library. Wrong symbol is not allowed
  ASSERT_EQ(aptx_support, LOAD_SUCCESS);

  tAPTX_API aptx_api;
  ASSERT_TRUE(A2DP_VendorCopyAptxApi(aptx_api));

  ASSERT_EQ(aptx_api.sizeof_params_func(), 5008);
  void* handle = osi_malloc(aptx_api.sizeof_params_func());
  ASSERT_TRUE(handle != NULL);
  aptx_api.init_func(handle, 0);

  size_t pcm_bytes_encoded = 0;
  size_t frame = 0;
  const uint16_t *data16_in = (uint16_t *)"01234567890123456789012345678901234567890123456789012345678901234567890123456789";
  uint8_t data_out[20];
  const uint8_t expected_data_out[20] = {75,  191, 75,  191, 7,   255, 7,
                                         255, 39,  255, 39,  249, 76,  79,
                                         76,  79,  148, 41,  148, 41};

  size_t data_out_index = 0;

  for (size_t samples = 0;
       samples < strlen((char*)data16_in) / 16;  // 16 bit encode
       samples++) {
    uint32_t pcmL[4];
    uint32_t pcmR[4];
    uint16_t encoded_sample[2];
    for (size_t i = 0, j = frame; i < 4; i++, j++) {
      pcmL[i] = (uint16_t) * (data16_in + (2 * j));
      pcmR[i] = (uint16_t) * (data16_in + ((2 * j) + 1));
    }

    aptx_api.encode_stereo_func(handle, &pcmL, &pcmR, &encoded_sample);

    data_out[data_out_index + 0] = (uint8_t)((encoded_sample[0] >> 8) & 0xff);
    data_out[data_out_index + 1] = (uint8_t)((encoded_sample[0] >> 0) & 0xff);
    data_out[data_out_index + 2] = (uint8_t)((encoded_sample[1] >> 8) & 0xff);
    data_out[data_out_index + 3] = (uint8_t)((encoded_sample[1] >> 0) & 0xff);
    frame += 4;
    pcm_bytes_encoded += 16;
    data_out_index += 4;
  }

  ASSERT_EQ(sizeof(expected_data_out), data_out_index);
  ASSERT_EQ(0, memcmp(data_out, expected_data_out, sizeof(expected_data_out)));

  osi_free(handle);
}
