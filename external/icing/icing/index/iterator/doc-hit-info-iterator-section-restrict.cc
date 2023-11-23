// Copyright (C) 2019 Google LLC
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

#include "icing/index/iterator/doc-hit-info-iterator-section-restrict.h"

#include <cstdint>
#include <memory>
#include <string>
#include <string_view>
#include <utility>

#include "icing/text_classifier/lib3/utils/base/status.h"
#include "icing/text_classifier/lib3/utils/base/statusor.h"
#include "icing/absl_ports/canonical_errors.h"
#include "icing/absl_ports/str_cat.h"
#include "icing/index/hit/doc-hit-info.h"
#include "icing/index/iterator/doc-hit-info-iterator.h"
#include "icing/schema/schema-store.h"
#include "icing/schema/section.h"
#include "icing/store/document-filter-data.h"
#include "icing/store/document-id.h"
#include "icing/store/document-store.h"

namespace icing {
namespace lib {

DocHitInfoIteratorSectionRestrict::DocHitInfoIteratorSectionRestrict(
    std::unique_ptr<DocHitInfoIterator> delegate,
    const DocumentStore* document_store, const SchemaStore* schema_store,
    std::set<std::string> target_sections, int64_t current_time_ms)
    : delegate_(std::move(delegate)),
      document_store_(*document_store),
      schema_store_(*schema_store),
      target_sections_(std::move(target_sections)),
      current_time_ms_(current_time_ms) {}

libtextclassifier3::Status DocHitInfoIteratorSectionRestrict::Advance() {
  doc_hit_info_ = DocHitInfo(kInvalidDocumentId);
  hit_intersect_section_ids_mask_ = kSectionIdMaskNone;
  while (delegate_->Advance().ok()) {
    DocumentId document_id = delegate_->doc_hit_info().document_id();

    SectionIdMask section_id_mask =
        delegate_->doc_hit_info().hit_section_ids_mask();

    auto data_optional = document_store_.GetAliveDocumentFilterData(
        document_id, current_time_ms_);
    if (!data_optional) {
      // Ran into some error retrieving information on this hit, skip
      continue;
    }

    // Guaranteed that the DocumentFilterData exists at this point
    SchemaTypeId schema_type_id = data_optional.value().schema_type_id();

    // A hit can be in multiple sections at once, need to check which of the
    // section ids match the target sections
    while (section_id_mask != 0) {
      // There was a hit in this section id
      SectionId section_id = __builtin_ctzll(section_id_mask);

      auto section_metadata_or =
          schema_store_.GetSectionMetadata(schema_type_id, section_id);

      if (section_metadata_or.ok()) {
        const SectionMetadata* section_metadata =
            section_metadata_or.ValueOrDie();

        if (target_sections_.find(section_metadata->path) !=
            target_sections_.end()) {
          // The hit was in the target section name, return OK/found
          hit_intersect_section_ids_mask_ |= UINT64_C(1) << section_id;
        }
      }

      // Mark this section as checked
      section_id_mask &= ~(UINT64_C(1) << section_id);
    }

    if (hit_intersect_section_ids_mask_ != kSectionIdMaskNone) {
      doc_hit_info_ = delegate_->doc_hit_info();
      doc_hit_info_.set_hit_section_ids_mask(hit_intersect_section_ids_mask_);
      return libtextclassifier3::Status::OK;
    }
    // Didn't find a matching section name for this hit. Continue.
  }

  // Didn't find anything on the delegate iterator.
  return absl_ports::ResourceExhaustedError("No more DocHitInfos in iterator");
}

libtextclassifier3::StatusOr<DocHitInfoIterator::TrimmedNode>
DocHitInfoIteratorSectionRestrict::TrimRightMostNode() && {
  ICING_ASSIGN_OR_RETURN(TrimmedNode trimmed_delegate,
                         std::move(*delegate_).TrimRightMostNode());
  if (trimmed_delegate.iterator_ == nullptr) {
    // TODO(b/228240987): Update TrimmedNode and downstream code to handle
    // multiple section restricts.
    trimmed_delegate.target_section_ = std::move(*target_sections_.begin());
    return trimmed_delegate;
  }
  trimmed_delegate.iterator_ =
      std::make_unique<DocHitInfoIteratorSectionRestrict>(
          std::move(trimmed_delegate.iterator_), &document_store_,
          &schema_store_, std::move(target_sections_), current_time_ms_);
  return std::move(trimmed_delegate);
}

int32_t DocHitInfoIteratorSectionRestrict::GetNumBlocksInspected() const {
  return delegate_->GetNumBlocksInspected();
}

int32_t DocHitInfoIteratorSectionRestrict::GetNumLeafAdvanceCalls() const {
  return delegate_->GetNumLeafAdvanceCalls();
}

std::string DocHitInfoIteratorSectionRestrict::ToString() const {
  return absl_ports::StrCat("(", absl_ports::StrJoin(target_sections_, ","),
                            "): ", delegate_->ToString());
}

}  // namespace lib
}  // namespace icing
