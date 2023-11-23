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

#include "icing/join/join-processor.h"

#include <algorithm>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

#include "icing/text_classifier/lib3/utils/base/statusor.h"
#include "icing/absl_ports/canonical_errors.h"
#include "icing/absl_ports/str_cat.h"
#include "icing/join/aggregation-scorer.h"
#include "icing/join/doc-join-info.h"
#include "icing/join/join-children-fetcher.h"
#include "icing/join/qualified-id.h"
#include "icing/proto/schema.pb.h"
#include "icing/proto/scoring.pb.h"
#include "icing/proto/search.pb.h"
#include "icing/schema/joinable-property.h"
#include "icing/scoring/scored-document-hit.h"
#include "icing/store/document-filter-data.h"
#include "icing/store/document-id.h"
#include "icing/util/status-macros.h"

namespace icing {
namespace lib {

libtextclassifier3::StatusOr<JoinChildrenFetcher>
JoinProcessor::GetChildrenFetcher(
    const JoinSpecProto& join_spec,
    std::vector<ScoredDocumentHit>&& child_scored_document_hits) {
  if (join_spec.parent_property_expression() != kQualifiedIdExpr) {
    // TODO(b/256022027): So far we only support kQualifiedIdExpr for
    // parent_property_expression, we could support more.
    return absl_ports::UnimplementedError(absl_ports::StrCat(
        "Parent property expression must be ", kQualifiedIdExpr));
  }

  std::sort(
      child_scored_document_hits.begin(), child_scored_document_hits.end(),
      ScoredDocumentHitComparator(
          /*is_descending=*/join_spec.nested_spec().scoring_spec().order_by() ==
          ScoringSpecProto::Order::DESC));

  // TODO(b/256022027):
  // - Optimization
  //   - Cache property to speed up property retrieval.
  //   - If there is no cache, then we still have the flexibility to fetch it
  //     from actual docs via DocumentStore.

  // Step 1: group child documents by parent documentId. Currently we only
  //         support QualifiedId joining, so fetch the qualified id content of
  //         child_property_expression, break it down into namespace + uri, and
  //         lookup the DocumentId.
  // The keys of this map are the DocumentIds of the parent docs the child
  // ScoredDocumentHits refer to. The values in this map are vectors of child
  // ScoredDocumentHits that refer to a parent DocumentId.
  std::unordered_map<DocumentId, std::vector<ScoredDocumentHit>>
      map_joinable_qualified_id;
  for (const ScoredDocumentHit& child : child_scored_document_hits) {
    ICING_ASSIGN_OR_RETURN(
        DocumentId ref_doc_id,
        FetchReferencedQualifiedId(child.document_id(),
                                   join_spec.child_property_expression()));
    if (ref_doc_id == kInvalidDocumentId) {
      continue;
    }

    map_joinable_qualified_id[ref_doc_id].push_back(child);
  }
  return JoinChildrenFetcher(join_spec, std::move(map_joinable_qualified_id));
}

libtextclassifier3::StatusOr<std::vector<JoinedScoredDocumentHit>>
JoinProcessor::Join(
    const JoinSpecProto& join_spec,
    std::vector<ScoredDocumentHit>&& parent_scored_document_hits,
    const JoinChildrenFetcher& join_children_fetcher) {
  std::unique_ptr<AggregationScorer> aggregation_scorer =
      AggregationScorer::Create(join_spec);

  std::vector<JoinedScoredDocumentHit> joined_scored_document_hits;
  joined_scored_document_hits.reserve(parent_scored_document_hits.size());

  // Step 2: iterate through all parent documentIds and construct
  //         JoinedScoredDocumentHit for each by looking up
  //         join_children_fetcher.
  for (ScoredDocumentHit& parent : parent_scored_document_hits) {
    ICING_ASSIGN_OR_RETURN(
        std::vector<ScoredDocumentHit> children,
        join_children_fetcher.GetChildren(parent.document_id()));

    double final_score = aggregation_scorer->GetScore(parent, children);
    joined_scored_document_hits.emplace_back(final_score, std::move(parent),
                                             std::move(children));
  }

  return joined_scored_document_hits;
}

libtextclassifier3::StatusOr<DocumentId>
JoinProcessor::FetchReferencedQualifiedId(
    const DocumentId& document_id, const std::string& property_path) const {
  std::optional<DocumentFilterData> filter_data =
      doc_store_->GetAliveDocumentFilterData(document_id, current_time_ms_);
  if (!filter_data) {
    return kInvalidDocumentId;
  }

  ICING_ASSIGN_OR_RETURN(const JoinablePropertyMetadata* metadata,
                         schema_store_->GetJoinablePropertyMetadata(
                             filter_data->schema_type_id(), property_path));
  if (metadata == nullptr ||
      metadata->value_type != JoinableConfig::ValueType::QUALIFIED_ID) {
    // Currently we only support qualified id.
    return kInvalidDocumentId;
  }

  DocJoinInfo info(document_id, metadata->id);
  libtextclassifier3::StatusOr<std::string_view> ref_qualified_id_str_or =
      qualified_id_join_index_->Get(info);
  if (!ref_qualified_id_str_or.ok()) {
    if (absl_ports::IsNotFound(ref_qualified_id_str_or.status())) {
      return kInvalidDocumentId;
    }
    return std::move(ref_qualified_id_str_or).status();
  }

  libtextclassifier3::StatusOr<QualifiedId> ref_qualified_id_or =
      QualifiedId::Parse(std::move(ref_qualified_id_str_or).ValueOrDie());
  if (!ref_qualified_id_or.ok()) {
    // This shouldn't happen because we've validated it during indexing and only
    // put valid qualified id strings into qualified id join index.
    return kInvalidDocumentId;
  }
  QualifiedId qualified_id = std::move(ref_qualified_id_or).ValueOrDie();

  libtextclassifier3::StatusOr<DocumentId> ref_document_id_or =
      doc_store_->GetDocumentId(qualified_id.name_space(), qualified_id.uri());
  if (!ref_document_id_or.ok()) {
    return kInvalidDocumentId;
  }
  return std::move(ref_document_id_or).ValueOrDie();
}

}  // namespace lib
}  // namespace icing
