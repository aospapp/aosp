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

#include "icing/monkey_test/monkey-test-generators.h"

namespace icing {
namespace lib {

SchemaProto MonkeySchemaGenerator::GenerateSchema(
    int num_types, const std::vector<int>& possible_num_properties) const {
  SchemaProto schema;
  std::uniform_int_distribution<> dist(0, possible_num_properties.size() - 1);
  while (num_types > 0) {
    int num_properties = possible_num_properties[dist(*random_)];
    *schema.add_types() = GenerateType(
        "MonkeyTestType" + std::to_string(num_types), num_properties);
    --num_types;
  }
  return schema;
}

PropertyConfigProto MonkeySchemaGenerator::GenerateProperty(
    std::string_view name, TermMatchType::Code term_match_type) const {
  PropertyConfigProto prop;
  prop.set_property_name(name.data(), name.length());
  // TODO: Perhaps in future iterations we will want to generate more than just
  // string properties.
  prop.set_data_type(PropertyConfigProto::DataType::STRING);

  constexpr std::array<PropertyConfigProto::Cardinality::Code, 3>
      cardinalities = {PropertyConfigProto::Cardinality::REPEATED,
                       PropertyConfigProto::Cardinality::OPTIONAL,
                       PropertyConfigProto::Cardinality::REQUIRED};
  std::uniform_int_distribution<> dist(0, cardinalities.size() - 1);
  prop.set_cardinality(cardinalities[dist(*random_)]);

  if (term_match_type != TermMatchType::UNKNOWN) {
    StringIndexingConfig* string_indexing_config =
        prop.mutable_string_indexing_config();
    string_indexing_config->set_term_match_type(term_match_type);
    string_indexing_config->set_tokenizer_type(
        StringIndexingConfig::TokenizerType::PLAIN);
  }
  return prop;
}

SchemaTypeConfigProto MonkeySchemaGenerator::GenerateType(
    std::string_view name, int num_properties) const {
  SchemaTypeConfigProto type_config;
  type_config.set_schema_type(name.data(), name.length());
  int num_indexed_properties = 0;
  constexpr std::array<TermMatchType::Code, 3> term_match_types = {
      TermMatchType::UNKNOWN, TermMatchType::EXACT_ONLY, TermMatchType::PREFIX};
  std::uniform_int_distribution<> dist(0, term_match_types.size() - 1);
  while (--num_properties >= 0) {
    std::string prop_name = "MonkeyTestProp" + std::to_string(num_properties);
    TermMatchType::Code term_match_type = TermMatchType::UNKNOWN;
    if (num_indexed_properties < kTotalNumSections) {
      term_match_type = term_match_types[dist(*random_)];
    }
    if (term_match_type != TermMatchType::UNKNOWN) {
      num_indexed_properties += 1;
    }
    (*type_config.add_properties()) =
        GenerateProperty(prop_name, term_match_type);
  }
  return type_config;
}

std::string MonkeyDocumentGenerator::GetNamespace() const {
  uint32_t name_space;
  // When num_namespaces is 0, all documents generated get different namespaces.
  // Otherwise, namespaces will be randomly picked from a set with
  // num_namespaces elements.
  if (num_namespaces_ == 0) {
    name_space = num_docs_generated_;
  } else {
    std::uniform_int_distribution<> dist(0, num_namespaces_ - 1);
    name_space = dist(*random_);
  }
  return absl_ports::StrCat("namespace", std::to_string(name_space));
}

std::string MonkeyDocumentGenerator::GetUri() const {
  uint32_t uri;
  // When num_uris is 0, all documents generated get different URIs. Otherwise,
  // URIs will be randomly picked from a set with num_uris elements.
  if (num_uris_ == 0) {
    uri = num_docs_generated_;
  } else {
    std::uniform_int_distribution<> dist(0, num_uris_ - 1);
    uri = dist(*random_);
  }
  return absl_ports::StrCat("uri", std::to_string(uri));
}

int MonkeyDocumentGenerator::GetNumTokens() const {
  std::uniform_int_distribution<> dist(0, possible_num_tokens_.size() - 1);
  int n = possible_num_tokens_[dist(*random_)];
  // Add some noise
  std::uniform_real_distribution<> real_dist(0.5, 1);
  float p = real_dist(*random_);
  return n * p;
}

std::vector<std::string> MonkeyDocumentGenerator::GetPropertyContent() const {
  std::vector<std::string> content;
  int num_tokens = GetNumTokens();
  while (num_tokens) {
    content.push_back(std::string(GetToken()));
    --num_tokens;
  }
  return content;
}

MonkeyTokenizedDocument MonkeyDocumentGenerator::GenerateDocument() {
  MonkeyTokenizedDocument document;
  const SchemaTypeConfigProto& type_config = GetType();
  const std::string& name_space = GetNamespace();
  DocumentBuilder doc_builder =
      DocumentBuilder()
          .SetNamespace(name_space)
          .SetSchema(type_config.schema_type())
          .SetUri(GetUri())
          .SetCreationTimestampMs(clock_.GetSystemTimeMilliseconds());
  for (const PropertyConfigProto& prop : type_config.properties()) {
    std::vector<std::string> prop_content = GetPropertyContent();
    doc_builder.AddStringProperty(prop.property_name(),
                                  absl_ports::StrJoin(prop_content, " "));
    // Create a tokenized section if the current property is indexable.
    if (prop.data_type() == PropertyConfigProto::DataType::STRING &&
        prop.string_indexing_config().term_match_type() !=
            TermMatchType::UNKNOWN) {
      MonkeyTokenizedSection section = {
          prop.property_name(), prop.string_indexing_config().term_match_type(),
          std::move(prop_content)};
      document.tokenized_sections.push_back(std::move(section));
    }
  }
  document.document = doc_builder.Build();
  ++num_docs_generated_;
  return document;
}

}  // namespace lib
}  // namespace icing
