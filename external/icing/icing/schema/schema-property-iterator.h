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

#ifndef ICING_SCHEMA_SCHEMA_PROPERTY_ITERATOR_H_
#define ICING_SCHEMA_SCHEMA_PROPERTY_ITERATOR_H_

#include <algorithm>
#include <numeric>
#include <string>
#include <vector>

#include "icing/text_classifier/lib3/utils/base/status.h"
#include "icing/proto/schema.pb.h"
#include "icing/schema/property-util.h"
#include "icing/schema/schema-util.h"

namespace icing {
namespace lib {

// SchemaPropertyIterator: a class for iterating through all properties of a
// given SchemaTypeConfigProto in lexicographical order. Only leaf
// (non-document-type) properties will be returned, and for document type
// properties, the iterator will traverse down to the next nested level of
// schema.
//
// REQUIRED: The schema in which this SchemaTypeConfigProto is defined must have
// already passed the validation step during SetSchema.
class SchemaPropertyIterator {
 public:
  explicit SchemaPropertyIterator(
      const SchemaTypeConfigProto& base_schema_type_config,
      const SchemaUtil::TypeConfigMap& type_config_map)
      : type_config_map_(type_config_map) {
    levels_.push_back(LevelInfo(base_schema_type_config,
                                /*base_property_path=*/"",
                                /*is_nested_indexable=*/true));
    parent_type_config_names_.insert(base_schema_type_config.schema_type());
  }

  // Gets the current property config.
  //
  // REQUIRES: The preceding call for Advance() is OK.
  const PropertyConfigProto& GetCurrentPropertyConfig() const {
    return levels_.back().GetCurrentPropertyConfig();
  }

  // Gets the current property path.
  //
  // REQUIRES: The preceding call for Advance() is OK.
  std::string GetCurrentPropertyPath() const {
    return levels_.back().GetCurrentPropertyPath();
  }

  // Gets if the current property is nested indexable.
  //
  // REQUIRES: The preceding call for Advance() is OK.
  bool GetCurrentNestedIndexable() const {
    return levels_.back().GetCurrentNestedIndexable();
  }

  // Advances to the next leaf property.
  //
  // Returns:
  //   - OK on success
  //   - OUT_OF_RANGE_ERROR if there is no more leaf property
  //   - INVALID_ARGUMENT_ERROR if cycle dependency is detected in the nested
  //     schema
  //   - NOT_FOUND_ERROR if any nested schema name is not found in
  //     type_config_map
  libtextclassifier3::Status Advance();

 private:
  // An inner class for maintaining the iterating state of a (nested) level.
  // Nested SchemaTypeConfig is a tree structure, so we have to traverse it
  // recursively to all leaf properties.
  class LevelInfo {
   public:
    explicit LevelInfo(const SchemaTypeConfigProto& schema_type_config,
                       std::string base_property_path, bool is_nested_indexable)
        : schema_type_config_(schema_type_config),
          base_property_path_(std::move(base_property_path)),
          sorted_property_indices_(schema_type_config.properties_size()),
          current_vec_idx_(-1),
          is_nested_indexable_(is_nested_indexable) {
      // Index sort property by lexicographical order.
      std::iota(sorted_property_indices_.begin(),
                sorted_property_indices_.end(),
                /*value=*/0);
      std::sort(
          sorted_property_indices_.begin(), sorted_property_indices_.end(),
          [&schema_type_config](int lhs_idx, int rhs_idx) -> bool {
            return schema_type_config.properties(lhs_idx).property_name() <
                   schema_type_config.properties(rhs_idx).property_name();
          });
    }

    bool Advance() {
      return ++current_vec_idx_ < sorted_property_indices_.size();
    }

    const PropertyConfigProto& GetCurrentPropertyConfig() const {
      return schema_type_config_.properties(
          sorted_property_indices_[current_vec_idx_]);
    }

    std::string GetCurrentPropertyPath() const {
      return property_util::ConcatenatePropertyPathExpr(
          base_property_path_, GetCurrentPropertyConfig().property_name());
    }

    bool GetCurrentNestedIndexable() const { return is_nested_indexable_; }

    std::string_view GetSchemaTypeName() const {
      return schema_type_config_.schema_type();
    }

   private:
    const SchemaTypeConfigProto& schema_type_config_;  // Does not own

    // Concatenated property path of all parent levels.
    std::string base_property_path_;

    // We perform index sort (comparing property name) in order to iterate all
    // leaf properties in lexicographical order. This vector is for storing
    // these sorted indices.
    std::vector<int> sorted_property_indices_;
    int current_vec_idx_;

    // Indicates if the current level is nested indexable. Document type
    // property has index_nested_properties flag indicating whether properties
    // under this level should be indexed or not. If any of parent document type
    // property sets its flag false, then all child level properties should not
    // be indexed.
    bool is_nested_indexable_;
  };

  const SchemaUtil::TypeConfigMap& type_config_map_;  // Does not own

  // For maintaining the stack of recursive nested schema type traversal. We use
  // std::vector instead of std::stack to avoid memory allocate and free too
  // frequently.
  std::vector<LevelInfo> levels_;

  // Maintaining all traversed parent schema type config names of the current
  // stack (levels_). It is used to detect nested schema cycle dependency.
  std::unordered_set<std::string_view> parent_type_config_names_;
};

}  // namespace lib
}  // namespace icing

#endif  // ICING_SCHEMA_SCHEMA_PROPERTY_ITERATOR_H_
