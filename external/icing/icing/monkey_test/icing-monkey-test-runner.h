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

#ifndef ICING_MONKEY_TEST_ICING_MONKEY_TEST_RUNNER_H_
#define ICING_MONKEY_TEST_ICING_MONKEY_TEST_RUNNER_H_

#include <cstdint>
#include <random>

#include "icing/file/destructible-directory.h"
#include "icing/icing-search-engine.h"
#include "icing/monkey_test/in-memory-icing-search-engine.h"
#include "icing/monkey_test/monkey-test-generators.h"

namespace icing {
namespace lib {

class IcingMonkeyTestRunner;

struct IcingMonkeyTestRunnerConfiguration {
  explicit IcingMonkeyTestRunnerConfiguration(uint32_t seed, int num_types,
                                              int num_namespaces, int num_uris,
                                              int index_merge_size)
      : seed(seed),
        num_types(num_types),
        num_namespaces(num_namespaces),
        num_uris(num_uris),
        index_merge_size(index_merge_size) {}

  uint32_t seed;
  int num_types;
  int num_namespaces;
  int num_uris;
  int index_merge_size;

  // The possible number of properties that may appear in generated schema
  // types.
  std::vector<int> possible_num_properties;

  // The possible number of tokens that may appear in generated documents, with
  // a noise factor from 0.5 to 1 applied.
  std::vector<int> possible_num_tokens_;

  // An array of pairs of monkey test APIs with frequencies.
  // If f_sum is the sum of all the frequencies, an operation with frequency f
  // means for every f_sum iterations, the operation is expected to run f times.
  std::vector<std::pair<std::function<void(IcingMonkeyTestRunner*)>, uint32_t>>
      monkey_api_schedules;
};

class IcingMonkeyTestRunner {
 public:
  IcingMonkeyTestRunner(const IcingMonkeyTestRunnerConfiguration& config);
  IcingMonkeyTestRunner(const IcingMonkeyTestRunner&) = delete;
  IcingMonkeyTestRunner& operator=(const IcingMonkeyTestRunner&) = delete;

  // This function must and should only be called before running the monkey
  // test.
  void CreateIcingSearchEngineWithSchema();

  // Run the monkey test with num operations.
  void Run(uint32_t num);

  // APIs supported in icing search engine.
  void DoGet();
  void DoGetAllNamespaces();
  void DoPut();
  void DoDelete();
  void DoDeleteByNamespace();
  void DoDeleteBySchemaType();
  void DoDeleteByQuery();
  void DoSearch();

  // Operations with no observable side-effects.
  void ReloadFromDisk();
  void DoOptimize();

 private:
  IcingMonkeyTestRunnerConfiguration config_;
  MonkeyTestRandomEngine random_;
  Filesystem filesystem_;
  std::unique_ptr<DestructibleDirectory> icing_dir_;
  std::unique_ptr<InMemoryIcingSearchEngine> in_memory_icing_;
  std::unique_ptr<IcingSearchEngine> icing_;

  std::unique_ptr<MonkeyDocumentGenerator> document_generator_;

  void CreateIcingSearchEngine();
};

}  // namespace lib
}  // namespace icing

#endif  // ICING_MONKEY_TEST_ICING_MONKEY_TEST_RUNNER_H_
