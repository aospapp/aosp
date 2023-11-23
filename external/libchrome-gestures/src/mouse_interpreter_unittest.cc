// Copyright 2012 The ChromiumOS Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include <gtest/gtest.h>

#include "include/gestures.h"
#include "include/mouse_interpreter.h"
#include "include/unittest_util.h"
#include "include/util.h"

namespace gestures {

HardwareProperties make_hwprops_for_mouse(
    unsigned has_wheel, unsigned wheel_is_hi_res) {
  return {
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,  // touch-specific properties
    has_wheel,
    wheel_is_hi_res,
    0,  // is_haptic_pad
  };
}

class MouseInterpreterTest : public ::testing::Test {};

TEST(MouseInterpreterTest, SimpleTest) {
  HardwareProperties hwprops = make_hwprops_for_mouse(1, 0);
  MouseInterpreter mi(NULL, NULL);
  TestInterpreterWrapper wrapper(&mi, &hwprops);
  Gesture* gs;

  HardwareState hwstates[] = {
    { 200000, 0, 0, 0, NULL, 0, 0, 0, 0, 0, 0.0 },
    { 210000, 0, 0, 0, NULL, 9, -7, 0, 0, 0, 0.0 },
    { 220000, 1, 0, 0, NULL, 0, 0, 0, 0, 0, 0.0 },
    { 230000, 0, 0, 0, NULL, 0, 0, 0, 0, 0, 0.0 },
    { 240000, 0, 0, 0, NULL, 0, 0, -3, -360, 4, 0.0 },
  };

  mi.output_mouse_wheel_gestures_.val_ = true;

  gs = wrapper.SyncInterpret(&hwstates[0], NULL);
  EXPECT_EQ(reinterpret_cast<Gesture*>(NULL), gs);

  gs = wrapper.SyncInterpret(&hwstates[1], NULL);
  ASSERT_NE(reinterpret_cast<Gesture*>(NULL), gs);
  EXPECT_EQ(kGestureTypeMove, gs->type);
  EXPECT_EQ(9, gs->details.move.dx);
  EXPECT_EQ(-7, gs->details.move.dy);
  EXPECT_EQ(200000, gs->start_time);
  EXPECT_EQ(210000, gs->end_time);

  gs = wrapper.SyncInterpret(&hwstates[2], NULL);
  ASSERT_NE(reinterpret_cast<Gesture*>(NULL), gs);
  EXPECT_EQ(kGestureTypeButtonsChange, gs->type);
  EXPECT_EQ(1, gs->details.buttons.down);
  EXPECT_EQ(0, gs->details.buttons.up);
  EXPECT_EQ(210000, gs->start_time);
  EXPECT_EQ(220000, gs->end_time);

  gs = wrapper.SyncInterpret(&hwstates[3], NULL);
  ASSERT_NE(reinterpret_cast<Gesture*>(NULL), gs);
  EXPECT_EQ(kGestureTypeButtonsChange, gs->type);
  EXPECT_EQ(0, gs->details.buttons.down);
  EXPECT_EQ(1, gs->details.buttons.up);
  EXPECT_EQ(220000, gs->start_time);
  EXPECT_EQ(230000, gs->end_time);

  gs = wrapper.SyncInterpret(&hwstates[4], NULL);
  ASSERT_NE(reinterpret_cast<Gesture*>(NULL), gs);
  EXPECT_EQ(kGestureTypeMouseWheel, gs->type);
  EXPECT_LT(-1, gs->details.wheel.dx);
  EXPECT_GT(1, gs->details.wheel.dy);
  EXPECT_EQ(240000, gs->start_time);
  EXPECT_EQ(240000, gs->end_time);
}

TEST(MouseInterpreterTest, HighResolutionVerticalScrollTest) {
  HardwareProperties hwprops = make_hwprops_for_mouse(1, 1);
  MouseInterpreter mi(NULL, NULL);
  TestInterpreterWrapper wrapper(&mi, &hwprops);
  Gesture* gs;

  HardwareState hwstates[] = {
    { 200000, 0, 0, 0, NULL, 0, 0,  0,   0, 0, 0.0 },
    { 210000, 0, 0, 0, NULL, 0, 0,  0, -15, 0, 0.0 },
    { 220000, 0, 0, 0, NULL, 0, 0, -1, -15, 0, 0.0 },
    { 230000, 0, 0, 0, NULL, 0, 0,  0,-120, 0, 0.0 },
    { 240000, 0, 0, 0, NULL, 0, 0, -1,   0, 0, 0.0 },
  };

  mi.output_mouse_wheel_gestures_.val_ = true;
  mi.hi_res_scrolling_.val_ = 1;

  gs = wrapper.SyncInterpret(&hwstates[0], NULL);
  EXPECT_EQ(reinterpret_cast<Gesture*>(NULL), gs);

  gs = wrapper.SyncInterpret(&hwstates[1], NULL);
  ASSERT_NE(reinterpret_cast<Gesture*>(NULL), gs);
  EXPECT_EQ(kGestureTypeMouseWheel, gs->type);
  EXPECT_EQ(0, gs->details.wheel.dx);
  float offset_of_8th_notch_scroll = gs->details.wheel.dy;
  EXPECT_LT(1, offset_of_8th_notch_scroll);

  gs = wrapper.SyncInterpret(&hwstates[2], NULL);
  ASSERT_NE(reinterpret_cast<Gesture*>(NULL), gs);
  EXPECT_EQ(kGestureTypeMouseWheel, gs->type);
  EXPECT_EQ(0, gs->details.wheel.dx);
  // Having a low-res scroll event as well as the high-resolution one shouldn't
  // change the output value.
  EXPECT_NEAR(offset_of_8th_notch_scroll, gs->details.wheel.dy, 0.1);

  gs = wrapper.SyncInterpret(&hwstates[3], NULL);
  ASSERT_NE(reinterpret_cast<Gesture*>(NULL), gs);
  EXPECT_EQ(kGestureTypeMouseWheel, gs->type);
  EXPECT_EQ(0, gs->details.wheel.dx);
  float offset_of_high_res_scroll = gs->details.wheel.dy;

  mi.hi_res_scrolling_.val_ = 0;

  gs = wrapper.SyncInterpret(&hwstates[4], NULL);
  ASSERT_NE(reinterpret_cast<Gesture*>(NULL), gs);
  EXPECT_EQ(kGestureTypeMouseWheel, gs->type);
  EXPECT_EQ(0, gs->details.wheel.dx);
  // A high-res scroll should yield the same offset as a low-res one with
  // proper unit conversion.
  EXPECT_NEAR(offset_of_high_res_scroll, gs->details.wheel.dy, 0.1);
}

TEST(MouseInterpreterTest, JankyScrollTest) {
  HardwareProperties hwprops = make_hwprops_for_mouse(1, 0);
  MouseInterpreter mi(NULL, NULL);
  TestInterpreterWrapper wrapper(&mi, &hwprops);
  Gesture* gs;

  // Because we do not allow time deltas less than 8ms when calculating scroll
  // acceleration, the last two scroll events should give the same dy
  // (timestamp is in units of seconds)
  HardwareState hwstates[] = {
    { 200000,      0, 0, 0, NULL, 0, 0, -1, 0, 0, 0.0 },
    { 200000.008,  0, 0, 0, NULL, 0, 0, -1, 0, 0, 0.0 },
    { 200000.0085, 0, 0, 0, NULL, 0, 0, -1, 0, 0, 0.0 },
  };

  mi.output_mouse_wheel_gestures_.val_ = true;

  gs = wrapper.SyncInterpret(&hwstates[0], NULL);
  ASSERT_NE(reinterpret_cast<Gesture*>(NULL), gs);
  EXPECT_EQ(kGestureTypeMouseWheel, gs->type);
  EXPECT_EQ(0, gs->details.wheel.dx);
  // Ignore the dy from the first scroll event, as the gesture interpreter
  // hardcodes that time delta to 1 second, making it invalid for this test.

  gs = wrapper.SyncInterpret(&hwstates[1], NULL);
  ASSERT_NE(reinterpret_cast<Gesture*>(NULL), gs);
  EXPECT_EQ(kGestureTypeMouseWheel, gs->type);
  EXPECT_EQ(0, gs->details.wheel.dx);
  float scroll_offset = gs->details.wheel.dy;

  gs = wrapper.SyncInterpret(&hwstates[2], NULL);
  ASSERT_NE(reinterpret_cast<Gesture*>(NULL), gs);
  EXPECT_EQ(kGestureTypeMouseWheel, gs->type);
  EXPECT_EQ(0, gs->details.wheel.dx);

  EXPECT_NEAR(scroll_offset, gs->details.wheel.dy, 0.1);
}

TEST(MouseInterpreterTest, WheelTickReportingHighResTest) {
  HardwareProperties hwprops = make_hwprops_for_mouse(1, 1);
  MouseInterpreter mi(NULL, NULL);
  TestInterpreterWrapper wrapper(&mi, &hwprops);
  Gesture* gs;

  HardwareState hwstates[] = {
    { 200000, 0, 0, 0, NULL, 0, 0, 0,   0, 0, 0.0 },
    { 210000, 0, 0, 0, NULL, 0, 0, 0, -30, 0, 0.0 },
  };

  mi.output_mouse_wheel_gestures_.val_ = true;
  mi.hi_res_scrolling_.val_ = true;

  gs = wrapper.SyncInterpret(&hwstates[0], NULL);
  EXPECT_EQ(reinterpret_cast<Gesture*>(NULL), gs);

  gs = wrapper.SyncInterpret(&hwstates[1], NULL);
  ASSERT_NE(reinterpret_cast<Gesture*>(NULL), gs);
  EXPECT_EQ(kGestureTypeMouseWheel, gs->type);
  EXPECT_EQ( 0, gs->details.wheel.tick_120ths_dx);
  EXPECT_EQ(30, gs->details.wheel.tick_120ths_dy);
}

TEST(MouseInterpreterTest, WheelTickReportingLowResTest) {
  HardwareProperties hwprops = make_hwprops_for_mouse(1, 0);
  MouseInterpreter mi(NULL, NULL);
  TestInterpreterWrapper wrapper(&mi, &hwprops);
  Gesture* gs;

  HardwareState hwstates[] = {
    { 200000, 0, 0, 0, NULL, 0, 0, 0, 0, 0, 0.0 },
    { 210000, 0, 0, 0, NULL, 0, 0, 1, 0, 0, 0.0 },
    { 210000, 0, 0, 0, NULL, 0, 0, 0, 0, 1, 0.0 },
  };

  mi.output_mouse_wheel_gestures_.val_ = true;
  mi.hi_res_scrolling_.val_ = false;

  gs = wrapper.SyncInterpret(&hwstates[0], NULL);
  EXPECT_EQ(reinterpret_cast<Gesture*>(NULL), gs);

  gs = wrapper.SyncInterpret(&hwstates[1], NULL);
  ASSERT_NE(reinterpret_cast<Gesture*>(NULL), gs);
  EXPECT_EQ(kGestureTypeMouseWheel, gs->type);
  EXPECT_EQ(   0, gs->details.wheel.tick_120ths_dx);
  EXPECT_EQ(-120, gs->details.wheel.tick_120ths_dy);

  gs = wrapper.SyncInterpret(&hwstates[2], NULL);
  ASSERT_NE(reinterpret_cast<Gesture*>(NULL), gs);
  EXPECT_EQ(kGestureTypeMouseWheel, gs->type);
  EXPECT_EQ(120, gs->details.wheel.tick_120ths_dx);
  EXPECT_EQ(  0, gs->details.wheel.tick_120ths_dy);
}

}  // namespace gestures
