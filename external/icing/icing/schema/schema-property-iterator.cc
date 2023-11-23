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

#include "icing/schema/schema-property-iterator.h"

#include "icing/text_classifier/lib3/utils/base/status.h"
#include "icing/absl_ports/canonical_errors.h"
#include "icing/absl_ports/str_cat.h"

namespace icing {
namespace lib {

libtextclassifier3::Status SchemaPropertyIterator::Advance() {
  while (!levels_.empty()) {
    if (!levels_.back().Advance()) {
      // When finishing iterating all properties of the current level, pop it
      // from the stack (levels_), return to the previous level and resume the
      // iteration.
      parent_type_config_names_.erase(levels_.back().GetSchemaTypeName());
      levels_.pop_back();
      continue;
    }

    const PropertyConfigProto& curr_property_config =
        levels_.back().GetCurrentPropertyConfig();
    if (curr_property_config.data_type() !=
        PropertyConfigProto::DataType::DOCUMENT) {
      // We've advanced to a leaf property.
      return libtextclassifier3::Status::OK;
    }

    // - When advancing to a TYPE_DOCUMENT property, it means it is a nested
    //   schema and we need to traverse the next level. Look up SchemaTypeConfig
    //   (by the schema name) by type_config_map_, and push a new level into
    //   levels_.
    // - Each level has to record the index of property it is currently at, so
    //   we can resume the iteration when returning back to it. Also other
    //   essential info will be maintained in LevelInfo as well.
    auto nested_type_config_iter =
        type_config_map_.find(curr_property_config.schema_type());
    if (nested_type_config_iter == type_config_map_.end()) {
      // This should never happen because our schema should already be
      // validated by this point.
      return absl_ports::NotFoundError(absl_ports::StrCat(
          "Type config not found: ", curr_property_config.schema_type()));
    }

    if (parent_type_config_names_.count(
            nested_type_config_iter->second.schema_type()) > 0) {
      // Cycle detected. The schema definition is guaranteed to be valid here
      // since it must have already been validated during SchemaUtil::Validate,
      // which would have rejected any schema with bad cycles.
      //
      // We do not need to iterate this type further so we simply move on to
      // other properties in the parent type.
      continue;
    }

    std::string curr_property_path = levels_.back().GetCurrentPropertyPath();
    bool is_nested_indexable = levels_.back().GetCurrentNestedIndexable() &&
                               curr_property_config.document_indexing_config()
                                   .index_nested_properties();
    levels_.push_back(LevelInfo(nested_type_config_iter->second,
                                std::move(curr_property_path),
                                is_nested_indexable));
    parent_type_config_names_.insert(
        nested_type_config_iter->second.schema_type());
  }
  return absl_ports::OutOfRangeError("End of iterator");
}

}  // namespace lib
}  // namespace icing
