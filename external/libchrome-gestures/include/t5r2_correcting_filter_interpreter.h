// Copyright 2012 The ChromiumOS Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "include/filter_interpreter.h"
#include "include/gestures.h"
#include "include/prop_registry.h"
#include "include/tracer.h"

#ifndef GESTURES_T5R2_CORRECTING_FILTER_INTERPRETER_H_
#define GESTURES_T5R2_CORRECTING_FILTER_INTERPRETER_H_

namespace gestures {

// T5R2 ("Track 5, Report 2") is an approach used by some old touchpads which
// can track up to five fingers but only report the locations of two of them.
// (See https://crrev.com/37cccb42e652b50f9e788d90e82252f78c78f1ed for more
// details.)
//
// This interpreter corrects some T5R2 HardwareState structures. It
// has been noticed that in some cases, fingers are removed from the
// touchpad but, a HardwareState with touch_cnt set to 0 is never
// sent. This always seems to happen when we get two HardwareStates in
// a row with the same non-zero value for touch_cnt while finger_cnt
// is 0. To address this issue, if this interpreter sees two
// HardwareStates in a row have the same non-zero touch_cnt with a
// zero finger_cnt, it sets the second touch_cnt to 0.

class T5R2CorrectingFilterInterpreter : public FilterInterpreter {
 public:
  // Takes ownership of |next|:
  T5R2CorrectingFilterInterpreter(PropRegistry* prop_reg, Interpreter* next,
                                  Tracer* tracer);
  virtual ~T5R2CorrectingFilterInterpreter() {}

 protected:
  virtual void SyncInterpretImpl(HardwareState* hwstate, stime_t* timeout);

 private:
  unsigned short last_finger_cnt_;
  unsigned short last_touch_cnt_;

  BoolProperty touch_cnt_correct_enabled_;
};

}  // namespace gestures

#endif  // GESTURES_T5R2_CORRECTING_FILTER_INTERPRETER_H_
