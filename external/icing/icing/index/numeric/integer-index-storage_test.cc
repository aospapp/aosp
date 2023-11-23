// Copyright (C) 2022 Google LLC
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

#include "icing/index/numeric/integer-index-storage.h"

#include <unistd.h>

#include <cstdint>
#include <limits>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

#include "icing/text_classifier/lib3/utils/base/status.h"
#include "icing/text_classifier/lib3/utils/base/statusor.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "icing/file/file-backed-vector.h"
#include "icing/file/filesystem.h"
#include "icing/file/persistent-storage.h"
#include "icing/file/posting_list/flash-index-storage.h"
#include "icing/file/posting_list/index-block.h"
#include "icing/file/posting_list/posting-list-identifier.h"
#include "icing/index/hit/doc-hit-info.h"
#include "icing/index/iterator/doc-hit-info-iterator.h"
#include "icing/index/numeric/posting-list-integer-index-serializer.h"
#include "icing/schema/section.h"
#include "icing/store/document-id.h"
#include "icing/testing/common-matchers.h"
#include "icing/testing/tmp-directory.h"
#include "icing/util/crc32.h"

namespace icing {
namespace lib {

namespace {

using ::testing::Contains;
using ::testing::ElementsAre;
using ::testing::ElementsAreArray;
using ::testing::Eq;
using ::testing::Ge;
using ::testing::Gt;
using ::testing::HasSubstr;
using ::testing::IsEmpty;
using ::testing::IsFalse;
using ::testing::IsTrue;
using ::testing::Key;
using ::testing::Le;
using ::testing::Ne;
using ::testing::Not;

using Bucket = IntegerIndexStorage::Bucket;
using Crcs = PersistentStorage::Crcs;
using Info = IntegerIndexStorage::Info;
using Options = IntegerIndexStorage::Options;

static constexpr int32_t kCorruptedValueOffset = 3;
static constexpr DocumentId kDefaultDocumentId = 123;
static constexpr SectionId kDefaultSectionId = 31;

class IntegerIndexStorageTest : public ::testing::TestWithParam<bool> {
 protected:
  void SetUp() override {
    base_dir_ = GetTestTempDir() + "/icing";
    ASSERT_THAT(filesystem_.CreateDirectoryRecursively(base_dir_.c_str()),
                IsTrue());

    working_path_ = base_dir_ + "/integer_index_storage_test";

    serializer_ = std::make_unique<PostingListIntegerIndexSerializer>();
  }

  void TearDown() override {
    serializer_.reset();
    filesystem_.DeleteDirectoryRecursively(base_dir_.c_str());
  }

  Filesystem filesystem_;
  std::string base_dir_;
  std::string working_path_;
  std::unique_ptr<PostingListIntegerIndexSerializer> serializer_;
};

libtextclassifier3::StatusOr<std::vector<DocHitInfo>> Query(
    const IntegerIndexStorage* storage, int64_t key_lower, int64_t key_upper) {
  ICING_ASSIGN_OR_RETURN(std::unique_ptr<DocHitInfoIterator> iter,
                         storage->GetIterator(key_lower, key_upper));
  std::vector<DocHitInfo> hits;
  while (iter->Advance().ok()) {
    hits.push_back(iter->doc_hit_info());
  }
  return hits;
}

TEST_P(IntegerIndexStorageTest, OptionsEmptyCustomInitBucketsShouldBeValid) {
  EXPECT_THAT(Options(/*pre_mapping_fbv_in=*/GetParam()).IsValid(), IsTrue());
}

TEST_P(IntegerIndexStorageTest, OptionsInvalidCustomInitBucketsRange) {
  // Invalid custom init sorted bucket
  EXPECT_THAT(
      Options(/*custom_init_sorted_buckets_in=*/
              {Bucket(std::numeric_limits<int64_t>::min(), 5), Bucket(9, 6)},
              /*custom_init_unsorted_buckets_in=*/
              {Bucket(10, std::numeric_limits<int64_t>::max())},
              /*pre_mapping_fbv_in=*/GetParam())
          .IsValid(),
      IsFalse());

  // Invalid custom init unsorted bucket
  EXPECT_THAT(
      Options(/*custom_init_sorted_buckets_in=*/
              {Bucket(10, std::numeric_limits<int64_t>::max())},
              /*custom_init_unsorted_buckets_in=*/
              {Bucket(std::numeric_limits<int64_t>::min(), 5), Bucket(9, 6)},
              /*pre_mapping_fbv_in=*/GetParam())
          .IsValid(),
      IsFalse());
}

TEST_P(IntegerIndexStorageTest,
       OptionsInvalidCustomInitBucketsPostingListIdentifier) {
  // Custom init buckets should contain invalid posting list identifier.
  PostingListIdentifier valid_posting_list_identifier(0, 0, 0);
  ASSERT_THAT(valid_posting_list_identifier.is_valid(), IsTrue());

  // Invalid custom init sorted bucket
  EXPECT_THAT(Options(/*custom_init_sorted_buckets_in=*/
                      {Bucket(std::numeric_limits<int64_t>::min(),
                              std::numeric_limits<int64_t>::max(),
                              valid_posting_list_identifier)},
                      /*custom_init_unsorted_buckets_in=*/{},
                      /*pre_mapping_fbv_in=*/GetParam())
                  .IsValid(),
              IsFalse());

  // Invalid custom init unsorted bucket
  EXPECT_THAT(Options(/*custom_init_sorted_buckets_in=*/{},
                      /*custom_init_unsorted_buckets_in=*/
                      {Bucket(std::numeric_limits<int64_t>::min(),
                              std::numeric_limits<int64_t>::max(),
                              valid_posting_list_identifier)},
                      /*pre_mapping_fbv_in=*/GetParam())
                  .IsValid(),
              IsFalse());
}

TEST_P(IntegerIndexStorageTest, OptionsInvalidCustomInitBucketsOverlapping) {
  // sorted buckets overlap
  EXPECT_THAT(Options(/*custom_init_sorted_buckets_in=*/
                      {Bucket(std::numeric_limits<int64_t>::min(), -100),
                       Bucket(-100, std::numeric_limits<int64_t>::max())},
                      /*custom_init_unsorted_buckets_in=*/{},
                      /*pre_mapping_fbv_in=*/GetParam())
                  .IsValid(),
              IsFalse());

  // unsorted buckets overlap
  EXPECT_THAT(Options(/*custom_init_sorted_buckets_in=*/{},
                      /*custom_init_unsorted_buckets_in=*/
                      {Bucket(-100, std::numeric_limits<int64_t>::max()),
                       Bucket(std::numeric_limits<int64_t>::min(), -100)},
                      /*pre_mapping_fbv_in=*/GetParam())
                  .IsValid(),
              IsFalse());

  // Cross buckets overlap
  EXPECT_THAT(Options(/*custom_init_sorted_buckets_in=*/
                      {Bucket(std::numeric_limits<int64_t>::min(), -100),
                       Bucket(-99, 0)},
                      /*custom_init_unsorted_buckets_in=*/
                      {Bucket(200, std::numeric_limits<int64_t>::max()),
                       Bucket(0, 50), Bucket(51, 199)},
                      /*pre_mapping_fbv_in=*/GetParam())
                  .IsValid(),
              IsFalse());
}

TEST_P(IntegerIndexStorageTest, OptionsInvalidCustomInitBucketsUnion) {
  // Missing INT64_MAX
  EXPECT_THAT(Options(/*custom_init_sorted_buckets_in=*/
                      {Bucket(std::numeric_limits<int64_t>::min(), -100),
                       Bucket(-99, 0)},
                      /*custom_init_unsorted_buckets_in=*/
                      {Bucket(1, 1000)}, /*pre_mapping_fbv_in=*/GetParam())
                  .IsValid(),
              IsFalse());

  // Missing INT64_MIN
  EXPECT_THAT(Options(/*custom_init_sorted_buckets_in=*/
                      {Bucket(-200, -100), Bucket(-99, 0)},
                      /*custom_init_unsorted_buckets_in=*/
                      {Bucket(1, std::numeric_limits<int64_t>::max())},
                      /*pre_mapping_fbv_in=*/GetParam())
                  .IsValid(),
              IsFalse());

  // Missing some intermediate ranges
  EXPECT_THAT(Options(/*custom_init_sorted_buckets_in=*/
                      {Bucket(std::numeric_limits<int64_t>::min(), -100)},
                      /*custom_init_unsorted_buckets_in=*/
                      {Bucket(1, std::numeric_limits<int64_t>::max())},
                      /*pre_mapping_fbv_in=*/GetParam())
                  .IsValid(),
              IsFalse());
}

TEST_P(IntegerIndexStorageTest, InvalidWorkingPath) {
  EXPECT_THAT(
      IntegerIndexStorage::Create(
          filesystem_, "/dev/null/integer_index_storage_test",
          Options(/*pre_mapping_fbv_in=*/GetParam()), serializer_.get()),
      StatusIs(libtextclassifier3::StatusCode::INTERNAL));
}

TEST_P(IntegerIndexStorageTest, CreateWithInvalidOptionsShouldFail) {
  Options invalid_options(
      /*custom_init_sorted_buckets_in=*/{},
      /*custom_init_unsorted_buckets_in=*/
      {Bucket(-100, std::numeric_limits<int64_t>::max()),
       Bucket(std::numeric_limits<int64_t>::min(), -100)},
      /*pre_mapping_fbv_in=*/GetParam());
  ASSERT_THAT(invalid_options.IsValid(), IsFalse());

  EXPECT_THAT(IntegerIndexStorage::Create(filesystem_, working_path_,
                                          invalid_options, serializer_.get()),
              StatusIs(libtextclassifier3::StatusCode::INVALID_ARGUMENT));
}

TEST_P(IntegerIndexStorageTest, InitializeNewFiles) {
  {
    // Create new integer index storage
    ASSERT_FALSE(filesystem_.DirectoryExists(working_path_.c_str()));
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<IntegerIndexStorage> storage,
        IntegerIndexStorage::Create(filesystem_, working_path_,
                                    Options(/*pre_mapping_fbv_in=*/GetParam()),
                                    serializer_.get()));

    ICING_ASSERT_OK(storage->PersistToDisk());
  }

  // Metadata file should be initialized correctly for both info and crcs
  // sections.
  const std::string metadata_file_path = absl_ports::StrCat(
      working_path_, "/", IntegerIndexStorage::kFilePrefix, ".m");
  ScopedFd metadata_sfd(filesystem_.OpenForWrite(metadata_file_path.c_str()));
  ASSERT_TRUE(metadata_sfd.is_valid());

  // Check info section
  Info info;
  ASSERT_TRUE(filesystem_.PRead(metadata_sfd.get(), &info, sizeof(Info),
                                IntegerIndexStorage::kInfoMetadataFileOffset));
  EXPECT_THAT(info.magic, Eq(Info::kMagic));
  EXPECT_THAT(info.num_data, Eq(0));

  // Check crcs section
  Crcs crcs;
  ASSERT_TRUE(filesystem_.PRead(metadata_sfd.get(), &crcs, sizeof(Crcs),
                                IntegerIndexStorage::kCrcsMetadataFileOffset));
  // # of elements in sorted_buckets should be 1, so it should have non-zero
  // all storages crc value.
  EXPECT_THAT(crcs.component_crcs.storages_crc, Ne(0));
  EXPECT_THAT(crcs.component_crcs.info_crc,
              Eq(Crc32(std::string_view(reinterpret_cast<const char*>(&info),
                                        sizeof(Info)))
                     .Get()));
  EXPECT_THAT(crcs.all_crc,
              Eq(Crc32(std::string_view(
                           reinterpret_cast<const char*>(&crcs.component_crcs),
                           sizeof(Crcs::ComponentCrcs)))
                     .Get()));
}

TEST_P(IntegerIndexStorageTest,
       InitializationShouldFailWithoutPersistToDiskOrDestruction) {
  // Create new integer index storage
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage,
      IntegerIndexStorage::Create(filesystem_, working_path_,
                                  Options(/*pre_mapping_fbv_in=*/GetParam()),
                                  serializer_.get()));

  // Insert some data.
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/0, /*section_id=*/20,
                                   /*new_keys=*/{0, 100, -100}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/1, /*section_id=*/2,
                                   /*new_keys=*/{3, -1000, 500}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/2, /*section_id=*/15,
                                   /*new_keys=*/{-6, 321, 98}));

  // Without calling PersistToDisk, checksums will not be recomputed or synced
  // to disk, so initializing another instance on the same files should fail.
  EXPECT_THAT(
      IntegerIndexStorage::Create(filesystem_, working_path_,
                                  Options(/*pre_mapping_fbv_in=*/GetParam()),
                                  serializer_.get()),
      StatusIs(libtextclassifier3::StatusCode::FAILED_PRECONDITION));
}

TEST_P(IntegerIndexStorageTest, InitializationShouldSucceedWithPersistToDisk) {
  // Create new integer index storage
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage1,
      IntegerIndexStorage::Create(filesystem_, working_path_,
                                  Options(/*pre_mapping_fbv_in=*/GetParam()),
                                  serializer_.get()));

  // Insert some data.
  ICING_ASSERT_OK(storage1->AddKeys(/*document_id=*/0, /*section_id=*/20,
                                    /*new_keys=*/{0, 100, -100}));
  ICING_ASSERT_OK(storage1->AddKeys(/*document_id=*/1, /*section_id=*/2,
                                    /*new_keys=*/{3, -1000, 500}));
  ICING_ASSERT_OK(storage1->AddKeys(/*document_id=*/2, /*section_id=*/15,
                                    /*new_keys=*/{-6, 321, 98}));
  ICING_ASSERT_OK_AND_ASSIGN(
      std::vector<DocHitInfo> doc_hit_info_vec,
      Query(storage1.get(),
            /*key_lower=*/std::numeric_limits<int64_t>::min(),
            /*key_upper=*/std::numeric_limits<int64_t>::max()));

  // After calling PersistToDisk, all checksums should be recomputed and synced
  // correctly to disk, so initializing another instance on the same files
  // should succeed, and we should be able to get the same contents.
  ICING_EXPECT_OK(storage1->PersistToDisk());

  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage2,
      IntegerIndexStorage::Create(filesystem_, working_path_,
                                  Options(/*pre_mapping_fbv_in=*/GetParam()),
                                  serializer_.get()));
  EXPECT_THAT(
      Query(storage2.get(), /*key_lower=*/std::numeric_limits<int64_t>::min(),
            /*key_upper=*/std::numeric_limits<int64_t>::max()),
      IsOkAndHolds(
          ElementsAreArray(doc_hit_info_vec.begin(), doc_hit_info_vec.end())));
}

TEST_P(IntegerIndexStorageTest, InitializationShouldSucceedAfterDestruction) {
  std::vector<DocHitInfo> doc_hit_info_vec;
  {
    // Create new integer index storage
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<IntegerIndexStorage> storage,
        IntegerIndexStorage::Create(filesystem_, working_path_,
                                    Options(/*pre_mapping_fbv_in=*/GetParam()),
                                    serializer_.get()));

    // Insert some data.
    ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/0, /*section_id=*/20,
                                     /*new_keys=*/{0, 100, -100}));
    ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/1, /*section_id=*/2,
                                     /*new_keys=*/{3, -1000, 500}));
    ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/2, /*section_id=*/15,
                                     /*new_keys=*/{-6, 321, 98}));
    ICING_ASSERT_OK_AND_ASSIGN(
        doc_hit_info_vec,
        Query(storage.get(),
              /*key_lower=*/std::numeric_limits<int64_t>::min(),
              /*key_upper=*/std::numeric_limits<int64_t>::max()));
  }

  {
    // The previous instance went out of scope and was destructed. Although we
    // didn't call PersistToDisk explicitly, the destructor should invoke it and
    // thus initializing another instance on the same files should succeed, and
    // we should be able to get the same contents.
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<IntegerIndexStorage> storage,
        IntegerIndexStorage::Create(filesystem_, working_path_,
                                    Options(/*pre_mapping_fbv_in=*/GetParam()),
                                    serializer_.get()));
    EXPECT_THAT(
        Query(storage.get(), /*key_lower=*/std::numeric_limits<int64_t>::min(),
              /*key_upper=*/std::numeric_limits<int64_t>::max()),
        IsOkAndHolds(ElementsAreArray(doc_hit_info_vec.begin(),
                                      doc_hit_info_vec.end())));
  }
}

TEST_P(IntegerIndexStorageTest,
       InitializeExistingFilesWithWrongAllCrcShouldFail) {
  {
    // Create new integer index storage
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<IntegerIndexStorage> storage,
        IntegerIndexStorage::Create(filesystem_, working_path_,
                                    Options(/*pre_mapping_fbv_in=*/GetParam()),
                                    serializer_.get()));
    ICING_ASSERT_OK(storage->AddKeys(kDefaultDocumentId, kDefaultSectionId,
                                     /*new_keys=*/{0, 100, -100}));

    ICING_ASSERT_OK(storage->PersistToDisk());
  }

  const std::string metadata_file_path = absl_ports::StrCat(
      working_path_, "/", IntegerIndexStorage::kFilePrefix, ".m");
  ScopedFd metadata_sfd(filesystem_.OpenForWrite(metadata_file_path.c_str()));
  ASSERT_TRUE(metadata_sfd.is_valid());

  Crcs crcs;
  ASSERT_TRUE(filesystem_.PRead(metadata_sfd.get(), &crcs, sizeof(Crcs),
                                IntegerIndexStorage::kCrcsMetadataFileOffset));

  // Manually corrupt all_crc
  crcs.all_crc += kCorruptedValueOffset;
  ASSERT_TRUE(filesystem_.PWrite(metadata_sfd.get(),
                                 IntegerIndexStorage::kCrcsMetadataFileOffset,
                                 &crcs, sizeof(Crcs)));
  metadata_sfd.reset();

  {
    // Attempt to create the integer index storage with metadata containing
    // corrupted all_crc. This should fail.
    libtextclassifier3::StatusOr<std::unique_ptr<IntegerIndexStorage>>
        storage_or = IntegerIndexStorage::Create(
            filesystem_, working_path_,
            Options(/*pre_mapping_fbv_in=*/GetParam()), serializer_.get());
    EXPECT_THAT(storage_or,
                StatusIs(libtextclassifier3::StatusCode::FAILED_PRECONDITION));
    EXPECT_THAT(storage_or.status().error_message(),
                HasSubstr("Invalid all crc"));
  }
}

TEST_P(IntegerIndexStorageTest,
       InitializeExistingFilesWithCorruptedInfoShouldFail) {
  {
    // Create new integer index storage
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<IntegerIndexStorage> storage,
        IntegerIndexStorage::Create(filesystem_, working_path_,
                                    Options(/*pre_mapping_fbv_in=*/GetParam()),
                                    serializer_.get()));
    ICING_ASSERT_OK(storage->AddKeys(kDefaultDocumentId, kDefaultSectionId,
                                     /*new_keys=*/{0, 100, -100}));

    ICING_ASSERT_OK(storage->PersistToDisk());
  }

  const std::string metadata_file_path = absl_ports::StrCat(
      working_path_, "/", IntegerIndexStorage::kFilePrefix, ".m");
  ScopedFd metadata_sfd(filesystem_.OpenForWrite(metadata_file_path.c_str()));
  ASSERT_TRUE(metadata_sfd.is_valid());

  Info info;
  ASSERT_TRUE(filesystem_.PRead(metadata_sfd.get(), &info, sizeof(Info),
                                IntegerIndexStorage::kInfoMetadataFileOffset));

  // Modify info, but don't update the checksum. This would be similar to
  // corruption of info.
  info.num_data += kCorruptedValueOffset;
  ASSERT_TRUE(filesystem_.PWrite(metadata_sfd.get(),
                                 IntegerIndexStorage::kInfoMetadataFileOffset,
                                 &info, sizeof(Info)));
  metadata_sfd.reset();

  {
    // Attempt to create the integer index storage with info that doesn't match
    // its checksum and confirm that it fails.
    libtextclassifier3::StatusOr<std::unique_ptr<IntegerIndexStorage>>
        storage_or = IntegerIndexStorage::Create(
            filesystem_, working_path_,
            Options(/*pre_mapping_fbv_in=*/GetParam()), serializer_.get());
    EXPECT_THAT(storage_or,
                StatusIs(libtextclassifier3::StatusCode::FAILED_PRECONDITION));
    EXPECT_THAT(storage_or.status().error_message(),
                HasSubstr("Invalid info crc"));
  }
}

TEST_P(IntegerIndexStorageTest,
       InitializeExistingFilesWithCorruptedSortedBucketsShouldFail) {
  {
    // Create new integer index storage
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<IntegerIndexStorage> storage,
        IntegerIndexStorage::Create(filesystem_, working_path_,
                                    Options(/*pre_mapping_fbv_in=*/GetParam()),
                                    serializer_.get()));
    ICING_ASSERT_OK(storage->AddKeys(kDefaultDocumentId, kDefaultSectionId,
                                     /*new_keys=*/{0, 100, -100}));

    ICING_ASSERT_OK(storage->PersistToDisk());
  }

  {
    // Corrupt sorted buckets manually.
    const std::string sorted_buckets_file_path = absl_ports::StrCat(
        working_path_, "/", IntegerIndexStorage::kFilePrefix, ".s");
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<FileBackedVector<Bucket>> sorted_buckets,
        FileBackedVector<Bucket>::Create(
            filesystem_, sorted_buckets_file_path,
            MemoryMappedFile::Strategy::READ_WRITE_AUTO_SYNC));
    ICING_ASSERT_OK_AND_ASSIGN(Crc32 old_crc,
                               sorted_buckets->ComputeChecksum());
    ICING_ASSERT_OK(sorted_buckets->Append(Bucket(
        /*key_lower=*/0, /*key_upper=*/std::numeric_limits<int64_t>::max())));
    ICING_ASSERT_OK(sorted_buckets->PersistToDisk());
    ICING_ASSERT_OK_AND_ASSIGN(Crc32 new_crc,
                               sorted_buckets->ComputeChecksum());
    ASSERT_THAT(old_crc, Not(Eq(new_crc)));
  }

  {
    // Attempt to create the integer index storage with metadata containing
    // corrupted sorted_buckets_crc. This should fail.
    libtextclassifier3::StatusOr<std::unique_ptr<IntegerIndexStorage>>
        storage_or = IntegerIndexStorage::Create(
            filesystem_, working_path_,
            Options(/*pre_mapping_fbv_in=*/GetParam()), serializer_.get());
    EXPECT_THAT(storage_or,
                StatusIs(libtextclassifier3::StatusCode::FAILED_PRECONDITION));
    EXPECT_THAT(storage_or.status().error_message(),
                HasSubstr("Invalid storages crc"));
  }
}

TEST_P(IntegerIndexStorageTest,
       InitializeExistingFilesWithCorruptedUnsortedBucketsShouldFail) {
  {
    // Create new integer index storage
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<IntegerIndexStorage> storage,
        IntegerIndexStorage::Create(filesystem_, working_path_,
                                    Options(/*pre_mapping_fbv_in=*/GetParam()),
                                    serializer_.get()));
    ICING_ASSERT_OK(storage->AddKeys(kDefaultDocumentId, kDefaultSectionId,
                                     /*new_keys=*/{0, 100, -100}));

    ICING_ASSERT_OK(storage->PersistToDisk());
  }

  {
    // Corrupt unsorted buckets manually.
    const std::string unsorted_buckets_file_path = absl_ports::StrCat(
        working_path_, "/", IntegerIndexStorage::kFilePrefix, ".u");
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<FileBackedVector<Bucket>> unsorted_buckets,
        FileBackedVector<Bucket>::Create(
            filesystem_, unsorted_buckets_file_path,
            MemoryMappedFile::Strategy::READ_WRITE_AUTO_SYNC,
            /*max_file_size=*/sizeof(Bucket) * 100 +
                FileBackedVector<Bucket>::Header::kHeaderSize));
    ICING_ASSERT_OK_AND_ASSIGN(Crc32 old_crc,
                               unsorted_buckets->ComputeChecksum());
    ICING_ASSERT_OK(unsorted_buckets->Append(Bucket(
        /*key_lower=*/0, /*key_upper=*/std::numeric_limits<int64_t>::max())));
    ICING_ASSERT_OK(unsorted_buckets->PersistToDisk());
    ICING_ASSERT_OK_AND_ASSIGN(Crc32 new_crc,
                               unsorted_buckets->ComputeChecksum());
    ASSERT_THAT(old_crc, Not(Eq(new_crc)));
  }

  {
    // Attempt to create the integer index storage with metadata containing
    // corrupted unsorted_buckets_crc. This should fail.
    libtextclassifier3::StatusOr<std::unique_ptr<IntegerIndexStorage>>
        storage_or = IntegerIndexStorage::Create(
            filesystem_, working_path_,
            Options(/*pre_mapping_fbv_in=*/GetParam()), serializer_.get());
    EXPECT_THAT(storage_or,
                StatusIs(libtextclassifier3::StatusCode::FAILED_PRECONDITION));
    EXPECT_THAT(storage_or.status().error_message(),
                HasSubstr("Invalid storages crc"));
  }
}

// TODO(b/259744228): add test for corrupted flash_index_storage

TEST_P(IntegerIndexStorageTest, InvalidQuery) {
  // Create new integer index storage
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage,
      IntegerIndexStorage::Create(filesystem_, working_path_,
                                  Options(/*pre_mapping_fbv_in=*/GetParam()),
                                  serializer_.get()));
  EXPECT_THAT(
      storage->GetIterator(/*query_key_lower=*/0, /*query_key_upper=*/-1),
      StatusIs(libtextclassifier3::StatusCode::INVALID_ARGUMENT));
}

TEST_P(IntegerIndexStorageTest, ExactQuerySortedBuckets) {
  // We use predefined custom buckets to initialize new integer index storage
  // and create some test keys accordingly.
  std::vector<Bucket> custom_init_sorted_buckets = {
      Bucket(-1000, -100), Bucket(0, 100), Bucket(150, 199), Bucket(200, 300),
      Bucket(301, 999)};
  std::vector<Bucket> custom_init_unsorted_buckets = {
      Bucket(1000, std::numeric_limits<int64_t>::max()), Bucket(-99, -1),
      Bucket(101, 149), Bucket(std::numeric_limits<int64_t>::min(), -1001)};
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage,
      IntegerIndexStorage::Create(
          filesystem_, working_path_,
          Options(std::move(custom_init_sorted_buckets),
                  std::move(custom_init_unsorted_buckets),
                  /*pre_mapping_fbv_in=*/GetParam()),
          serializer_.get()));

  // Add some keys into sorted buckets [(-1000,-100), (200,300)].
  EXPECT_THAT(storage->AddKeys(/*document_id=*/0, kDefaultSectionId,
                               /*new_keys=*/{-500}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/1, kDefaultSectionId,
                               /*new_keys=*/{208}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/2, kDefaultSectionId,
                               /*new_keys=*/{-200}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/3, kDefaultSectionId,
                               /*new_keys=*/{-1000}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/4, kDefaultSectionId,
                               /*new_keys=*/{300}),
              IsOk());
  EXPECT_THAT(storage->num_data(), Eq(5));

  std::vector<SectionId> expected_sections = {kDefaultSectionId};
  // Exact query on key in each sorted bucket should get the correct result.
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/-500, /*key_upper=*/-500),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/0, expected_sections))));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/208, /*key_upper=*/208),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/1, expected_sections))));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/-200, /*key_upper=*/-200),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/2, expected_sections))));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/-1000, /*key_upper=*/-1000),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/3, expected_sections))));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/300, /*key_upper=*/300),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/4, expected_sections))));
}

TEST_P(IntegerIndexStorageTest, ExactQueryUnsortedBuckets) {
  // We use predefined custom buckets to initialize new integer index storage
  // and create some test keys accordingly.
  std::vector<Bucket> custom_init_sorted_buckets = {
      Bucket(-1000, -100), Bucket(0, 100), Bucket(150, 199), Bucket(200, 300),
      Bucket(301, 999)};
  std::vector<Bucket> custom_init_unsorted_buckets = {
      Bucket(1000, std::numeric_limits<int64_t>::max()), Bucket(-99, -1),
      Bucket(101, 149), Bucket(std::numeric_limits<int64_t>::min(), -1001)};
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage,
      IntegerIndexStorage::Create(
          filesystem_, working_path_,
          Options(std::move(custom_init_sorted_buckets),
                  std::move(custom_init_unsorted_buckets),
                  /*pre_mapping_fbv_in=*/GetParam()),
          serializer_.get()));

  // Add some keys into unsorted buckets [(1000,INT64_MAX), (INT64_MIN,-1001)].
  EXPECT_THAT(storage->AddKeys(/*document_id=*/0, kDefaultSectionId,
                               /*new_keys=*/{1024}),
              IsOk());
  EXPECT_THAT(
      storage->AddKeys(/*document_id=*/1, kDefaultSectionId,
                       /*new_keys=*/{std::numeric_limits<int64_t>::max()}),
      IsOk());
  EXPECT_THAT(
      storage->AddKeys(/*document_id=*/2, kDefaultSectionId,
                       /*new_keys=*/{std::numeric_limits<int64_t>::min()}),
      IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/3, kDefaultSectionId,
                               /*new_keys=*/{-1500}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/4, kDefaultSectionId,
                               /*new_keys=*/{2000}),
              IsOk());
  EXPECT_THAT(storage->num_data(), Eq(5));

  std::vector<SectionId> expected_sections = {kDefaultSectionId};
  // Exact query on key in each unsorted bucket should get the correct result.
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/1024, /*key_upper=*/1024),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/0, expected_sections))));
  EXPECT_THAT(
      Query(storage.get(), /*key_lower=*/std::numeric_limits<int64_t>::max(),
            /*key_upper=*/std::numeric_limits<int64_t>::max()),
      IsOkAndHolds(
          ElementsAre(EqualsDocHitInfo(/*document_id=*/1, expected_sections))));
  EXPECT_THAT(
      Query(storage.get(), /*key_lower=*/std::numeric_limits<int64_t>::min(),
            /*key_upper=*/std::numeric_limits<int64_t>::min()),
      IsOkAndHolds(
          ElementsAre(EqualsDocHitInfo(/*document_id=*/2, expected_sections))));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/-1500, /*key_upper=*/-1500),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/3, expected_sections))));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/2000, /*key_upper=*/2000),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/4, expected_sections))));
}

TEST_P(IntegerIndexStorageTest, ExactQueryIdenticalKeys) {
  // We use predefined custom buckets to initialize new integer index storage
  // and create some test keys accordingly.
  std::vector<Bucket> custom_init_sorted_buckets = {
      Bucket(-1000, -100), Bucket(0, 100), Bucket(150, 199), Bucket(200, 300),
      Bucket(301, 999)};
  std::vector<Bucket> custom_init_unsorted_buckets = {
      Bucket(1000, std::numeric_limits<int64_t>::max()), Bucket(-99, -1),
      Bucket(101, 149), Bucket(std::numeric_limits<int64_t>::min(), -1001)};
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage,
      IntegerIndexStorage::Create(
          filesystem_, working_path_,
          Options(std::move(custom_init_sorted_buckets),
                  std::move(custom_init_unsorted_buckets),
                  /*pre_mapping_fbv_in=*/GetParam()),
          serializer_.get()));

  // Add some keys into buckets [(0,100), (1000,INT64_MAX)].
  EXPECT_THAT(storage->AddKeys(/*document_id=*/0, kDefaultSectionId,
                               /*new_keys=*/{1024}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/1, kDefaultSectionId,
                               /*new_keys=*/{1024}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/2, kDefaultSectionId,
                               /*new_keys=*/{20}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/3, kDefaultSectionId,
                               /*new_keys=*/{20}),
              IsOk());
  EXPECT_THAT(storage->num_data(), Eq(4));

  std::vector<SectionId> expected_sections = {kDefaultSectionId};
  // Exact query on key with multiple hits should get the correct result.
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/1024, /*key_upper=*/1024),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/1, expected_sections),
                  EqualsDocHitInfo(/*document_id=*/0, expected_sections))));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/20, /*key_upper=*/20),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/3, expected_sections),
                  EqualsDocHitInfo(/*document_id=*/2, expected_sections))));
}

TEST_P(IntegerIndexStorageTest, RangeQueryEmptyIntegerIndexStorage) {
  std::vector<Bucket> custom_init_sorted_buckets = {
      Bucket(-1000, -100), Bucket(0, 100), Bucket(150, 199), Bucket(200, 300),
      Bucket(301, 999)};
  std::vector<Bucket> custom_init_unsorted_buckets = {
      Bucket(1000, std::numeric_limits<int64_t>::max()), Bucket(-99, -1),
      Bucket(101, 149), Bucket(std::numeric_limits<int64_t>::min(), -1001)};
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage,
      IntegerIndexStorage::Create(
          filesystem_, working_path_,
          Options(std::move(custom_init_sorted_buckets),
                  std::move(custom_init_unsorted_buckets),
                  /*pre_mapping_fbv_in=*/GetParam()),
          serializer_.get()));

  EXPECT_THAT(
      Query(storage.get(), /*key_lower=*/std::numeric_limits<int64_t>::min(),
            /*key_upper=*/std::numeric_limits<int64_t>::max()),
      IsOkAndHolds(IsEmpty()));
}

TEST_P(IntegerIndexStorageTest, RangeQuerySingleEntireSortedBucket) {
  // We use predefined custom buckets to initialize new integer index storage
  // and create some test keys accordingly.
  std::vector<Bucket> custom_init_sorted_buckets = {
      Bucket(-1000, -100), Bucket(0, 100), Bucket(150, 199), Bucket(200, 300),
      Bucket(301, 999)};
  std::vector<Bucket> custom_init_unsorted_buckets = {
      Bucket(1000, std::numeric_limits<int64_t>::max()), Bucket(-99, -1),
      Bucket(101, 149), Bucket(std::numeric_limits<int64_t>::min(), -1001)};
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage,
      IntegerIndexStorage::Create(
          filesystem_, working_path_,
          Options(std::move(custom_init_sorted_buckets),
                  std::move(custom_init_unsorted_buckets),
                  /*pre_mapping_fbv_in=*/GetParam()),
          serializer_.get()));

  // Add some keys into sorted buckets [(-1000,-100), (200,300)].
  EXPECT_THAT(storage->AddKeys(/*document_id=*/0, kDefaultSectionId,
                               /*new_keys=*/{-500}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/1, kDefaultSectionId,
                               /*new_keys=*/{208}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/2, kDefaultSectionId,
                               /*new_keys=*/{-200}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/3, kDefaultSectionId,
                               /*new_keys=*/{-1000}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/4, kDefaultSectionId,
                               /*new_keys=*/{300}),
              IsOk());
  EXPECT_THAT(storage->num_data(), Eq(5));

  std::vector<SectionId> expected_sections = {kDefaultSectionId};
  // Range query on each sorted bucket boundary should get the correct result.
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/-1000, /*key_upper=*/-100),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/3, expected_sections),
                  EqualsDocHitInfo(/*document_id=*/2, expected_sections),
                  EqualsDocHitInfo(/*document_id=*/0, expected_sections))));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/0, /*key_upper=*/100),
              IsOkAndHolds(IsEmpty()));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/150, /*key_upper=*/199),
              IsOkAndHolds(IsEmpty()));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/200, /*key_upper=*/300),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/4, expected_sections),
                  EqualsDocHitInfo(/*document_id=*/1, expected_sections))));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/301, /*key_upper=*/999),
              IsOkAndHolds(IsEmpty()));
}

TEST_P(IntegerIndexStorageTest, RangeQuerySingleEntireUnsortedBucket) {
  // We use predefined custom buckets to initialize new integer index storage
  // and create some test keys accordingly.
  std::vector<Bucket> custom_init_sorted_buckets = {
      Bucket(-1000, -100), Bucket(0, 100), Bucket(150, 199), Bucket(200, 300),
      Bucket(301, 999)};
  std::vector<Bucket> custom_init_unsorted_buckets = {
      Bucket(1000, std::numeric_limits<int64_t>::max()), Bucket(-99, -1),
      Bucket(101, 149), Bucket(std::numeric_limits<int64_t>::min(), -1001)};
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage,
      IntegerIndexStorage::Create(
          filesystem_, working_path_,
          Options(std::move(custom_init_sorted_buckets),
                  std::move(custom_init_unsorted_buckets),
                  /*pre_mapping_fbv_in=*/GetParam()),
          serializer_.get()));

  // Add some keys into unsorted buckets [(1000,INT64_MAX), (INT64_MIN,-1001)].
  EXPECT_THAT(storage->AddKeys(/*document_id=*/0, kDefaultSectionId,
                               /*new_keys=*/{1024}),
              IsOk());
  EXPECT_THAT(
      storage->AddKeys(/*document_id=*/1, kDefaultSectionId,
                       /*new_keys=*/{std::numeric_limits<int64_t>::max()}),
      IsOk());
  EXPECT_THAT(
      storage->AddKeys(/*document_id=*/2, kDefaultSectionId,
                       /*new_keys=*/{std::numeric_limits<int64_t>::min()}),
      IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/3, kDefaultSectionId,
                               /*new_keys=*/{-1500}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/4, kDefaultSectionId,
                               /*new_keys=*/{2000}),
              IsOk());
  EXPECT_THAT(storage->num_data(), Eq(5));

  std::vector<SectionId> expected_sections = {kDefaultSectionId};
  // Range query on each unsorted bucket boundary should get the correct result.
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/1000,
                    /*key_upper=*/std::numeric_limits<int64_t>::max()),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/4, expected_sections),
                  EqualsDocHitInfo(/*document_id=*/1, expected_sections),
                  EqualsDocHitInfo(/*document_id=*/0, expected_sections))));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/-99, /*key_upper=*/-1),
              IsOkAndHolds(IsEmpty()));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/101, /*key_upper=*/149),
              IsOkAndHolds(IsEmpty()));
  EXPECT_THAT(
      Query(storage.get(), /*key_lower=*/std::numeric_limits<int64_t>::min(),
            /*key_upper=*/-1001),
      IsOkAndHolds(
          ElementsAre(EqualsDocHitInfo(/*document_id=*/3, expected_sections),
                      EqualsDocHitInfo(/*document_id=*/2, expected_sections))));
}

TEST_P(IntegerIndexStorageTest, RangeQuerySinglePartialSortedBucket) {
  // We use predefined custom buckets to initialize new integer index storage
  // and create some test keys accordingly.
  std::vector<Bucket> custom_init_sorted_buckets = {
      Bucket(-1000, -100), Bucket(0, 100), Bucket(150, 199), Bucket(200, 300),
      Bucket(301, 999)};
  std::vector<Bucket> custom_init_unsorted_buckets = {
      Bucket(1000, std::numeric_limits<int64_t>::max()), Bucket(-99, -1),
      Bucket(101, 149), Bucket(std::numeric_limits<int64_t>::min(), -1001)};
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage,
      IntegerIndexStorage::Create(
          filesystem_, working_path_,
          Options(std::move(custom_init_sorted_buckets),
                  std::move(custom_init_unsorted_buckets),
                  /*pre_mapping_fbv_in=*/GetParam()),
          serializer_.get()));

  // Add some keys into sorted bucket (0,100).
  EXPECT_THAT(storage->AddKeys(/*document_id=*/0, kDefaultSectionId,
                               /*new_keys=*/{43}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/1, kDefaultSectionId,
                               /*new_keys=*/{30}),
              IsOk());
  EXPECT_THAT(storage->num_data(), Eq(2));

  std::vector<SectionId> expected_sections = {kDefaultSectionId};
  // Range query on partial range of each sorted bucket should get the correct
  // result.
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/25, /*key_upper=*/200),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/1, expected_sections),
                  EqualsDocHitInfo(/*document_id=*/0, expected_sections))));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/-1000, /*key_upper=*/49),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/1, expected_sections),
                  EqualsDocHitInfo(/*document_id=*/0, expected_sections))));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/25, /*key_upper=*/49),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/1, expected_sections),
                  EqualsDocHitInfo(/*document_id=*/0, expected_sections))));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/31, /*key_upper=*/49),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/0, expected_sections))));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/25, /*key_upper=*/31),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/1, expected_sections))));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/3, /*key_upper=*/5),
              IsOkAndHolds(IsEmpty()));
}

TEST_P(IntegerIndexStorageTest, RangeQuerySinglePartialUnsortedBucket) {
  // We use predefined custom buckets to initialize new integer index storage
  // and create some test keys accordingly.
  std::vector<Bucket> custom_init_sorted_buckets = {
      Bucket(-1000, -100), Bucket(0, 100), Bucket(150, 199), Bucket(200, 300),
      Bucket(301, 999)};
  std::vector<Bucket> custom_init_unsorted_buckets = {
      Bucket(1000, std::numeric_limits<int64_t>::max()), Bucket(-99, -1),
      Bucket(101, 149), Bucket(std::numeric_limits<int64_t>::min(), -1001)};
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage,
      IntegerIndexStorage::Create(
          filesystem_, working_path_,
          Options(std::move(custom_init_sorted_buckets),
                  std::move(custom_init_unsorted_buckets),
                  /*pre_mapping_fbv_in=*/GetParam()),
          serializer_.get()));

  // Add some keys into unsorted buckets (-99,-1).
  EXPECT_THAT(storage->AddKeys(/*document_id=*/0, kDefaultSectionId,
                               /*new_keys=*/{-19}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/1, kDefaultSectionId,
                               /*new_keys=*/{-72}),
              IsOk());
  EXPECT_THAT(storage->num_data(), Eq(2));

  std::vector<SectionId> expected_sections = {kDefaultSectionId};
  // Range query on partial range of each unsorted bucket should get the correct
  // result.
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/-1000, /*key_upper=*/-15),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/1, expected_sections),
                  EqualsDocHitInfo(/*document_id=*/0, expected_sections))));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/-80, /*key_upper=*/149),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/1, expected_sections),
                  EqualsDocHitInfo(/*document_id=*/0, expected_sections))));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/-80, /*key_upper=*/-15),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/1, expected_sections),
                  EqualsDocHitInfo(/*document_id=*/0, expected_sections))));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/-38, /*key_upper=*/-15),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/0, expected_sections))));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/-80, /*key_upper=*/-38),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/1, expected_sections))));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/-95, /*key_upper=*/-92),
              IsOkAndHolds(IsEmpty()));
}

TEST_P(IntegerIndexStorageTest, RangeQueryMultipleBuckets) {
  // We use predefined custom buckets to initialize new integer index storage
  // and create some test keys accordingly.
  std::vector<Bucket> custom_init_sorted_buckets = {
      Bucket(-1000, -100), Bucket(0, 100), Bucket(150, 199), Bucket(200, 300),
      Bucket(301, 999)};
  std::vector<Bucket> custom_init_unsorted_buckets = {
      Bucket(1000, std::numeric_limits<int64_t>::max()), Bucket(-99, -1),
      Bucket(101, 149), Bucket(std::numeric_limits<int64_t>::min(), -1001)};
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage,
      IntegerIndexStorage::Create(
          filesystem_, working_path_,
          Options(std::move(custom_init_sorted_buckets),
                  std::move(custom_init_unsorted_buckets),
                  /*pre_mapping_fbv_in=*/GetParam()),
          serializer_.get()));

  // Add some keys into buckets [(-1000,-100), (200,300), (1000,INT64_MAX),
  // (INT64_MIN,-1001)]
  EXPECT_THAT(storage->AddKeys(/*document_id=*/0, kDefaultSectionId,
                               /*new_keys=*/{-500}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/1, kDefaultSectionId,
                               /*new_keys=*/{1024}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/2, kDefaultSectionId,
                               /*new_keys=*/{-200}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/3, kDefaultSectionId,
                               /*new_keys=*/{208}),
              IsOk());
  EXPECT_THAT(
      storage->AddKeys(/*document_id=*/4, kDefaultSectionId,
                       /*new_keys=*/{std::numeric_limits<int64_t>::max()}),
      IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/5, kDefaultSectionId,
                               /*new_keys=*/{-1000}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/6, kDefaultSectionId,
                               /*new_keys=*/{300}),
              IsOk());
  EXPECT_THAT(
      storage->AddKeys(/*document_id=*/7, kDefaultSectionId,
                       /*new_keys=*/{std::numeric_limits<int64_t>::min()}),
      IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/8, kDefaultSectionId,
                               /*new_keys=*/{-1500}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(/*document_id=*/9, kDefaultSectionId,
                               /*new_keys=*/{2000}),
              IsOk());
  EXPECT_THAT(storage->num_data(), Eq(10));

  std::vector<SectionId> expected_sections = {kDefaultSectionId};
  // Range query should get the correct result.
  EXPECT_THAT(
      Query(storage.get(), /*key_lower=*/std::numeric_limits<int64_t>::min(),
            /*key_upper=*/std::numeric_limits<int64_t>::max()),
      IsOkAndHolds(
          ElementsAre(EqualsDocHitInfo(/*document_id=*/9, expected_sections),
                      EqualsDocHitInfo(/*document_id=*/8, expected_sections),
                      EqualsDocHitInfo(/*document_id=*/7, expected_sections),
                      EqualsDocHitInfo(/*document_id=*/6, expected_sections),
                      EqualsDocHitInfo(/*document_id=*/5, expected_sections),
                      EqualsDocHitInfo(/*document_id=*/4, expected_sections),
                      EqualsDocHitInfo(/*document_id=*/3, expected_sections),
                      EqualsDocHitInfo(/*document_id=*/2, expected_sections),
                      EqualsDocHitInfo(/*document_id=*/1, expected_sections),
                      EqualsDocHitInfo(/*document_id=*/0, expected_sections))));
  EXPECT_THAT(Query(storage.get(), /*key_lower=*/-199,
                    /*key_upper=*/std::numeric_limits<int64_t>::max()),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/9, expected_sections),
                  EqualsDocHitInfo(/*document_id=*/6, expected_sections),
                  EqualsDocHitInfo(/*document_id=*/4, expected_sections),
                  EqualsDocHitInfo(/*document_id=*/3, expected_sections),
                  EqualsDocHitInfo(/*document_id=*/1, expected_sections))));
  EXPECT_THAT(
      Query(storage.get(), /*key_lower=*/std::numeric_limits<int64_t>::min(),
            /*key_upper=*/-200),
      IsOkAndHolds(
          ElementsAre(EqualsDocHitInfo(/*document_id=*/8, expected_sections),
                      EqualsDocHitInfo(/*document_id=*/7, expected_sections),
                      EqualsDocHitInfo(/*document_id=*/5, expected_sections),
                      EqualsDocHitInfo(/*document_id=*/2, expected_sections),
                      EqualsDocHitInfo(/*document_id=*/0, expected_sections))));
}

TEST_P(IntegerIndexStorageTest, BatchAdd) {
  // We use predefined custom buckets to initialize new integer index storage
  // and create some test keys accordingly.
  std::vector<Bucket> custom_init_sorted_buckets = {
      Bucket(-1000, -100), Bucket(0, 100), Bucket(150, 199), Bucket(200, 300),
      Bucket(301, 999)};
  std::vector<Bucket> custom_init_unsorted_buckets = {
      Bucket(1000, std::numeric_limits<int64_t>::max()), Bucket(-99, -1),
      Bucket(101, 149), Bucket(std::numeric_limits<int64_t>::min(), -1001)};
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage,
      IntegerIndexStorage::Create(
          filesystem_, working_path_,
          Options(std::move(custom_init_sorted_buckets),
                  std::move(custom_init_unsorted_buckets),
                  /*pre_mapping_fbv_in=*/GetParam()),
          serializer_.get()));

  // Batch add the following keys (including some edge cases) to test the
  // correctness of the sort and binary search logic in AddKeys().
  // clang-format off
  std::vector<int64_t> keys = {4000, 3000, 2000,  300,   201,   200,  106, 104,
                               100,  3,    2,     1,     0,     -97,  -98, -99,
                               -100, -200, -1000, -1001, -1500, -2000,
                               std::numeric_limits<int64_t>::max(),
                               std::numeric_limits<int64_t>::min()};
  // clang-format on
  EXPECT_THAT(storage->AddKeys(kDefaultDocumentId, kDefaultSectionId,
                               std::vector<int64_t>(keys)),
              IsOk());
  EXPECT_THAT(storage->num_data(), Eq(keys.size()));

  std::vector<SectionId> expected_sections = {kDefaultSectionId};
  for (int64_t key : keys) {
    EXPECT_THAT(Query(storage.get(), /*key_lower=*/key, /*key_upper=*/key),
                IsOkAndHolds(ElementsAre(
                    EqualsDocHitInfo(kDefaultDocumentId, expected_sections))));
  }
}

TEST_P(IntegerIndexStorageTest, BatchAddShouldDedupeKeys) {
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage,
      IntegerIndexStorage::Create(filesystem_, working_path_,
                                  Options(/*pre_mapping_fbv_in=*/GetParam()),
                                  serializer_.get()));

  std::vector<int64_t> keys = {2, 3, 1, 2, 4, -1, -1, 100, 3};
  EXPECT_THAT(
      storage->AddKeys(kDefaultDocumentId, kDefaultSectionId, std::move(keys)),
      IsOk());
  EXPECT_THAT(storage->num_data(), Eq(6));
}

TEST_P(IntegerIndexStorageTest, MultipleKeysShouldMergeAndDedupeDocHitInfo) {
  // We use predefined custom buckets to initialize new integer index storage
  // and create some test keys accordingly.
  std::vector<Bucket> custom_init_sorted_buckets = {
      Bucket(-1000, -100), Bucket(0, 100), Bucket(150, 199), Bucket(200, 300),
      Bucket(301, 999)};
  std::vector<Bucket> custom_init_unsorted_buckets = {
      Bucket(1000, std::numeric_limits<int64_t>::max()), Bucket(-99, -1),
      Bucket(101, 149), Bucket(std::numeric_limits<int64_t>::min(), -1001)};
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage,
      IntegerIndexStorage::Create(
          filesystem_, working_path_,
          Options(std::move(custom_init_sorted_buckets),
                  std::move(custom_init_unsorted_buckets),
                  /*pre_mapping_fbv_in=*/GetParam()),
          serializer_.get()));

  // Add some keys with same document id and section id.
  EXPECT_THAT(
      storage->AddKeys(
          /*document_id=*/0, kDefaultSectionId, /*new_keys=*/
          {-500, 1024, -200, 208, std::numeric_limits<int64_t>::max(), -1000,
           300, std::numeric_limits<int64_t>::min(), -1500, 2000}),
      IsOk());
  EXPECT_THAT(storage->num_data(), Eq(10));

  std::vector<SectionId> expected_sections = {kDefaultSectionId};
  EXPECT_THAT(
      Query(storage.get(), /*key_lower=*/std::numeric_limits<int64_t>::min(),
            /*key_upper=*/std::numeric_limits<int64_t>::max()),
      IsOkAndHolds(
          ElementsAre(EqualsDocHitInfo(/*document_id=*/0, expected_sections))));
}

TEST_P(IntegerIndexStorageTest,
       MultipleSectionsShouldMergeSectionsAndDedupeDocHitInfo) {
  // We use predefined custom buckets to initialize new integer index storage
  // and create some test keys accordingly.
  std::vector<Bucket> custom_init_sorted_buckets = {
      Bucket(-1000, -100), Bucket(0, 100), Bucket(150, 199), Bucket(200, 300),
      Bucket(301, 999)};
  std::vector<Bucket> custom_init_unsorted_buckets = {
      Bucket(1000, std::numeric_limits<int64_t>::max()), Bucket(-99, -1),
      Bucket(101, 149), Bucket(std::numeric_limits<int64_t>::min(), -1001)};
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage,
      IntegerIndexStorage::Create(
          filesystem_, working_path_,
          Options(std::move(custom_init_sorted_buckets),
                  std::move(custom_init_unsorted_buckets),
                  /*pre_mapping_fbv_in=*/GetParam()),
          serializer_.get()));

  // Add some keys with same document id but different section ids.
  EXPECT_THAT(storage->AddKeys(kDefaultDocumentId, /*section_id=*/63,
                               /*new_keys=*/{-500}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(kDefaultDocumentId, /*section_id=*/62,
                               /*new_keys=*/{1024}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(kDefaultDocumentId, /*section_id=*/61,
                               /*new_keys=*/{-200}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(kDefaultDocumentId, /*section_id=*/60,
                               /*new_keys=*/{208}),
              IsOk());
  EXPECT_THAT(
      storage->AddKeys(kDefaultDocumentId, /*section_id=*/59,
                       /*new_keys=*/{std::numeric_limits<int64_t>::max()}),
      IsOk());
  EXPECT_THAT(storage->AddKeys(kDefaultDocumentId, /*section_id=*/58,
                               /*new_keys=*/{-1000}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(kDefaultDocumentId, /*section_id=*/57,
                               /*new_keys=*/{300}),
              IsOk());
  EXPECT_THAT(
      storage->AddKeys(kDefaultDocumentId, /*section_id=*/56,
                       /*new_keys=*/{std::numeric_limits<int64_t>::min()}),
      IsOk());
  EXPECT_THAT(storage->AddKeys(kDefaultDocumentId, /*section_id=*/55,
                               /*new_keys=*/{-1500}),
              IsOk());
  EXPECT_THAT(storage->AddKeys(kDefaultDocumentId, /*section_id=*/54,
                               /*new_keys=*/{2000}),
              IsOk());
  EXPECT_THAT(storage->num_data(), Eq(10));

  std::vector<SectionId> expected_sections = {63, 62, 61, 60, 59,
                                              58, 57, 56, 55, 54};
  EXPECT_THAT(
      Query(storage.get(), /*key_lower=*/std::numeric_limits<int64_t>::min(),
            /*key_upper=*/std::numeric_limits<int64_t>::max()),
      IsOkAndHolds(ElementsAre(
          EqualsDocHitInfo(kDefaultDocumentId, expected_sections))));
}

TEST_P(IntegerIndexStorageTest, SplitBuckets) {
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage,
      IntegerIndexStorage::Create(filesystem_, working_path_,
                                  Options(/*pre_mapping_fbv_in=*/GetParam()),
                                  serializer_.get()));

  uint32_t block_size = FlashIndexStorage::SelectBlockSize();
  uint32_t max_posting_list_bytes = IndexBlock::CalculateMaxPostingListBytes(
      block_size, serializer_->GetDataTypeBytes());
  uint32_t max_num_data_before_split =
      max_posting_list_bytes / serializer_->GetDataTypeBytes();

  // Add max_num_data_before_split + 1 keys to invoke bucket splitting.
  // Keys: max_num_data_before_split to 0
  // Document ids: 0 to max_num_data_before_split
  std::unordered_map<int64_t, DocumentId> data;
  int64_t key = max_num_data_before_split;
  DocumentId document_id = 0;
  for (int i = 0; i < max_num_data_before_split + 1; ++i) {
    data[key] = document_id;
    ICING_ASSERT_OK(
        storage->AddKeys(document_id, kDefaultSectionId, /*new_keys=*/{key}));
    ++document_id;
    --key;
  }
  ICING_ASSERT_OK(storage->PersistToDisk());

  // Manually check sorted and unsorted buckets.
  {
    // Check sorted buckets.
    const std::string sorted_buckets_file_path = absl_ports::StrCat(
        working_path_, "/", IntegerIndexStorage::kFilePrefix, ".s");
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<FileBackedVector<Bucket>> sorted_buckets,
        FileBackedVector<Bucket>::Create(
            filesystem_, sorted_buckets_file_path,
            MemoryMappedFile::Strategy::READ_WRITE_AUTO_SYNC));

    EXPECT_THAT(sorted_buckets->num_elements(), Eq(1));
    ICING_ASSERT_OK_AND_ASSIGN(const Bucket* bucket1,
                               sorted_buckets->Get(/*idx=*/0));
    EXPECT_THAT(bucket1->key_lower(), Eq(std::numeric_limits<int64_t>::min()));
    EXPECT_THAT(bucket1->key_upper(), Ne(std::numeric_limits<int64_t>::max()));

    int64_t sorted_bucket_key_upper = bucket1->key_upper();

    // Check unsorted buckets.
    const std::string unsorted_buckets_file_path = absl_ports::StrCat(
        working_path_, "/", IntegerIndexStorage::kFilePrefix, ".u");
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<FileBackedVector<Bucket>> unsorted_buckets,
        FileBackedVector<Bucket>::Create(
            filesystem_, unsorted_buckets_file_path,
            MemoryMappedFile::Strategy::READ_WRITE_AUTO_SYNC));

    EXPECT_THAT(unsorted_buckets->num_elements(), Ge(1));
    ICING_ASSERT_OK_AND_ASSIGN(const Bucket* bucket2,
                               unsorted_buckets->Get(/*idx=*/0));
    EXPECT_THAT(bucket2->key_lower(), Eq(sorted_bucket_key_upper + 1));
  }

  // Ensure that search works normally.
  std::vector<SectionId> expected_sections = {kDefaultSectionId};
  for (int64_t key = max_num_data_before_split; key >= 0; key--) {
    ASSERT_THAT(data, Contains(Key(key)));
    DocumentId expected_document_id = data[key];
    EXPECT_THAT(Query(storage.get(), /*key_lower=*/key, /*key_upper=*/key),
                IsOkAndHolds(ElementsAre(EqualsDocHitInfo(expected_document_id,
                                                          expected_sections))));
  }
}

TEST_P(IntegerIndexStorageTest, SplitBucketsTriggerSortBuckets) {
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage,
      IntegerIndexStorage::Create(filesystem_, working_path_,
                                  Options(/*pre_mapping_fbv_in=*/GetParam()),
                                  serializer_.get()));

  uint32_t block_size = FlashIndexStorage::SelectBlockSize();
  uint32_t max_posting_list_bytes = IndexBlock::CalculateMaxPostingListBytes(
      block_size, serializer_->GetDataTypeBytes());
  uint32_t max_num_data_before_split =
      max_posting_list_bytes / serializer_->GetDataTypeBytes();

  // Add IntegerIndexStorage::kUnsortedBucketsLengthThreshold keys. For each
  // key, add max_num_data_before_split + 1 data. Then we will get:
  // - Bucket splitting will create kUnsortedBucketsLengthThreshold + 1 unsorted
  //   buckets [[50, 50], [49, 49], ..., [1, 1], [51, INT64_MAX]].
  // - Since there are kUnsortedBucketsLengthThreshold + 1 unsorted buckets, we
  //   should sort and merge buckets.
  std::unordered_map<int64_t, std::vector<DocumentId>> data;
  int64_t key = IntegerIndexStorage::kUnsortedBucketsLengthThreshold;
  DocumentId document_id = 0;
  for (int i = 0; i < IntegerIndexStorage::kUnsortedBucketsLengthThreshold;
       ++i) {
    for (int j = 0; j < max_num_data_before_split + 1; ++j) {
      data[key].push_back(document_id);
      ICING_ASSERT_OK(
          storage->AddKeys(document_id, kDefaultSectionId, /*new_keys=*/{key}));
      ++document_id;
    }
    --key;
  }
  ICING_ASSERT_OK(storage->PersistToDisk());

  // Manually check sorted and unsorted buckets.
  {
    // Check unsorted buckets.
    const std::string unsorted_buckets_file_path = absl_ports::StrCat(
        working_path_, "/", IntegerIndexStorage::kFilePrefix, ".u");
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<FileBackedVector<Bucket>> unsorted_buckets,
        FileBackedVector<Bucket>::Create(
            filesystem_, unsorted_buckets_file_path,
            MemoryMappedFile::Strategy::READ_WRITE_AUTO_SYNC));
    EXPECT_THAT(unsorted_buckets->num_elements(), Eq(0));

    // Check sorted buckets.
    const std::string sorted_buckets_file_path = absl_ports::StrCat(
        working_path_, "/", IntegerIndexStorage::kFilePrefix, ".s");
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<FileBackedVector<Bucket>> sorted_buckets,
        FileBackedVector<Bucket>::Create(
            filesystem_, sorted_buckets_file_path,
            MemoryMappedFile::Strategy::READ_WRITE_AUTO_SYNC));
    EXPECT_THAT(sorted_buckets->num_elements(), Gt(1));
  }

  // Ensure that search works normally.
  for (key = 1; key <= IntegerIndexStorage::kUnsortedBucketsLengthThreshold;
       ++key) {
    ASSERT_THAT(data, Contains(Key(key)));

    std::vector<DocHitInfo> expected_doc_hit_infos;
    for (DocumentId doc_id : data[key]) {
      expected_doc_hit_infos.push_back(DocHitInfo(
          doc_id, /*hit_section_ids_mask=*/UINT64_C(1) << kDefaultSectionId));
    }
    EXPECT_THAT(Query(storage.get(), /*key_lower=*/key, /*key_upper=*/key),
                IsOkAndHolds(ElementsAreArray(expected_doc_hit_infos.rbegin(),
                                              expected_doc_hit_infos.rend())));
  }
}

TEST_P(IntegerIndexStorageTest, TransferIndex) {
  // We use predefined custom buckets to initialize new integer index storage
  // and create some test keys accordingly.
  std::vector<Bucket> custom_init_sorted_buckets = {
      Bucket(-1000, -100), Bucket(0, 100), Bucket(150, 199), Bucket(200, 300),
      Bucket(301, 999)};
  std::vector<Bucket> custom_init_unsorted_buckets = {
      Bucket(1000, std::numeric_limits<int64_t>::max()), Bucket(-99, -1),
      Bucket(101, 149), Bucket(std::numeric_limits<int64_t>::min(), -1001)};
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage,
      IntegerIndexStorage::Create(
          filesystem_, working_path_,
          Options(std::move(custom_init_sorted_buckets),
                  std::move(custom_init_unsorted_buckets),
                  /*pre_mapping_fbv_in=*/GetParam()),
          serializer_.get()));

  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/1, kDefaultSectionId,
                                   /*new_keys=*/{-500}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/2, kDefaultSectionId,
                                   /*new_keys=*/{1024}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/3, kDefaultSectionId,
                                   /*new_keys=*/{-200}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/5, kDefaultSectionId,
                                   /*new_keys=*/{-60}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/8, kDefaultSectionId,
                                   /*new_keys=*/{-60}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/13, kDefaultSectionId,
                                   /*new_keys=*/{-500}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/21, kDefaultSectionId,
                                   /*new_keys=*/{2048}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/34, kDefaultSectionId,
                                   /*new_keys=*/{156}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/55, kDefaultSectionId,
                                   /*new_keys=*/{20}));
  ASSERT_THAT(storage->num_data(), Eq(9));

  // Delete doc id = 5, 34, compress and keep the rest.
  std::vector<DocumentId> document_id_old_to_new(56, kInvalidDocumentId);
  document_id_old_to_new[1] = 8;
  document_id_old_to_new[2] = 3;
  document_id_old_to_new[3] = 0;
  document_id_old_to_new[8] = 2;
  document_id_old_to_new[13] = 6;
  document_id_old_to_new[21] = 1;
  document_id_old_to_new[55] = 4;

  // Transfer to new storage.
  {
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<IntegerIndexStorage> new_storage,
        IntegerIndexStorage::Create(filesystem_, working_path_ + "_temp",
                                    Options(/*pre_mapping_fbv_in=*/GetParam()),
                                    serializer_.get()));
    EXPECT_THAT(
        storage->TransferIndex(document_id_old_to_new, new_storage.get()),
        IsOk());
    ICING_ASSERT_OK(new_storage->PersistToDisk());
  }

  // Verify after transferring and reinitializing the instance.
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> new_storage,
      IntegerIndexStorage::Create(filesystem_, working_path_ + "_temp",
                                  Options(/*pre_mapping_fbv_in=*/GetParam()),
                                  serializer_.get()));

  std::vector<SectionId> expected_sections = {kDefaultSectionId};
  EXPECT_THAT(new_storage->num_data(), Eq(7));

  // -500 had hits for old_docids 1 and 13, which are now 6 and 8.
  EXPECT_THAT(Query(new_storage.get(), /*key_lower=*/-500, /*key_upper=*/-500),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/8, expected_sections),
                  EqualsDocHitInfo(/*document_id=*/6, expected_sections))));

  // 1024 had a hit for old_docid 2, which is now 3.
  EXPECT_THAT(Query(new_storage.get(), /*key_lower=*/1024, /*key_upper=*/1024),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/3, expected_sections))));

  // -200 had a hit for old_docid 3, which is now 0.
  EXPECT_THAT(Query(new_storage.get(), /*key_lower=*/-200, /*key_upper=*/-200),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/0, expected_sections))));

  // -60 had hits for old_docids 5 and 8, which is now only 2 (because doc 5 has
  // been deleted).
  EXPECT_THAT(Query(new_storage.get(), /*key_lower=*/-60, /*key_upper=*/-60),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/2, expected_sections))));

  // 2048 had a hit for old_docid 21, which is now 1.
  EXPECT_THAT(Query(new_storage.get(), /*key_lower=*/2048, /*key_upper=*/2048),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/1, expected_sections))));

  // 156 had a hit for old_docid 34, which is not found now (because doc 34 has
  // been deleted).
  EXPECT_THAT(Query(new_storage.get(), /*key_lower=*/156, /*key_upper=*/156),
              IsOkAndHolds(IsEmpty()));

  // 20 had a hit for old_docid 55, which is now 4.
  EXPECT_THAT(Query(new_storage.get(), /*key_lower=*/20, /*key_upper=*/20),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/4, expected_sections))));
}

TEST_P(IntegerIndexStorageTest, TransferIndexOutOfRangeDocumentId) {
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage,
      IntegerIndexStorage::Create(filesystem_, working_path_,
                                  Options(/*pre_mapping_fbv_in=*/GetParam()),
                                  serializer_.get()));

  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/1, kDefaultSectionId,
                                   /*new_keys=*/{120}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/2, kDefaultSectionId,
                                   /*new_keys=*/{-2000}));
  ASSERT_THAT(storage->num_data(), Eq(2));

  // Create document_id_old_to_new with size = 2. TransferIndex should handle
  // out of range DocumentId properly.
  std::vector<DocumentId> document_id_old_to_new = {kInvalidDocumentId, 0};

  // Transfer to new storage.
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> new_storage,
      IntegerIndexStorage::Create(filesystem_, working_path_ + "_temp",
                                  Options(/*pre_mapping_fbv_in=*/GetParam()),
                                  serializer_.get()));
  EXPECT_THAT(storage->TransferIndex(document_id_old_to_new, new_storage.get()),
              IsOk());

  // Verify after transferring.
  std::vector<SectionId> expected_sections = {kDefaultSectionId};
  EXPECT_THAT(new_storage->num_data(), Eq(1));
  EXPECT_THAT(Query(new_storage.get(), /*key_lower=*/120, /*key_upper=*/120),
              IsOkAndHolds(ElementsAre(
                  EqualsDocHitInfo(/*document_id=*/0, expected_sections))));
  EXPECT_THAT(
      Query(new_storage.get(), /*key_lower=*/-2000, /*key_upper=*/-2000),
      IsOkAndHolds(IsEmpty()));
}

TEST_P(IntegerIndexStorageTest, TransferEmptyIndex) {
  // We use predefined custom buckets to initialize new integer index storage
  // and create some test keys accordingly.
  std::vector<Bucket> custom_init_sorted_buckets = {
      Bucket(-1000, -100), Bucket(0, 100), Bucket(150, 199), Bucket(200, 300),
      Bucket(301, 999)};
  std::vector<Bucket> custom_init_unsorted_buckets = {
      Bucket(1000, std::numeric_limits<int64_t>::max()), Bucket(-99, -1),
      Bucket(101, 149), Bucket(std::numeric_limits<int64_t>::min(), -1001)};
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage,
      IntegerIndexStorage::Create(
          filesystem_, working_path_,
          Options(std::move(custom_init_sorted_buckets),
                  std::move(custom_init_unsorted_buckets),
                  /*pre_mapping_fbv_in=*/GetParam()),
          serializer_.get()));
  ASSERT_THAT(storage->num_data(), Eq(0));

  std::vector<DocumentId> document_id_old_to_new = {kInvalidDocumentId, 0, 1,
                                                    kInvalidDocumentId, 2};

  // Transfer to new storage.
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> new_storage,
      IntegerIndexStorage::Create(filesystem_, working_path_ + "_temp",
                                  Options(/*pre_mapping_fbv_in=*/GetParam()),
                                  serializer_.get()));
  EXPECT_THAT(storage->TransferIndex(document_id_old_to_new, new_storage.get()),
              IsOk());

  // Verify after transferring.
  EXPECT_THAT(new_storage->num_data(), Eq(0));
  EXPECT_THAT(Query(new_storage.get(),
                    /*key_lower=*/std::numeric_limits<int64_t>::min(),
                    /*key_upper=*/std::numeric_limits<int64_t>::max()),
              IsOkAndHolds(IsEmpty()));
}

TEST_P(IntegerIndexStorageTest, TransferIndexDeleteAll) {
  // We use predefined custom buckets to initialize new integer index storage
  // and create some test keys accordingly.
  std::vector<Bucket> custom_init_sorted_buckets = {
      Bucket(-1000, -100), Bucket(0, 100), Bucket(150, 199), Bucket(200, 300),
      Bucket(301, 999)};
  std::vector<Bucket> custom_init_unsorted_buckets = {
      Bucket(1000, std::numeric_limits<int64_t>::max()), Bucket(-99, -1),
      Bucket(101, 149), Bucket(std::numeric_limits<int64_t>::min(), -1001)};
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage,
      IntegerIndexStorage::Create(
          filesystem_, working_path_,
          Options(std::move(custom_init_sorted_buckets),
                  std::move(custom_init_unsorted_buckets),
                  /*pre_mapping_fbv_in=*/GetParam()),
          serializer_.get()));

  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/1, kDefaultSectionId,
                                   /*new_keys=*/{-500}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/2, kDefaultSectionId,
                                   /*new_keys=*/{1024}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/3, kDefaultSectionId,
                                   /*new_keys=*/{-200}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/5, kDefaultSectionId,
                                   /*new_keys=*/{-60}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/8, kDefaultSectionId,
                                   /*new_keys=*/{-60}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/13, kDefaultSectionId,
                                   /*new_keys=*/{-500}));
  ASSERT_THAT(storage->num_data(), Eq(6));

  // Delete all documents.
  std::vector<DocumentId> document_id_old_to_new(14, kInvalidDocumentId);

  // Transfer to new storage.
  {
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<IntegerIndexStorage> new_storage,
        IntegerIndexStorage::Create(filesystem_, working_path_ + "_temp",
                                    Options(/*pre_mapping_fbv_in=*/GetParam()),
                                    serializer_.get()));
    EXPECT_THAT(
        storage->TransferIndex(document_id_old_to_new, new_storage.get()),
        IsOk());
    ICING_ASSERT_OK(new_storage->PersistToDisk());
  }

  // Verify after transferring and reinitializing the instance.
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> new_storage,
      IntegerIndexStorage::Create(filesystem_, working_path_ + "_temp",
                                  Options(/*pre_mapping_fbv_in=*/GetParam()),
                                  serializer_.get()));

  std::vector<SectionId> expected_sections = {kDefaultSectionId};
  EXPECT_THAT(new_storage->num_data(), Eq(0));
  EXPECT_THAT(Query(new_storage.get(),
                    /*key_lower=*/std::numeric_limits<int64_t>::min(),
                    /*key_upper=*/std::numeric_limits<int64_t>::max()),
              IsOkAndHolds(IsEmpty()));
}

TEST_P(IntegerIndexStorageTest, TransferIndexShouldInvokeMergeBuckets) {
  // This test verifies that if TransferIndex invokes bucket merging logic to
  // ensure sure we're able to avoid having mostly empty buckets after inserting
  // and deleting data for many rounds.

  // We use predefined custom buckets to initialize new integer index storage
  // and create some test keys accordingly.
  std::vector<Bucket> custom_init_sorted_buckets = {
      Bucket(-1000, -100), Bucket(0, 100), Bucket(150, 199), Bucket(200, 300),
      Bucket(301, 999)};
  std::vector<Bucket> custom_init_unsorted_buckets = {
      Bucket(1000, std::numeric_limits<int64_t>::max()), Bucket(-99, -1),
      Bucket(101, 149), Bucket(std::numeric_limits<int64_t>::min(), -1001)};
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage,
      IntegerIndexStorage::Create(
          filesystem_, working_path_,
          Options(std::move(custom_init_sorted_buckets),
                  std::move(custom_init_unsorted_buckets),
                  /*pre_mapping_fbv_in=*/GetParam()),
          serializer_.get()));

  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/0, kDefaultSectionId,
                                   /*new_keys=*/{-500}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/1, kDefaultSectionId,
                                   /*new_keys=*/{1024}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/2, kDefaultSectionId,
                                   /*new_keys=*/{-200}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/3, kDefaultSectionId,
                                   /*new_keys=*/{-60}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/4, kDefaultSectionId,
                                   /*new_keys=*/{-60}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/5, kDefaultSectionId,
                                   /*new_keys=*/{-500}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/6, kDefaultSectionId,
                                   /*new_keys=*/{2048}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/7, kDefaultSectionId,
                                   /*new_keys=*/{156}));
  ICING_ASSERT_OK(storage->AddKeys(/*document_id=*/8, kDefaultSectionId,
                                   /*new_keys=*/{20}));
  ASSERT_THAT(storage->num_data(), Eq(9));
  ASSERT_THAT(storage->num_data(),
              Le(IntegerIndexStorage::kNumDataThresholdForBucketMerge));

  // Create document_id_old_to_new that keeps all existing documents.
  std::vector<DocumentId> document_id_old_to_new(9);
  std::iota(document_id_old_to_new.begin(), document_id_old_to_new.end(), 0);

  // Transfer to new storage. It should result in 1 bucket: [INT64_MIN,
  // INT64_MAX] after transferring.
  const std::string new_storage_working_path = working_path_ + "_temp";
  {
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<IntegerIndexStorage> new_storage,
        IntegerIndexStorage::Create(filesystem_, new_storage_working_path,
                                    Options(/*pre_mapping_fbv_in=*/GetParam()),
                                    serializer_.get()));
    EXPECT_THAT(
        storage->TransferIndex(document_id_old_to_new, new_storage.get()),
        IsOk());
  }

  // Check new_storage->sorted_bucket_ manually.
  const std::string sorted_buckets_file_path = absl_ports::StrCat(
      new_storage_working_path, "/", IntegerIndexStorage::kFilePrefix, ".s");
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<FileBackedVector<Bucket>> sorted_buckets,
      FileBackedVector<Bucket>::Create(
          filesystem_, sorted_buckets_file_path,
          MemoryMappedFile::Strategy::READ_WRITE_AUTO_SYNC));
  EXPECT_THAT(sorted_buckets->num_elements(), Eq(1));

  ICING_ASSERT_OK_AND_ASSIGN(const Bucket* bk1, sorted_buckets->Get(/*idx=*/0));
  EXPECT_THAT(bk1->key_lower(), Eq(std::numeric_limits<int64_t>::min()));
  EXPECT_THAT(bk1->key_upper(), Eq(std::numeric_limits<int64_t>::max()));
}

TEST_P(IntegerIndexStorageTest, TransferIndexExceedsMergeThreshold) {
  // This test verifies that if TransferIndex invokes bucket merging logic and
  // doesn't merge buckets too aggressively to ensure we won't get a bucket with
  // too many data.

  // We use predefined custom buckets to initialize new integer index storage
  // and create some test keys accordingly.
  std::vector<Bucket> custom_init_sorted_buckets = {
      Bucket(-1000, -100), Bucket(0, 100), Bucket(150, 199), Bucket(200, 300),
      Bucket(301, 999)};
  std::vector<Bucket> custom_init_unsorted_buckets = {
      Bucket(1000, std::numeric_limits<int64_t>::max()), Bucket(-99, -1),
      Bucket(101, 149), Bucket(std::numeric_limits<int64_t>::min(), -1001)};
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<IntegerIndexStorage> storage,
      IntegerIndexStorage::Create(
          filesystem_, working_path_,
          Options(std::move(custom_init_sorted_buckets),
                  std::move(custom_init_unsorted_buckets),
                  /*pre_mapping_fbv_in=*/GetParam()),
          serializer_.get()));

  // Insert data into 2 buckets so that total # of these 2 buckets exceed
  // kNumDataThresholdForBucketMerge.
  // - Bucket 1: [-1000, -100]
  // - Bucket 2: [101, 149]
  DocumentId document_id = 0;
  int num_data_for_bucket1 = 200;
  for (int i = 0; i < num_data_for_bucket1; ++i) {
    ICING_ASSERT_OK(storage->AddKeys(document_id, kDefaultSectionId,
                                     /*new_keys=*/{-200}));
    ++document_id;
  }

  int num_data_for_bucket2 = 150;
  for (int i = 0; i < num_data_for_bucket2; ++i) {
    ICING_ASSERT_OK(storage->AddKeys(document_id, kDefaultSectionId,
                                     /*new_keys=*/{120}));
    ++document_id;
  }

  ASSERT_THAT(num_data_for_bucket1 + num_data_for_bucket2,
              Gt(IntegerIndexStorage::kNumDataThresholdForBucketMerge));

  // Create document_id_old_to_new that keeps all existing documents.
  std::vector<DocumentId> document_id_old_to_new(document_id);
  std::iota(document_id_old_to_new.begin(), document_id_old_to_new.end(), 0);

  // Transfer to new storage. This should result in 2 buckets: [INT64_MIN, 100]
  // and [101, INT64_MAX]
  const std::string new_storage_working_path = working_path_ + "_temp";
  {
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<IntegerIndexStorage> new_storage,
        IntegerIndexStorage::Create(filesystem_, new_storage_working_path,
                                    Options(/*pre_mapping_fbv_in=*/GetParam()),
                                    serializer_.get()));
    EXPECT_THAT(
        storage->TransferIndex(document_id_old_to_new, new_storage.get()),
        IsOk());
  }

  // Check new_storage->sorted_bucket_ manually.
  const std::string sorted_buckets_file_path = absl_ports::StrCat(
      new_storage_working_path, "/", IntegerIndexStorage::kFilePrefix, ".s");
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<FileBackedVector<Bucket>> sorted_buckets,
      FileBackedVector<Bucket>::Create(
          filesystem_, sorted_buckets_file_path,
          MemoryMappedFile::Strategy::READ_WRITE_AUTO_SYNC));
  EXPECT_THAT(sorted_buckets->num_elements(), Eq(2));

  ICING_ASSERT_OK_AND_ASSIGN(const Bucket* bk1, sorted_buckets->Get(/*idx=*/0));
  EXPECT_THAT(bk1->key_lower(), Eq(std::numeric_limits<int64_t>::min()));
  EXPECT_THAT(bk1->key_upper(), Eq(100));
  ICING_ASSERT_OK_AND_ASSIGN(const Bucket* bk2, sorted_buckets->Get(/*idx=*/1));
  EXPECT_THAT(bk2->key_lower(), Eq(101));
  EXPECT_THAT(bk2->key_upper(), Eq(std::numeric_limits<int64_t>::max()));
}

INSTANTIATE_TEST_SUITE_P(IntegerIndexStorageTest, IntegerIndexStorageTest,
                         testing::Values(true, false));

}  // namespace

}  // namespace lib
}  // namespace icing
