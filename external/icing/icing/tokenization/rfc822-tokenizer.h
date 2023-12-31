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

#ifndef ICING_TOKENIZATION_RFC822_TOKENIZER_H_
#define ICING_TOKENIZATION_RFC822_TOKENIZER_H_

#include <vector>

#include "icing/tokenization/tokenizer.h"

namespace icing {
namespace lib {

class Rfc822Tokenizer : public Tokenizer {
 public:
  libtextclassifier3::StatusOr<std::unique_ptr<Tokenizer::Iterator>> Tokenize(
      std::string_view text) const override;

  libtextclassifier3::StatusOr<std::vector<Token>> TokenizeAll(
      std::string_view text) const override;

};

}  // namespace lib
}  // namespace icing

#endif  // ICING_TOKENIZATION_RFC822_TOKENIZER_H_
