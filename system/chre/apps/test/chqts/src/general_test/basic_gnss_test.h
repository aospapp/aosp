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
#ifndef _GTS_NANOAPPS_GENERAL_TEST_BASIC_GNSS_TEST_H_
#define _GTS_NANOAPPS_GENERAL_TEST_BASIC_GNSS_TEST_H_

#include <general_test/test.h>

#include <cstdint>

#include <shared/test_success_marker.h>

#include "chre_api/chre.h"

namespace general_test {

class BasicGnssTest : public Test {
 public:
  BasicGnssTest();

 protected:
  void handleEvent(uint32_t senderInstanceId, uint16_t eventType,
                   const void *eventData) override;
  void setUp(uint32_t messageSize, const void *message) override;

 private:
  enum BasicGnssTestStage {
    BASIC_GNSS_TEST_STAGE_LOCATION = 0,
    BASIC_GNSS_TEST_STAGE_MEASUREMENT,
    BASIC_GNSS_TEST_STAGE_LISTENER,
    BASIC_GNSS_TEST_STAGE_COUNT,
  };

  nanoapp_testing::TestSuccessMarker mTestSuccessMarker =
      nanoapp_testing::TestSuccessMarker(BASIC_GNSS_TEST_STAGE_COUNT);

  bool isCapabilitySet(uint32_t capability) {
    return (chreGnssGetCapabilities() & capability);
  };

  void handleGnssAsyncResult(const chreAsyncResult *result);
};

}  // namespace general_test

#endif  // _GTS_NANOAPPS_GENERAL_TEST_BASIC_GNSS_TEST_H_
