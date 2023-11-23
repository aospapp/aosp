// Copyright 2017 The ChromiumOS Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include <gtest/gtest.h>

#include "include/gestures.h"
#include "include/timestamp_filter_interpreter.h"
#include "include/unittest_util.h"
#include "include/util.h"

namespace gestures {

class TimestampFilterInterpreterTest : public ::testing::Test {};

class TimestampFilterInterpreterTestInterpreter : public Interpreter {
 public:
  TimestampFilterInterpreterTestInterpreter()
      : Interpreter(NULL, NULL, false) {}
};

static HardwareState make_hwstate_times(stime_t timestamp,
                                        stime_t msc_timestamp) {
  return { timestamp, 0, 1, 1, NULL, 0, 0, 0, 0, 0, msc_timestamp };
}

TEST(TimestampFilterInterpreterTest, SimpleTest) {
  TimestampFilterInterpreterTestInterpreter* base_interpreter =
      new TimestampFilterInterpreterTestInterpreter;
  TimestampFilterInterpreter interpreter(NULL, base_interpreter, NULL);
  TestInterpreterWrapper wrapper(&interpreter);

  HardwareState hs[] = {
    make_hwstate_times(1.000, 0.000),
    make_hwstate_times(1.010, 0.012),
    make_hwstate_times(1.020, 0.018),
    make_hwstate_times(1.030, 0.031),
  };

  stime_t expected_timestamps[] = { 1.000, 1.012, 1.018, 1.031 };

  for (size_t i = 0; i < arraysize(hs); i++) {
    wrapper.SyncInterpret(&hs[i], NULL);
    EXPECT_EQ(hs[i].timestamp, expected_timestamps[i]);
  }
}

TEST(TimestampFilterInterpreterTest, NoMscTimestampTest) {
  TimestampFilterInterpreterTestInterpreter* base_interpreter =
      new TimestampFilterInterpreterTestInterpreter;
  TimestampFilterInterpreter interpreter(NULL, base_interpreter, NULL);
  TestInterpreterWrapper wrapper(&interpreter);

  HardwareState hs[] = {
    make_hwstate_times(1.000, 0.000),
    make_hwstate_times(1.010, 0.000),
    make_hwstate_times(1.020, 0.000),
    make_hwstate_times(1.030, 0.000),
  };

  for (size_t i = 0; i < arraysize(hs); i++) {
    stime_t expected_timestamp = hs[i].timestamp;
    wrapper.SyncInterpret(&hs[i], NULL);
    EXPECT_EQ(hs[i].timestamp, expected_timestamp);
  }
}

TEST(TimestampFilterInterpreterTest, MscTimestampResetTest) {
  TimestampFilterInterpreterTestInterpreter* base_interpreter =
      new TimestampFilterInterpreterTestInterpreter;
  TimestampFilterInterpreter interpreter(NULL, base_interpreter, NULL);
  TestInterpreterWrapper wrapper(&interpreter);

  HardwareState hs[] = {
    make_hwstate_times(1.000, 0.000),
    make_hwstate_times(1.010, 0.012),
    make_hwstate_times(1.020, 0.018),
    make_hwstate_times(1.030, 0.031),
    make_hwstate_times(3.000, 0.000),  // msc_timestamp reset to 0
    make_hwstate_times(3.010, 0.008),
    make_hwstate_times(3.020, 0.020),
    make_hwstate_times(3.030, 0.035),
  };

  stime_t expected_timestamps[] = {
    1.000, 1.012, 1.018, 1.031,
    3.000, 3.008, 3.020, 3.035
  };

  for (size_t i = 0; i < arraysize(hs); i++) {
    wrapper.SyncInterpret(&hs[i], NULL);
    EXPECT_EQ(hs[i].timestamp, expected_timestamps[i]);
  }
}

TEST(TimestampFilterInterpreterTest, FakeTimestampTest) {
  TimestampFilterInterpreterTestInterpreter* base_interpreter =
      new TimestampFilterInterpreterTestInterpreter;
  TimestampFilterInterpreter interpreter(NULL, base_interpreter, NULL);
  TestInterpreterWrapper wrapper(&interpreter);

  interpreter.fake_timestamp_delta_.val_ = 0.010;

  HardwareState hs[] = {
    make_hwstate_times(1.000, 0.002),
    make_hwstate_times(1.002, 6.553),
    make_hwstate_times(1.008, 0.001),
    make_hwstate_times(1.031, 0.001),
  };

  stime_t expected_timestamps[] = { 1.000, 1.010, 1.020, 1.030 };

  for (size_t i = 0; i < arraysize(hs); i++) {
    wrapper.SyncInterpret(&hs[i], NULL);
    EXPECT_TRUE(DoubleEq(hs[i].timestamp, expected_timestamps[i]));
  }
}

TEST(TimestampFilterInterpreterTest, FakeTimestampJumpForwardTest) {
  TimestampFilterInterpreterTestInterpreter* base_interpreter =
      new TimestampFilterInterpreterTestInterpreter;
  TimestampFilterInterpreter interpreter(NULL, base_interpreter, NULL);
  TestInterpreterWrapper wrapper(&interpreter);

  interpreter.fake_timestamp_delta_.val_ = 0.010;

  HardwareState hs[] = {
    make_hwstate_times(1.000, 0.002),
    make_hwstate_times(1.002, 6.553),
    make_hwstate_times(1.008, 0.001),
    make_hwstate_times(1.031, 0.001),
    make_hwstate_times(2.000, 6.552),
    make_hwstate_times(2.002, 6.553),
    make_hwstate_times(2.008, 0.002),
    make_hwstate_times(2.031, 0.001),
  };

  stime_t expected_timestamps[] = {
    1.000, 1.010, 1.020, 1.030,
    2.000, 2.010, 2.020, 2.030
  };

  for (size_t i = 0; i < arraysize(hs); i++) {
    wrapper.SyncInterpret(&hs[i], NULL);
    EXPECT_TRUE(DoubleEq(hs[i].timestamp, expected_timestamps[i]));
  }
}

TEST(TimestampFilterInterpreterTest, FakeTimestampFallBackwardTest) {
  TimestampFilterInterpreterTestInterpreter* base_interpreter =
      new TimestampFilterInterpreterTestInterpreter;
  TimestampFilterInterpreter interpreter(NULL, base_interpreter, NULL);
  TestInterpreterWrapper wrapper(&interpreter);

  interpreter.fake_timestamp_delta_.val_ = 0.010;
  interpreter.fake_timestamp_max_divergence_ = 0.030;

  HardwareState hs[] = {
    make_hwstate_times(1.000, 0.002),
    make_hwstate_times(1.001, 6.553),
    make_hwstate_times(1.002, 0.001),
    make_hwstate_times(1.003, 0.001),
    make_hwstate_times(1.004, 6.552),
    make_hwstate_times(1.005, 6.553),
    make_hwstate_times(1.006, 0.002),
    make_hwstate_times(1.007, 6.552),
  };

  stime_t expected_timestamps[] = {
    1.000, 1.010, 1.020, 1.030,
    1.004, 1.014, 1.024, 1.034,
  };

  for (size_t i = 0; i < arraysize(hs); i++) {
    wrapper.SyncInterpret(&hs[i], NULL);
    EXPECT_TRUE(DoubleEq(hs[i].timestamp, expected_timestamps[i]));
  }
}
}  // namespace gestures
