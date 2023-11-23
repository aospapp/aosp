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

#ifndef ICING_MONKEY_TEST_MONKEY_TEST_GENERATORS_H_
#define ICING_MONKEY_TEST_MONKEY_TEST_GENERATORS_H_

#include <algorithm>
#include <cstdint>
#include <random>
#include <string>
#include <string_view>
#include <vector>

#include "icing/absl_ports/str_cat.h"
#include "icing/absl_ports/str_join.h"
#include "icing/document-builder.h"
#include "icing/monkey_test/monkey-test-common-words.h"
#include "icing/monkey_test/monkey-tokenized-document.h"
#include "icing/proto/document.pb.h"
#include "icing/proto/schema.pb.h"
#include "icing/schema/section.h"
#include "icing/util/clock.h"

namespace icing {
namespace lib {

using MonkeyTestRandomEngine = std::mt19937;

// A random schema generator used for monkey testing.
class MonkeySchemaGenerator {
 public:
  explicit MonkeySchemaGenerator(MonkeyTestRandomEngine* random)
      : random_(random) {}

  // To ensure that the random schema is generated with the best quality, the
  // number of properties for each type will only be randomly picked from the
  // list of possible_num_properties, instead of picking it from a range.
  // For example, a vector of [1, 2, 3, 4] means each generated types have a 25%
  // chance of getting 1 property, 2 properties, 3 properties and 4 properties.
  SchemaProto GenerateSchema(
      int num_types, const std::vector<int>& possible_num_properties) const;

 private:
  PropertyConfigProto GenerateProperty(
      std::string_view name, TermMatchType::Code term_match_type) const;

  SchemaTypeConfigProto GenerateType(std::string_view name,
                                     int num_properties) const;

  // Does not own.
  MonkeyTestRandomEngine* random_;
};

// A random document generator used for monkey testing.
// When num_uris is 0, all documents generated get different URIs. Otherwise,
// URIs will be randomly picked from a set with num_uris elements.
// Same for num_namespaces.
class MonkeyDocumentGenerator {
 public:
  explicit MonkeyDocumentGenerator(MonkeyTestRandomEngine* random,
                                   const SchemaProto* schema,
                                   std::vector<int> possible_num_tokens,
                                   uint32_t num_namespaces,
                                   uint32_t num_uris = 0)
      : random_(random),
        schema_(schema),
        possible_num_tokens_(std::move(possible_num_tokens)),
        num_namespaces_(num_namespaces),
        num_uris_(num_uris) {}

  const SchemaTypeConfigProto& GetType() const {
    std::uniform_int_distribution<> dist(0, schema_->types_size() - 1);
    return schema_->types(dist(*random_));
  }

  std::string_view GetToken() const {
    // TODO: Instead of randomly picking tokens from the language set
    // kCommonWords, we can make some words more common than others to simulate
    // term frequencies in the real world. This can help us get extremely large
    // posting lists.
    std::uniform_int_distribution<> dist(0, kCommonWords.size() - 1);
    return kCommonWords[dist(*random_)];
  }

  std::string GetNamespace() const;

  std::string GetUri() const;

  int GetNumTokens() const;

  std::vector<std::string> GetPropertyContent() const;

  MonkeyTokenizedDocument GenerateDocument();

 private:
  MonkeyTestRandomEngine* random_;  // Does not own.
  const SchemaProto* schema_;       // Does not own.

  // The possible number of tokens that may appear in generated documents, with
  // a noise factor from 0.5 to 1 applied.
  std::vector<int> possible_num_tokens_;

  uint32_t num_namespaces_;
  uint32_t num_uris_;
  uint32_t num_docs_generated_ = 0;
  Clock clock_;
};

}  // namespace lib
}  // namespace icing

#endif  // ICING_MONKEY_TEST_MONKEY_TEST_GENERATORS_H_
