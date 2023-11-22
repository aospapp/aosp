
#ifndef LIBTEXTCLASSIFIER_UTILS_INTENTS_INTENT_GENERATOR_H_
#define LIBTEXTCLASSIFIER_UTILS_INTENTS_INTENT_GENERATOR_H_

#include <jni.h>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include "actions/types.h"
#include "annotator/types.h"
#include "utils/i18n/locale.h"
#include "utils/intents/intent-config_generated.h"
#include "utils/java/jni-cache.h"
#include "utils/java/scoped_local_ref.h"
#include "utils/optional.h"
#include "utils/resources.h"
#include "utils/resources_generated.h"
#include "utils/strings/stringpiece.h"

namespace libtextclassifier3 {

// A template with parameters for an Android remote action.
struct RemoteActionTemplate {
  // Title shown for the action (see: RemoteAction.getTitle).
  Optional<std::string> title_without_entity;

  // Title with entity for the action. It is not guaranteed that the client
  // will use this, so title should be always given and general enough.
  Optional<std::string> title_with_entity;

  // Description shown for the action (see: RemoteAction.getContentDescription).
  Optional<std::string> description;

  // Description shown for the action (see: RemoteAction.getContentDescription)
  // when app name is available. Caller is expected to replace the placeholder
  // by the name of the app that is going to handle the action.
  Optional<std::string> description_with_app_name;

  // The action to set on the Intent (see: Intent.setAction).
  Optional<std::string> action;

  // The data to set on the Intent (see: Intent.setData).
  Optional<std::string> data;

  // The type to set on the Intent (see: Intent.setType).
  Optional<std::string> type;

  // Flags for launching the Intent (see: Intent.setFlags).
  Optional<int> flags;

  // Categories to set on the Intent (see: Intent.addCategory).
  std::vector<std::string> category;

  // Explicit application package to set on the Intent (see: Intent.setPackage).
  Optional<std::string> package_name;

  // The list of all the extras to add to the Intent.
  std::map<std::string, Variant> extra;

  // Private request code ot use for the Intent.
  Optional<int> request_code;
};

// Helper class to generate Android intents for text classifier results.
class IntentGenerator {
 public:
  static std::unique_ptr<IntentGenerator> Create(
      const IntentFactoryModel* options, const ResourcePool* resources,
      const std::shared_ptr<JniCache>& jni_cache);

  // Generates intents for a classification result.
  // Returns true, if the intent generator snippets could be successfully run,
  // returns false otherwise.
  bool GenerateIntents(const jstring device_locales,
                       const ClassificationResult& classification,
                       const int64 reference_time_ms_utc,
                       const std::string& text,
                       const CodepointSpan selection_indices,
                       const jobject context,
                       const reflection::Schema* annotations_entity_data_schema,
                       std::vector<RemoteActionTemplate>* remote_actions) const;

  // Generates intents for an action suggestion.
  // Returns true, if the intent generator snippets could be successfully run,
  // returns false otherwise.
  bool GenerateIntents(const jstring device_locales,
                       const ActionSuggestion& action,
                       const Conversation& conversation, const jobject context,
                       const reflection::Schema* annotations_entity_data_schema,
                       const reflection::Schema* actions_entity_data_schema,
                       std::vector<RemoteActionTemplate>* remote_actions) const;

 private:
  IntentGenerator(const IntentFactoryModel* options,
                  const ResourcePool* resources,
                  const std::shared_ptr<JniCache>& jni_cache)
      : options_(options),
        resources_(Resources(resources)),
        jni_cache_(jni_cache) {}

  std::vector<Locale> ParseDeviceLocales(const jstring device_locales) const;

  const IntentFactoryModel* options_;
  const Resources resources_;
  std::shared_ptr<JniCache> jni_cache_;
  std::map<std::string, std::string> generators_;
};

}  // namespace libtextclassifier3

#endif  // LIBTEXTCLASSIFIER_UTILS_INTENTS_INTENT_GENERATOR_H_
