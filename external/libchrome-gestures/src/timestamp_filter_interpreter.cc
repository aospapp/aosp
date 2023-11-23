// Copyright 2017 The ChromiumOS Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "include/timestamp_filter_interpreter.h"

#include <math.h>

#include "include/logging.h"
#include "include/tracer.h"

namespace gestures {

TimestampFilterInterpreter::TimestampFilterInterpreter(
    PropRegistry* prop_reg, Interpreter* next, Tracer* tracer)
    : FilterInterpreter(NULL, next, tracer, false),
      prev_msc_timestamp_(-1.0),
      msc_timestamp_offset_(-1.0),
      fake_timestamp_(-1.0),
      fake_timestamp_max_divergence_(0.1),
      skew_(0.0),
      fake_timestamp_delta_(prop_reg, "Fake Timestamp Delta", 0.0) {
  InitName();
}

void TimestampFilterInterpreter::SyncInterpretImpl(
    HardwareState* hwstate, stime_t* timeout) {
  if (fake_timestamp_delta_.val_ == 0.0)
    ChangeTimestampDefault(hwstate);
  else
    ChangeTimestampUsingFake(hwstate);
  next_->SyncInterpret(hwstate, timeout);
}

void TimestampFilterInterpreter::ChangeTimestampDefault(
    HardwareState* hwstate) {
  // Check if this is the first event or there has been a jump backwards.
  if (prev_msc_timestamp_ < 0.0 ||
      hwstate->msc_timestamp == 0.0 ||
      hwstate->msc_timestamp < prev_msc_timestamp_) {
    msc_timestamp_offset_ = hwstate->timestamp - hwstate->msc_timestamp;
  }
  prev_msc_timestamp_ = hwstate->msc_timestamp;

  stime_t new_timestamp = hwstate->msc_timestamp + msc_timestamp_offset_;
  skew_ = new_timestamp - hwstate->timestamp;
  hwstate->timestamp = new_timestamp;

  hwstate->msc_timestamp = 0.0;
}

void TimestampFilterInterpreter::ChangeTimestampUsingFake(
    HardwareState* hwstate) {
  fake_timestamp_ += fake_timestamp_delta_.val_;
  if (fabs(fake_timestamp_ - hwstate->timestamp) >
      fake_timestamp_max_divergence_)
    fake_timestamp_ = hwstate->timestamp;

  skew_ = fake_timestamp_ - hwstate->timestamp;
  hwstate->timestamp = fake_timestamp_;
}

void TimestampFilterInterpreter::ConsumeGesture(const Gesture& gs) {
  // Adjust gesture timestamp by latest skew to match browser clock
  Gesture copy = gs;
  copy.start_time -= skew_;
  copy.end_time -= skew_;
  ProduceGesture(copy);
}

}  // namespace gestures
