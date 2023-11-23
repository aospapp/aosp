// Copyright 2012 The ChromiumOS Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include <string>

#include <gtest/gtest.h>

#include "include/activity_replay.h"
#include "include/file_util.h"
#include "include/gestures.h"
#include "include/interpreter.h"
#include "include/logging_filter_interpreter.h"
#include "include/prop_registry.h"
#include "include/unittest_util.h"
using std::string;

namespace gestures {

class LoggingFilterInterpreterTest : public ::testing::Test {};

class LoggingFilterInterpreterResetLogTestInterpreter : public Interpreter {
 public:
  LoggingFilterInterpreterResetLogTestInterpreter()
      : Interpreter(NULL, NULL, false) {}
 protected:
  virtual void SyncInterpretImpl(HardwareState* hwstate,
                                     stime_t* timeout) {}
  virtual void SetHardwarePropertiesImpl(const HardwareProperties& hw_props) {
  }
  virtual void HandleTimerImpl(stime_t now, stime_t* timeout) {}
};

TEST(LoggingFilterInterpreterTest, LogResetHandlerTest) {
  PropRegistry prop_reg;
  LoggingFilterInterpreterResetLogTestInterpreter* base_interpreter =
      new LoggingFilterInterpreterResetLogTestInterpreter();
  LoggingFilterInterpreter interpreter(&prop_reg, base_interpreter, NULL);

  interpreter.event_logging_enable_.SetValue(Json::Value(true));
  interpreter.BoolWasWritten(&interpreter.event_logging_enable_);

  HardwareProperties hwprops = {
    0, 0, 100, 100,  // left, top, right, bottom
    10,  // x res (pixels/mm)
    10,  // y res (pixels/mm)
    133, 133,  // scrn DPI X, Y
    -1,  // orientation minimum
    2,   // orientation maximum
    2, 5,  // max fingers, max_touch,
    1, 0, 0,  // t5r2, semi, button pad
    0, 0,  // has wheel, vertical wheel is high resolution
    0,  // haptic pad
  };

  TestInterpreterWrapper wrapper(&interpreter, &hwprops);
  FingerState finger_state = {
    // TM, Tm, WM, Wm, Press, Orientation, X, Y, TrID
    0, 0, 0, 0, 10, 0, 50, 50, 1, 0
  };
  HardwareState hardware_state = make_hwstate(200000, 0, 1, 1, &finger_state);
  stime_t timeout = NO_DEADLINE;
  wrapper.SyncInterpret(&hardware_state, &timeout);
  EXPECT_EQ(interpreter.log_->size(), 1);

  wrapper.SyncInterpret(&hardware_state, &timeout);
  EXPECT_EQ(interpreter.log_->size(), 2);

  // Assume the ResetLog property is set.
  interpreter.logging_reset_.HandleGesturesPropWritten();
  EXPECT_EQ(interpreter.log_->size(), 0);

  wrapper.SyncInterpret(&hardware_state, &timeout);
  EXPECT_EQ(interpreter.log_->size(), 1);

  std::string str = interpreter.EncodeActivityLog();
  EXPECT_NE(0, str.size());

  const char* filename = "testlog.dump";
  interpreter.Dump(filename);

  std::string read_str = "";
  bool couldRead = ReadFileToString(filename, &read_str);

  EXPECT_TRUE(couldRead);
  EXPECT_NE(0, read_str.size());
}
}  // namespace gestures
