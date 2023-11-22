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

#include "utils/calendar/calendar-javaicu.h"

#include "annotator/types.h"
#include "utils/java/scoped_local_ref.h"

namespace libtextclassifier3 {
namespace {

// Generic version of icu::Calendar::add with error checking.
bool CalendarAdd(JniCache* jni_cache, JNIEnv* jenv, jobject calendar,
                 jint field, jint value) {
  jenv->CallVoidMethod(calendar, jni_cache->calendar_add, field, value);
  return !jni_cache->ExceptionCheckAndClear();
}

// Generic version of icu::Calendar::get with error checking.
bool CalendarGet(JniCache* jni_cache, JNIEnv* jenv, jobject calendar,
                 jint field, jint* value) {
  *value = jenv->CallIntMethod(calendar, jni_cache->calendar_get, field);
  return !jni_cache->ExceptionCheckAndClear();
}

// Generic version of icu::Calendar::set with error checking.
bool CalendarSet(JniCache* jni_cache, JNIEnv* jenv, jobject calendar,
                 jint field, jint value) {
  jenv->CallVoidMethod(calendar, jni_cache->calendar_set, field, value);
  return !jni_cache->ExceptionCheckAndClear();
}

// Extracts the first tag from a BCP47 tag (e.g. "en" for "en-US").
std::string GetFirstBcp47Tag(const std::string& tag) {
  for (size_t i = 0; i < tag.size(); ++i) {
    if (tag[i] == '_' || tag[i] == '-') {
      return std::string(tag, 0, i);
    }
  }
  return tag;
}

}  // anonymous namespace

Calendar::Calendar(JniCache* jni_cache)
    : jni_cache_(jni_cache),
      jenv_(jni_cache_ ? jni_cache->GetEnv() : nullptr) {}

bool Calendar::Initialize(const std::string& time_zone,
                          const std::string& locale, int64 time_ms_utc) {
  if (!jni_cache_ || !jenv_) {
    TC3_LOG(ERROR) << "Initialize without env";
    return false;
  }

  // We'll assume the day indices match later on, so verify it here.
  if (jni_cache_->calendar_sunday !=
          static_cast<int>(DateParseData::RelationType::SUNDAY) ||
      jni_cache_->calendar_monday !=
          static_cast<int>(DateParseData::RelationType::MONDAY) ||
      jni_cache_->calendar_tuesday !=
          static_cast<int>(DateParseData::RelationType::TUESDAY) ||
      jni_cache_->calendar_wednesday !=
          static_cast<int>(DateParseData::RelationType::WEDNESDAY) ||
      jni_cache_->calendar_thursday !=
          static_cast<int>(DateParseData::RelationType::THURSDAY) ||
      jni_cache_->calendar_friday !=
          static_cast<int>(DateParseData::RelationType::FRIDAY) ||
      jni_cache_->calendar_saturday !=
          static_cast<int>(DateParseData::RelationType::SATURDAY)) {
    TC3_LOG(ERROR) << "day of the week indices mismatch";
    return false;
  }

  // Get the time zone.
  ScopedLocalRef<jstring> java_time_zone_str(
      jenv_->NewStringUTF(time_zone.c_str()));
  ScopedLocalRef<jobject> java_time_zone(jenv_->CallStaticObjectMethod(
      jni_cache_->timezone_class.get(), jni_cache_->timezone_get_timezone,
      java_time_zone_str.get()));
  if (jni_cache_->ExceptionCheckAndClear() || !java_time_zone) {
    TC3_LOG(ERROR) << "failed to get timezone";
    return false;
  }

  // Get the locale.
  ScopedLocalRef<jobject> java_locale;
  if (jni_cache_->locale_for_language_tag) {
    // API level 21+, we can actually parse language tags.
    ScopedLocalRef<jstring> java_locale_str(
        jenv_->NewStringUTF(locale.c_str()));
    java_locale.reset(jenv_->CallStaticObjectMethod(
        jni_cache_->locale_class.get(), jni_cache_->locale_for_language_tag,
        java_locale_str.get()));
  } else {
    // API level <21. We can't parse tags, so we just use the language.
    ScopedLocalRef<jstring> java_language_str(
        jenv_->NewStringUTF(GetFirstBcp47Tag(locale).c_str()));
    java_locale.reset(jenv_->NewObject(jni_cache_->locale_class.get(),
                                       jni_cache_->locale_init_string,
                                       java_language_str.get()));
  }
  if (jni_cache_->ExceptionCheckAndClear() || !java_locale) {
    TC3_LOG(ERROR) << "failed to get locale";
    return false;
  }

  // Get the calendar.
  calendar_.reset(jenv_->CallStaticObjectMethod(
      jni_cache_->calendar_class.get(), jni_cache_->calendar_get_instance,
      java_time_zone.get(), java_locale.get()));
  if (jni_cache_->ExceptionCheckAndClear() || !calendar_) {
    TC3_LOG(ERROR) << "failed to get calendar";
    return false;
  }

  // Set the time.
  jenv_->CallVoidMethod(calendar_.get(),
                        jni_cache_->calendar_set_time_in_millis, time_ms_utc);
  if (jni_cache_->ExceptionCheckAndClear()) {
    TC3_LOG(ERROR) << "failed to set time";
    return false;
  }
  return true;
}

bool Calendar::GetFirstDayOfWeek(int* value) const {
  if (!jni_cache_ || !jenv_ || !calendar_) return false;
  *value = jenv_->CallIntMethod(calendar_.get(),
                                jni_cache_->calendar_get_first_day_of_week);
  return !jni_cache_->ExceptionCheckAndClear();
}

bool Calendar::GetTimeInMillis(int64* value) const {
  if (!jni_cache_ || !jenv_ || !calendar_) return false;
  *value = jenv_->CallLongMethod(calendar_.get(),
                                 jni_cache_->calendar_get_time_in_millis);
  return !jni_cache_->ExceptionCheckAndClear();
}

CalendarLib::CalendarLib() {
  TC3_LOG(FATAL) << "Java ICU CalendarLib must be initialized with a JniCache.";
}

CalendarLib::CalendarLib(const std::shared_ptr<JniCache>& jni_cache)
    : jni_cache_(jni_cache) {}

// Below is the boilerplate code for implementing the specialisations of
// get/set/add for the various field types.
#define TC3_DEFINE_FIELD_ACCESSOR(NAME, FIELD, KIND, TYPE)      \
  bool Calendar::KIND##NAME(TYPE value) const {                 \
    if (!jni_cache_ || !jenv_ || !calendar_) return false;      \
    return Calendar##KIND(jni_cache_, jenv_, calendar_.get(),   \
                          jni_cache_->calendar_##FIELD, value); \
  }
#define TC3_DEFINE_ADD(NAME, CONST) \
  TC3_DEFINE_FIELD_ACCESSOR(NAME, CONST, Add, int)
#define TC3_DEFINE_SET(NAME, CONST) \
  TC3_DEFINE_FIELD_ACCESSOR(NAME, CONST, Set, int)
#define TC3_DEFINE_GET(NAME, CONST) \
  TC3_DEFINE_FIELD_ACCESSOR(NAME, CONST, Get, int*)

TC3_DEFINE_ADD(Second, second)
TC3_DEFINE_ADD(Minute, minute)
TC3_DEFINE_ADD(HourOfDay, hour_of_day)
TC3_DEFINE_ADD(DayOfMonth, day_of_month)
TC3_DEFINE_ADD(Year, year)
TC3_DEFINE_ADD(Month, month)
TC3_DEFINE_GET(DayOfWeek, day_of_week)
TC3_DEFINE_SET(ZoneOffset, zone_offset)
TC3_DEFINE_SET(DstOffset, dst_offset)
TC3_DEFINE_SET(Year, year)
TC3_DEFINE_SET(Month, month)
TC3_DEFINE_SET(DayOfYear, day_of_year)
TC3_DEFINE_SET(DayOfMonth, day_of_month)
TC3_DEFINE_SET(DayOfWeek, day_of_week)
TC3_DEFINE_SET(HourOfDay, hour_of_day)
TC3_DEFINE_SET(Minute, minute)
TC3_DEFINE_SET(Second, second)
TC3_DEFINE_SET(Millisecond, millisecond)

#undef TC3_DEFINE_FIELD_ACCESSOR
#undef TC3_DEFINE_ADD
#undef TC3_DEFINE_SET
#undef TC3_DEFINE_GET

}  // namespace libtextclassifier3
