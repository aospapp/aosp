// Copyright (C) 2019 Google LLC
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

#include <jni.h>

#include <string>
#include <utility>

#include "icing/icing-search-engine.h"
#include "icing/jni/jni-cache.h"
#include "icing/jni/scoped-primitive-array-critical.h"
#include "icing/jni/scoped-utf-chars.h"
#include "icing/proto/document.pb.h"
#include "icing/proto/initialize.pb.h"
#include "icing/proto/optimize.pb.h"
#include "icing/proto/persist.pb.h"
#include "icing/proto/schema.pb.h"
#include "icing/proto/scoring.pb.h"
#include "icing/proto/search.pb.h"
#include "icing/proto/storage.pb.h"
#include "icing/proto/usage.pb.h"
#include "icing/util/logging.h"
#include "icing/util/status-macros.h"
#include <google/protobuf/message_lite.h>

namespace {

// JNI string constants
// Matches field name of IcingSearchEngine#nativePointer.
const char kNativePointerField[] = "nativePointer";

bool ParseProtoFromJniByteArray(JNIEnv* env, jbyteArray bytes,
                                google::protobuf::MessageLite* protobuf) {
  icing::lib::ScopedPrimitiveArrayCritical<uint8_t> scoped_array(env, bytes);
  return protobuf->ParseFromArray(scoped_array.data(), scoped_array.size());
}

jbyteArray SerializeProtoToJniByteArray(JNIEnv* env,
                                        const google::protobuf::MessageLite& protobuf) {
  int size = protobuf.ByteSizeLong();
  jbyteArray ret = env->NewByteArray(size);
  if (ret == nullptr) {
    ICING_LOG(icing::lib::ERROR)
        << "Failed to allocated bytes for jni protobuf";
    return nullptr;
  }

  icing::lib::ScopedPrimitiveArrayCritical<uint8_t> scoped_array(env, ret);
  protobuf.SerializeWithCachedSizesToArray(scoped_array.data());
  return ret;
}

icing::lib::IcingSearchEngine* GetIcingSearchEnginePointer(JNIEnv* env,
                                                           jobject object) {
  jclass cls = env->GetObjectClass(object);
  jfieldID field_id = env->GetFieldID(cls, kNativePointerField, "J");
  jlong native_pointer = env->GetLongField(object, field_id);
  return reinterpret_cast<icing::lib::IcingSearchEngine*>(native_pointer);
}

}  // namespace

extern "C" {

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  JNIEnv* env;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    ICING_LOG(icing::lib::ERROR) << "ERROR: GetEnv failed";
    return JNI_ERR;
  }

  return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeCreate(
    JNIEnv* env, jclass clazz, jbyteArray icing_search_engine_options_bytes) {
  icing::lib::IcingSearchEngineOptions options;
  if (!ParseProtoFromJniByteArray(env, icing_search_engine_options_bytes,
                                  &options)) {
    ICING_LOG(icing::lib::ERROR)
        << "Failed to parse IcingSearchEngineOptions in nativeCreate";
    return 0;
  }

  std::unique_ptr<const icing::lib::JniCache> jni_cache;
#ifdef ICING_REVERSE_JNI_SEGMENTATION
  ICING_ASSIGN_OR_RETURN(jni_cache, icing::lib::JniCache::Create(env), 0);
#endif  // ICING_REVERSE_JNI_SEGMENTATION
  icing::lib::IcingSearchEngine* icing =
      new icing::lib::IcingSearchEngine(options, std::move(jni_cache));
  return reinterpret_cast<jlong>(icing);
}

JNIEXPORT void JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeDestroy(
    JNIEnv* env, jclass clazz, jobject object) {
  icing::lib::IcingSearchEngine* icing =
      GetIcingSearchEnginePointer(env, object);
  delete icing;
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeInitialize(
    JNIEnv* env, jclass clazz, jobject object) {
  icing::lib::IcingSearchEngine* icing =
      GetIcingSearchEnginePointer(env, object);

  icing::lib::InitializeResultProto initialize_result_proto =
      icing->Initialize();

  return SerializeProtoToJniByteArray(env, initialize_result_proto);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeSetSchema(
    JNIEnv* env, jclass clazz, jobject object, jbyteArray schema_bytes,
    jboolean ignore_errors_and_delete_documents) {
  icing::lib::IcingSearchEngine* icing =
      GetIcingSearchEnginePointer(env, object);

  icing::lib::SchemaProto schema_proto;
  if (!ParseProtoFromJniByteArray(env, schema_bytes, &schema_proto)) {
    ICING_LOG(icing::lib::ERROR)
        << "Failed to parse SchemaProto in nativeSetSchema";
    return nullptr;
  }

  icing::lib::SetSchemaResultProto set_schema_result_proto = icing->SetSchema(
      std::move(schema_proto), ignore_errors_and_delete_documents);

  return SerializeProtoToJniByteArray(env, set_schema_result_proto);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeGetSchema(
    JNIEnv* env, jclass clazz, jobject object) {
  icing::lib::IcingSearchEngine* icing =
      GetIcingSearchEnginePointer(env, object);

  icing::lib::GetSchemaResultProto get_schema_result_proto = icing->GetSchema();

  return SerializeProtoToJniByteArray(env, get_schema_result_proto);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeGetSchemaType(
    JNIEnv* env, jclass clazz, jobject object, jstring schema_type) {
  icing::lib::IcingSearchEngine* icing =
      GetIcingSearchEnginePointer(env, object);

  icing::lib::ScopedUtfChars scoped_schema_type_chars(env, schema_type);
  icing::lib::GetSchemaTypeResultProto get_schema_type_result_proto =
      icing->GetSchemaType(scoped_schema_type_chars.c_str());

  return SerializeProtoToJniByteArray(env, get_schema_type_result_proto);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativePut(
    JNIEnv* env, jclass clazz, jobject object, jbyteArray document_bytes) {
  icing::lib::IcingSearchEngine* icing =
      GetIcingSearchEnginePointer(env, object);

  icing::lib::DocumentProto document_proto;
  if (!ParseProtoFromJniByteArray(env, document_bytes, &document_proto)) {
    ICING_LOG(icing::lib::ERROR)
        << "Failed to parse DocumentProto in nativePut";
    return nullptr;
  }

  icing::lib::PutResultProto put_result_proto =
      icing->Put(std::move(document_proto));

  return SerializeProtoToJniByteArray(env, put_result_proto);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeGet(
    JNIEnv* env, jclass clazz, jobject object, jstring name_space, jstring uri,
    jbyteArray result_spec_bytes) {
  icing::lib::IcingSearchEngine* icing =
      GetIcingSearchEnginePointer(env, object);

  icing::lib::GetResultSpecProto get_result_spec;
  if (!ParseProtoFromJniByteArray(env, result_spec_bytes, &get_result_spec)) {
    ICING_LOG(icing::lib::ERROR)
        << "Failed to parse GetResultSpecProto in nativeGet";
    return nullptr;
  }
  icing::lib::ScopedUtfChars scoped_name_space_chars(env, name_space);
  icing::lib::ScopedUtfChars scoped_uri_chars(env, uri);
  icing::lib::GetResultProto get_result_proto =
      icing->Get(scoped_name_space_chars.c_str(), scoped_uri_chars.c_str(),
                 get_result_spec);

  return SerializeProtoToJniByteArray(env, get_result_proto);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeReportUsage(
    JNIEnv* env, jclass clazz, jobject object, jbyteArray usage_report_bytes) {
  icing::lib::IcingSearchEngine* icing =
      GetIcingSearchEnginePointer(env, object);

  icing::lib::UsageReport usage_report;
  if (!ParseProtoFromJniByteArray(env, usage_report_bytes, &usage_report)) {
    ICING_LOG(icing::lib::ERROR)
        << "Failed to parse UsageReport in nativeReportUsage";
    return nullptr;
  }

  icing::lib::ReportUsageResultProto report_usage_result_proto =
      icing->ReportUsage(usage_report);

  return SerializeProtoToJniByteArray(env, report_usage_result_proto);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeGetAllNamespaces(
    JNIEnv* env, jclass clazz, jobject object) {
  icing::lib::IcingSearchEngine* icing =
      GetIcingSearchEnginePointer(env, object);

  icing::lib::GetAllNamespacesResultProto get_all_namespaces_result_proto =
      icing->GetAllNamespaces();

  return SerializeProtoToJniByteArray(env, get_all_namespaces_result_proto);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeGetNextPage(
    JNIEnv* env, jclass clazz, jobject object, jlong next_page_token,
    jlong java_to_native_start_timestamp_ms) {
  icing::lib::IcingSearchEngine* icing =
      GetIcingSearchEnginePointer(env, object);

  const std::unique_ptr<const icing::lib::Clock> clock =
      std::make_unique<icing::lib::Clock>();
  int32_t java_to_native_jni_latency_ms =
      clock->GetSystemTimeMilliseconds() - java_to_native_start_timestamp_ms;

  icing::lib::SearchResultProto next_page_result_proto =
      icing->GetNextPage(next_page_token);

  icing::lib::QueryStatsProto* query_stats =
      next_page_result_proto.mutable_query_stats();
  query_stats->set_java_to_native_jni_latency_ms(java_to_native_jni_latency_ms);
  query_stats->set_native_to_java_start_timestamp_ms(
      clock->GetSystemTimeMilliseconds());

  return SerializeProtoToJniByteArray(env, next_page_result_proto);
}

JNIEXPORT void JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeInvalidateNextPageToken(
    JNIEnv* env, jclass clazz, jobject object, jlong next_page_token) {
  icing::lib::IcingSearchEngine* icing =
      GetIcingSearchEnginePointer(env, object);

  icing->InvalidateNextPageToken(next_page_token);

  return;
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeSearch(
    JNIEnv* env, jclass clazz, jobject object, jbyteArray search_spec_bytes,
    jbyteArray scoring_spec_bytes, jbyteArray result_spec_bytes,
    jlong java_to_native_start_timestamp_ms) {
  icing::lib::IcingSearchEngine* icing =
      GetIcingSearchEnginePointer(env, object);

  icing::lib::SearchSpecProto search_spec_proto;
  if (!ParseProtoFromJniByteArray(env, search_spec_bytes, &search_spec_proto)) {
    ICING_LOG(icing::lib::ERROR)
        << "Failed to parse SearchSpecProto in nativeSearch";
    return nullptr;
  }

  icing::lib::ScoringSpecProto scoring_spec_proto;
  if (!ParseProtoFromJniByteArray(env, scoring_spec_bytes,
                                  &scoring_spec_proto)) {
    ICING_LOG(icing::lib::ERROR)
        << "Failed to parse ScoringSpecProto in nativeSearch";
    return nullptr;
  }

  icing::lib::ResultSpecProto result_spec_proto;
  if (!ParseProtoFromJniByteArray(env, result_spec_bytes, &result_spec_proto)) {
    ICING_LOG(icing::lib::ERROR)
        << "Failed to parse ResultSpecProto in nativeSearch";
    return nullptr;
  }

  const std::unique_ptr<const icing::lib::Clock> clock =
      std::make_unique<icing::lib::Clock>();
  int32_t java_to_native_jni_latency_ms =
      clock->GetSystemTimeMilliseconds() - java_to_native_start_timestamp_ms;

  icing::lib::SearchResultProto search_result_proto =
      icing->Search(search_spec_proto, scoring_spec_proto, result_spec_proto);

  icing::lib::QueryStatsProto* query_stats =
      search_result_proto.mutable_query_stats();
  query_stats->set_java_to_native_jni_latency_ms(java_to_native_jni_latency_ms);
  query_stats->set_native_to_java_start_timestamp_ms(
      clock->GetSystemTimeMilliseconds());

  return SerializeProtoToJniByteArray(env, search_result_proto);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeDelete(
    JNIEnv* env, jclass clazz, jobject object, jstring name_space,
    jstring uri) {
  icing::lib::IcingSearchEngine* icing =
      GetIcingSearchEnginePointer(env, object);

  icing::lib::ScopedUtfChars scoped_name_space_chars(env, name_space);
  icing::lib::ScopedUtfChars scoped_uri_chars(env, uri);
  icing::lib::DeleteResultProto delete_result_proto =
      icing->Delete(scoped_name_space_chars.c_str(), scoped_uri_chars.c_str());

  return SerializeProtoToJniByteArray(env, delete_result_proto);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeDeleteByNamespace(
    JNIEnv* env, jclass clazz, jobject object, jstring name_space) {
  icing::lib::IcingSearchEngine* icing =
      GetIcingSearchEnginePointer(env, object);

  icing::lib::ScopedUtfChars scoped_name_space_chars(env, name_space);
  icing::lib::DeleteByNamespaceResultProto delete_by_namespace_result_proto =
      icing->DeleteByNamespace(scoped_name_space_chars.c_str());

  return SerializeProtoToJniByteArray(env, delete_by_namespace_result_proto);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeDeleteBySchemaType(
    JNIEnv* env, jclass clazz, jobject object, jstring schema_type) {
  icing::lib::IcingSearchEngine* icing =
      GetIcingSearchEnginePointer(env, object);

  icing::lib::ScopedUtfChars scoped_schema_type_chars(env, schema_type);
  icing::lib::DeleteBySchemaTypeResultProto delete_by_schema_type_result_proto =
      icing->DeleteBySchemaType(scoped_schema_type_chars.c_str());

  return SerializeProtoToJniByteArray(env, delete_by_schema_type_result_proto);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeDeleteByQuery(
    JNIEnv* env, jclass clazz, jobject object, jbyteArray search_spec_bytes,
    jboolean return_deleted_document_info) {
  icing::lib::IcingSearchEngine* icing =
      GetIcingSearchEnginePointer(env, object);

  icing::lib::SearchSpecProto search_spec_proto;
  if (!ParseProtoFromJniByteArray(env, search_spec_bytes, &search_spec_proto)) {
    ICING_LOG(icing::lib::ERROR)
        << "Failed to parse SearchSpecProto in nativeSearch";
    return nullptr;
  }
  icing::lib::DeleteByQueryResultProto delete_result_proto =
      icing->DeleteByQuery(search_spec_proto, return_deleted_document_info);

  return SerializeProtoToJniByteArray(env, delete_result_proto);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativePersistToDisk(
    JNIEnv* env, jclass clazz, jobject object, jint persist_type_code) {
  icing::lib::IcingSearchEngine* icing =
      GetIcingSearchEnginePointer(env, object);

  if (!icing::lib::PersistType::Code_IsValid(persist_type_code)) {
    ICING_LOG(icing::lib::ERROR)
        << persist_type_code << " is an invalid value for PersistType::Code";
    return nullptr;
  }
  icing::lib::PersistType::Code persist_type_code_enum =
      static_cast<icing::lib::PersistType::Code>(persist_type_code);
  icing::lib::PersistToDiskResultProto persist_to_disk_result_proto =
      icing->PersistToDisk(persist_type_code_enum);

  return SerializeProtoToJniByteArray(env, persist_to_disk_result_proto);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeOptimize(
    JNIEnv* env, jclass clazz, jobject object) {
  icing::lib::IcingSearchEngine* icing =
      GetIcingSearchEnginePointer(env, object);

  icing::lib::OptimizeResultProto optimize_result_proto = icing->Optimize();

  return SerializeProtoToJniByteArray(env, optimize_result_proto);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeGetOptimizeInfo(
    JNIEnv* env, jclass clazz, jobject object) {
  icing::lib::IcingSearchEngine* icing =
      GetIcingSearchEnginePointer(env, object);

  icing::lib::GetOptimizeInfoResultProto get_optimize_info_result_proto =
      icing->GetOptimizeInfo();

  return SerializeProtoToJniByteArray(env, get_optimize_info_result_proto);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeGetStorageInfo(
    JNIEnv* env, jclass clazz, jobject object) {
  icing::lib::IcingSearchEngine* icing =
      GetIcingSearchEnginePointer(env, object);

  icing::lib::StorageInfoResultProto storage_info_result_proto =
      icing->GetStorageInfo();

  return SerializeProtoToJniByteArray(env, storage_info_result_proto);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeReset(
    JNIEnv* env, jclass clazz, jobject object) {
  icing::lib::IcingSearchEngine* icing =
      GetIcingSearchEnginePointer(env, object);

  icing::lib::ResetResultProto reset_result_proto = icing->Reset();

  return SerializeProtoToJniByteArray(env, reset_result_proto);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeSearchSuggestions(
    JNIEnv* env, jclass clazz, jobject object,
    jbyteArray suggestion_spec_bytes) {
  icing::lib::IcingSearchEngine* icing =
      GetIcingSearchEnginePointer(env, object);

  icing::lib::SuggestionSpecProto suggestion_spec_proto;
  if (!ParseProtoFromJniByteArray(env, suggestion_spec_bytes,
                                  &suggestion_spec_proto)) {
    ICING_LOG(icing::lib::ERROR)
        << "Failed to parse SuggestionSpecProto in nativeSearch";
    return nullptr;
  }
  icing::lib::SuggestionResponse suggestionResponse =
      icing->SearchSuggestions(suggestion_spec_proto);

  return SerializeProtoToJniByteArray(env, suggestionResponse);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeGetDebugInfo(
    JNIEnv* env, jclass clazz, jobject object, jint verbosity) {
  icing::lib::IcingSearchEngine* icing =
      GetIcingSearchEnginePointer(env, object);

  if (!icing::lib::DebugInfoVerbosity::Code_IsValid(verbosity)) {
    ICING_LOG(icing::lib::ERROR)
        << "Invalid value for Debug Info verbosity: " << verbosity;
    return nullptr;
  }

  icing::lib::DebugInfoResultProto debug_info_result_proto =
      icing->GetDebugInfo(
          static_cast<icing::lib::DebugInfoVerbosity::Code>(verbosity));

  return SerializeProtoToJniByteArray(env, debug_info_result_proto);
}

JNIEXPORT jboolean JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeShouldLog(
    JNIEnv* env, jclass clazz, jshort severity, jshort verbosity) {
  if (!icing::lib::LogSeverity::Code_IsValid(severity)) {
    ICING_LOG(icing::lib::ERROR)
        << "Invalid value for logging severity: " << severity;
    return false;
  }
  return icing::lib::ShouldLog(
      static_cast<icing::lib::LogSeverity::Code>(severity), verbosity);
}

JNIEXPORT jboolean JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeSetLoggingLevel(
    JNIEnv* env, jclass clazz, jshort severity, jshort verbosity) {
  if (!icing::lib::LogSeverity::Code_IsValid(severity)) {
    ICING_LOG(icing::lib::ERROR)
        << "Invalid value for logging severity: " << severity;
    return false;
  }
  return icing::lib::SetLoggingLevel(
      static_cast<icing::lib::LogSeverity::Code>(severity), verbosity);
}

JNIEXPORT jstring JNICALL
Java_com_google_android_icing_IcingSearchEngineImpl_nativeGetLoggingTag(
    JNIEnv* env, jclass clazz) {
  return env->NewStringUTF(icing::lib::kIcingLoggingTag);
}

// TODO(b/240333360) Remove the methods below for IcingSearchEngine once we have
// a sync from Jetpack to g3 to contain the refactored IcingSearchEngine(with
// IcingSearchEngineImpl).
JNIEXPORT jlong JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeCreate(
    JNIEnv* env, jclass clazz, jbyteArray icing_search_engine_options_bytes) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativeCreate(
      env, clazz, icing_search_engine_options_bytes);
}

JNIEXPORT void JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeDestroy(JNIEnv* env,
                                                              jclass clazz,
                                                              jobject object) {
  Java_com_google_android_icing_IcingSearchEngineImpl_nativeDestroy(env, clazz,
                                                                    object);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeInitialize(
    JNIEnv* env, jclass clazz, jobject object) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativeInitialize(
      env, clazz, object);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeSetSchema(
    JNIEnv* env, jclass clazz, jobject object, jbyteArray schema_bytes,
    jboolean ignore_errors_and_delete_documents) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativeSetSchema(
      env, clazz, object, schema_bytes, ignore_errors_and_delete_documents);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeGetSchema(
    JNIEnv* env, jclass clazz, jobject object) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativeGetSchema(
      env, clazz, object);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeGetSchemaType(
    JNIEnv* env, jclass clazz, jobject object, jstring schema_type) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativeGetSchemaType(
      env, clazz, object, schema_type);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativePut(
    JNIEnv* env, jclass clazz, jobject object, jbyteArray document_bytes) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativePut(
      env, clazz, object, document_bytes);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeGet(
    JNIEnv* env, jclass clazz, jobject object, jstring name_space, jstring uri,
    jbyteArray result_spec_bytes) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativeGet(
      env, clazz, object, name_space, uri, result_spec_bytes);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeReportUsage(
    JNIEnv* env, jclass clazz, jobject object, jbyteArray usage_report_bytes) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativeReportUsage(
      env, clazz, object, usage_report_bytes);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeGetAllNamespaces(
    JNIEnv* env, jclass clazz, jobject object) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativeGetAllNamespaces(
      env, clazz, object);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeGetNextPage(
    JNIEnv* env, jclass clazz, jobject object, jlong next_page_token,
    jlong java_to_native_start_timestamp_ms) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativeGetNextPage(
      env, clazz, object, next_page_token, java_to_native_start_timestamp_ms);
}

JNIEXPORT void JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeInvalidateNextPageToken(
    JNIEnv* env, jclass clazz, jobject object, jlong next_page_token) {
  Java_com_google_android_icing_IcingSearchEngineImpl_nativeInvalidateNextPageToken(
      env, clazz, object, next_page_token);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeSearch(
    JNIEnv* env, jclass clazz, jobject object, jbyteArray search_spec_bytes,
    jbyteArray scoring_spec_bytes, jbyteArray result_spec_bytes,
    jlong java_to_native_start_timestamp_ms) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativeSearch(
      env, clazz, object, search_spec_bytes, scoring_spec_bytes,
      result_spec_bytes, java_to_native_start_timestamp_ms);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeDelete(JNIEnv* env,
                                                             jclass clazz,
                                                             jobject object,
                                                             jstring name_space,
                                                             jstring uri) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativeDelete(
      env, clazz, object, name_space, uri);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeDeleteByNamespace(
    JNIEnv* env, jclass clazz, jobject object, jstring name_space) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativeDeleteByNamespace(
      env, clazz, object, name_space);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeDeleteBySchemaType(
    JNIEnv* env, jclass clazz, jobject object, jstring schema_type) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativeDeleteBySchemaType(
      env, clazz, object, schema_type);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeDeleteByQuery(
    JNIEnv* env, jclass clazz, jobject object, jbyteArray search_spec_bytes,
    jboolean return_deleted_document_info) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativeDeleteByQuery(
      env, clazz, object, search_spec_bytes, return_deleted_document_info);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativePersistToDisk(
    JNIEnv* env, jclass clazz, jobject object, jint persist_type_code) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativePersistToDisk(
      env, clazz, object, persist_type_code);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeOptimize(JNIEnv* env,
                                                               jclass clazz,
                                                               jobject object) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativeOptimize(
      env, clazz, object);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeGetOptimizeInfo(
    JNIEnv* env, jclass clazz, jobject object) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativeGetOptimizeInfo(
      env, clazz, object);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeGetStorageInfo(
    JNIEnv* env, jclass clazz, jobject object) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativeGetStorageInfo(
      env, clazz, object);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeReset(JNIEnv* env,
                                                            jclass clazz,
                                                            jobject object) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativeReset(
      env, clazz, object);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeSearchSuggestions(
    JNIEnv* env, jclass clazz, jobject object,
    jbyteArray suggestion_spec_bytes) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativeSearchSuggestions(
      env, clazz, object, suggestion_spec_bytes);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeGetDebugInfo(
    JNIEnv* env, jclass clazz, jobject object, jint verbosity) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativeGetDebugInfo(
      env, clazz, object, verbosity);
}

JNIEXPORT jboolean JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeShouldLog(
    JNIEnv* env, jclass clazz, jshort severity, jshort verbosity) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativeShouldLog(
      env, clazz, severity, verbosity);
}

JNIEXPORT jboolean JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeSetLoggingLevel(
    JNIEnv* env, jclass clazz, jshort severity, jshort verbosity) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativeSetLoggingLevel(
      env, clazz, severity, verbosity);
}

JNIEXPORT jstring JNICALL
Java_com_google_android_icing_IcingSearchEngine_nativeGetLoggingTag(
    JNIEnv* env, jclass clazz) {
  return Java_com_google_android_icing_IcingSearchEngineImpl_nativeGetLoggingTag(
      env, clazz);
}

}  // extern "C"
