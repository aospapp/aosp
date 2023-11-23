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
#define LOG_TAG "Cts-NdkBinderTest"

#include <aidl/test_package/BnEmpty.h>
#include <aidl/test_package/BpTest.h>
#include <aidl/test_package/Foo.h>
#include <aidl/test_package/RegularPolygon.h>
#include <android/binder_ibinder_jni.h>
#include <gtest/gtest.h>

#include "itest_impl.h"
#include "utilities.h"

#include <stdio.h>
#include <sys/socket.h>
#include <sys/types.h>

using ::aidl::test_package::Bar;
using ::aidl::test_package::BpTest;
using ::aidl::test_package::Foo;
using ::aidl::test_package::ITest;
using ::aidl::test_package::RegularPolygon;
using ::ndk::ScopedAStatus;
using ::ndk::ScopedFileDescriptor;
using ::ndk::SharedRefBase;
using ::ndk::SpAIBinder;

// AIDL tests which are independent of the service
class NdkBinderTest_AidlLocal : public NdkBinderTest {};

TEST_F(NdkBinderTest_AidlLocal, FromBinder) {
  std::shared_ptr<MyTest> test = SharedRefBase::make<MyTest>();
  SpAIBinder binder = test->asBinder();
  EXPECT_EQ(test, ITest::fromBinder(binder));

  EXPECT_FALSE(test->isRemote());
}

struct Params {
  std::shared_ptr<ITest> iface;
  bool shouldBeRemote;
  bool shouldBeWrapped;
  std::string expectedName;
  bool shouldBeOld;
};

#define iface GetParam().iface
#define shouldBeRemote GetParam().shouldBeRemote
#define shouldBeWrapped GetParam().shouldBeWrapped

// AIDL tests which run on each type of service (local C++, local Java, remote C++, remote Java,
// etc..)
class NdkBinderTest_Aidl : public NdkBinderTest,
                           public ::testing::WithParamInterface<Params> {};

TEST_P(NdkBinderTest_Aidl, GotTest) { ASSERT_NE(nullptr, iface); }

TEST_P(NdkBinderTest_Aidl, SanityCheckSource) {
  std::string name;
  ASSERT_OK(iface->GetName(&name));
  EXPECT_EQ(GetParam().expectedName, name);
}

TEST_P(NdkBinderTest_Aidl, Remoteness) {
  ASSERT_EQ(shouldBeRemote, iface->isRemote());
}

TEST_P(NdkBinderTest_Aidl, UseBinder) {
  ASSERT_EQ(STATUS_OK, AIBinder_ping(iface->asBinder().get()));
}

bool ReadFdToString(int fd, std::string* content) {
  char buf[64];
  ssize_t n;
  while ((n = TEMP_FAILURE_RETRY(read(fd, &buf[0], sizeof(buf)))) > 0) {
    content->append(buf, n);
  }
  return (n == 0) ? true : false;
}

std::string dumpToString(std::shared_ptr<ITest> itest, std::vector<const char*> args) {
  int fd[2] = {-1, -1};
  EXPECT_EQ(0, socketpair(AF_UNIX, SOCK_STREAM, 0, fd));

  EXPECT_OK(itest->dump(fd[0], args.data(), args.size()));
  close(fd[0]);

  std::string ret;
  EXPECT_TRUE(ReadFdToString(fd[1], &ret));

  close(fd[1]);
  return ret;
}

TEST_P(NdkBinderTest_Aidl, UseDump) {
  std::string name;
  EXPECT_OK(iface->GetName(&name));
  if (name == "JAVA" && !iface->isRemote()) {
    // TODO(b/127361166): GTEST_SKIP is considered a failure, would prefer to use that here
    // TODO(b/127339049): JavaBBinder doesn't implement dump
    return;
  }

  EXPECT_EQ("", dumpToString(iface, {}));
  EXPECT_EQ("", dumpToString(iface, {"", ""}));
  EXPECT_EQ("Hello World!", dumpToString(iface, {"Hello ", "World!"}));
  EXPECT_EQ("ABC", dumpToString(iface, {"A", "B", "C"}));
}

TEST_P(NdkBinderTest_Aidl, Trivial) {
  ASSERT_OK(iface->TestVoidReturn());

  if (shouldBeWrapped) {
    ASSERT_OK(iface->TestOneway());
  } else {
    ASSERT_EQ(STATUS_UNKNOWN_ERROR, AStatus_getStatus(iface->TestOneway().get()));
  }
}

TEST_P(NdkBinderTest_Aidl, CallingInfo) {
  EXPECT_OK(iface->CacheCallingInfoFromOneway());
  int32_t res;

  EXPECT_OK(iface->GiveMeMyCallingPid(&res));
  EXPECT_EQ(getpid(), res);

  EXPECT_OK(iface->GiveMeMyCallingUid(&res));
  EXPECT_EQ(getuid(), res);

  EXPECT_OK(iface->GiveMeMyCallingPidFromOneway(&res));
  if (shouldBeRemote) {
    // PID is hidden from oneway calls
    EXPECT_EQ(0, res);
  } else {
    EXPECT_EQ(getpid(), res);
  }

  EXPECT_OK(iface->GiveMeMyCallingUidFromOneway(&res));
  EXPECT_EQ(getuid(), res);
}

TEST_P(NdkBinderTest_Aidl, Constants) {
  ASSERT_EQ(0, ITest::kZero);
  ASSERT_EQ(1, ITest::kOne);
  ASSERT_EQ(0xffffffff, ITest::kOnes);
  ASSERT_EQ(std::string(""), ITest::kEmpty);
  ASSERT_EQ(std::string("foo"), ITest::kFoo);
}

TEST_P(NdkBinderTest_Aidl, RepeatPrimitives) {
  {
    int32_t out;
    ASSERT_OK(iface->RepeatInt(3, &out));
    EXPECT_EQ(3, out);
  }

  {
    int64_t out;
    ASSERT_OK(iface->RepeatLong(3, &out));
    EXPECT_EQ(3, out);
  }

  {
    float out;
    ASSERT_OK(iface->RepeatFloat(2.0f, &out));
    EXPECT_EQ(2.0f, out);
  }

  {
    double out;
    ASSERT_OK(iface->RepeatDouble(3.0, &out));
    EXPECT_EQ(3.0, out);
  }

  {
    bool out;
    ASSERT_OK(iface->RepeatBoolean(true, &out));
    EXPECT_EQ(true, out);
  }

  {
    char16_t out;
    ASSERT_OK(iface->RepeatChar(L'@', &out));
    EXPECT_EQ(L'@', out);
  }

  {
    int8_t out;
    ASSERT_OK(iface->RepeatByte(3, &out));
    EXPECT_EQ(3, out);
  }
}

TEST_P(NdkBinderTest_Aidl, RepeatBinder) {
  SpAIBinder binder = iface->asBinder();
  SpAIBinder ret;

  ASSERT_OK(iface->RepeatBinder(binder, &ret));
  EXPECT_EQ(binder.get(), ret.get());

  ASSERT_OK(iface->RepeatNullableBinder(binder, &ret));
  EXPECT_EQ(binder.get(), ret.get());

  ASSERT_OK(iface->RepeatNullableBinder(nullptr, &ret));
  EXPECT_EQ(nullptr, ret.get());
}

TEST_P(NdkBinderTest_Aidl, RepeatInterface) {
  class MyEmpty : public ::aidl::test_package::BnEmpty {};

  std::shared_ptr<IEmpty> empty = SharedRefBase::make<MyEmpty>();

  std::shared_ptr<IEmpty> ret;
  ASSERT_OK(iface->RepeatInterface(empty, &ret));
  EXPECT_EQ(empty.get(), ret.get());

  ASSERT_OK(iface->RepeatNullableInterface(empty, &ret));
  EXPECT_EQ(empty.get(), ret.get());

  ASSERT_OK(iface->RepeatNullableInterface(nullptr, &ret));
  EXPECT_EQ(nullptr, ret.get());
}

static void checkInOut(const ScopedFileDescriptor& inFd,
                       const ScopedFileDescriptor& outFd) {
  static const std::string kContent = "asdf";

  ASSERT_EQ(static_cast<int>(kContent.size()),
            write(inFd.get(), kContent.data(), kContent.size()));

  std::string out;
  out.resize(kContent.size());
  ASSERT_EQ(static_cast<int>(kContent.size()),
            read(outFd.get(), &out[0], kContent.size()));

  EXPECT_EQ(kContent, out);
}

static void checkFdRepeat(
    const std::shared_ptr<ITest>& test,
    ScopedAStatus (ITest::*repeatFd)(const ScopedFileDescriptor&,
                                     ScopedFileDescriptor*)) {
  int fds[2];

  while (pipe(fds) == -1 && errno == EAGAIN)
    ;

  ScopedFileDescriptor readFd(fds[0]);
  ScopedFileDescriptor writeFd(fds[1]);

  ScopedFileDescriptor readOutFd;
  ASSERT_OK((test.get()->*repeatFd)(readFd, &readOutFd));

  checkInOut(writeFd, readOutFd);
}

TEST_P(NdkBinderTest_Aidl, RepeatFd) { checkFdRepeat(iface, &ITest::RepeatFd); }

TEST_P(NdkBinderTest_Aidl, RepeatNullableFd) {
  checkFdRepeat(iface, &ITest::RepeatNullableFd);

  ScopedFileDescriptor in;
  EXPECT_EQ(-1, in.get());

  ScopedFileDescriptor out;
  ASSERT_OK(iface->RepeatNullableFd(in, &out));

  EXPECT_EQ(-1, out.get());
}

TEST_P(NdkBinderTest_Aidl, RepeatString) {
  std::string res;

  EXPECT_OK(iface->RepeatString("", &res));
  EXPECT_EQ("", res);

  EXPECT_OK(iface->RepeatString("a", &res));
  EXPECT_EQ("a", res);

  EXPECT_OK(iface->RepeatString("say what?", &res));
  EXPECT_EQ("say what?", res);
}

TEST_P(NdkBinderTest_Aidl, RepeatNullableString) {
  std::optional<std::string> res;

  EXPECT_OK(iface->RepeatNullableString(std::nullopt, &res));
  EXPECT_EQ(std::nullopt, res);

  EXPECT_OK(iface->RepeatNullableString("", &res));
  EXPECT_EQ("", *res);

  EXPECT_OK(iface->RepeatNullableString("a", &res));
  EXPECT_EQ("a", *res);

  EXPECT_OK(iface->RepeatNullableString("say what?", &res));
  EXPECT_EQ("say what?", *res);
}

TEST_P(NdkBinderTest_Aidl, ParcelableDefaults) {
  RegularPolygon polygon;

  EXPECT_EQ("square", polygon.name);
  EXPECT_EQ(4, polygon.numSides);
  EXPECT_EQ(1.0f, polygon.sideLength);
}

TEST_P(NdkBinderTest_Aidl, RepeatPolygon) {
  RegularPolygon defaultPolygon = {"hexagon", 6, 2.0f};
  RegularPolygon outputPolygon;
  ASSERT_OK(iface->RepeatPolygon(defaultPolygon, &outputPolygon));
  EXPECT_EQ("hexagon", outputPolygon.name);
  EXPECT_EQ(defaultPolygon.numSides, outputPolygon.numSides);
  EXPECT_EQ(defaultPolygon.sideLength, outputPolygon.sideLength);
}

TEST_P(NdkBinderTest_Aidl, InsAndOuts) {
  RegularPolygon defaultPolygon;
  ASSERT_OK(iface->RenamePolygon(&defaultPolygon, "Jerry"));
  EXPECT_EQ("Jerry", defaultPolygon.name);
}

TEST_P(NdkBinderTest_Aidl, RenameFoo) {
  Foo foo;
  Foo outputFoo;
  ASSERT_OK(iface->renameFoo(&foo, "MYFOO"));

  EXPECT_EQ("MYFOO", foo.a);
}

TEST_P(NdkBinderTest_Aidl, RenameBar) {
  Foo foo;
  Foo outputFoo;
  ASSERT_OK(iface->renameBar(&foo, "MYBAR"));

  EXPECT_EQ("MYBAR", foo.d.a);
}

TEST_P(NdkBinderTest_Aidl, GetLastItem) {
  Foo foo;
  foo.f = 15;
  int retF;
  ASSERT_OK(iface->getF(foo, &retF));
  EXPECT_EQ(15, retF);
}

TEST_P(NdkBinderTest_Aidl, RepeatFoo) {
  Foo foo;
  foo.a = "NEW FOO";
  foo.b = 57;
  foo.d.b = "a";
  foo.e.d = 99;
  Foo retFoo;

  ASSERT_OK(iface->repeatFoo(foo, &retFoo));

  EXPECT_EQ(foo.a, retFoo.a);
  EXPECT_EQ(foo.b, retFoo.b);
  EXPECT_EQ(foo.d.b, retFoo.d.b);
  EXPECT_EQ(foo.e.d, retFoo.e.d);
}

template <typename T>
using RepeatMethod = ScopedAStatus (ITest::*)(const std::vector<T>&,
                                              std::vector<T>*, std::vector<T>*);

namespace aidl {
namespace test_package {
inline bool operator==(const RegularPolygon& lhs, const RegularPolygon& rhs) {
  return lhs.name == rhs.name && lhs.numSides == rhs.numSides && lhs.sideLength == rhs.sideLength;
}
inline bool operator==(const std::vector<RegularPolygon>& lhs,
                       const std::vector<RegularPolygon>& rhs) {
  if (lhs.size() != rhs.size()) return false;
  for (size_t i = 0; i < lhs.size(); i++) {
    if (!(lhs[i] == rhs[i])) return false;
  }
  return true;
}
}  // namespace test_package
}  // namespace aidl

template <typename T>
void testRepeat(const std::shared_ptr<ITest>& i, RepeatMethod<T> repeatMethod,
                std::vector<std::vector<T>> tests) {
  for (const auto& input : tests) {
    std::vector<T> out1;
    out1.resize(input.size());
    std::vector<T> out2;

    ASSERT_OK((i.get()->*repeatMethod)(input, &out1, &out2)) << input.size();
    EXPECT_EQ(input, out1);
    EXPECT_EQ(input, out2);
  }
}

TEST_P(NdkBinderTest_Aidl, Arrays) {
  testRepeat<bool>(iface, &ITest::RepeatBooleanArray,
                   {
                       {},
                       {true},
                       {false, true, false},
                   });
  testRepeat<int8_t>(iface, &ITest::RepeatByteArray,
                     {
                         {},
                         {1},
                         {1, 2, 3},
                     });
  testRepeat<char16_t>(iface, &ITest::RepeatCharArray,
                       {
                           {},
                           {L'@'},
                           {L'@', L'!', L'A'},
                       });
  testRepeat<int32_t>(iface, &ITest::RepeatIntArray,
                      {
                          {},
                          {1},
                          {1, 2, 3},
                      });
  testRepeat<int64_t>(iface, &ITest::RepeatLongArray,
                      {
                          {},
                          {1},
                          {1, 2, 3},
                      });
  testRepeat<float>(iface, &ITest::RepeatFloatArray,
                    {
                        {},
                        {1.0f},
                        {1.0f, 2.0f, 3.0f},
                    });
  testRepeat<double>(iface, &ITest::RepeatDoubleArray,
                     {
                         {},
                         {1.0},
                         {1.0, 2.0, 3.0},
                     });
  testRepeat<std::string>(iface, &ITest::RepeatStringArray,
                          {
                              {},
                              {"asdf"},
                              {"", "aoeu", "lol", "brb"},
                          });
  testRepeat<RegularPolygon>(iface, &ITest::RepeatRegularPolygonArray,
                             {
                                 {},
                                 {{"hexagon", 6, 2.0f}},
                                 {{"hexagon", 6, 2.0f}, {"square", 4, 7.0f}, {"pentagon", 5, 4.2f}},
                             });
}

template <typename T>
using RepeatNullableMethod = ScopedAStatus (ITest::*)(
    const std::optional<std::vector<std::optional<T>>>&,
    std::optional<std::vector<std::optional<T>>>*,
    std::optional<std::vector<std::optional<T>>>*);

template <typename T>
void testRepeat(
    const std::shared_ptr<ITest>& i, RepeatNullableMethod<T> repeatMethod,
    std::vector<std::optional<std::vector<std::optional<T>>>> tests) {
  for (const auto& input : tests) {
    std::optional<std::vector<std::optional<T>>> out1;
    if (input) {
      out1 = std::vector<std::optional<T>>{};
      out1->resize(input->size());
    }
    std::optional<std::vector<std::optional<T>>> out2;

    ASSERT_OK((i.get()->*repeatMethod)(input, &out1, &out2))
        << (input ? input->size() : -1);
    EXPECT_EQ(input, out1);
    EXPECT_EQ(input, out2);
  }
}

template <typename T>
using SingleRepeatNullableMethod = ScopedAStatus (ITest::*)(
    const std::optional<std::vector<T>>&, std::optional<std::vector<T>>*);

template <typename T>
void testRepeat(const std::shared_ptr<ITest>& i,
                SingleRepeatNullableMethod<T> repeatMethod,
                std::vector<std::optional<std::vector<T>>> tests) {
  for (const auto& input : tests) {
    std::optional<std::vector<T>> ret;
    ASSERT_OK((i.get()->*repeatMethod)(input, &ret))
        << (input ? input->size() : -1);
    EXPECT_EQ(input, ret);
  }
}

TEST_P(NdkBinderTest_Aidl, NullableArrays) {
  testRepeat<bool>(iface, &ITest::RepeatNullableBooleanArray,
                   {
                       std::nullopt,
                       {{}},
                       {{true}},
                       {{false, true, false}},
                   });
  testRepeat<int8_t>(iface, &ITest::RepeatNullableByteArray,
                     {
                         std::nullopt,
                         {{}},
                         {{1}},
                         {{1, 2, 3}},
                     });
  testRepeat<char16_t>(iface, &ITest::RepeatNullableCharArray,
                       {
                           std::nullopt,
                           {{}},
                           {{L'@'}},
                           {{L'@', L'!', L'A'}},
                       });
  testRepeat<int32_t>(iface, &ITest::RepeatNullableIntArray,
                      {
                          std::nullopt,
                          {{}},
                          {{1}},
                          {{1, 2, 3}},
                      });
  testRepeat<int64_t>(iface, &ITest::RepeatNullableLongArray,
                      {
                          std::nullopt,
                          {{}},
                          {{1}},
                          {{1, 2, 3}},
                      });
  testRepeat<float>(iface, &ITest::RepeatNullableFloatArray,
                    {
                        std::nullopt,
                        {{}},
                        {{1.0f}},
                        {{1.0f, 2.0f, 3.0f}},
                    });
  testRepeat<double>(iface, &ITest::RepeatNullableDoubleArray,
                     {
                         std::nullopt,
                         {{}},
                         {{1.0}},
                         {{1.0, 2.0, 3.0}},
                     });
  testRepeat<std::optional<std::string>>(
      iface, &ITest::RepeatNullableStringArray,
      {
          std::nullopt,
          {{}},
          {{"asdf"}},
          {{std::nullopt}},
          {{"aoeu", "lol", "brb"}},
          {{"", "aoeu", std::nullopt, "brb"}},
      });
  testRepeat<std::string>(iface, &ITest::DoubleRepeatNullableStringArray,
                          {
                              {{}},
                              {{"asdf"}},
                              {{std::nullopt}},
                              {{"aoeu", "lol", "brb"}},
                              {{"", "aoeu", std::nullopt, "brb"}},
                          });
}

class DefaultImpl : public ::aidl::test_package::ITestDefault {
 public:
  ::ndk::ScopedAStatus NewMethodThatReturns10(int32_t* _aidl_return) override {
    *_aidl_return = 100;  // default impl returns different value
    return ::ndk::ScopedAStatus(AStatus_newOk());
  }
};

TEST_P(NdkBinderTest_Aidl, NewMethod) {
  std::shared_ptr<ITest> default_impl = SharedRefBase::make<DefaultImpl>();
  ::aidl::test_package::ITest::setDefaultImpl(default_impl);

  int32_t res;
  EXPECT_OK(iface->NewMethodThatReturns10(&res));
  if (GetParam().shouldBeOld) {
    // Remote was built with version 1 interface which does not have
    // "NewMethodThatReturns10". In this case the default method
    // which returns 100 is called.
    EXPECT_EQ(100, res);
  } else {
    // Remote is built with the current version of the interface.
    // The method returns 10.
    EXPECT_EQ(10, res);
  }
}

TEST_P(NdkBinderTest_Aidl, GetInterfaceVersion) {
  int32_t res;
  EXPECT_OK(iface->getInterfaceVersion(&res));
  if (GetParam().shouldBeOld) {
    EXPECT_EQ(1, res);
  } else {
    // 10000 is the not-yet-frozen version
    EXPECT_EQ(10000, res);
  }
}

std::shared_ptr<ITest> getProxyLocalService() {
  // BpTest -> AIBinder -> test
  //
  // Warning: for testing purposes only. This parcels things within the same process for testing
  // purposes. In normal usage, this should just return SharedRefBase::make<MyTest> directly.
  std::shared_ptr<MyTest> test = SharedRefBase::make<MyTest>();
  return (new BpTest(test->asBinder()))->ref<ITest>();
}

std::shared_ptr<ITest> getNdkBinderTestJavaService(const std::string& method) {
  JNIEnv* env = GetEnv();
  if (env == nullptr) {
    std::cout << "No environment" << std::endl;
    return nullptr;
  }

  jclass cl = env->FindClass("android/binder/cts/NdkBinderTest");
  if (cl == nullptr) {
    std::cout << "No class" << std::endl;
    return nullptr;
  }

  jmethodID mid =
      env->GetStaticMethodID(cl, method.c_str(), "()Landroid/os/IBinder;");
  if (mid == nullptr) {
    std::cout << "No method id" << std::endl;
    return nullptr;
  }

  jobject object = env->CallStaticObjectMethod(cl, mid);
  if (object == nullptr) {
    std::cout << "Got null service from Java" << std::endl;
    return nullptr;
  }

  SpAIBinder binder = SpAIBinder(AIBinder_fromJavaBinder(env, object));

  return ITest::fromBinder(binder);
}

INSTANTIATE_TEST_CASE_P(LocalProxyToNative, NdkBinderTest_Aidl,
                        ::testing::Values(Params{getProxyLocalService(), false /*shouldBeRemote*/,
                                                 true /*shouldBeWrapped*/, "CPP",
                                                 false /*shouldBeOld*/}));
INSTANTIATE_TEST_CASE_P(LocalNativeFromJava, NdkBinderTest_Aidl,
                        ::testing::Values(Params{
                            getNdkBinderTestJavaService("getLocalNativeService"),
                            false /*shouldBeRemote*/, false /*shouldBeWrapped*/, "CPP",
                            false /*shouldBeOld*/}));
INSTANTIATE_TEST_CASE_P(LocalJava, NdkBinderTest_Aidl,
                        ::testing::Values(Params{getNdkBinderTestJavaService("getLocalJavaService"),
                                                 false /*shouldBeRemote*/, true /*shouldBeWrapped*/,
                                                 "JAVA", false /*shouldBeOld*/}));
INSTANTIATE_TEST_CASE_P(RemoteNative, NdkBinderTest_Aidl,
                        ::testing::Values(Params{
                            getNdkBinderTestJavaService("getRemoteNativeService"),
                            true /*shouldBeRemote*/, true /*shouldBeWrapped*/, "CPP",
                            false /*shouldBeOld*/}));
INSTANTIATE_TEST_CASE_P(RemoteJava, NdkBinderTest_Aidl,
                        ::testing::Values(Params{
                            getNdkBinderTestJavaService("getRemoteJavaService"),
                            true /*shouldBeRemote*/, true /*shouldBeWrapped*/, "JAVA",
                            false /*shouldBeOld*/}));

INSTANTIATE_TEST_CASE_P(RemoteNativeOld, NdkBinderTest_Aidl,
                        ::testing::Values(Params{
                            getNdkBinderTestJavaService("getRemoteOldNativeService"),
                            true /*shouldBeRemote*/, true /*shouldBeWrapped*/, "CPP",
                            true /*shouldBeOld*/}));
