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

#include <aidl/test_package/BnTest.h>

#include <stdio.h>
#include <unistd.h>
#include <condition_variable>
#include <mutex>

#include "utilities.h"

using IEmpty = ::aidl::test_package::IEmpty;
using RegularPolygon = ::aidl::test_package::RegularPolygon;
using Foo = ::aidl::test_package::Foo;
using Bar = ::aidl::test_package::Bar;

class MyTest : public ::aidl::test_package::BnTest,
               public ThisShouldBeDestroyed {
 public:
  binder_status_t dump(int fd, const char** args, uint32_t numArgs) override {
    for (uint32_t i = 0; i < numArgs; i++) {
      dprintf(fd, "%s", args[i]);
    }
    fsync(fd);
    return STATUS_OK;
  }

  ::ndk::ScopedAStatus GetName(std::string* _aidl_return) override {
    *_aidl_return = "CPP";
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus TestVoidReturn() override {
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus TestOneway() override {
    // This return code should be ignored since it is oneway.
    return ::ndk::ScopedAStatus(AStatus_fromStatus(STATUS_UNKNOWN_ERROR));
  }

  ::ndk::ScopedAStatus GiveMeMyCallingPid(int32_t* _aidl_return) override {
    *_aidl_return = AIBinder_getCallingPid();
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus GiveMeMyCallingUid(int32_t* _aidl_return) override {
    *_aidl_return = AIBinder_getCallingUid();
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }

 private:
  bool mCached = false;
  std::mutex mCachedMutex;
  std::condition_variable mCachedCondition;
  int mCachedPid = -1;
  int mCachedUid = -1;

 public:
  ::ndk::ScopedAStatus CacheCallingInfoFromOneway() override {
    std::unique_lock<std::mutex> l(mCachedMutex);
    mCached = true;
    mCachedPid = AIBinder_getCallingPid();
    mCachedUid = AIBinder_getCallingUid();
    mCachedCondition.notify_all();

    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus GiveMeMyCallingPidFromOneway(
      int32_t* _aidl_return) override {
    std::unique_lock<std::mutex> l(mCachedMutex);
    mCachedCondition.wait(l, [&] { return mCached; });

    *_aidl_return = mCachedPid;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus GiveMeMyCallingUidFromOneway(
      int32_t* _aidl_return) override {
    std::unique_lock<std::mutex> l(mCachedMutex);
    mCachedCondition.wait(l, [&] { return mCached; });

    *_aidl_return = mCachedUid;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatInt(int32_t in_value,
                                 int32_t* _aidl_return) override {
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatLong(int64_t in_value,
                                  int64_t* _aidl_return) override {
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatFloat(float in_value,
                                   float* _aidl_return) override {
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatDouble(double in_value,
                                    double* _aidl_return) override {
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatBoolean(bool in_value,
                                     bool* _aidl_return) override {
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatChar(char16_t in_value,
                                  char16_t* _aidl_return) override {
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatByte(int8_t in_value,
                                  int8_t* _aidl_return) override {
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatBinder(const ::ndk::SpAIBinder& in_value,
                                    ::ndk::SpAIBinder* _aidl_return) override {
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatNullableBinder(
      const ::ndk::SpAIBinder& in_value,
      ::ndk::SpAIBinder* _aidl_return) override {
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatInterface(
      const std::shared_ptr<IEmpty>& in_value,
      std::shared_ptr<IEmpty>* _aidl_return) override {
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatNullableInterface(
      const std::shared_ptr<IEmpty>& in_value,
      std::shared_ptr<IEmpty>* _aidl_return) override {
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatFd(
      const ::ndk::ScopedFileDescriptor& in_value,
      ::ndk::ScopedFileDescriptor* _aidl_return) override {
    _aidl_return->set(dup(in_value.get()));
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatNullableFd(
      const ::ndk::ScopedFileDescriptor& in_value,
      ::ndk::ScopedFileDescriptor* _aidl_return) override {
    _aidl_return->set(dup(in_value.get()));
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatString(const std::string& in_value,
                                    std::string* _aidl_return) override {
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatNullableString(
      const std::optional<std::string>& in_value,
      std::optional<std::string>* _aidl_return) override {
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatPolygon(const RegularPolygon& in_value,
                                     RegularPolygon* _aidl_return) override {
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RenamePolygon(RegularPolygon* value,
                                     const std::string& newName) override {
    value->name = newName;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatBooleanArray(
      const std::vector<bool>& in_value, std::vector<bool>* out_repeated,
      std::vector<bool>* _aidl_return) override {
    *out_repeated = in_value;
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatByteArray(
      const std::vector<int8_t>& in_value, std::vector<int8_t>* out_repeated,
      std::vector<int8_t>* _aidl_return) override {
    *out_repeated = in_value;
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatCharArray(
      const std::vector<char16_t>& in_value,
      std::vector<char16_t>* out_repeated,
      std::vector<char16_t>* _aidl_return) override {
    *out_repeated = in_value;
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatIntArray(
      const std::vector<int32_t>& in_value, std::vector<int32_t>* out_repeated,
      std::vector<int32_t>* _aidl_return) override {
    *out_repeated = in_value;
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatLongArray(
      const std::vector<int64_t>& in_value, std::vector<int64_t>* out_repeated,
      std::vector<int64_t>* _aidl_return) override {
    *out_repeated = in_value;
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatFloatArray(
      const std::vector<float>& in_value, std::vector<float>* out_repeated,
      std::vector<float>* _aidl_return) override {
    *out_repeated = in_value;
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatDoubleArray(
      const std::vector<double>& in_value, std::vector<double>* out_repeated,
      std::vector<double>* _aidl_return) override {
    *out_repeated = in_value;
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatStringArray(
      const std::vector<std::string>& in_value,
      std::vector<std::string>* out_repeated,
      std::vector<std::string>* _aidl_return) override {
    *out_repeated = in_value;
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatRegularPolygonArray(
      const std::vector<RegularPolygon>& in_value, std::vector<RegularPolygon>* out_repeated,
      std::vector<RegularPolygon>* _aidl_return) override {
    *out_repeated = in_value;
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatNullableBooleanArray(
      const std::optional<std::vector<bool>>& in_value,
      std::optional<std::vector<bool>>* _aidl_return) override {
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatNullableByteArray(
      const std::optional<std::vector<int8_t>>& in_value,
      std::optional<std::vector<int8_t>>* _aidl_return) override {
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatNullableCharArray(
      const std::optional<std::vector<char16_t>>& in_value,
      std::optional<std::vector<char16_t>>* _aidl_return) override {
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatNullableIntArray(
      const std::optional<std::vector<int32_t>>& in_value,
      std::optional<std::vector<int32_t>>* _aidl_return) override {
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatNullableLongArray(
      const std::optional<std::vector<int64_t>>& in_value,
      std::optional<std::vector<int64_t>>* _aidl_return) override {
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatNullableFloatArray(
      const std::optional<std::vector<float>>& in_value,
      std::optional<std::vector<float>>* _aidl_return) override {
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatNullableDoubleArray(
      const std::optional<std::vector<double>>& in_value,
      std::optional<std::vector<double>>* _aidl_return) override {
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus RepeatNullableStringArray(
      const std::optional<std::vector<std::optional<std::string>>>& in_value,
      std::optional<std::vector<std::optional<std::string>>>* _aidl_return) {
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
  ::ndk::ScopedAStatus DoubleRepeatNullableStringArray(
      const std::optional<std::vector<std::optional<std::string>>>& in_value,
      std::optional<std::vector<std::optional<std::string>>>* out_repeated,
      std::optional<std::vector<std::optional<std::string>>>* _aidl_return)
      override {
    *out_repeated = in_value;
    *_aidl_return = in_value;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
#ifndef USING_VERSION_1
  // All methods added from now on should be within this macro
  ::ndk::ScopedAStatus NewMethodThatReturns10(int32_t* _aidl_return) override {
    *_aidl_return = 10;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
#endif

  ::ndk::ScopedAStatus repeatFoo(const Foo& in_inFoo, Foo* _aidl_return) {
    *_aidl_return = in_inFoo;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }

  ::ndk::ScopedAStatus renameFoo(Foo* in_foo, const std::string& in_name) {
    in_foo->a = in_name;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }

  ::ndk::ScopedAStatus renameBar(Foo* in_foo, const std::string& in_name) {
    in_foo->d.a = in_name;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }

  ::ndk::ScopedAStatus getF(const Foo& foo, int32_t* _aidl_return) {
    *_aidl_return = foo.f;
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
};
