// Copyright (C) 2023 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "icing/file/version-util.h"

#include <optional>
#include <string>
#include <utility>

#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "icing/file/filesystem.h"
#include "icing/file/posting_list/flash-index-storage-header.h"
#include "icing/testing/common-matchers.h"
#include "icing/testing/tmp-directory.h"

namespace icing {
namespace lib {
namespace version_util {

namespace {

using ::testing::Eq;

struct VersionUtilReadVersionTestParam {
  std::optional<VersionInfo> existing_version_info;
  std::optional<int> existing_flash_index_magic;
  VersionInfo expected_version_info;

  explicit VersionUtilReadVersionTestParam(
      std::optional<VersionInfo> existing_version_info_in,
      std::optional<int> existing_flash_index_magic_in,
      VersionInfo expected_version_info_in)
      : existing_version_info(std::move(existing_version_info_in)),
        existing_flash_index_magic(std::move(existing_flash_index_magic_in)),
        expected_version_info(std::move(expected_version_info_in)) {}
};

class VersionUtilReadVersionTest
    : public ::testing::TestWithParam<VersionUtilReadVersionTestParam> {
 protected:
  void SetUp() override {
    base_dir_ = GetTestTempDir() + "/version_util_test";
    version_file_path_ = base_dir_ + "/version";
    index_path_ = base_dir_ + "/index";

    ASSERT_TRUE(filesystem_.CreateDirectoryRecursively(base_dir_.c_str()));
  }

  void TearDown() override {
    ASSERT_TRUE(filesystem_.DeleteDirectoryRecursively(base_dir_.c_str()));
  }

  const Filesystem& filesystem() const { return filesystem_; }

  Filesystem filesystem_;
  std::string base_dir_;
  std::string version_file_path_;
  std::string index_path_;
};

TEST_P(VersionUtilReadVersionTest, ReadVersion) {
  const VersionUtilReadVersionTestParam& param = GetParam();

  // Prepare version file and flash index file.
  if (param.existing_version_info.has_value()) {
    ICING_ASSERT_OK(WriteVersion(filesystem_, version_file_path_,
                                 param.existing_version_info.value()));
  }

  if (param.existing_flash_index_magic.has_value()) {
    HeaderBlock header_block(&filesystem_, /*block_size=*/4096);
    header_block.header()->magic = param.existing_flash_index_magic.value();

    std::string main_index_dir = index_path_ + "/idx/main";
    ASSERT_TRUE(filesystem_.CreateDirectoryRecursively(main_index_dir.c_str()));
    std::string flash_index_file_path = main_index_dir + "/main_index";

    ScopedFd sfd(filesystem_.OpenForWrite(flash_index_file_path.c_str()));
    ASSERT_TRUE(sfd.is_valid());
    ASSERT_TRUE(header_block.Write(sfd.get()));
  }

  ICING_ASSERT_OK_AND_ASSIGN(
      VersionInfo version_info,
      ReadVersion(filesystem_, version_file_path_, index_path_));
  EXPECT_THAT(version_info, Eq(param.expected_version_info));
}

INSTANTIATE_TEST_SUITE_P(
    VersionUtilReadVersionTest, VersionUtilReadVersionTest,
    testing::Values(
        // - Version file doesn't exist
        // - Flash index doesn't exist
        // - Result: version -1, max_version -1 (invalid)
        VersionUtilReadVersionTestParam(
            /*existing_version_info_in=*/std::nullopt,
            /*existing_flash_index_magic_in=*/std::nullopt,
            /*expected_version_info_in=*/
            VersionInfo(/*version_in=*/-1, /*max_version=*/-1)),

        // - Version file doesn't exist
        // - Flash index exists with version 0 magic
        // - Result: version 0, max_version 0
        VersionUtilReadVersionTestParam(
            /*existing_version_info_in=*/std::nullopt,
            /*existing_flash_index_magic_in=*/
            std::make_optional<int>(kVersionZeroFlashIndexMagic),
            /*expected_version_info_in=*/
            VersionInfo(/*version_in=*/0, /*max_version=*/0)),

        // - Version file doesn't exist
        // - Flash index exists with non version 0 magic
        // - Result: version -1, max_version -1 (invalid)
        VersionUtilReadVersionTestParam(
            /*existing_version_info_in=*/std::nullopt,
            /*existing_flash_index_magic_in=*/
            std::make_optional<int>(kVersionZeroFlashIndexMagic + 1),
            /*expected_version_info_in=*/
            VersionInfo(/*version_in=*/-1, /*max_version=*/-1)),

        // - Version file exists
        // - Flash index doesn't exist
        // - Result: version -1, max_version 1 (invalid)
        VersionUtilReadVersionTestParam(
            /*existing_version_info_in=*/std::make_optional<VersionInfo>(
                /*version_in=*/1, /*max_version=*/1),
            /*existing_flash_index_magic_in=*/std::nullopt,
            /*expected_version_info_in=*/
            VersionInfo(/*version_in=*/-1, /*max_version=*/1)),

        // - Version file exists: version 1, max_version 1
        // - Flash index exists with version 0 magic
        // - Result: version 0, max_version 1
        VersionUtilReadVersionTestParam(
            /*existing_version_info_in=*/std::make_optional<VersionInfo>(
                /*version_in=*/1, /*max_version=*/1),
            /*existing_flash_index_magic_in=*/
            std::make_optional<int>(kVersionZeroFlashIndexMagic),
            /*expected_version_info_in=*/
            VersionInfo(/*version_in=*/0, /*max_version=*/1)),

        // - Version file exists: version 2, max_version 3
        // - Flash index exists with version 0 magic
        // - Result: version 0, max_version 3
        VersionUtilReadVersionTestParam(
            /*existing_version_info_in=*/std::make_optional<VersionInfo>(
                /*version_in=*/2, /*max_version=*/3),
            /*existing_flash_index_magic_in=*/
            std::make_optional<int>(kVersionZeroFlashIndexMagic),
            /*expected_version_info_in=*/
            VersionInfo(/*version_in=*/0, /*max_version=*/3)),

        // - Version file exists: version 1, max_version 1
        // - Flash index exists with non version 0 magic
        // - Result: version 1, max_version 1
        VersionUtilReadVersionTestParam(
            /*existing_version_info_in=*/std::make_optional<VersionInfo>(
                /*version_in=*/1, /*max_version=*/1),
            /*existing_flash_index_magic_in=*/
            std::make_optional<int>(kVersionZeroFlashIndexMagic + 1),
            /*expected_version_info_in=*/
            VersionInfo(/*version_in=*/1, /*max_version=*/1)),

        // - Version file exists: version 2, max_version 3
        // - Flash index exists with non version 0 magic
        // - Result: version 2, max_version 3
        VersionUtilReadVersionTestParam(
            /*existing_version_info_in=*/std::make_optional<VersionInfo>(
                /*version_in=*/2, /*max_version=*/3),
            /*existing_flash_index_magic_in=*/
            std::make_optional<int>(kVersionZeroFlashIndexMagic + 1),
            /*expected_version_info_in=*/
            VersionInfo(/*version_in=*/2, /*max_version=*/3))));

struct VersionUtilStateChangeTestParam {
  VersionInfo existing_version_info;
  int32_t curr_version;
  StateChange expected_state_change;

  explicit VersionUtilStateChangeTestParam(VersionInfo existing_version_info_in,
                                           int32_t curr_version_in,
                                           StateChange expected_state_change_in)
      : existing_version_info(std::move(existing_version_info_in)),
        curr_version(curr_version_in),
        expected_state_change(expected_state_change_in) {}
};

class VersionUtilStateChangeTest
    : public ::testing::TestWithParam<VersionUtilStateChangeTestParam> {};

TEST_P(VersionUtilStateChangeTest, GetVersionStateChange) {
  const VersionUtilStateChangeTestParam& param = GetParam();

  EXPECT_THAT(
      GetVersionStateChange(param.existing_version_info, param.curr_version),
      Eq(param.expected_state_change));
}

INSTANTIATE_TEST_SUITE_P(
    VersionUtilStateChangeTest, VersionUtilStateChangeTest,
    testing::Values(
        // - version -1, max_version -1 (invalid)
        // - Current version = 1
        // - Result: undetermined
        VersionUtilStateChangeTestParam(
            /*existing_version_info_in=*/VersionInfo(-1, -1),
            /*curr_version_in=*/1,
            /*expected_state_change_in=*/StateChange::kUndetermined),

        // - version -1, max_version 1 (invalid)
        // - Current version = 1
        // - Result: undetermined
        VersionUtilStateChangeTestParam(
            /*existing_version_info_in=*/VersionInfo(-1, 1),
            /*curr_version_in=*/1,
            /*expected_state_change_in=*/StateChange::kUndetermined),

        // - version -1, max_version -1 (invalid)
        // - Current version = 2
        // - Result: undetermined
        VersionUtilStateChangeTestParam(
            /*existing_version_info_in=*/VersionInfo(-1, -1),
            /*curr_version_in=*/2,
            /*expected_state_change_in=*/StateChange::kUndetermined),

        // - version -1, max_version 1 (invalid)
        // - Current version = 2
        // - Result: undetermined
        VersionUtilStateChangeTestParam(
            /*existing_version_info_in=*/VersionInfo(-1, 1),
            /*curr_version_in=*/2,
            /*expected_state_change_in=*/StateChange::kUndetermined),

        // - version 0, max_version 0
        // - Current version = 1
        // - Result: version 0 upgrade
        VersionUtilStateChangeTestParam(
            /*existing_version_info_in=*/VersionInfo(0, 0),
            /*curr_version_in=*/1,
            /*expected_state_change_in=*/StateChange::kVersionZeroUpgrade),

        // - version 0, max_version 1
        // - Current version = 1
        // - Result: version 0 roll forward
        VersionUtilStateChangeTestParam(
            /*existing_version_info_in=*/VersionInfo(0, 1),
            /*curr_version_in=*/1,
            /*expected_state_change_in=*/StateChange::kVersionZeroRollForward),

        // - version 0, max_version 2
        // - Current version = 1
        // - Result: version 0 roll forward
        VersionUtilStateChangeTestParam(
            /*existing_version_info_in=*/VersionInfo(0, 2),
            /*curr_version_in=*/1,
            /*expected_state_change_in=*/StateChange::kVersionZeroRollForward),

        // - version 0, max_version 0
        // - Current version = 2
        // - Result: version 0 upgrade
        VersionUtilStateChangeTestParam(
            /*existing_version_info_in=*/VersionInfo(0, 0),
            /*curr_version_in=*/2,
            /*expected_state_change_in=*/StateChange::kVersionZeroUpgrade),

        // - version 0, max_version 1
        // - Current version = 2
        // - Result: version 0 upgrade
        VersionUtilStateChangeTestParam(
            /*existing_version_info_in=*/VersionInfo(0, 1),
            /*curr_version_in=*/2,
            /*expected_state_change_in=*/StateChange::kVersionZeroRollForward),

        // - version 0, max_version 2
        // - Current version = 2
        // - Result: version 0 roll forward
        VersionUtilStateChangeTestParam(
            /*existing_version_info_in=*/VersionInfo(0, 2),
            /*curr_version_in=*/2,
            /*expected_state_change_in=*/StateChange::kVersionZeroRollForward),

        // - version 1, max_version 1
        // - Current version = 1
        // - Result: compatible
        VersionUtilStateChangeTestParam(
            /*existing_version_info_in=*/VersionInfo(1, 1),
            /*curr_version_in=*/1,
            /*expected_state_change_in=*/StateChange::kCompatible),

        // - version 1, max_version 2
        // - Current version = 1
        // - Result: compatible
        VersionUtilStateChangeTestParam(
            /*existing_version_info_in=*/VersionInfo(1, 2),
            /*curr_version_in=*/1,
            /*expected_state_change_in=*/StateChange::kCompatible),

        // - version 2, max_version 2
        // - Current version = 1
        // - Result: roll back
        VersionUtilStateChangeTestParam(
            /*existing_version_info_in=*/VersionInfo(2, 2),
            /*curr_version_in=*/1,
            /*expected_state_change_in=*/StateChange::kRollBack),

        // - version 2, max_version 3
        // - Current version = 1
        // - Result: roll back
        VersionUtilStateChangeTestParam(
            /*existing_version_info_in=*/VersionInfo(2, 3),
            /*curr_version_in=*/1,
            /*expected_state_change_in=*/StateChange::kRollBack),

        // - version 1, max_version 1
        // - Current version = 2
        // - Result: upgrade
        VersionUtilStateChangeTestParam(
            /*existing_version_info_in=*/VersionInfo(1, 1),
            /*curr_version_in=*/2,
            /*expected_state_change_in=*/StateChange::kUpgrade),

        // - version 1, max_version 2
        // - Current version = 2
        // - Result: roll forward
        VersionUtilStateChangeTestParam(
            /*existing_version_info_in=*/VersionInfo(1, 2),
            /*curr_version_in=*/2,
            /*expected_state_change_in=*/StateChange::kRollForward),

        // - version 1, max_version 3
        // - Current version = 2
        // - Result: roll forward
        VersionUtilStateChangeTestParam(
            /*existing_version_info_in=*/VersionInfo(1, 3),
            /*curr_version_in=*/2,
            /*expected_state_change_in=*/StateChange::kRollForward),

        // - version 2, max_version 2
        // - Current version = 2
        // - Result: compatible
        VersionUtilStateChangeTestParam(
            /*existing_version_info_in=*/VersionInfo(2, 2),
            /*curr_version_in=*/2,
            /*expected_state_change_in=*/StateChange::kCompatible),

        // - version 2, max_version 3
        // - Current version = 2
        // - Result: compatible
        VersionUtilStateChangeTestParam(
            /*existing_version_info_in=*/VersionInfo(2, 3),
            /*curr_version_in=*/2,
            /*expected_state_change_in=*/StateChange::kCompatible),

        // - version 3, max_version 3
        // - Current version = 2
        // - Result: rollback
        VersionUtilStateChangeTestParam(
            /*existing_version_info_in=*/VersionInfo(3, 3),
            /*curr_version_in=*/2,
            /*expected_state_change_in=*/StateChange::kRollBack),

        // - version 3, max_version 4
        // - Current version = 2
        // - Result: rollback
        VersionUtilStateChangeTestParam(
            /*existing_version_info_in=*/VersionInfo(3, 4),
            /*curr_version_in=*/2,
            /*expected_state_change_in=*/StateChange::kRollBack)));

}  // namespace

}  // namespace version_util
}  // namespace lib
}  // namespace icing
