/*
 * Copyright 2021 The Android Open Source Project
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

/*
 * Generated mock file from original source file
 *   Functions generated:8
 */

#include <map>
#include <string>

#include "common/message_loop_thread.h"
#include "common/repeating_timer.h"
#include "common/time_util.h"
#include "test/common/mock_functions.h"

#ifndef UNUSED_ATTR
#define UNUSED_ATTR
#endif

using namespace bluetooth::common;

RepeatingTimer::~RepeatingTimer() { inc_func_call_count(__func__); }
bool RepeatingTimer::IsScheduled() const {
  inc_func_call_count(__func__);
  return false;
}
bool RepeatingTimer::SchedulePeriodic(
    const base::WeakPtr<MessageLoopThread>& thread,
    const base::Location& from_here, base::Closure task,
    base::TimeDelta period) {
  inc_func_call_count(__func__);
  return false;
}
void RepeatingTimer::Cancel() {
  inc_func_call_count(__func__);
  expected_time_next_task_us_ = 0;
}
void RepeatingTimer::CancelAndWait() { inc_func_call_count(__func__); }
void RepeatingTimer::CancelClosure(std::promise<void> promise) {
  inc_func_call_count(__func__);
}
void RepeatingTimer::CancelHelper(std::promise<void> promise) {
  inc_func_call_count(__func__);
}
void RepeatingTimer::RunTask() { inc_func_call_count(__func__); }
