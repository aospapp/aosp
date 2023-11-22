/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "lang_id/lang-id.h"

#include <stdio.h>

#include <algorithm>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

#include "lang_id/common/embedding-feature-interface.h"
#include "lang_id/common/embedding-network-params.h"
#include "lang_id/common/embedding-network.h"
#include "lang_id/common/fel/feature-extractor.h"
#include "lang_id/common/lite_base/logging.h"
#include "lang_id/common/lite_strings/numbers.h"
#include "lang_id/common/lite_strings/str-split.h"
#include "lang_id/common/lite_strings/stringpiece.h"
#include "lang_id/common/math/algorithm.h"
#include "lang_id/common/math/softmax.h"
#include "lang_id/custom-tokenizer.h"
#include "lang_id/features/light-sentence-features.h"
#include "lang_id/light-sentence.h"

namespace libtextclassifier3 {
namespace mobile {
namespace lang_id {

namespace {
// Default value for the confidence threshold.  If the confidence of the top
// prediction is below this threshold, then FindLanguage() returns
// LangId::kUnknownLanguageCode.  Note: this is just a default value; if the
// TaskSpec from the model specifies a "reliability_thresh" parameter, then we
// use that value instead.  Note: for legacy reasons, our code and comments use
// the terms "confidence", "probability" and "reliability" equivalently.
static const float kDefaultConfidenceThreshold = 0.50f;
}  // namespace

// Class that performs all work behind LangId.
class LangIdImpl {
 public:
  explicit LangIdImpl(std::unique_ptr<ModelProvider> model_provider)
      : model_provider_(std::move(model_provider)),
        lang_id_brain_interface_("language_identifier") {
    // Note: in the code below, we set valid_ to true only if all initialization
    // steps completed successfully.  Otherwise, we return early, leaving valid_
    // to its default value false.
    if (!model_provider_ || !model_provider_->is_valid()) {
      SAFTM_LOG(ERROR) << "Invalid model provider";
      return;
    }

    auto *nn_params = model_provider_->GetNnParams();
    if (!nn_params) {
      SAFTM_LOG(ERROR) << "No NN params";
      return;
    }
    network_.reset(new EmbeddingNetwork(nn_params));

    languages_ = model_provider_->GetLanguages();
    if (languages_.empty()) {
      SAFTM_LOG(ERROR) << "No known languages";
      return;
    }

    TaskContext context = *model_provider_->GetTaskContext();
    if (!Setup(&context)) {
      SAFTM_LOG(ERROR) << "Unable to Setup() LangId";
      return;
    }
    if (!Init(&context)) {
      SAFTM_LOG(ERROR) << "Unable to Init() LangId";
      return;
    }
    valid_ = true;
  }

  string FindLanguage(StringPiece text) const {
    // NOTE: it would be wasteful to implement this method in terms of
    // FindLanguages().  We just need the most likely language and its
    // probability; no need to compute (and allocate) a vector of pairs for all
    // languages, nor to compute probabilities for all non-top languages.
    if (!is_valid()) {
      return LangId::kUnknownLanguageCode;
    }

    std::vector<float> scores;
    ComputeScores(text, &scores);

    int prediction_id = GetArgMax(scores);
    const string language = GetLanguageForSoftmaxLabel(prediction_id);
    float probability = ComputeSoftmaxProbability(scores, prediction_id);
    SAFTM_DLOG(INFO) << "Predicted " << language
                     << " with prob: " << probability << " for \"" << text
                     << "\"";

    // Find confidence threshold for language.
    float threshold = default_threshold_;
    auto it = per_lang_thresholds_.find(language);
    if (it != per_lang_thresholds_.end()) {
      threshold = it->second;
    }
    if (probability < threshold) {
      SAFTM_DLOG(INFO) << "  below threshold => "
                       << LangId::kUnknownLanguageCode;
      return LangId::kUnknownLanguageCode;
    }
    return language;
  }

  void FindLanguages(StringPiece text, LangIdResult *result) const {
    if (result == nullptr) return;

    result->predictions.clear();
    if (!is_valid()) {
      result->predictions.emplace_back(LangId::kUnknownLanguageCode, 1);
      return;
    }

    std::vector<float> scores;
    ComputeScores(text, &scores);

    // Compute and sort softmax in descending order by probability and convert
    // IDs to language code strings.  When probabilities are equal, we sort by
    // language code string in ascending order.
    std::vector<float> softmax = ComputeSoftmax(scores);

    for (int i = 0; i < softmax.size(); ++i) {
      result->predictions.emplace_back(GetLanguageForSoftmaxLabel(i),
                                       softmax[i]);
    }

    // Sort the resulting language predictions by probability in descending
    // order.
    std::sort(result->predictions.begin(), result->predictions.end(),
              [](const std::pair<string, float> &a,
                 const std::pair<string, float> &b) {
                if (a.second == b.second) {
                  return a.first.compare(b.first) < 0;
                } else {
                  return a.second > b.second;
                }
              });
  }

  bool is_valid() const { return valid_; }

  int GetModelVersion() const { return model_version_; }

  // Returns a property stored in the model file.
  template <typename T, typename R>
  R GetProperty(const string &property, T default_value) const {
    return model_provider_->GetTaskContext()->Get(property, default_value);
  }

 private:
  bool Setup(TaskContext *context) {
    tokenizer_.Setup(context);
    if (!lang_id_brain_interface_.SetupForProcessing(context)) return false;
    default_threshold_ =
        context->Get("reliability_thresh", kDefaultConfidenceThreshold);

    // Parse task parameter "per_lang_reliability_thresholds", fill
    // per_lang_thresholds_.
    const string thresholds_str =
        context->Get("per_lang_reliability_thresholds", "");
    std::vector<StringPiece> tokens = LiteStrSplit(thresholds_str, ',');
    for (const auto &token : tokens) {
      if (token.empty()) continue;
      std::vector<StringPiece> parts = LiteStrSplit(token, '=');
      float threshold = 0.0f;
      if ((parts.size() == 2) && LiteAtof(parts[1], &threshold)) {
        per_lang_thresholds_[string(parts[0])] = threshold;
      } else {
        SAFTM_LOG(ERROR) << "Broken token: \"" << token << "\"";
      }
    }
    model_version_ = context->Get("model_version", model_version_);
    return true;
  }

  bool Init(TaskContext *context) {
    return lang_id_brain_interface_.InitForProcessing(context);
  }

  // Extracts features for |text|, runs them through the feed-forward neural
  // network, and computes the output scores (activations from the last layer).
  // These scores can be used to compute the softmax probabilities for our
  // labels (in this case, the languages).
  void ComputeScores(StringPiece text, std::vector<float> *scores) const {
    // Create a Sentence storing the input text.
    LightSentence sentence;
    tokenizer_.Tokenize(text, &sentence);

    std::vector<FeatureVector> features =
        lang_id_brain_interface_.GetFeaturesNoCaching(&sentence);

    // Run feed-forward neural network to compute scores.
    network_->ComputeFinalScores(features, scores);
  }

  // Returns language code for a softmax label.  See comments for languages_
  // field.  If label is out of range, returns LangId::kUnknownLanguageCode.
  string GetLanguageForSoftmaxLabel(int label) const {
    if ((label >= 0) && (label < languages_.size())) {
      return languages_[label];
    } else {
      SAFTM_LOG(ERROR) << "Softmax label " << label << " outside range [0, "
                       << languages_.size() << ")";
      return LangId::kUnknownLanguageCode;
    }
  }

  std::unique_ptr<ModelProvider> model_provider_;

  TokenizerForLangId tokenizer_;

  EmbeddingFeatureInterface<LightSentenceExtractor, LightSentence>
      lang_id_brain_interface_;

  // Neural network to use for scoring.
  std::unique_ptr<EmbeddingNetwork> network_;

  // True if this object is ready to perform language predictions.
  bool valid_ = false;

  // Only predictions with a probability (confidence) above this threshold are
  // reported.  Otherwise, we report LangId::kUnknownLanguageCode.
  float default_threshold_ = kDefaultConfidenceThreshold;

  std::unordered_map<string, float> per_lang_thresholds_;

  // Recognized languages: softmax label i means languages_[i] (something like
  // "en", "fr", "ru", etc).
  std::vector<string> languages_;

  // Version of the model used by this LangIdImpl object.  Zero means that the
  // model version could not be determined.
  int model_version_ = 0;
};

const char LangId::kUnknownLanguageCode[] = "und";

LangId::LangId(std::unique_ptr<ModelProvider> model_provider)
    : pimpl_(new LangIdImpl(std::move(model_provider))) {}

LangId::~LangId() = default;

string LangId::FindLanguage(const char *data, size_t num_bytes) const {
  StringPiece text(data, num_bytes);
  return pimpl_->FindLanguage(text);
}

void LangId::FindLanguages(const char *data, size_t num_bytes,
                           LangIdResult *result) const {
  SAFTM_DCHECK(result) << "LangIdResult must not be null.";
  StringPiece text(data, num_bytes);
  pimpl_->FindLanguages(text, result);
}

bool LangId::is_valid() const { return pimpl_->is_valid(); }

int LangId::GetModelVersion() const { return pimpl_->GetModelVersion(); }

float LangId::GetFloatProperty(const string &property,
                               float default_value) const {
  return pimpl_->GetProperty<float, float>(property, default_value);
}

}  // namespace lang_id
}  // namespace mobile
}  // namespace nlp_saft
