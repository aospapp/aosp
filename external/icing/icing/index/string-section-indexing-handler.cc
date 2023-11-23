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

#include "icing/index/string-section-indexing-handler.h"

#include <cstdint>
#include <memory>
#include <string>
#include <string_view>

#include "icing/text_classifier/lib3/utils/base/status.h"
#include "icing/text_classifier/lib3/utils/base/statusor.h"
#include "icing/absl_ports/canonical_errors.h"
#include "icing/index/index.h"
#include "icing/legacy/core/icing-string-util.h"
#include "icing/proto/logging.pb.h"
#include "icing/proto/schema.pb.h"
#include "icing/schema/section.h"
#include "icing/store/document-id.h"
#include "icing/transform/normalizer.h"
#include "icing/util/clock.h"
#include "icing/util/logging.h"
#include "icing/util/status-macros.h"
#include "icing/util/tokenized-document.h"

namespace icing {
namespace lib {

/* static */ libtextclassifier3::StatusOr<
    std::unique_ptr<StringSectionIndexingHandler>>
StringSectionIndexingHandler::Create(const Clock* clock,
                                     const Normalizer* normalizer,
                                     Index* index) {
  ICING_RETURN_ERROR_IF_NULL(clock);
  ICING_RETURN_ERROR_IF_NULL(normalizer);
  ICING_RETURN_ERROR_IF_NULL(index);

  return std::unique_ptr<StringSectionIndexingHandler>(
      new StringSectionIndexingHandler(clock, normalizer, index));
}

libtextclassifier3::Status StringSectionIndexingHandler::Handle(
    const TokenizedDocument& tokenized_document, DocumentId document_id,
    bool recovery_mode, PutDocumentStatsProto* put_document_stats) {
  std::unique_ptr<Timer> index_timer = clock_.GetNewTimer();

  if (index_.last_added_document_id() != kInvalidDocumentId &&
      document_id <= index_.last_added_document_id()) {
    if (recovery_mode) {
      // Skip the document if document_id <= last_added_document_id in recovery
      // mode without returning an error.
      return libtextclassifier3::Status::OK;
    }
    return absl_ports::InvalidArgumentError(IcingStringUtil::StringPrintf(
        "DocumentId %d must be greater than last added document_id %d",
        document_id, index_.last_added_document_id()));
  }
  index_.set_last_added_document_id(document_id);

  uint32_t num_tokens = 0;
  libtextclassifier3::Status status;
  for (const TokenizedSection& section :
       tokenized_document.tokenized_string_sections()) {
    if (section.metadata.tokenizer ==
        StringIndexingConfig::TokenizerType::NONE) {
      ICING_LOG(WARNING)
          << "Unexpected TokenizerType::NONE found when indexing document.";
    }
    // TODO(b/152934343): pass real namespace ids in
    Index::Editor editor =
        index_.Edit(document_id, section.metadata.id,
                    section.metadata.term_match_type, /*namespace_id=*/0);
    for (std::string_view token : section.token_sequence) {
      ++num_tokens;

      switch (section.metadata.tokenizer) {
        case StringIndexingConfig::TokenizerType::VERBATIM:
          // data() is safe to use here because a token created from the
          // VERBATIM tokenizer is the entire string value. The character at
          // data() + token.length() is guaranteed to be a null char.
          status = editor.BufferTerm(token.data());
          break;
        case StringIndexingConfig::TokenizerType::NONE:
          [[fallthrough]];
        case StringIndexingConfig::TokenizerType::RFC822:
          [[fallthrough]];
        case StringIndexingConfig::TokenizerType::URL:
          [[fallthrough]];
        case StringIndexingConfig::TokenizerType::PLAIN:
          std::string normalized_term = normalizer_.NormalizeTerm(token);
          status = editor.BufferTerm(normalized_term.c_str());
      }

      if (!status.ok()) {
        // We've encountered a failure. Bail out. We'll mark this doc as deleted
        // and signal a failure to the client.
        ICING_LOG(WARNING) << "Failed to buffer term in lite lexicon due to: "
                           << status.error_message();
        break;
      }
    }
    if (!status.ok()) {
      break;
    }
    // Add all the seen terms to the index with their term frequency.
    status = editor.IndexAllBufferedTerms();
    if (!status.ok()) {
      ICING_LOG(WARNING) << "Failed to add hits in lite index due to: "
                         << status.error_message();
      break;
    }
  }

  if (put_document_stats != nullptr) {
    put_document_stats->set_term_index_latency_ms(
        index_timer->GetElapsedMilliseconds());
    put_document_stats->mutable_tokenization_stats()->set_num_tokens_indexed(
        num_tokens);
  }

  // If we're either successful or we've hit resource exhausted, then attempt a
  // merge.
  if ((status.ok() || absl_ports::IsResourceExhausted(status)) &&
      index_.WantsMerge()) {
    ICING_LOG(INFO) << "Merging the index at docid " << document_id << ".";

    std::unique_ptr<Timer> merge_timer = clock_.GetNewTimer();
    libtextclassifier3::Status merge_status = index_.Merge();

    if (!merge_status.ok()) {
      ICING_LOG(ERROR) << "Index merging failed. Clearing index.";
      if (!index_.Reset().ok()) {
        return absl_ports::InternalError(IcingStringUtil::StringPrintf(
            "Unable to reset to clear index after merge failure. Merge "
            "failure=%d:%s",
            merge_status.error_code(), merge_status.error_message().c_str()));
      } else {
        return absl_ports::DataLossError(IcingStringUtil::StringPrintf(
            "Forced to reset index after merge failure. Merge failure=%d:%s",
            merge_status.error_code(), merge_status.error_message().c_str()));
      }
    }

    if (put_document_stats != nullptr) {
      put_document_stats->set_index_merge_latency_ms(
          merge_timer->GetElapsedMilliseconds());
    }
  }

  return status;
}

}  // namespace lib
}  // namespace icing
