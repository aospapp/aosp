/*
 * Copyright (C) 2022, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <aidl/android/frameworks/stats/VendorAtom.h>
#include <gtest/gtest.h>
#include <test_vendor_atoms.h>

#include <limits>

#include "frameworks/proto_logging/stats/stats_log_api_gen/test_vendor_atoms.pb.h"

namespace android {
namespace stats_log_api_gen {

using namespace android::VendorAtoms;
using namespace aidl::android::frameworks::stats;

using std::string;
using std::vector;

namespace {

static const int32_t kTestIntValue = 100;
static const int64_t kTestLongValue = std::numeric_limits<int64_t>::max() - kTestIntValue;
static const float kTestFloatValue = (float)kTestIntValue / kTestLongValue;
static const bool kTestBoolValue = true;
static const char* kTestStringValue = "test_string";
static const char* kTestStringValue2 = "test_string2";

}  // namespace

/**
 * Tests native auto generated code for specific vendor atom contains proper ids
 */
TEST(ApiGenVendorAtomTest, AtomIdConstantsTest) {
    EXPECT_EQ(VENDOR_ATOM1, 105501);
    EXPECT_EQ(VENDOR_ATOM2, 105502);
    EXPECT_EQ(VENDOR_ATOM4, 105504);
}

/**
 * Tests native auto generated code for specific vendor atom contains proper enums
 */
TEST(ApiGenVendorAtomTest, AtomEnumTest) {
    EXPECT_EQ(VendorAtom1::TYPE_UNKNOWN, 0);
    EXPECT_EQ(VendorAtom1::TYPE_1, 1);
    EXPECT_EQ(VendorAtom1::TYPE_2, 2);
    EXPECT_EQ(VendorAtom1::TYPE_3, 3);

    EXPECT_EQ(VendorAtom1::ANOTHER_TYPE_UNKNOWN, 0);
    EXPECT_EQ(VendorAtom1::ANOTHER_TYPE_1, 1);
    EXPECT_EQ(VendorAtom1::ANOTHER_TYPE_2, 2);
    EXPECT_EQ(VendorAtom1::ANOTHER_TYPE_3, 3);

    EXPECT_EQ(VendorAtom2::TYPE_UNKNOWN, 0);
    EXPECT_EQ(VendorAtom2::TYPE_1, 1);
    EXPECT_EQ(VendorAtom2::TYPE_2, 2);
    EXPECT_EQ(VendorAtom2::TYPE_3, 3);

    EXPECT_EQ(VendorAtom2::ANOTHER_TYPE_UNKNOWN, 0);
    EXPECT_EQ(VendorAtom2::ANOTHER_TYPE_1, 1);
    EXPECT_EQ(VendorAtom2::ANOTHER_TYPE_2, 2);
    EXPECT_EQ(VendorAtom2::ANOTHER_TYPE_3, 3);

    EXPECT_EQ(VendorAtom4::TYPE_UNKNOWN, 0);
    EXPECT_EQ(VendorAtom4::TYPE_1, 1);

    typedef void (*Atom1FuncWithEnum)(VendorAtom1::EnumType arg);
    typedef void (*Atom1FuncWithEnum2)(VendorAtom1::EnumType2 arg);
    typedef void (*Atom2FuncWithEnum)(VendorAtom2::EnumType arg);
    typedef void (*Atom2FuncWithEnum2)(VendorAtom2::EnumType2 arg);

    Atom1FuncWithEnum f1 = nullptr;
    Atom1FuncWithEnum2 f2 = nullptr;
    Atom2FuncWithEnum f3 = nullptr;
    Atom2FuncWithEnum2 f4 = nullptr;

    EXPECT_EQ(f1, nullptr);
    EXPECT_EQ(f2, nullptr);
    EXPECT_EQ(f3, nullptr);
    EXPECT_EQ(f4, nullptr);
}

TEST(ApiGenVendorAtomTest, buildVendorAtom1ApiTest) {
    typedef VendorAtom (*VendorAtom1BuildFunc)(
            int32_t code, char const* reverse_domain_name, int32_t enumField1, int32_t enumField2,
            int32_t int_value32, int64_t int_value64, float float_value, bool bool_value,
            int32_t enumField3, int32_t enumField4);
    VendorAtom1BuildFunc func = &createVendorAtom;

    EXPECT_NE(func, nullptr);

    VendorAtom atom = func(VENDOR_ATOM1, kTestStringValue, VendorAtom1::TYPE_1, VendorAtom1::TYPE_2,
                           kTestIntValue, kTestLongValue, kTestFloatValue, kTestBoolValue,
                           VendorAtom1::ANOTHER_TYPE_2, VendorAtom1::ANOTHER_TYPE_3);

    EXPECT_EQ(atom.atomId, VENDOR_ATOM1);
    EXPECT_EQ(atom.reverseDomainName, kTestStringValue);
    EXPECT_EQ(atom.values.size(), static_cast<size_t>(8));
    EXPECT_EQ(atom.values[0].get<VendorAtomValue::intValue>(), VendorAtom1::TYPE_1);
    EXPECT_EQ(atom.values[1].get<VendorAtomValue::intValue>(), VendorAtom1::TYPE_2);
    EXPECT_EQ(atom.values[2].get<VendorAtomValue::intValue>(), kTestIntValue);
    EXPECT_EQ(atom.values[3].get<VendorAtomValue::longValue>(), kTestLongValue);
    EXPECT_EQ(atom.values[4].get<VendorAtomValue::floatValue>(), kTestFloatValue);
    EXPECT_EQ(atom.values[5].get<VendorAtomValue::boolValue>(), kTestBoolValue);
    EXPECT_EQ(atom.values[6].get<VendorAtomValue::intValue>(), VendorAtom1::ANOTHER_TYPE_2);
    EXPECT_EQ(atom.values[7].get<VendorAtomValue::intValue>(), VendorAtom1::ANOTHER_TYPE_3);
}

TEST(ApiGenVendorAtomTest, buildVendorAtom3ApiTest) {
    typedef VendorAtom (*VendorAtom3BuildFunc)(int32_t code, char const* arg1, int32_t arg2);
    VendorAtom3BuildFunc func = &createVendorAtom;

    EXPECT_NE(func, nullptr);

    VendorAtom atom = func(VENDOR_ATOM3, kTestStringValue, kTestIntValue);

    EXPECT_EQ(atom.atomId, VENDOR_ATOM3);
    EXPECT_EQ(atom.reverseDomainName, kTestStringValue);
    EXPECT_EQ(atom.values.size(), static_cast<size_t>(1));
    EXPECT_EQ(atom.values[0].get<VendorAtomValue::intValue>(), kTestIntValue);
}

TEST(ApiGenVendorAtomTest, buildVendorAtom4ApiTest) {
    typedef VendorAtom (*VendorAtom4BuildFunc)(
            int32_t code, char const* arg1, float arg2, int32_t arg3, int64_t arg4, bool arg5,
            int32_t arg6, const vector<bool>& arg7, const vector<float>& arg8,
            const vector<int32_t>& arg9, const vector<int64_t>& arg10,
            const vector<char const*>& arg11, const vector<int32_t>& arg12);
    VendorAtom4BuildFunc func = &createVendorAtom;

    EXPECT_NE(func, nullptr);

    const vector<bool> repeatedBool{true, false, true};
    const vector<float> repeatedFloat{kTestFloatValue, kTestFloatValue + 1.f,
                                      kTestFloatValue + 2.f};
    const vector<int32_t> repeatedInt{kTestIntValue, kTestIntValue + 1, kTestIntValue + 2};
    const vector<int64_t> repeatedLong{kTestLongValue, kTestLongValue + 1, kTestLongValue + 2};
    const vector<const char*> repeatedString{kTestStringValue, kTestStringValue2, kTestStringValue};
    const vector<int32_t> repeatedEnum{VendorAtom4::TYPE_1, VendorAtom4::TYPE_UNKNOWN,
                                       VendorAtom4::TYPE_1};

    VendorAtom atom = func(VENDOR_ATOM4, kTestStringValue, kTestFloatValue, kTestIntValue,
                           kTestLongValue, kTestBoolValue, VendorAtom4::TYPE_1, repeatedBool,
                           repeatedFloat, repeatedInt, repeatedLong, repeatedString, repeatedEnum);

    EXPECT_EQ(atom.atomId, VENDOR_ATOM4);
    EXPECT_EQ(atom.reverseDomainName, kTestStringValue);
    EXPECT_EQ(atom.values.size(), static_cast<size_t>(11));
    EXPECT_EQ(atom.values[0].get<VendorAtomValue::floatValue>(), kTestFloatValue);
    EXPECT_EQ(atom.values[1].get<VendorAtomValue::intValue>(), kTestIntValue);
    EXPECT_EQ(atom.values[2].get<VendorAtomValue::longValue>(), kTestLongValue);
    EXPECT_EQ(atom.values[3].get<VendorAtomValue::boolValue>(), kTestBoolValue);
    EXPECT_EQ(atom.values[4].get<VendorAtomValue::intValue>(), VendorAtom4::TYPE_1);

    EXPECT_EQ(atom.values[5].get<VendorAtomValue::repeatedBoolValue>(), repeatedBool);
    EXPECT_EQ(atom.values[6].get<VendorAtomValue::repeatedFloatValue>(), repeatedFloat);
    EXPECT_EQ(atom.values[7].get<VendorAtomValue::repeatedIntValue>(), repeatedInt);
    EXPECT_EQ(atom.values[8].get<VendorAtomValue::repeatedLongValue>(), repeatedLong);
    EXPECT_EQ(atom.values[9].get<VendorAtomValue::repeatedStringValue>().has_value(), true);
    EXPECT_EQ(atom.values[9].get<VendorAtomValue::repeatedStringValue>()->size(),
              repeatedString.size());
    const auto& repeatedStringValue = *atom.values[9].get<VendorAtomValue::repeatedStringValue>();
    for (size_t i = 0; i < repeatedString.size(); i++) {
        EXPECT_EQ(repeatedString[i], *repeatedStringValue[i]);
    }
    EXPECT_EQ(atom.values[10].get<VendorAtomValue::repeatedIntValue>(), repeatedEnum);
}

TEST(ApiGenVendorAtomTest, buildVendorAtom5ApiTest) {
    typedef VendorAtom (*VendorAtom5BuildFunc)(int32_t code, char const* arg1, float arg2,
                                               int32_t arg3, int64_t arg4,
                                               const vector<uint8_t>& arg5);
    VendorAtom5BuildFunc func = &createVendorAtom;

    EXPECT_NE(func, nullptr);

    ::android::stats_log_api_gen::TestNestedMessage nestedMessage;
    nestedMessage.set_float_field(kTestFloatValue);
    nestedMessage.set_int_field(kTestIntValue);
    nestedMessage.set_long_field(kTestLongValue);

    string nestedMessageString;
    nestedMessage.SerializeToString(&nestedMessageString);

    vector<uint8_t> nestedMessageBytes(nestedMessageString.begin(), nestedMessageString.end());

    VendorAtom atom = func(VENDOR_ATOM5, kTestStringValue, kTestFloatValue, kTestIntValue,
                           kTestLongValue, nestedMessageBytes);

    EXPECT_EQ(atom.atomId, VENDOR_ATOM5);
    EXPECT_EQ(atom.reverseDomainName, kTestStringValue);
    EXPECT_EQ(atom.values.size(), static_cast<size_t>(4));
    EXPECT_EQ(atom.values[0].get<VendorAtomValue::floatValue>(), kTestFloatValue);
    EXPECT_EQ(atom.values[1].get<VendorAtomValue::intValue>(), kTestIntValue);
    EXPECT_EQ(atom.values[2].get<VendorAtomValue::longValue>(), kTestLongValue);
    EXPECT_EQ(atom.values[3].get<VendorAtomValue::byteArrayValue>(), nestedMessageBytes);

    string nestedMessageStringResult(atom.values[3].get<VendorAtomValue::byteArrayValue>()->begin(),
                                     atom.values[3].get<VendorAtomValue::byteArrayValue>()->end());
    EXPECT_EQ(nestedMessageStringResult, nestedMessageString);

    ::android::stats_log_api_gen::TestNestedMessage nestedMessageResult;
    nestedMessageResult.ParseFromString(nestedMessageStringResult);
    EXPECT_EQ(nestedMessageResult.float_field(), kTestFloatValue);
    EXPECT_EQ(nestedMessageResult.int_field(), kTestIntValue);
    EXPECT_EQ(nestedMessageResult.long_field(), kTestLongValue);
}

}  // namespace stats_log_api_gen
}  // namespace android
