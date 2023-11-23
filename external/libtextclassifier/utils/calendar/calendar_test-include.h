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

// This is a shared test between icu and javaicu calendar implementations.
// It is meant to be #include'd.

#ifndef LIBTEXTCLASSIFIER_UTILS_CALENDAR_CALENDAR_TEST_INCLUDE_H_
#define LIBTEXTCLASSIFIER_UTILS_CALENDAR_CALENDAR_TEST_INCLUDE_H_

#if defined TC3_CALENDAR_ICU
#include "utils/calendar/calendar-icu.h"
#define TC3_TESTING_CREATE_CALENDARLIB_INSTANCE(VAR) VAR()
#elif defined TC3_CALENDAR_JAVAICU
#include <jni.h>
extern JNIEnv* g_jenv;
#define TC3_TESTING_CREATE_CALENDARLIB_INSTANCE(VAR) \
  VAR(JniCache::Create(g_jenv))
#include "utils/calendar/calendar-javaicu.h"
#else
#error Unsupported calendar implementation.
#endif
#include "utils/base/logging.h"

#include "gtest/gtest.h"

// This can get overridden in the javaicu version which needs to pass an JNIEnv*
// argument to the constructor.
#ifndef TC3_TESTING_CREATE_CALENDARLIB_INSTANCE

#endif

namespace libtextclassifier3 {
namespace test_internal {

class CalendarTest : public ::testing::Test {
 protected:
  CalendarTest() : TC3_TESTING_CREATE_CALENDARLIB_INSTANCE(calendarlib_) {}
  CalendarLib calendarlib_;
};

}  // namespace test_internal
}  // namespace libtextclassifier3

#endif  // LIBTEXTCLASSIFIER_UTILS_CALENDAR_CALENDAR_TEST_INCLUDE_H_
