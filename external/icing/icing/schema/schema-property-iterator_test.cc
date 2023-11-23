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

#include <string>

#include "icing/text_classifier/lib3/utils/base/status.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "icing/proto/schema.pb.h"
#include "icing/schema-builder.h"
#include "icing/schema/schema-util.h"
#include "icing/testing/common-matchers.h"

namespace icing {
namespace lib {

namespace {

using portable_equals_proto::EqualsProto;
using ::testing::Eq;
using ::testing::IsFalse;
using ::testing::IsTrue;

TEST(SchemaPropertyIteratorTest,
     SingleLevelSchemaTypeConfigShouldIterateInCorrectOrder) {
  std::string schema_type_name = "Schema";

  SchemaTypeConfigProto schema_type_config =
      SchemaTypeConfigBuilder()
          .SetType(schema_type_name)
          .AddProperty(PropertyConfigBuilder().SetName("Google").SetDataType(
              TYPE_STRING))
          .AddProperty(PropertyConfigBuilder().SetName("Youtube").SetDataType(
              TYPE_BYTES))
          .AddProperty(PropertyConfigBuilder()
                           .SetName("Alphabet")
                           .SetDataType(TYPE_INT64))
          .Build();
  SchemaUtil::TypeConfigMap type_config_map = {
      {schema_type_name, schema_type_config}};

  SchemaPropertyIterator iterator(schema_type_config, type_config_map);
  EXPECT_THAT(iterator.Advance(), IsOk());
  EXPECT_THAT(iterator.GetCurrentPropertyPath(), Eq("Alphabet"));
  EXPECT_THAT(iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config.properties(2)));
  EXPECT_THAT(iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(iterator.Advance(), IsOk());
  EXPECT_THAT(iterator.GetCurrentPropertyPath(), Eq("Google"));
  EXPECT_THAT(iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config.properties(0)));
  EXPECT_THAT(iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(iterator.Advance(), IsOk());
  EXPECT_THAT(iterator.GetCurrentPropertyPath(), Eq("Youtube"));
  EXPECT_THAT(iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config.properties(1)));
  EXPECT_THAT(iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(iterator.Advance(),
              StatusIs(libtextclassifier3::StatusCode::OUT_OF_RANGE));
}

TEST(SchemaPropertyIteratorTest,
     NestedSchemaTypeConfigShouldIterateInCorrectOrder) {
  std::string schema_type_name1 = "SchemaOne";
  std::string schema_type_name2 = "SchemaTwo";
  std::string schema_type_name3 = "SchemaThree";

  SchemaTypeConfigProto schema_type_config1 =
      SchemaTypeConfigBuilder()
          .SetType(schema_type_name1)
          .AddProperty(PropertyConfigBuilder().SetName("Google").SetDataType(
              TYPE_STRING))
          .AddProperty(PropertyConfigBuilder().SetName("Youtube").SetDataType(
              TYPE_BYTES))
          .AddProperty(PropertyConfigBuilder()
                           .SetName("Alphabet")
                           .SetDataType(TYPE_INT64))
          .Build();
  SchemaTypeConfigProto schema_type_config2 =
      SchemaTypeConfigBuilder()
          .SetType(schema_type_name2)
          .AddProperty(
              PropertyConfigBuilder().SetName("Foo").SetDataType(TYPE_STRING))
          .AddProperty(
              PropertyConfigBuilder().SetName("Bar").SetDataTypeDocument(
                  schema_type_name1, /*index_nested_properties=*/true))
          .Build();
  SchemaTypeConfigProto schema_type_config3 =
      SchemaTypeConfigBuilder()
          .SetType(schema_type_name3)
          .AddProperty(
              PropertyConfigBuilder().SetName("Hello").SetDataType(TYPE_STRING))
          .AddProperty(
              PropertyConfigBuilder().SetName("World").SetDataTypeDocument(
                  schema_type_name1, /*index_nested_properties=*/true))
          .AddProperty(
              PropertyConfigBuilder().SetName("Icing").SetDataTypeDocument(
                  schema_type_name2, /*index_nested_properties=*/true))
          .Build();
  SchemaUtil::TypeConfigMap type_config_map = {
      {schema_type_name1, schema_type_config1},
      {schema_type_name2, schema_type_config2},
      {schema_type_name3, schema_type_config3}};

  // SchemaThree: {
  //   "Hello": TYPE_STRING,
  //   "World": TYPE_DOCUMENT SchemaOne {
  //     "Google": TYPE_STRING,
  //     "Youtube": TYPE_BYTES,
  //     "Alphabet": TYPE_INT64,
  //   },
  //   "Icing": TYPE_DOCUMENT SchemaTwo {
  //     "Foo": TYPE_STRING,
  //     "Bar": TYPE_DOCUMENT SchemaOne {
  //       "Google": TYPE_STRING,
  //       "Youtube": TYPE_BYTES,
  //       "Alphabet": TYPE_INT64,
  //     },
  //   },
  // }
  SchemaPropertyIterator iterator(schema_type_config3, type_config_map);
  EXPECT_THAT(iterator.Advance(), IsOk());
  EXPECT_THAT(iterator.GetCurrentPropertyPath(), Eq("Hello"));
  EXPECT_THAT(iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config3.properties(0)));
  EXPECT_THAT(iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(iterator.Advance(), IsOk());
  EXPECT_THAT(iterator.GetCurrentPropertyPath(), Eq("Icing.Bar.Alphabet"));
  EXPECT_THAT(iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config1.properties(2)));
  EXPECT_THAT(iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(iterator.Advance(), IsOk());
  EXPECT_THAT(iterator.GetCurrentPropertyPath(), Eq("Icing.Bar.Google"));
  EXPECT_THAT(iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config1.properties(0)));
  EXPECT_THAT(iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(iterator.Advance(), IsOk());
  EXPECT_THAT(iterator.GetCurrentPropertyPath(), Eq("Icing.Bar.Youtube"));
  EXPECT_THAT(iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config1.properties(1)));
  EXPECT_THAT(iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(iterator.Advance(), IsOk());
  EXPECT_THAT(iterator.GetCurrentPropertyPath(), Eq("Icing.Foo"));
  EXPECT_THAT(iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config2.properties(0)));
  EXPECT_THAT(iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(iterator.Advance(), IsOk());
  EXPECT_THAT(iterator.GetCurrentPropertyPath(), Eq("World.Alphabet"));
  EXPECT_THAT(iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config1.properties(2)));
  EXPECT_THAT(iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(iterator.Advance(), IsOk());
  EXPECT_THAT(iterator.GetCurrentPropertyPath(), Eq("World.Google"));
  EXPECT_THAT(iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config1.properties(0)));
  EXPECT_THAT(iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(iterator.Advance(), IsOk());
  EXPECT_THAT(iterator.GetCurrentPropertyPath(), Eq("World.Youtube"));
  EXPECT_THAT(iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config1.properties(1)));
  EXPECT_THAT(iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(iterator.Advance(),
              StatusIs(libtextclassifier3::StatusCode::OUT_OF_RANGE));
}

TEST(SchemaPropertyIteratorTest,
     NonExistingNestedSchemaTypeConfigShouldGetNotFoundError) {
  std::string schema_type_name1 = "SchemaOne";
  std::string schema_type_name2 = "SchemaTwo";

  SchemaTypeConfigProto schema_type_config1 =
      SchemaTypeConfigBuilder()
          .SetType(schema_type_name1)
          .AddProperty(PropertyConfigBuilder().SetName("Google").SetDataType(
              TYPE_STRING))
          .AddProperty(PropertyConfigBuilder().SetName("Youtube").SetDataType(
              TYPE_BYTES))
          .AddProperty(PropertyConfigBuilder()
                           .SetName("Alphabet")
                           .SetDataType(TYPE_INT64))
          .Build();
  SchemaTypeConfigProto schema_type_config2 =
      SchemaTypeConfigBuilder()
          .SetType(schema_type_name2)
          .AddProperty(
              PropertyConfigBuilder().SetName("Foo").SetDataTypeDocument(
                  schema_type_name1, /*index_nested_properties=*/true))
          .Build();
  // Remove the second level (schema_type_config1) from type_config_map.
  SchemaUtil::TypeConfigMap type_config_map = {
      {schema_type_name2, schema_type_config2}};

  SchemaPropertyIterator iterator(schema_type_config2, type_config_map);
  // Since Foo is a document type property with schema type = "SchemaOne" and
  // "SchemaOne" is not in type_config_map, Advance() should return NOT_FOUND
  // error.
  EXPECT_THAT(iterator.Advance(),
              StatusIs(libtextclassifier3::StatusCode::NOT_FOUND));
}

TEST(SchemaPropertyIteratorTest,
     SchemaTypeConfigWithEmptyPropertyShouldGetOutOfRangeErrorAtFirstAdvance) {
  std::string schema_type_name = "Schema";

  SchemaTypeConfigProto schema_type_config =
      SchemaTypeConfigBuilder().SetType(schema_type_name).Build();
  SchemaUtil::TypeConfigMap type_config_map = {
      {schema_type_name, schema_type_config}};

  SchemaPropertyIterator iterator(schema_type_config, type_config_map);
  EXPECT_THAT(iterator.Advance(),
              StatusIs(libtextclassifier3::StatusCode::OUT_OF_RANGE));
}

TEST(SchemaPropertyIteratorTest, NestedIndexable) {
  std::string schema_type_name1 = "SchemaOne";
  std::string schema_type_name2 = "SchemaTwo";
  std::string schema_type_name3 = "SchemaThree";
  std::string schema_type_name4 = "SchemaFour";

  SchemaTypeConfigProto schema_type_config1 =
      SchemaTypeConfigBuilder()
          .SetType(schema_type_name1)
          .AddProperty(
              PropertyConfigBuilder().SetName("Google").SetDataTypeString(
                  TERM_MATCH_EXACT, TOKENIZER_PLAIN))
          .Build();
  SchemaTypeConfigProto schema_type_config2 =
      SchemaTypeConfigBuilder()
          .SetType(schema_type_name2)
          .AddProperty(
              PropertyConfigBuilder().SetName("Bar").SetDataTypeDocument(
                  schema_type_name1, /*index_nested_properties=*/true))
          .AddProperty(PropertyConfigBuilder().SetName("Foo").SetDataTypeString(
              TERM_MATCH_EXACT, TOKENIZER_PLAIN))
          .Build();
  SchemaTypeConfigProto schema_type_config3 =
      SchemaTypeConfigBuilder()
          .SetType(schema_type_name3)
          .AddProperty(
              PropertyConfigBuilder().SetName("Bar").SetDataTypeDocument(
                  schema_type_name1,
                  /*index_nested_properties=*/false))
          .AddProperty(PropertyConfigBuilder().SetName("Foo").SetDataTypeString(
              TERM_MATCH_EXACT, TOKENIZER_PLAIN))
          .Build();
  SchemaTypeConfigProto schema_type_config4 =
      SchemaTypeConfigBuilder()
          .SetType(schema_type_name4)
          .AddProperty(
              PropertyConfigBuilder().SetName("Baz1").SetDataTypeDocument(
                  schema_type_name2, /*index_nested_properties=*/true))
          .AddProperty(
              PropertyConfigBuilder().SetName("Baz2").SetDataTypeDocument(
                  schema_type_name2, /*index_nested_properties=*/false))
          .AddProperty(
              PropertyConfigBuilder().SetName("Baz3").SetDataTypeDocument(
                  schema_type_name3, /*index_nested_properties=*/true))
          .AddProperty(
              PropertyConfigBuilder().SetName("Baz4").SetDataTypeDocument(
                  schema_type_name3, /*index_nested_properties=*/false))
          .AddProperty(
              PropertyConfigBuilder().SetName("Hello1").SetDataTypeDocument(
                  schema_type_name1, /*index_nested_properties=*/true))
          .AddProperty(
              PropertyConfigBuilder().SetName("Hello2").SetDataTypeDocument(
                  schema_type_name1, /*index_nested_properties=*/false))
          .AddProperty(
              PropertyConfigBuilder().SetName("World").SetDataTypeString(
                  TERM_MATCH_EXACT, TOKENIZER_PLAIN))
          .Build();
  SchemaUtil::TypeConfigMap type_config_map = {
      {schema_type_name1, schema_type_config1},
      {schema_type_name2, schema_type_config2},
      {schema_type_name3, schema_type_config3},
      {schema_type_name4, schema_type_config4}};

  // SchemaFour: {
  //   "Baz1": TYPE_DOCUMENT INDEX_NESTED_PROPERTIES=true SchemaTwo {
  //     "Bar": TYPE_DOCUMENT INDEX_NESTED_PROPERTIES=true SchemaOne {
  //       "Google": TYPE_STRING INDEXABLE,
  //     },
  //     "Foo": TYPE_STRING INDEXABLE,
  //   },
  //   "Baz2": TYPE_DOCUMENT INDEX_NESTED_PROPERTIES=false SchemaTwo {
  //     "Bar": TYPE_DOCUMENT INDEX_NESTED_PROPERTIES=true SchemaOne {
  //       "Google": TYPE_STRING INDEXABLE,
  //     },
  //     "Foo": TYPE_STRING INDEXABLE,
  //   },
  //   "Baz3": TYPE_DOCUMENT INDEX_NESTED_PROPERTIES=true SchemaThree {
  //     "Bar": TYPE_DOCUMENT INDEX_NESTED_PROPERTIES=false SchemaOne {
  //       "Google": TYPE_STRING INDEXABLE,
  //     },
  //     "Foo": TYPE_STRING INDEXABLE,
  //   },
  //   "Baz4": TYPE_DOCUMENT INDEX_NESTED_PROPERTIES=false SchemaThree {
  //     "Bar": TYPE_DOCUMENT INDEX_NESTED_PROPERTIES=false SchemaOne {
  //       "Google": TYPE_STRING INDEXABLE,
  //     },
  //     "Foo": TYPE_STRING INDEXABLE,
  //   },
  //   "Hello": TYPE_DOCUMENT INDEX_NESTED_PROPERTIES=false SchemaOne {
  //     "Google": TYPE_STRING INDEXABLE,
  //   },
  //   "World": TYPE_STRING INDEXABLE,
  // }
  SchemaPropertyIterator iterator(schema_type_config4, type_config_map);

  // Baz1 to Baz4: 2 levels of nested document type property.
  // For Baz1, all levels set index_nested_properties = true, so all leaf
  // properties should be nested indexable.
  EXPECT_THAT(iterator.Advance(), IsOk());
  EXPECT_THAT(iterator.GetCurrentPropertyPath(), Eq("Baz1.Bar.Google"));
  EXPECT_THAT(iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config1.properties(0)));
  EXPECT_THAT(iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(iterator.Advance(), IsOk());
  EXPECT_THAT(iterator.GetCurrentPropertyPath(), Eq("Baz1.Foo"));
  EXPECT_THAT(iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config2.properties(1)));
  EXPECT_THAT(iterator.GetCurrentNestedIndexable(), IsTrue());

  // For Baz2, the parent level sets index_nested_properties = false, so all
  // leaf properties in child levels should be nested unindexable even if
  // they've set their index_nested_properties = true.
  EXPECT_THAT(iterator.Advance(), IsOk());
  EXPECT_THAT(iterator.GetCurrentPropertyPath(), Eq("Baz2.Bar.Google"));
  EXPECT_THAT(iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config1.properties(0)));
  EXPECT_THAT(iterator.GetCurrentNestedIndexable(), IsFalse());

  EXPECT_THAT(iterator.Advance(), IsOk());
  EXPECT_THAT(iterator.GetCurrentPropertyPath(), Eq("Baz2.Foo"));
  EXPECT_THAT(iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config2.properties(1)));
  EXPECT_THAT(iterator.GetCurrentNestedIndexable(), IsFalse());

  // For Baz3, the parent level sets index_nested_properties = true, but the
  // child level sets index_nested_properties = false.
  // - Leaf properties in the parent level should be nested indexable.
  // - Leaf properties in the child level should be nested unindexable.
  EXPECT_THAT(iterator.Advance(), IsOk());
  EXPECT_THAT(iterator.GetCurrentPropertyPath(), Eq("Baz3.Bar.Google"));
  EXPECT_THAT(iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config1.properties(0)));
  EXPECT_THAT(iterator.GetCurrentNestedIndexable(), IsFalse());

  EXPECT_THAT(iterator.Advance(), IsOk());
  EXPECT_THAT(iterator.GetCurrentPropertyPath(), Eq("Baz3.Foo"));
  EXPECT_THAT(iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config2.properties(1)));
  EXPECT_THAT(iterator.GetCurrentNestedIndexable(), IsTrue());

  // For Baz4, all levels set index_nested_properties = false, so all leaf
  // properties should be nested unindexable.
  EXPECT_THAT(iterator.Advance(), IsOk());
  EXPECT_THAT(iterator.GetCurrentPropertyPath(), Eq("Baz4.Bar.Google"));
  EXPECT_THAT(iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config1.properties(0)));
  EXPECT_THAT(iterator.GetCurrentNestedIndexable(), IsFalse());

  EXPECT_THAT(iterator.Advance(), IsOk());
  EXPECT_THAT(iterator.GetCurrentPropertyPath(), Eq("Baz4.Foo"));
  EXPECT_THAT(iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config2.properties(1)));
  EXPECT_THAT(iterator.GetCurrentNestedIndexable(), IsFalse());

  // Verify 1 and 0 level of nested document type properties.
  EXPECT_THAT(iterator.Advance(), IsOk());
  EXPECT_THAT(iterator.GetCurrentPropertyPath(), Eq("Hello1.Google"));
  EXPECT_THAT(iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config1.properties(0)));
  EXPECT_THAT(iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(iterator.Advance(), IsOk());
  EXPECT_THAT(iterator.GetCurrentPropertyPath(), Eq("Hello2.Google"));
  EXPECT_THAT(iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config1.properties(0)));
  EXPECT_THAT(iterator.GetCurrentNestedIndexable(), IsFalse());

  EXPECT_THAT(iterator.Advance(), IsOk());
  EXPECT_THAT(iterator.GetCurrentPropertyPath(), Eq("World"));
  EXPECT_THAT(iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config4.properties(6)));
  EXPECT_THAT(iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(iterator.Advance(),
              StatusIs(libtextclassifier3::StatusCode::OUT_OF_RANGE));
}

TEST(SchemaPropertyIteratorTest, SingleLevelCycle) {
  std::string schema_a = "A";
  std::string schema_b = "B";

  // Create schema with A -> B -> B -> B...
  SchemaTypeConfigProto schema_type_config_a =
      SchemaTypeConfigBuilder()
          .SetType(schema_a)
          .AddProperty(PropertyConfigBuilder()
                           .SetName("schemaAprop1")
                           .SetDataTypeDocument(
                               schema_b, /*index_nested_properties=*/true))
          .AddProperty(
              PropertyConfigBuilder()
                  .SetName("schemaAprop2")
                  .SetDataTypeString(TERM_MATCH_EXACT, TOKENIZER_PLAIN))
          .Build();
  SchemaTypeConfigProto schema_type_config_b =
      SchemaTypeConfigBuilder()
          .SetType(schema_b)
          .AddProperty(PropertyConfigBuilder()
                           .SetName("schemaBprop1")
                           .SetDataTypeDocument(
                               schema_b, /*index_nested_properties=*/false))
          .AddProperty(
              PropertyConfigBuilder()
                  .SetName("schemaBprop2")
                  .SetDataTypeString(TERM_MATCH_EXACT, TOKENIZER_PLAIN))
          .Build();

  SchemaUtil::TypeConfigMap type_config_map = {
      {schema_a, schema_type_config_a}, {schema_b, schema_type_config_b}};

  // Order of iteration for schema A:
  // {"schemaAprop1.schemaBprop2", "schemaAprop2"}, both indexable
  SchemaPropertyIterator schema_a_iterator(schema_type_config_a,
                                           type_config_map);

  EXPECT_THAT(schema_a_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_a_iterator.GetCurrentPropertyPath(),
              Eq("schemaAprop1.schemaBprop2"));
  EXPECT_THAT(schema_a_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_b.properties(1)));
  EXPECT_THAT(schema_a_iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(schema_a_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_a_iterator.GetCurrentPropertyPath(), Eq("schemaAprop2"));
  EXPECT_THAT(schema_a_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_a.properties(1)));
  EXPECT_THAT(schema_a_iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(schema_a_iterator.Advance(),
              StatusIs(libtextclassifier3::StatusCode::OUT_OF_RANGE));

  // Order of iteration for schema B:
  // {"schemaBprop2"}, indexable.
  SchemaPropertyIterator schema_b_iterator(schema_type_config_b,
                                           type_config_map);

  EXPECT_THAT(schema_b_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_b_iterator.GetCurrentPropertyPath(), Eq("schemaBprop2"));
  EXPECT_THAT(schema_b_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_b.properties(1)));
  EXPECT_THAT(schema_b_iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(schema_b_iterator.Advance(),
              StatusIs(libtextclassifier3::StatusCode::OUT_OF_RANGE));
}

TEST(SchemaPropertyIteratorTest, MultipleLevelCycle) {
  std::string schema_a = "A";
  std::string schema_b = "B";
  std::string schema_c = "C";

  // Create schema with A -> B -> C -> A -> B -> C...
  SchemaTypeConfigProto schema_type_config_a =
      SchemaTypeConfigBuilder()
          .SetType(schema_a)
          .AddProperty(PropertyConfigBuilder()
                           .SetName("schemaAprop1")
                           .SetDataTypeDocument(
                               schema_b, /*index_nested_properties=*/true))
          .AddProperty(
              PropertyConfigBuilder()
                  .SetName("schemaAprop2")
                  .SetDataTypeString(TERM_MATCH_EXACT, TOKENIZER_PLAIN))
          .Build();
  SchemaTypeConfigProto schema_type_config_b =
      SchemaTypeConfigBuilder()
          .SetType(schema_b)
          .AddProperty(PropertyConfigBuilder()
                           .SetName("schemaBprop1")
                           .SetDataTypeDocument(
                               schema_c, /*index_nested_properties=*/true))
          .AddProperty(
              PropertyConfigBuilder()
                  .SetName("schemaBprop2")
                  .SetDataTypeString(TERM_MATCH_EXACT, TOKENIZER_PLAIN))
          .Build();
  SchemaTypeConfigProto schema_type_config_c =
      SchemaTypeConfigBuilder()
          .SetType(schema_c)
          .AddProperty(PropertyConfigBuilder()
                           .SetName("schemaCprop1")
                           .SetDataTypeDocument(
                               schema_a, /*index_nested_properties=*/false))
          .AddProperty(
              PropertyConfigBuilder()
                  .SetName("schemaCprop2")
                  .SetDataTypeString(TERM_MATCH_EXACT, TOKENIZER_PLAIN))
          .Build();

  SchemaUtil::TypeConfigMap type_config_map = {
      {schema_a, schema_type_config_a},
      {schema_b, schema_type_config_b},
      {schema_c, schema_type_config_c}};

  // Order of iteration for schema A:
  // {"schemaAprop1.schemaBprop1.schemaCprop2", "schemaAprop1.schemaBprop2",
  // "schemaAprop2"}, all indexable
  SchemaPropertyIterator schema_a_iterator(schema_type_config_a,
                                           type_config_map);

  EXPECT_THAT(schema_a_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_a_iterator.GetCurrentPropertyPath(),
              Eq("schemaAprop1.schemaBprop1.schemaCprop2"));
  EXPECT_THAT(schema_a_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_c.properties(1)));
  EXPECT_THAT(schema_a_iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(schema_a_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_a_iterator.GetCurrentPropertyPath(),
              Eq("schemaAprop1.schemaBprop2"));
  EXPECT_THAT(schema_a_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_b.properties(1)));
  EXPECT_THAT(schema_a_iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(schema_a_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_a_iterator.GetCurrentPropertyPath(), Eq("schemaAprop2"));
  EXPECT_THAT(schema_a_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_a.properties(1)));
  EXPECT_THAT(schema_a_iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(schema_a_iterator.Advance(),
              StatusIs(libtextclassifier3::StatusCode::OUT_OF_RANGE));

  // Order of iteration for schema B:
  // {"schemaBprop1.schemaCprop1.schemaAprop2", "schemaBprop1.schemaCprop2",
  // "schemaBprop2"}
  //
  // Indexable properties: {"schemaBprop1.schemaCprop2", "schemaBprop2"}
  SchemaPropertyIterator schema_b_iterator(schema_type_config_b,
                                           type_config_map);

  EXPECT_THAT(schema_b_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_b_iterator.GetCurrentPropertyPath(),
              Eq("schemaBprop1.schemaCprop1.schemaAprop2"));
  EXPECT_THAT(schema_b_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_a.properties(1)));
  EXPECT_THAT(schema_b_iterator.GetCurrentNestedIndexable(), IsFalse());

  EXPECT_THAT(schema_b_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_b_iterator.GetCurrentPropertyPath(),
              Eq("schemaBprop1.schemaCprop2"));
  EXPECT_THAT(schema_b_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_c.properties(1)));
  EXPECT_THAT(schema_b_iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(schema_b_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_b_iterator.GetCurrentPropertyPath(), Eq("schemaBprop2"));
  EXPECT_THAT(schema_b_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_b.properties(1)));
  EXPECT_THAT(schema_b_iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(schema_b_iterator.Advance(),
              StatusIs(libtextclassifier3::StatusCode::OUT_OF_RANGE));

  // Order of iteration for schema C:
  // {"schemaCprop1.schemaAprop1.schemaBprop2", "schemaCprop1.schemaAprop2",
  // "schemaCprop2"}
  //
  // Indexable properties: {"schemaCprop2"}
  SchemaPropertyIterator schema_c_iterator(schema_type_config_c,
                                           type_config_map);

  EXPECT_THAT(schema_c_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_c_iterator.GetCurrentPropertyPath(),
              Eq("schemaCprop1.schemaAprop1.schemaBprop2"));
  EXPECT_THAT(schema_c_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_b.properties(1)));
  EXPECT_THAT(schema_c_iterator.GetCurrentNestedIndexable(), IsFalse());

  EXPECT_THAT(schema_c_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_c_iterator.GetCurrentPropertyPath(),
              Eq("schemaCprop1.schemaAprop2"));
  EXPECT_THAT(schema_c_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_a.properties(1)));
  EXPECT_THAT(schema_c_iterator.GetCurrentNestedIndexable(), IsFalse());

  EXPECT_THAT(schema_c_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_c_iterator.GetCurrentPropertyPath(), Eq("schemaCprop2"));
  EXPECT_THAT(schema_c_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_c.properties(1)));
  EXPECT_THAT(schema_c_iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(schema_c_iterator.Advance(),
              StatusIs(libtextclassifier3::StatusCode::OUT_OF_RANGE));
}

TEST(SchemaPropertyIteratorTest, MultipleCycles) {
  std::string schema_a = "A";
  std::string schema_b = "B";
  std::string schema_c = "C";
  std::string schema_d = "D";

  // Create schema with D <-> A -> B -> C -> A -> B -> C -> A...
  // Schema type A has two cycles: A-B-C-A and A-D-A
  SchemaTypeConfigProto schema_type_config_a =
      SchemaTypeConfigBuilder()
          .SetType(schema_a)
          .AddProperty(PropertyConfigBuilder()
                           .SetName("schemaAprop1")
                           .SetDataTypeDocument(
                               schema_b, /*index_nested_properties=*/true))
          .AddProperty(
              PropertyConfigBuilder()
                  .SetName("schemaAprop2")
                  .SetDataTypeString(TERM_MATCH_EXACT, TOKENIZER_PLAIN))
          .AddProperty(PropertyConfigBuilder()
                           .SetName("schemaAprop3")
                           .SetDataTypeDocument(
                               schema_d, /*index_nested_properties=*/true))
          .Build();
  SchemaTypeConfigProto schema_type_config_b =
      SchemaTypeConfigBuilder()
          .SetType(schema_b)
          .AddProperty(PropertyConfigBuilder()
                           .SetName("schemaBprop1")
                           .SetDataTypeDocument(
                               schema_c, /*index_nested_properties=*/true))
          .AddProperty(
              PropertyConfigBuilder()
                  .SetName("schemaBprop2")
                  .SetDataTypeString(TERM_MATCH_EXACT, TOKENIZER_PLAIN))
          .Build();
  SchemaTypeConfigProto schema_type_config_c =
      SchemaTypeConfigBuilder()
          .SetType(schema_c)
          .AddProperty(PropertyConfigBuilder()
                           .SetName("schemaCprop1")
                           .SetDataTypeDocument(
                               schema_a, /*index_nested_properties=*/false))
          .AddProperty(
              PropertyConfigBuilder()
                  .SetName("schemaCprop2")
                  .SetDataTypeString(TERM_MATCH_EXACT, TOKENIZER_PLAIN))
          .Build();
  SchemaTypeConfigProto schema_type_config_d =
      SchemaTypeConfigBuilder()
          .SetType(schema_d)
          .AddProperty(PropertyConfigBuilder()
                           .SetName("schemaDprop1")
                           .SetDataTypeDocument(
                               schema_a, /*index_nested_properties=*/false))
          .AddProperty(
              PropertyConfigBuilder()
                  .SetName("schemaDprop2")
                  .SetDataTypeString(TERM_MATCH_EXACT, TOKENIZER_PLAIN))
          .Build();

  SchemaUtil::TypeConfigMap type_config_map = {
      {schema_a, schema_type_config_a},
      {schema_b, schema_type_config_b},
      {schema_c, schema_type_config_c},
      {schema_d, schema_type_config_d}};

  // Order of iteration for schema A:
  // {"schemaAprop1.schemaBprop1.schemaCprop2", "schemaAprop1.schemaBprop2",
  // "schemaAprop2", "schemaAprop3.schemaDprop2"}, all indexable
  SchemaPropertyIterator schema_a_iterator(schema_type_config_a,
                                           type_config_map);

  EXPECT_THAT(schema_a_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_a_iterator.GetCurrentPropertyPath(),
              Eq("schemaAprop1.schemaBprop1.schemaCprop2"));
  EXPECT_THAT(schema_a_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_c.properties(1)));
  EXPECT_THAT(schema_a_iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(schema_a_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_a_iterator.GetCurrentPropertyPath(),
              Eq("schemaAprop1.schemaBprop2"));
  EXPECT_THAT(schema_a_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_b.properties(1)));
  EXPECT_THAT(schema_a_iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(schema_a_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_a_iterator.GetCurrentPropertyPath(), Eq("schemaAprop2"));
  EXPECT_THAT(schema_a_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_a.properties(1)));
  EXPECT_THAT(schema_a_iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(schema_a_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_a_iterator.GetCurrentPropertyPath(),
              Eq("schemaAprop3.schemaDprop2"));
  EXPECT_THAT(schema_a_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_d.properties(1)));
  EXPECT_THAT(schema_a_iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(schema_a_iterator.Advance(),
              StatusIs(libtextclassifier3::StatusCode::OUT_OF_RANGE));

  // Order of iteration for schema B:
  // {"schemaBprop1.schemaCprop1.schemaAprop2",
  // "schemaBprop1.schemaCprop1.schemaAprop3.schemaDprop2",
  // "schemaBprop1.schemaCprop2", "schemaBprop2"}
  //
  // Indexable properties: {"schemaBprop1.schemaCprop2", "schemaBprop2"}
  SchemaPropertyIterator schema_b_iterator(schema_type_config_b,
                                           type_config_map);

  EXPECT_THAT(schema_b_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_b_iterator.GetCurrentPropertyPath(),
              Eq("schemaBprop1.schemaCprop1.schemaAprop2"));
  EXPECT_THAT(schema_b_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_a.properties(1)));
  EXPECT_THAT(schema_b_iterator.GetCurrentNestedIndexable(), IsFalse());

  EXPECT_THAT(schema_b_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_b_iterator.GetCurrentPropertyPath(),
              Eq("schemaBprop1.schemaCprop1.schemaAprop3.schemaDprop2"));
  EXPECT_THAT(schema_b_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_d.properties(1)));
  EXPECT_THAT(schema_b_iterator.GetCurrentNestedIndexable(), IsFalse());

  EXPECT_THAT(schema_b_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_b_iterator.GetCurrentPropertyPath(),
              Eq("schemaBprop1.schemaCprop2"));
  EXPECT_THAT(schema_b_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_c.properties(1)));
  EXPECT_THAT(schema_b_iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(schema_b_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_b_iterator.GetCurrentPropertyPath(), Eq("schemaBprop2"));
  EXPECT_THAT(schema_b_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_b.properties(1)));
  EXPECT_THAT(schema_b_iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(schema_b_iterator.Advance(),
              StatusIs(libtextclassifier3::StatusCode::OUT_OF_RANGE));

  // Order of iteration for schema C:
  // {"schemaCprop1.schemaAprop1.schemaBprop2", "schemaCprop1.schemaAprop2",
  // "schemaCprop1.schemaAprop3.schemaDprop2", "schemaCprop2"}
  //
  // Indexable properties: {"schemaCprop2"}
  SchemaPropertyIterator schema_c_iterator(schema_type_config_c,
                                           type_config_map);

  EXPECT_THAT(schema_c_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_c_iterator.GetCurrentPropertyPath(),
              Eq("schemaCprop1.schemaAprop1.schemaBprop2"));
  EXPECT_THAT(schema_c_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_b.properties(1)));
  EXPECT_THAT(schema_c_iterator.GetCurrentNestedIndexable(), IsFalse());

  EXPECT_THAT(schema_c_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_c_iterator.GetCurrentPropertyPath(),
              Eq("schemaCprop1.schemaAprop2"));
  EXPECT_THAT(schema_c_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_a.properties(1)));
  EXPECT_THAT(schema_c_iterator.GetCurrentNestedIndexable(), IsFalse());

  EXPECT_THAT(schema_c_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_c_iterator.GetCurrentPropertyPath(),
              Eq("schemaCprop1.schemaAprop3.schemaDprop2"));
  EXPECT_THAT(schema_c_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_d.properties(1)));
  EXPECT_THAT(schema_c_iterator.GetCurrentNestedIndexable(), IsFalse());

  EXPECT_THAT(schema_c_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_c_iterator.GetCurrentPropertyPath(), Eq("schemaCprop2"));
  EXPECT_THAT(schema_c_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_c.properties(1)));
  EXPECT_THAT(schema_c_iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(schema_c_iterator.Advance(),
              StatusIs(libtextclassifier3::StatusCode::OUT_OF_RANGE));

  // Order of iteration for schema D:
  // {"schemaDprop1.schemaAprop1.schemaBprop1.schemaCprop2",
  // "schemaDprop1.schemaAprop1.schemaBprop2", "schemaDprop1.schemaAprop2",
  // "schemaDprop2"}
  //
  // Indexable properties: {"schemaDprop2"}
  SchemaPropertyIterator schema_d_iterator(schema_type_config_d,
                                           type_config_map);

  EXPECT_THAT(schema_d_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_d_iterator.GetCurrentPropertyPath(),
              Eq("schemaDprop1.schemaAprop1.schemaBprop1.schemaCprop2"));
  EXPECT_THAT(schema_d_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_c.properties(1)));
  EXPECT_THAT(schema_d_iterator.GetCurrentNestedIndexable(), IsFalse());

  EXPECT_THAT(schema_d_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_d_iterator.GetCurrentPropertyPath(),
              Eq("schemaDprop1.schemaAprop1.schemaBprop2"));
  EXPECT_THAT(schema_d_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_b.properties(1)));
  EXPECT_THAT(schema_d_iterator.GetCurrentNestedIndexable(), IsFalse());

  EXPECT_THAT(schema_d_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_d_iterator.GetCurrentPropertyPath(),
              Eq("schemaDprop1.schemaAprop2"));
  EXPECT_THAT(schema_d_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_a.properties(1)));
  EXPECT_THAT(schema_d_iterator.GetCurrentNestedIndexable(), IsFalse());

  EXPECT_THAT(schema_d_iterator.Advance(), IsOk());
  EXPECT_THAT(schema_d_iterator.GetCurrentPropertyPath(), Eq("schemaDprop2"));
  EXPECT_THAT(schema_d_iterator.GetCurrentPropertyConfig(),
              EqualsProto(schema_type_config_d.properties(1)));
  EXPECT_THAT(schema_d_iterator.GetCurrentNestedIndexable(), IsTrue());

  EXPECT_THAT(schema_d_iterator.Advance(),
              StatusIs(libtextclassifier3::StatusCode::OUT_OF_RANGE));
}

}  // namespace

}  // namespace lib
}  // namespace icing
