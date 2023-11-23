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

#include "icing/join/qualified-id-join-indexing-handler.h"

#include <memory>
#include <string_view>

#include "icing/text_classifier/lib3/utils/base/status.h"
#include "icing/text_classifier/lib3/utils/base/statusor.h"
#include "icing/absl_ports/canonical_errors.h"
#include "icing/join/doc-join-info.h"
#include "icing/join/qualified-id-type-joinable-index.h"
#include "icing/join/qualified-id.h"
#include "icing/legacy/core/icing-string-util.h"
#include "icing/proto/logging.pb.h"
#include "icing/schema/joinable-property.h"
#include "icing/store/document-id.h"
#include "icing/util/clock.h"
#include "icing/util/logging.h"
#include "icing/util/status-macros.h"
#include "icing/util/tokenized-document.h"

namespace icing {
namespace lib {

/* static */ libtextclassifier3::StatusOr<
    std::unique_ptr<QualifiedIdJoinIndexingHandler>>
QualifiedIdJoinIndexingHandler::Create(
    const Clock* clock, QualifiedIdTypeJoinableIndex* qualified_id_join_index) {
  ICING_RETURN_ERROR_IF_NULL(clock);
  ICING_RETURN_ERROR_IF_NULL(qualified_id_join_index);

  return std::unique_ptr<QualifiedIdJoinIndexingHandler>(
      new QualifiedIdJoinIndexingHandler(clock, qualified_id_join_index));
}

libtextclassifier3::Status QualifiedIdJoinIndexingHandler::Handle(
    const TokenizedDocument& tokenized_document, DocumentId document_id,
    bool recovery_mode, PutDocumentStatsProto* put_document_stats) {
  std::unique_ptr<Timer> index_timer = clock_.GetNewTimer();

  if (!IsDocumentIdValid(document_id)) {
    return absl_ports::InvalidArgumentError(
        IcingStringUtil::StringPrintf("Invalid DocumentId %d", document_id));
  }

  if (qualified_id_join_index_.last_added_document_id() != kInvalidDocumentId &&
      document_id <= qualified_id_join_index_.last_added_document_id()) {
    if (recovery_mode) {
      // Skip the document if document_id <= last_added_document_id in recovery
      // mode without returning an error.
      return libtextclassifier3::Status::OK;
    }
    return absl_ports::InvalidArgumentError(IcingStringUtil::StringPrintf(
        "DocumentId %d must be greater than last added document_id %d",
        document_id, qualified_id_join_index_.last_added_document_id()));
  }
  qualified_id_join_index_.set_last_added_document_id(document_id);

  for (const JoinableProperty<std::string_view>& qualified_id_property :
       tokenized_document.qualified_id_join_properties()) {
    if (qualified_id_property.values.empty()) {
      continue;
    }

    DocJoinInfo info(document_id, qualified_id_property.metadata.id);
    // Currently we only support single (non-repeated) joinable value under a
    // property.
    std::string_view ref_qualified_id_str = qualified_id_property.values[0];

    // Attempt to parse qualified id string to make sure the format is correct.
    if (!QualifiedId::Parse(ref_qualified_id_str).ok()) {
      // Skip incorrect format of qualified id string to save disk space.
      continue;
    }

    libtextclassifier3::Status status =
        qualified_id_join_index_.Put(info, ref_qualified_id_str);
    if (!status.ok()) {
      ICING_LOG(WARNING)
          << "Failed to add data into qualified id join index due to: "
          << status.error_message();
      return status;
    }
  }

  if (put_document_stats != nullptr) {
    put_document_stats->set_qualified_id_join_index_latency_ms(
        index_timer->GetElapsedMilliseconds());
  }

  return libtextclassifier3::Status::OK;
}

}  // namespace lib
}  // namespace icing
