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

#include "icing/join/qualified-id-type-joinable-index.h"

#include <memory>
#include <string>
#include <string_view>

#include "icing/text_classifier/lib3/utils/base/status.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "icing/file/file-backed-vector.h"
#include "icing/file/filesystem.h"
#include "icing/file/persistent-storage.h"
#include "icing/join/doc-join-info.h"
#include "icing/store/document-id.h"
#include "icing/store/dynamic-trie-key-mapper.h"
#include "icing/store/key-mapper.h"
#include "icing/store/persistent-hash-map-key-mapper.h"
#include "icing/testing/common-matchers.h"
#include "icing/testing/tmp-directory.h"
#include "icing/util/crc32.h"

namespace icing {
namespace lib {

namespace {

using ::testing::Eq;
using ::testing::HasSubstr;
using ::testing::IsEmpty;
using ::testing::IsTrue;
using ::testing::Lt;
using ::testing::Ne;
using ::testing::Not;
using ::testing::Pointee;
using ::testing::SizeIs;

using Crcs = PersistentStorage::Crcs;
using Info = QualifiedIdTypeJoinableIndex::Info;

static constexpr int32_t kCorruptedValueOffset = 3;

struct QualifiedIdJoinIndexTestParam {
  bool pre_mapping_fbv;
  bool use_persistent_hash_map;

  explicit QualifiedIdJoinIndexTestParam(bool pre_mapping_fbv_in,
                                         bool use_persistent_hash_map_in)
      : pre_mapping_fbv(pre_mapping_fbv_in),
        use_persistent_hash_map(use_persistent_hash_map_in) {}
};

class QualifiedIdTypeJoinableIndexTest
    : public ::testing::TestWithParam<QualifiedIdJoinIndexTestParam> {
 protected:
  void SetUp() override {
    base_dir_ = GetTestTempDir() + "/icing";
    ASSERT_THAT(filesystem_.CreateDirectoryRecursively(base_dir_.c_str()),
                IsTrue());

    working_path_ = base_dir_ + "/qualified_id_type_joinable_index_test";
  }

  void TearDown() override {
    filesystem_.DeleteDirectoryRecursively(base_dir_.c_str());
  }

  Filesystem filesystem_;
  std::string base_dir_;
  std::string working_path_;
};

TEST_P(QualifiedIdTypeJoinableIndexTest, InvalidWorkingPath) {
  const QualifiedIdJoinIndexTestParam& param = GetParam();

  EXPECT_THAT(
      QualifiedIdTypeJoinableIndex::Create(
          filesystem_, "/dev/null/qualified_id_type_joinable_index_test",
          param.pre_mapping_fbv, param.use_persistent_hash_map),
      StatusIs(libtextclassifier3::StatusCode::INTERNAL));
}

TEST_P(QualifiedIdTypeJoinableIndexTest, InitializeNewFiles) {
  const QualifiedIdJoinIndexTestParam& param = GetParam();

  {
    // Create new qualified id type joinable index
    ASSERT_FALSE(filesystem_.DirectoryExists(working_path_.c_str()));
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<QualifiedIdTypeJoinableIndex> index,
        QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                             param.pre_mapping_fbv,
                                             param.use_persistent_hash_map));
    EXPECT_THAT(index, Pointee(IsEmpty()));

    ICING_ASSERT_OK(index->PersistToDisk());
  }

  // Metadata file should be initialized correctly for both info and crcs
  // sections.
  const std::string metadata_file_path =
      absl_ports::StrCat(working_path_, "/metadata");
  auto metadata_buffer = std::make_unique<uint8_t[]>(
      QualifiedIdTypeJoinableIndex::kMetadataFileSize);
  ASSERT_THAT(
      filesystem_.PRead(metadata_file_path.c_str(), metadata_buffer.get(),
                        QualifiedIdTypeJoinableIndex::kMetadataFileSize,
                        /*offset=*/0),
      IsTrue());

  // Check info section
  const Info* info = reinterpret_cast<const Info*>(
      metadata_buffer.get() +
      QualifiedIdTypeJoinableIndex::kInfoMetadataBufferOffset);
  EXPECT_THAT(info->magic, Eq(Info::kMagic));
  EXPECT_THAT(info->last_added_document_id, Eq(kInvalidDocumentId));

  // Check crcs section
  const Crcs* crcs = reinterpret_cast<const Crcs*>(
      metadata_buffer.get() +
      QualifiedIdTypeJoinableIndex::kCrcsMetadataBufferOffset);
  // There are some initial info in KeyMapper, so storages_crc should be
  // non-zero.
  EXPECT_THAT(crcs->component_crcs.storages_crc, Ne(0));
  EXPECT_THAT(crcs->component_crcs.info_crc,
              Eq(Crc32(std::string_view(reinterpret_cast<const char*>(info),
                                        sizeof(Info)))
                     .Get()));
  EXPECT_THAT(crcs->all_crc,
              Eq(Crc32(std::string_view(
                           reinterpret_cast<const char*>(&crcs->component_crcs),
                           sizeof(Crcs::ComponentCrcs)))
                     .Get()));
}

TEST_P(QualifiedIdTypeJoinableIndexTest,
       InitializationShouldFailWithoutPersistToDiskOrDestruction) {
  const QualifiedIdJoinIndexTestParam& param = GetParam();

  // Create new qualified id type joinable index
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<QualifiedIdTypeJoinableIndex> index,
      QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                           param.pre_mapping_fbv,
                                           param.use_persistent_hash_map));

  // Insert some data.
  ICING_ASSERT_OK(
      index->Put(DocJoinInfo(/*document_id=*/1, /*joinable_property_id=*/20),
                 /*ref_qualified_id_str=*/"namespace#uriA"));
  ICING_ASSERT_OK(index->PersistToDisk());
  ICING_ASSERT_OK(
      index->Put(DocJoinInfo(/*document_id=*/3, /*joinable_property_id=*/20),
                 /*ref_qualified_id_str=*/"namespace#uriB"));
  ICING_ASSERT_OK(
      index->Put(DocJoinInfo(/*document_id=*/5, /*joinable_property_id=*/20),
                 /*ref_qualified_id_str=*/"namespace#uriC"));

  // Without calling PersistToDisk, checksums will not be recomputed or synced
  // to disk, so initializing another instance on the same files should fail.
  EXPECT_THAT(QualifiedIdTypeJoinableIndex::Create(
                  filesystem_, working_path_, param.pre_mapping_fbv,
                  param.use_persistent_hash_map),
              StatusIs(param.use_persistent_hash_map
                           ? libtextclassifier3::StatusCode::FAILED_PRECONDITION
                           : libtextclassifier3::StatusCode::INTERNAL));
}

TEST_P(QualifiedIdTypeJoinableIndexTest,
       InitializationShouldSucceedWithPersistToDisk) {
  const QualifiedIdJoinIndexTestParam& param = GetParam();

  // Create new qualified id type joinable index
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<QualifiedIdTypeJoinableIndex> index1,
      QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                           param.pre_mapping_fbv,
                                           param.use_persistent_hash_map));

  // Insert some data.
  ICING_ASSERT_OK(
      index1->Put(DocJoinInfo(/*document_id=*/1, /*joinable_property_id=*/20),
                  /*ref_qualified_id_str=*/"namespace#uriA"));
  ICING_ASSERT_OK(
      index1->Put(DocJoinInfo(/*document_id=*/3, /*joinable_property_id=*/20),
                  /*ref_qualified_id_str=*/"namespace#uriB"));
  ICING_ASSERT_OK(
      index1->Put(DocJoinInfo(/*document_id=*/5, /*joinable_property_id=*/20),
                  /*ref_qualified_id_str=*/"namespace#uriC"));
  ASSERT_THAT(index1, Pointee(SizeIs(3)));

  // After calling PersistToDisk, all checksums should be recomputed and synced
  // correctly to disk, so initializing another instance on the same files
  // should succeed, and we should be able to get the same contents.
  ICING_EXPECT_OK(index1->PersistToDisk());

  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<QualifiedIdTypeJoinableIndex> index2,
      QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                           param.pre_mapping_fbv,
                                           param.use_persistent_hash_map));
  EXPECT_THAT(index2, Pointee(SizeIs(3)));
  EXPECT_THAT(
      index2->Get(DocJoinInfo(/*document_id=*/1, /*joinable_property_id=*/20)),
      IsOkAndHolds(/*ref_qualified_id_str=*/"namespace#uriA"));
  EXPECT_THAT(
      index2->Get(DocJoinInfo(/*document_id=*/3, /*joinable_property_id=*/20)),
      IsOkAndHolds(/*ref_qualified_id_str=*/"namespace#uriB"));
  EXPECT_THAT(
      index2->Get(DocJoinInfo(/*document_id=*/5, /*joinable_property_id=*/20)),
      IsOkAndHolds(/*ref_qualified_id_str=*/"namespace#uriC"));
}

TEST_P(QualifiedIdTypeJoinableIndexTest,
       InitializationShouldSucceedAfterDestruction) {
  const QualifiedIdJoinIndexTestParam& param = GetParam();

  {
    // Create new qualified id type joinable index
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<QualifiedIdTypeJoinableIndex> index,
        QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                             param.pre_mapping_fbv,
                                             param.use_persistent_hash_map));

    // Insert some data.
    ICING_ASSERT_OK(
        index->Put(DocJoinInfo(/*document_id=*/1, /*joinable_property_id=*/20),
                   /*ref_qualified_id_str=*/"namespace#uriA"));
    ICING_ASSERT_OK(
        index->Put(DocJoinInfo(/*document_id=*/3, /*joinable_property_id=*/20),
                   /*ref_qualified_id_str=*/"namespace#uriB"));
    ICING_ASSERT_OK(
        index->Put(DocJoinInfo(/*document_id=*/5, /*joinable_property_id=*/20),
                   /*ref_qualified_id_str=*/"namespace#uriC"));
    ASSERT_THAT(index, Pointee(SizeIs(3)));
  }

  {
    // The previous instance went out of scope and was destructed. Although we
    // didn't call PersistToDisk explicitly, the destructor should invoke it and
    // thus initializing another instance on the same files should succeed, and
    // we should be able to get the same contents.
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<QualifiedIdTypeJoinableIndex> index,
        QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                             param.pre_mapping_fbv,
                                             param.use_persistent_hash_map));
    EXPECT_THAT(index, Pointee(SizeIs(3)));
    EXPECT_THAT(index->Get(DocJoinInfo(/*document_id=*/1,
                                       /*joinable_property_id=*/20)),
                IsOkAndHolds("namespace#uriA"));
    EXPECT_THAT(index->Get(DocJoinInfo(/*document_id=*/3,
                                       /*joinable_property_id=*/20)),
                IsOkAndHolds("namespace#uriB"));
    EXPECT_THAT(index->Get(DocJoinInfo(/*document_id=*/5,
                                       /*joinable_property_id=*/20)),
                IsOkAndHolds("namespace#uriC"));
  }
}

TEST_P(QualifiedIdTypeJoinableIndexTest,
       InitializeExistingFilesWithDifferentMagicShouldFail) {
  const QualifiedIdJoinIndexTestParam& param = GetParam();

  {
    // Create new qualified id type joinable index
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<QualifiedIdTypeJoinableIndex> index,
        QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                             param.pre_mapping_fbv,
                                             param.use_persistent_hash_map));
    ICING_ASSERT_OK(
        index->Put(DocJoinInfo(/*document_id=*/1, /*joinable_property_id=*/20),
                   /*ref_qualified_id_str=*/"namespace#uriA"));

    ICING_ASSERT_OK(index->PersistToDisk());
  }

  {
    // Manually change magic and update checksum
    const std::string metadata_file_path =
        absl_ports::StrCat(working_path_, "/metadata");
    ScopedFd metadata_sfd(filesystem_.OpenForWrite(metadata_file_path.c_str()));
    ASSERT_THAT(metadata_sfd.is_valid(), IsTrue());

    auto metadata_buffer = std::make_unique<uint8_t[]>(
        QualifiedIdTypeJoinableIndex::kMetadataFileSize);
    ASSERT_THAT(
        filesystem_.PRead(metadata_sfd.get(), metadata_buffer.get(),
                          QualifiedIdTypeJoinableIndex::kMetadataFileSize,
                          /*offset=*/0),
        IsTrue());

    // Manually change magic and update checksums.
    Crcs* crcs = reinterpret_cast<Crcs*>(
        metadata_buffer.get() +
        QualifiedIdTypeJoinableIndex::kCrcsMetadataBufferOffset);
    Info* info = reinterpret_cast<Info*>(
        metadata_buffer.get() +
        QualifiedIdTypeJoinableIndex::kInfoMetadataBufferOffset);
    info->magic += kCorruptedValueOffset;
    crcs->component_crcs.info_crc = info->ComputeChecksum().Get();
    crcs->all_crc = crcs->component_crcs.ComputeChecksum().Get();
    ASSERT_THAT(filesystem_.PWrite(
                    metadata_sfd.get(), /*offset=*/0, metadata_buffer.get(),
                    QualifiedIdTypeJoinableIndex::kMetadataFileSize),
                IsTrue());
  }

  // Attempt to create the qualified id type joinable index with different
  // magic. This should fail.
  EXPECT_THAT(QualifiedIdTypeJoinableIndex::Create(
                  filesystem_, working_path_, param.pre_mapping_fbv,
                  param.use_persistent_hash_map),
              StatusIs(libtextclassifier3::StatusCode::FAILED_PRECONDITION,
                       HasSubstr("Incorrect magic value")));
}

TEST_P(QualifiedIdTypeJoinableIndexTest,
       InitializeExistingFilesWithWrongAllCrcShouldFail) {
  const QualifiedIdJoinIndexTestParam& param = GetParam();

  {
    // Create new qualified id type joinable index
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<QualifiedIdTypeJoinableIndex> index,
        QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                             param.pre_mapping_fbv,
                                             param.use_persistent_hash_map));
    ICING_ASSERT_OK(
        index->Put(DocJoinInfo(/*document_id=*/1, /*joinable_property_id=*/20),
                   /*ref_qualified_id_str=*/"namespace#uriA"));

    ICING_ASSERT_OK(index->PersistToDisk());
  }

  {
    const std::string metadata_file_path =
        absl_ports::StrCat(working_path_, "/metadata");
    ScopedFd metadata_sfd(filesystem_.OpenForWrite(metadata_file_path.c_str()));
    ASSERT_THAT(metadata_sfd.is_valid(), IsTrue());

    auto metadata_buffer = std::make_unique<uint8_t[]>(
        QualifiedIdTypeJoinableIndex::kMetadataFileSize);
    ASSERT_THAT(
        filesystem_.PRead(metadata_sfd.get(), metadata_buffer.get(),
                          QualifiedIdTypeJoinableIndex::kMetadataFileSize,
                          /*offset=*/0),
        IsTrue());

    // Manually corrupt all_crc
    Crcs* crcs = reinterpret_cast<Crcs*>(
        metadata_buffer.get() +
        QualifiedIdTypeJoinableIndex::kCrcsMetadataBufferOffset);
    crcs->all_crc += kCorruptedValueOffset;

    ASSERT_THAT(filesystem_.PWrite(
                    metadata_sfd.get(), /*offset=*/0, metadata_buffer.get(),
                    QualifiedIdTypeJoinableIndex::kMetadataFileSize),
                IsTrue());
  }

  // Attempt to create the qualified id type joinable index with metadata
  // containing corrupted all_crc. This should fail.
  EXPECT_THAT(QualifiedIdTypeJoinableIndex::Create(
                  filesystem_, working_path_, param.pre_mapping_fbv,
                  param.use_persistent_hash_map),
              StatusIs(libtextclassifier3::StatusCode::FAILED_PRECONDITION,
                       HasSubstr("Invalid all crc")));
}

TEST_P(QualifiedIdTypeJoinableIndexTest,
       InitializeExistingFilesWithCorruptedInfoShouldFail) {
  const QualifiedIdJoinIndexTestParam& param = GetParam();

  {
    // Create new qualified id type joinable index
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<QualifiedIdTypeJoinableIndex> index,
        QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                             param.pre_mapping_fbv,
                                             param.use_persistent_hash_map));
    ICING_ASSERT_OK(
        index->Put(DocJoinInfo(/*document_id=*/1, /*joinable_property_id=*/20),
                   /*ref_qualified_id_str=*/"namespace#uriA"));

    ICING_ASSERT_OK(index->PersistToDisk());
  }

  {
    const std::string metadata_file_path =
        absl_ports::StrCat(working_path_, "/metadata");
    ScopedFd metadata_sfd(filesystem_.OpenForWrite(metadata_file_path.c_str()));
    ASSERT_THAT(metadata_sfd.is_valid(), IsTrue());

    auto metadata_buffer = std::make_unique<uint8_t[]>(
        QualifiedIdTypeJoinableIndex::kMetadataFileSize);
    ASSERT_THAT(
        filesystem_.PRead(metadata_sfd.get(), metadata_buffer.get(),
                          QualifiedIdTypeJoinableIndex::kMetadataFileSize,
                          /*offset=*/0),
        IsTrue());

    // Modify info, but don't update the checksum. This would be similar to
    // corruption of info.
    Info* info = reinterpret_cast<Info*>(
        metadata_buffer.get() +
        QualifiedIdTypeJoinableIndex::kInfoMetadataBufferOffset);
    info->last_added_document_id += kCorruptedValueOffset;

    ASSERT_THAT(filesystem_.PWrite(
                    metadata_sfd.get(), /*offset=*/0, metadata_buffer.get(),
                    QualifiedIdTypeJoinableIndex::kMetadataFileSize),
                IsTrue());
  }

  // Attempt to create the qualified id type joinable index with info that
  // doesn't match its checksum. This should fail.
  EXPECT_THAT(QualifiedIdTypeJoinableIndex::Create(
                  filesystem_, working_path_, param.pre_mapping_fbv,
                  param.use_persistent_hash_map),
              StatusIs(libtextclassifier3::StatusCode::FAILED_PRECONDITION,
                       HasSubstr("Invalid info crc")));
}

TEST_P(QualifiedIdTypeJoinableIndexTest,
       InitializeExistingFilesWithCorruptedDocJoinInfoMapperShouldFail) {
  const QualifiedIdJoinIndexTestParam& param = GetParam();

  {
    // Create new qualified id type joinable index
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<QualifiedIdTypeJoinableIndex> index,
        QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                             param.pre_mapping_fbv,
                                             param.use_persistent_hash_map));
    ICING_ASSERT_OK(
        index->Put(DocJoinInfo(/*document_id=*/1, /*joinable_property_id=*/20),
                   /*ref_qualified_id_str=*/"namespace#uriA"));

    ICING_ASSERT_OK(index->PersistToDisk());
  }

  // Corrupt doc_join_info_mapper manually.
  {
    std::string mapper_working_path =
        absl_ports::StrCat(working_path_, "/doc_join_info_mapper");
    std::unique_ptr<KeyMapper<int32_t>> mapper;
    if (param.use_persistent_hash_map) {
      ICING_ASSERT_OK_AND_ASSIGN(
          mapper, PersistentHashMapKeyMapper<int32_t>::Create(
                      filesystem_, std::move(mapper_working_path),
                      param.pre_mapping_fbv));
    } else {
      ICING_ASSERT_OK_AND_ASSIGN(mapper,
                                 DynamicTrieKeyMapper<int32_t>::Create(
                                     filesystem_, mapper_working_path,
                                     /*maximum_size_bytes=*/128 * 1024 * 1024));
    }
    ICING_ASSERT_OK_AND_ASSIGN(Crc32 old_crc, mapper->ComputeChecksum());
    ICING_ASSERT_OK(mapper->Put("foo", 12345));
    ICING_ASSERT_OK(mapper->PersistToDisk());
    ICING_ASSERT_OK_AND_ASSIGN(Crc32 new_crc, mapper->ComputeChecksum());
    ASSERT_THAT(old_crc, Not(Eq(new_crc)));
  }

  // Attempt to create the qualified id type joinable index with corrupted
  // doc_join_info_mapper. This should fail.
  EXPECT_THAT(QualifiedIdTypeJoinableIndex::Create(
                  filesystem_, working_path_, param.pre_mapping_fbv,
                  param.use_persistent_hash_map),
              StatusIs(libtextclassifier3::StatusCode::FAILED_PRECONDITION,
                       HasSubstr("Invalid storages crc")));
}

TEST_P(QualifiedIdTypeJoinableIndexTest,
       InitializeExistingFilesWithCorruptedQualifiedIdStorageShouldFail) {
  const QualifiedIdJoinIndexTestParam& param = GetParam();

  {
    // Create new qualified id type joinable index
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<QualifiedIdTypeJoinableIndex> index,
        QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                             param.pre_mapping_fbv,
                                             param.use_persistent_hash_map));
    ICING_ASSERT_OK(
        index->Put(DocJoinInfo(/*document_id=*/1, /*joinable_property_id=*/20),
                   /*ref_qualified_id_str=*/"namespace#uriA"));

    ICING_ASSERT_OK(index->PersistToDisk());
  }

  {
    // Corrupt qualified_id_storage manually.
    std::string qualified_id_storage_path =
        absl_ports::StrCat(working_path_, "/qualified_id_storage");
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<FileBackedVector<char>> qualified_id_storage,
        FileBackedVector<char>::Create(
            filesystem_, qualified_id_storage_path,
            MemoryMappedFile::Strategy::READ_WRITE_AUTO_SYNC));
    ICING_ASSERT_OK_AND_ASSIGN(Crc32 old_crc,
                               qualified_id_storage->ComputeChecksum());
    ICING_ASSERT_OK(qualified_id_storage->Append('a'));
    ICING_ASSERT_OK(qualified_id_storage->Append('b'));
    ICING_ASSERT_OK(qualified_id_storage->PersistToDisk());
    ICING_ASSERT_OK_AND_ASSIGN(Crc32 new_crc,
                               qualified_id_storage->ComputeChecksum());
    ASSERT_THAT(old_crc, Not(Eq(new_crc)));
  }

  // Attempt to create the qualified id type joinable index with corrupted
  // qualified_id_storage. This should fail.
  EXPECT_THAT(QualifiedIdTypeJoinableIndex::Create(
                  filesystem_, working_path_, param.pre_mapping_fbv,
                  param.use_persistent_hash_map),
              StatusIs(libtextclassifier3::StatusCode::FAILED_PRECONDITION,
                       HasSubstr("Invalid storages crc")));
}

TEST_P(QualifiedIdTypeJoinableIndexTest, InvalidPut) {
  const QualifiedIdJoinIndexTestParam& param = GetParam();

  // Create new qualified id type joinable index
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<QualifiedIdTypeJoinableIndex> index,
      QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                           param.pre_mapping_fbv,
                                           param.use_persistent_hash_map));

  DocJoinInfo default_invalid;
  EXPECT_THAT(
      index->Put(default_invalid, /*ref_qualified_id_str=*/"namespace#uriA"),
      StatusIs(libtextclassifier3::StatusCode::INVALID_ARGUMENT));
}

TEST_P(QualifiedIdTypeJoinableIndexTest, InvalidGet) {
  const QualifiedIdJoinIndexTestParam& param = GetParam();

  // Create new qualified id type joinable index
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<QualifiedIdTypeJoinableIndex> index,
      QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                           param.pre_mapping_fbv,
                                           param.use_persistent_hash_map));

  DocJoinInfo default_invalid;
  EXPECT_THAT(index->Get(default_invalid),
              StatusIs(libtextclassifier3::StatusCode::INVALID_ARGUMENT));
}

TEST_P(QualifiedIdTypeJoinableIndexTest, PutAndGet) {
  const QualifiedIdJoinIndexTestParam& param = GetParam();

  DocJoinInfo target_info1(/*document_id=*/1, /*joinable_property_id=*/20);
  std::string_view ref_qualified_id_str_a = "namespace#uriA";

  DocJoinInfo target_info2(/*document_id=*/3, /*joinable_property_id=*/13);
  std::string_view ref_qualified_id_str_b = "namespace#uriB";

  DocJoinInfo target_info3(/*document_id=*/4, /*joinable_property_id=*/4);
  std::string_view ref_qualified_id_str_c = "namespace#uriC";

  {
    // Create new qualified id type joinable index
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<QualifiedIdTypeJoinableIndex> index,
        QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                             param.pre_mapping_fbv,
                                             param.use_persistent_hash_map));

    EXPECT_THAT(index->Put(target_info1, ref_qualified_id_str_a), IsOk());
    EXPECT_THAT(index->Put(target_info2, ref_qualified_id_str_b), IsOk());
    EXPECT_THAT(index->Put(target_info3, ref_qualified_id_str_c), IsOk());
    EXPECT_THAT(index, Pointee(SizeIs(3)));

    EXPECT_THAT(index->Get(target_info1), IsOkAndHolds(ref_qualified_id_str_a));
    EXPECT_THAT(index->Get(target_info2), IsOkAndHolds(ref_qualified_id_str_b));
    EXPECT_THAT(index->Get(target_info3), IsOkAndHolds(ref_qualified_id_str_c));

    ICING_ASSERT_OK(index->PersistToDisk());
  }

  // Verify we can get all of them after destructing and re-initializing.
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<QualifiedIdTypeJoinableIndex> index,
      QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                           param.pre_mapping_fbv,
                                           param.use_persistent_hash_map));
  EXPECT_THAT(index, Pointee(SizeIs(3)));
  EXPECT_THAT(index->Get(target_info1), IsOkAndHolds(ref_qualified_id_str_a));
  EXPECT_THAT(index->Get(target_info2), IsOkAndHolds(ref_qualified_id_str_b));
  EXPECT_THAT(index->Get(target_info3), IsOkAndHolds(ref_qualified_id_str_c));
}

TEST_P(QualifiedIdTypeJoinableIndexTest,
       GetShouldReturnNotFoundErrorIfNotExist) {
  const QualifiedIdJoinIndexTestParam& param = GetParam();

  DocJoinInfo target_info(/*document_id=*/1, /*joinable_property_id=*/20);
  std::string_view ref_qualified_id_str = "namespace#uriA";

  // Create new qualified id type joinable index
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<QualifiedIdTypeJoinableIndex> index,
      QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                           param.pre_mapping_fbv,
                                           param.use_persistent_hash_map));

  // Verify entry is not found in the beginning.
  EXPECT_THAT(index->Get(target_info),
              StatusIs(libtextclassifier3::StatusCode::NOT_FOUND));

  ICING_ASSERT_OK(index->Put(target_info, ref_qualified_id_str));
  ASSERT_THAT(index->Get(target_info), IsOkAndHolds(ref_qualified_id_str));

  // Get another non-existing entry. This should get NOT_FOUND_ERROR.
  DocJoinInfo another_target_info(/*document_id=*/2,
                                  /*joinable_property_id=*/20);
  EXPECT_THAT(index->Get(another_target_info),
              StatusIs(libtextclassifier3::StatusCode::NOT_FOUND));
}

TEST_P(QualifiedIdTypeJoinableIndexTest, SetLastAddedDocumentId) {
  const QualifiedIdJoinIndexTestParam& param = GetParam();

  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<QualifiedIdTypeJoinableIndex> index,
      QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                           param.pre_mapping_fbv,
                                           param.use_persistent_hash_map));

  EXPECT_THAT(index->last_added_document_id(), Eq(kInvalidDocumentId));

  constexpr DocumentId kDocumentId = 100;
  index->set_last_added_document_id(kDocumentId);
  EXPECT_THAT(index->last_added_document_id(), Eq(kDocumentId));

  constexpr DocumentId kNextDocumentId = 123;
  index->set_last_added_document_id(kNextDocumentId);
  EXPECT_THAT(index->last_added_document_id(), Eq(kNextDocumentId));
}

TEST_P(
    QualifiedIdTypeJoinableIndexTest,
    SetLastAddedDocumentIdShouldIgnoreNewDocumentIdNotGreaterThanTheCurrent) {
  const QualifiedIdJoinIndexTestParam& param = GetParam();

  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<QualifiedIdTypeJoinableIndex> index,
      QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                           param.pre_mapping_fbv,
                                           param.use_persistent_hash_map));

  constexpr DocumentId kDocumentId = 123;
  index->set_last_added_document_id(kDocumentId);
  ASSERT_THAT(index->last_added_document_id(), Eq(kDocumentId));

  constexpr DocumentId kNextDocumentId = 100;
  ASSERT_THAT(kNextDocumentId, Lt(kDocumentId));
  index->set_last_added_document_id(kNextDocumentId);
  // last_added_document_id() should remain unchanged.
  EXPECT_THAT(index->last_added_document_id(), Eq(kDocumentId));
}

TEST_P(QualifiedIdTypeJoinableIndexTest, Optimize) {
  const QualifiedIdJoinIndexTestParam& param = GetParam();

  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<QualifiedIdTypeJoinableIndex> index,
      QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                           param.pre_mapping_fbv,
                                           param.use_persistent_hash_map));

  ICING_ASSERT_OK(
      index->Put(DocJoinInfo(/*document_id=*/3, /*joinable_property_id=*/10),
                 /*ref_qualified_id_str=*/"namespace#uriA"));
  ICING_ASSERT_OK(
      index->Put(DocJoinInfo(/*document_id=*/5, /*joinable_property_id=*/3),
                 /*ref_qualified_id_str=*/"namespace#uriA"));
  ICING_ASSERT_OK(
      index->Put(DocJoinInfo(/*document_id=*/8, /*joinable_property_id=*/9),
                 /*ref_qualified_id_str=*/"namespace#uriB"));
  ICING_ASSERT_OK(
      index->Put(DocJoinInfo(/*document_id=*/13, /*joinable_property_id=*/4),
                 /*ref_qualified_id_str=*/"namespace#uriC"));
  ICING_ASSERT_OK(
      index->Put(DocJoinInfo(/*document_id=*/21, /*joinable_property_id=*/12),
                 /*ref_qualified_id_str=*/"namespace#uriC"));
  index->set_last_added_document_id(21);

  ASSERT_THAT(index, Pointee(SizeIs(5)));

  // Delete doc id = 5, 8, compress and keep the rest.
  std::vector<DocumentId> document_id_old_to_new(22, kInvalidDocumentId);
  document_id_old_to_new[3] = 0;
  document_id_old_to_new[13] = 1;
  document_id_old_to_new[21] = 2;

  DocumentId new_last_added_document_id = 2;
  EXPECT_THAT(
      index->Optimize(document_id_old_to_new, new_last_added_document_id),
      IsOk());
  EXPECT_THAT(index, Pointee(SizeIs(3)));
  EXPECT_THAT(index->last_added_document_id(), Eq(new_last_added_document_id));

  // Verify Put and Get API still work normally after Optimize().
  // (old_doc_id=3, joinable_property_id=10), which is now (doc_id=0,
  // joinable_property_id=10), has referenced qualified id str =
  // "namespace#uriA".
  EXPECT_THAT(
      index->Get(DocJoinInfo(/*document_id=*/0, /*joinable_property_id=*/10)),
      IsOkAndHolds("namespace#uriA"));

  // (old_doc_id=5, joinable_property_id=3) and (old_doc_id=8,
  // joinable_property_id=9) are now not found since we've deleted old_doc_id =
  // 5, 8. It is not testable via Get() because there is no valid doc_id mapping
  // for old_doc_id = 5, 8 and we cannot generate a valid DocJoinInfo for it.

  // (old_doc_id=13, joinable_property_id=4), which is now (doc_id=1,
  // joinable_property_id=4), has referenced qualified id str =
  // "namespace#uriC".
  EXPECT_THAT(
      index->Get(DocJoinInfo(/*document_id=*/1, /*joinable_property_id=*/4)),
      IsOkAndHolds("namespace#uriC"));

  // (old_doc_id=21, joinable_property_id=12), which is now (doc_id=2,
  // joinable_property_id=12), has referenced qualified id str =
  // "namespace#uriC".
  EXPECT_THAT(
      index->Get(DocJoinInfo(/*document_id=*/2, /*joinable_property_id=*/12)),
      IsOkAndHolds("namespace#uriC"));

  // Joinable index should be able to work normally after Optimize().
  ICING_ASSERT_OK(
      index->Put(DocJoinInfo(/*document_id=*/99, /*joinable_property_id=*/2),
                 /*ref_qualified_id_str=*/"namespace#uriD"));
  index->set_last_added_document_id(99);

  EXPECT_THAT(index, Pointee(SizeIs(4)));
  EXPECT_THAT(index->last_added_document_id(), Eq(99));
  EXPECT_THAT(index->Get(DocJoinInfo(/*document_id=*/99,
                                     /*joinable_property_id=*/2)),
              IsOkAndHolds("namespace#uriD"));
}

TEST_P(QualifiedIdTypeJoinableIndexTest, OptimizeOutOfRangeDocumentId) {
  const QualifiedIdJoinIndexTestParam& param = GetParam();

  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<QualifiedIdTypeJoinableIndex> index,
      QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                           param.pre_mapping_fbv,
                                           param.use_persistent_hash_map));

  ICING_ASSERT_OK(
      index->Put(DocJoinInfo(/*document_id=*/99, /*joinable_property_id=*/10),
                 /*ref_qualified_id_str=*/"namespace#uriA"));
  index->set_last_added_document_id(99);

  // Create document_id_old_to_new with size = 1. Optimize should handle out of
  // range DocumentId properly.
  std::vector<DocumentId> document_id_old_to_new = {kInvalidDocumentId};

  // There shouldn't be any error due to vector index.
  EXPECT_THAT(
      index->Optimize(document_id_old_to_new,
                      /*new_last_added_document_id=*/kInvalidDocumentId),
      IsOk());
  EXPECT_THAT(index->last_added_document_id(), Eq(kInvalidDocumentId));

  // Verify all data are discarded after Optimize().
  EXPECT_THAT(index, Pointee(IsEmpty()));
}

TEST_P(QualifiedIdTypeJoinableIndexTest, OptimizeDeleteAll) {
  const QualifiedIdJoinIndexTestParam& param = GetParam();

  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<QualifiedIdTypeJoinableIndex> index,
      QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                           param.pre_mapping_fbv,
                                           param.use_persistent_hash_map));

  ICING_ASSERT_OK(
      index->Put(DocJoinInfo(/*document_id=*/3, /*joinable_property_id=*/10),
                 /*ref_qualified_id_str=*/"namespace#uriA"));
  ICING_ASSERT_OK(
      index->Put(DocJoinInfo(/*document_id=*/5, /*joinable_property_id=*/3),
                 /*ref_qualified_id_str=*/"namespace#uriA"));
  ICING_ASSERT_OK(
      index->Put(DocJoinInfo(/*document_id=*/8, /*joinable_property_id=*/9),
                 /*ref_qualified_id_str=*/"namespace#uriB"));
  ICING_ASSERT_OK(
      index->Put(DocJoinInfo(/*document_id=*/13, /*joinable_property_id=*/4),
                 /*ref_qualified_id_str=*/"namespace#uriC"));
  ICING_ASSERT_OK(
      index->Put(DocJoinInfo(/*document_id=*/21, /*joinable_property_id=*/12),
                 /*ref_qualified_id_str=*/"namespace#uriC"));
  index->set_last_added_document_id(21);

  // Delete all documents.
  std::vector<DocumentId> document_id_old_to_new(22, kInvalidDocumentId);

  EXPECT_THAT(
      index->Optimize(document_id_old_to_new,
                      /*new_last_added_document_id=*/kInvalidDocumentId),
      IsOk());
  EXPECT_THAT(index->last_added_document_id(), Eq(kInvalidDocumentId));

  // Verify all data are discarded after Optimize().
  EXPECT_THAT(index, Pointee(IsEmpty()));
}

TEST_P(QualifiedIdTypeJoinableIndexTest, Clear) {
  const QualifiedIdJoinIndexTestParam& param = GetParam();

  DocJoinInfo target_info1(/*document_id=*/1, /*joinable_property_id=*/20);
  DocJoinInfo target_info2(/*document_id=*/3, /*joinable_property_id=*/5);
  DocJoinInfo target_info3(/*document_id=*/6, /*joinable_property_id=*/13);

  // Create new qualified id type joinable index
  ICING_ASSERT_OK_AND_ASSIGN(
      std::unique_ptr<QualifiedIdTypeJoinableIndex> index,
      QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                           param.pre_mapping_fbv,
                                           param.use_persistent_hash_map));
  ICING_ASSERT_OK(
      index->Put(target_info1, /*ref_qualified_id_str=*/"namespace#uriA"));
  ICING_ASSERT_OK(
      index->Put(target_info2, /*ref_qualified_id_str=*/"namespace#uriB"));
  ICING_ASSERT_OK(
      index->Put(target_info3, /*ref_qualified_id_str=*/"namespace#uriC"));
  ASSERT_THAT(index, Pointee(SizeIs(3)));
  index->set_last_added_document_id(6);
  ASSERT_THAT(index->last_added_document_id(), Eq(6));

  // After resetting, last_added_document_id should be set to
  // kInvalidDocumentId, and the previous added data should be deleted.
  EXPECT_THAT(index->Clear(), IsOk());
  EXPECT_THAT(index, Pointee(IsEmpty()));
  EXPECT_THAT(index->last_added_document_id(), Eq(kInvalidDocumentId));
  EXPECT_THAT(index->Get(target_info1),
              StatusIs(libtextclassifier3::StatusCode::NOT_FOUND));
  EXPECT_THAT(index->Get(target_info2),
              StatusIs(libtextclassifier3::StatusCode::NOT_FOUND));
  EXPECT_THAT(index->Get(target_info3),
              StatusIs(libtextclassifier3::StatusCode::NOT_FOUND));

  // Joinable index should be able to work normally after Clear().
  DocJoinInfo target_info4(/*document_id=*/2, /*joinable_property_id=*/19);
  ICING_ASSERT_OK(
      index->Put(target_info4, /*ref_qualified_id_str=*/"namespace#uriD"));
  index->set_last_added_document_id(2);

  EXPECT_THAT(index->last_added_document_id(), Eq(2));
  EXPECT_THAT(index->Get(target_info4), IsOkAndHolds("namespace#uriD"));

  ICING_ASSERT_OK(index->PersistToDisk());
  index.reset();

  // Verify index after reconstructing.
  ICING_ASSERT_OK_AND_ASSIGN(
      index, QualifiedIdTypeJoinableIndex::Create(
                 filesystem_, working_path_, param.pre_mapping_fbv,
                 param.use_persistent_hash_map));
  EXPECT_THAT(index->last_added_document_id(), Eq(2));
  EXPECT_THAT(index->Get(target_info1),
              StatusIs(libtextclassifier3::StatusCode::NOT_FOUND));
  EXPECT_THAT(index->Get(target_info2),
              StatusIs(libtextclassifier3::StatusCode::NOT_FOUND));
  EXPECT_THAT(index->Get(target_info3),
              StatusIs(libtextclassifier3::StatusCode::NOT_FOUND));
  EXPECT_THAT(index->Get(target_info4), IsOkAndHolds("namespace#uriD"));
}

TEST_P(QualifiedIdTypeJoinableIndexTest, SwitchKeyMapperTypeShouldReturnError) {
  const QualifiedIdJoinIndexTestParam& param = GetParam();

  {
    // Create new qualified id type joinable index
    ICING_ASSERT_OK_AND_ASSIGN(
        std::unique_ptr<QualifiedIdTypeJoinableIndex> index,
        QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                             param.pre_mapping_fbv,
                                             param.use_persistent_hash_map));
    ICING_ASSERT_OK(
        index->Put(DocJoinInfo(/*document_id=*/1, /*joinable_property_id=*/20),
                   /*ref_qualified_id_str=*/"namespace#uriA"));

    ICING_ASSERT_OK(index->PersistToDisk());
  }

  bool switch_key_mapper_flag = !param.use_persistent_hash_map;
  EXPECT_THAT(QualifiedIdTypeJoinableIndex::Create(filesystem_, working_path_,
                                                   param.pre_mapping_fbv,
                                                   switch_key_mapper_flag),
              StatusIs(libtextclassifier3::StatusCode::FAILED_PRECONDITION));
}

INSTANTIATE_TEST_SUITE_P(
    QualifiedIdTypeJoinableIndexTest, QualifiedIdTypeJoinableIndexTest,
    testing::Values(
        QualifiedIdJoinIndexTestParam(/*pre_mapping_fbv_in=*/true,
                                      /*use_persistent_hash_map_in=*/true),
        QualifiedIdJoinIndexTestParam(/*pre_mapping_fbv_in=*/true,
                                      /*use_persistent_hash_map_in=*/false),
        QualifiedIdJoinIndexTestParam(/*pre_mapping_fbv_in=*/false,
                                      /*use_persistent_hash_map_in=*/true),
        QualifiedIdJoinIndexTestParam(/*pre_mapping_fbv_in=*/false,
                                      /*use_persistent_hash_map_in=*/false)));

}  // namespace

}  // namespace lib
}  // namespace icing
