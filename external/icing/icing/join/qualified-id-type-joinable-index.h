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

#ifndef ICING_JOIN_QUALIFIED_ID_TYPE_JOINABLE_INDEX_H_
#define ICING_JOIN_QUALIFIED_ID_TYPE_JOINABLE_INDEX_H_

#include <cstdint>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

#include "icing/text_classifier/lib3/utils/base/status.h"
#include "icing/text_classifier/lib3/utils/base/statusor.h"
#include "icing/file/file-backed-vector.h"
#include "icing/file/filesystem.h"
#include "icing/file/persistent-storage.h"
#include "icing/join/doc-join-info.h"
#include "icing/store/document-id.h"
#include "icing/store/key-mapper.h"
#include "icing/util/crc32.h"

namespace icing {
namespace lib {

// QualifiedIdTypeJoinableIndex: a class to maintain data mapping DocJoinInfo to
// joinable qualified ids and delete propagation info.
class QualifiedIdTypeJoinableIndex : public PersistentStorage {
 public:
  struct Info {
    static constexpr int32_t kMagic = 0x48cabdc6;

    int32_t magic;
    DocumentId last_added_document_id;

    Crc32 ComputeChecksum() const {
      return Crc32(
          std::string_view(reinterpret_cast<const char*>(this), sizeof(Info)));
    }
  } __attribute__((packed));
  static_assert(sizeof(Info) == 8, "");

  // Metadata file layout: <Crcs><Info>
  static constexpr int32_t kCrcsMetadataBufferOffset = 0;
  static constexpr int32_t kInfoMetadataBufferOffset =
      static_cast<int32_t>(sizeof(Crcs));
  static constexpr int32_t kMetadataFileSize = sizeof(Crcs) + sizeof(Info);
  static_assert(kMetadataFileSize == 20, "");

  static constexpr WorkingPathType kWorkingPathType =
      WorkingPathType::kDirectory;

  // Creates a QualifiedIdTypeJoinableIndex instance to store qualified ids for
  // future joining search. If any of the underlying file is missing, then
  // delete the whole working_path and (re)initialize with new ones. Otherwise
  // initialize and create the instance by existing files.
  //
  // filesystem: Object to make system level calls
  // working_path: Specifies the working path for PersistentStorage.
  //               QualifiedIdTypeJoinableIndex uses working path as working
  //               directory and all related files will be stored under this
  //               directory. It takes full ownership and of working_path_,
  //               including creation/deletion. It is the caller's
  //               responsibility to specify correct working path and avoid
  //               mixing different persistent storages together under the same
  //               path. Also the caller has the ownership for the parent
  //               directory of working_path_, and it is responsible for parent
  //               directory creation/deletion. See PersistentStorage for more
  //               details about the concept of working_path.
  // pre_mapping_fbv: flag indicating whether memory map max possible file size
  //                  for underlying FileBackedVector before growing the actual
  //                  file size.
  // use_persistent_hash_map: flag indicating whether use persistent hash map as
  //                          the key mapper (if false, then fall back to
  //                          dynamic trie key mapper).
  //
  // Returns:
  //   - FAILED_PRECONDITION_ERROR if the file checksum doesn't match the stored
  //                               checksum
  //   - INTERNAL_ERROR on I/O errors
  //   - Any KeyMapper errors
  static libtextclassifier3::StatusOr<
      std::unique_ptr<QualifiedIdTypeJoinableIndex>>
  Create(const Filesystem& filesystem, std::string working_path,
         bool pre_mapping_fbv, bool use_persistent_hash_map);

  // Deletes QualifiedIdTypeJoinableIndex under working_path.
  //
  // Returns:
  //   - OK on success
  //   - INTERNAL_ERROR on I/O error
  static libtextclassifier3::Status Discard(const Filesystem& filesystem,
                                            const std::string& working_path) {
    return PersistentStorage::Discard(filesystem, working_path,
                                      kWorkingPathType);
  }

  // Delete copy and move constructor/assignment operator.
  QualifiedIdTypeJoinableIndex(const QualifiedIdTypeJoinableIndex&) = delete;
  QualifiedIdTypeJoinableIndex& operator=(const QualifiedIdTypeJoinableIndex&) =
      delete;

  QualifiedIdTypeJoinableIndex(QualifiedIdTypeJoinableIndex&&) = delete;
  QualifiedIdTypeJoinableIndex& operator=(QualifiedIdTypeJoinableIndex&&) =
      delete;

  ~QualifiedIdTypeJoinableIndex() override;

  // Puts a new data into index: DocJoinInfo (DocumentId, JoinablePropertyId)
  // references to ref_qualified_id_str (the identifier of another document).
  //
  // REQUIRES: ref_qualified_id_str contains no '\0'.
  //
  // Returns:
  //   - OK on success
  //   - INVALID_ARGUMENT_ERROR if doc_join_info is invalid
  //   - Any KeyMapper errors
  libtextclassifier3::Status Put(const DocJoinInfo& doc_join_info,
                                 std::string_view ref_qualified_id_str);

  // Gets the referenced document's qualified id string by DocJoinInfo.
  //
  // Returns:
  //   - A qualified id string referenced by the given DocJoinInfo (DocumentId,
  //     JoinablePropertyId) on success
  //   - INVALID_ARGUMENT_ERROR if doc_join_info is invalid
  //   - NOT_FOUND_ERROR if doc_join_info doesn't exist
  //   - Any KeyMapper errors
  libtextclassifier3::StatusOr<std::string_view> Get(
      const DocJoinInfo& doc_join_info) const;

  // Reduces internal file sizes by reclaiming space and ids of deleted
  // documents. Qualified id type joinable index will convert all entries to the
  // new document ids.
  //
  // - document_id_old_to_new: a map for converting old document id to new
  //   document id.
  // - new_last_added_document_id: will be used to update the last added
  //                               document id in the qualified id type joinable
  //                               index.
  //
  // Returns:
  //   - OK on success
  //   - INTERNAL_ERROR on I/O error. This could potentially leave the index in
  //     an invalid state and the caller should handle it properly (e.g. discard
  //     and rebuild)
  libtextclassifier3::Status Optimize(
      const std::vector<DocumentId>& document_id_old_to_new,
      DocumentId new_last_added_document_id);

  // Clears all data and set last_added_document_id to kInvalidDocumentId.
  //
  // Returns:
  //   - OK on success
  //   - INTERNAL_ERROR on I/O error
  libtextclassifier3::Status Clear();

  int32_t size() const { return doc_join_info_mapper_->num_keys(); }

  bool empty() const { return size() == 0; }

  DocumentId last_added_document_id() const {
    return info().last_added_document_id;
  }

  void set_last_added_document_id(DocumentId document_id) {
    Info& info_ref = info();
    if (info_ref.last_added_document_id == kInvalidDocumentId ||
        document_id > info_ref.last_added_document_id) {
      info_ref.last_added_document_id = document_id;
    }
  }

 private:
  explicit QualifiedIdTypeJoinableIndex(
      const Filesystem& filesystem, std::string&& working_path,
      std::unique_ptr<uint8_t[]> metadata_buffer,
      std::unique_ptr<KeyMapper<int32_t>> doc_join_info_mapper,
      std::unique_ptr<FileBackedVector<char>> qualified_id_storage,
      bool pre_mapping_fbv, bool use_persistent_hash_map)
      : PersistentStorage(filesystem, std::move(working_path),
                          kWorkingPathType),
        metadata_buffer_(std::move(metadata_buffer)),
        doc_join_info_mapper_(std::move(doc_join_info_mapper)),
        qualified_id_storage_(std::move(qualified_id_storage)),
        pre_mapping_fbv_(pre_mapping_fbv),
        use_persistent_hash_map_(use_persistent_hash_map) {}

  static libtextclassifier3::StatusOr<
      std::unique_ptr<QualifiedIdTypeJoinableIndex>>
  InitializeNewFiles(const Filesystem& filesystem, std::string&& working_path,
                     bool pre_mapping_fbv, bool use_persistent_hash_map);

  static libtextclassifier3::StatusOr<
      std::unique_ptr<QualifiedIdTypeJoinableIndex>>
  InitializeExistingFiles(const Filesystem& filesystem,
                          std::string&& working_path, bool pre_mapping_fbv,
                          bool use_persistent_hash_map);

  // Transfers qualified id type joinable index data from the current to
  // new_index and convert to new document id according to
  // document_id_old_to_new. It is a helper function for Optimize.
  //
  // Returns:
  //   - OK on success
  //   - INTERNAL_ERROR on I/O error
  libtextclassifier3::Status TransferIndex(
      const std::vector<DocumentId>& document_id_old_to_new,
      QualifiedIdTypeJoinableIndex* new_index) const;

  // Flushes contents of metadata file.
  //
  // Returns:
  //   - OK on success
  //   - INTERNAL_ERROR on I/O error
  libtextclassifier3::Status PersistMetadataToDisk() override;

  // Flushes contents of all storages to underlying files.
  //
  // Returns:
  //   - OK on success
  //   - INTERNAL_ERROR on I/O error
  libtextclassifier3::Status PersistStoragesToDisk() override;

  // Computes and returns Info checksum.
  //
  // Returns:
  //   - Crc of the Info on success
  libtextclassifier3::StatusOr<Crc32> ComputeInfoChecksum() override;

  // Computes and returns all storages checksum.
  //
  // Returns:
  //   - Crc of all storages on success
  //   - INTERNAL_ERROR if any data inconsistency
  libtextclassifier3::StatusOr<Crc32> ComputeStoragesChecksum() override;

  Crcs& crcs() override {
    return *reinterpret_cast<Crcs*>(metadata_buffer_.get() +
                                    kCrcsMetadataBufferOffset);
  }

  const Crcs& crcs() const override {
    return *reinterpret_cast<const Crcs*>(metadata_buffer_.get() +
                                          kCrcsMetadataBufferOffset);
  }

  Info& info() {
    return *reinterpret_cast<Info*>(metadata_buffer_.get() +
                                    kInfoMetadataBufferOffset);
  }

  const Info& info() const {
    return *reinterpret_cast<const Info*>(metadata_buffer_.get() +
                                          kInfoMetadataBufferOffset);
  }

  // Metadata buffer
  std::unique_ptr<uint8_t[]> metadata_buffer_;

  // Persistent KeyMapper for mapping (encoded) DocJoinInfo (DocumentId,
  // JoinablePropertyId) to another referenced document's qualified id string
  // index in qualified_id_storage_.
  std::unique_ptr<KeyMapper<int32_t>> doc_join_info_mapper_;

  // Storage for qualified id strings.
  std::unique_ptr<FileBackedVector<char>> qualified_id_storage_;

  // TODO(b/268521214): add delete propagation storage

  // Flag indicating whether memory map max possible file size for underlying
  // FileBackedVector before growing the actual file size.
  bool pre_mapping_fbv_;

  // Flag indicating whether use persistent hash map as the key mapper (if
  // false, then fall back to dynamic trie key mapper).
  bool use_persistent_hash_map_;
};

}  // namespace lib
}  // namespace icing

#endif  // ICING_JOIN_QUALIFIED_ID_TYPE_JOINABLE_INDEX_H_
