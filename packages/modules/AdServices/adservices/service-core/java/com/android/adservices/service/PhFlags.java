/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.adservices.service;

import static java.lang.Float.parseFloat;

import android.annotation.NonNull;
import android.os.SystemProperties;
import android.provider.DeviceConfig;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.android.adservices.LogUtil;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.build.SdkLevel;

import com.google.common.collect.ImmutableList;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Flags Implementation that delegates to DeviceConfig. */
// TODO(b/228037065): Add validation logics for Feature flags read from PH.
public final class PhFlags implements Flags {
    /*
     * Keys for ALL the flags stored in DeviceConfig.
     */
    // Common Keys
    static final String KEY_MAINTENANCE_JOB_PERIOD_MS = "maintenance_job_period_ms";
    static final String KEY_MAINTENANCE_JOB_FLEX_MS = "maintenance_job_flex_ms";

    static final String KEY_ERROR_CODE_LOGGING_DENY_LIST = "error_code_logging_deny_list";

    // Topics keys
    static final String KEY_TOPICS_EPOCH_JOB_PERIOD_MS = "topics_epoch_job_period_ms";
    static final String KEY_TOPICS_EPOCH_JOB_FLEX_MS = "topics_epoch_job_flex_ms";
    static final String KEY_TOPICS_PERCENTAGE_FOR_RANDOM_TOPIC =
            "topics_percentage_for_random_topics";
    static final String KEY_TOPICS_NUMBER_OF_TOP_TOPICS = "topics_number_of_top_topics";
    static final String KEY_TOPICS_NUMBER_OF_RANDOM_TOPICS = "topics_number_of_random_topics";
    static final String KEY_TOPICS_NUMBER_OF_LOOK_BACK_EPOCHS = "topics_number_of_lookback_epochs";
    static final String KEY_NUMBER_OF_EPOCHS_TO_KEEP_IN_HISTORY =
            "topics_number_of_epochs_to_keep_in_history";
    static final String KEY_GLOBAL_BLOCKED_TOPIC_IDS = "topics_global_blocked_topic_ids";

    // Topics classifier keys
    static final String KEY_CLASSIFIER_TYPE = "classifier_type";
    static final String KEY_CLASSIFIER_NUMBER_OF_TOP_LABELS = "classifier_number_of_top_labels";
    static final String KEY_CLASSIFIER_THRESHOLD = "classifier_threshold";
    static final String KEY_CLASSIFIER_DESCRIPTION_MAX_WORDS = "classifier_description_max_words";
    static final String KEY_CLASSIFIER_DESCRIPTION_MAX_LENGTH = "classifier_description_max_length";
    static final String KEY_CLASSIFIER_FORCE_USE_BUNDLED_FILES =
            "classifier_force_use_bundled_files";

    // Measurement keys
    static final String KEY_MEASUREMENT_EVENT_MAIN_REPORTING_JOB_PERIOD_MS =
            "measurement_event_main_reporting_job_period_ms";
    static final String KEY_MEASUREMENT_EVENT_FALLBACK_REPORTING_JOB_PERIOD_MS =
            "measurement_event_fallback_reporting_job_period_ms";
    static final String KEY_MEASUREMENT_AGGREGATE_ENCRYPTION_KEY_COORDINATOR_URL =
            "measurement_aggregate_encryption_key_coordinator_url";
    static final String KEY_MEASUREMENT_AGGREGATE_MAIN_REPORTING_JOB_PERIOD_MS =
            "measurement_aggregate_main_reporting_job_period_ms";
    static final String KEY_MEASUREMENT_AGGREGATE_FALLBACK_REPORTING_JOB_PERIOD_MS =
            "measurement_aggregate_fallback_reporting_job_period_ms";
    static final String KEY_MEASUREMENT_NETWORK_CONNECT_TIMEOUT_MS =
            "measurement_network_connect_timeout_ms";
    static final String KEY_MEASUREMENT_NETWORK_READ_TIMEOUT_MS =
            "measurement_network_read_timeout_ms";
    static final String KEY_MEASUREMENT_DB_SIZE_LIMIT = "measurement_db_size_limit";
    static final String KEY_MEASUREMENT_MANIFEST_FILE_URL = "mdd_measurement_manifest_file_url";
    static final String KEY_MEASUREMENT_REGISTRATION_INPUT_EVENT_VALID_WINDOW_MS =
            "measurement_registration_input_event_valid_window_ms";
    static final String KEY_MEASUREMENT_IS_CLICK_VERIFICATION_ENABLED =
            "measurement_is_click_verification_enabled";
    static final String KEY_MEASUREMENT_IS_CLICK_VERIFIED_BY_INPUT_EVENT =
            "measurement_is_click_verified_by_input_event";
    static final String KEY_MEASUREMENT_ENFORCE_FOREGROUND_STATUS_REGISTER_SOURCE =
            "measurement_enforce_foreground_status_register_source";
    static final String KEY_MEASUREMENT_ENFORCE_FOREGROUND_STATUS_REGISTER_TRIGGER =
            "measurement_enforce_foreground_status_register_trigger";
    static final String KEY_MEASUREMENT_ENFORCE_FOREGROUND_STATUS_REGISTER_WEB_SOURCE =
            "measurement_enforce_foreground_status_register_web_source";
    static final String KEY_MEASUREMENT_ENFORCE_FOREGROUND_STATUS_REGISTER_WEB_TRIGGER =
            "measurement_enforce_foreground_status_register_web_trigger";
    static final String KEY_MEASUREMENT_ENFORCE_ENROLLMENT_ORIGIN_MATCH =
            "measurement_enforce_enrollment_origin_match";
    static final String KEY_MEASUREMENT_ENFORCE_FOREGROUND_STATUS_DELETE_REGISTRATIONS =
            "measurement_enforce_foreground_status_delete_registrations";
    static final String KEY_MEASUREMENT_ENFORCE_FOREGROUND_STATUS_GET_STATUS =
            "measurement_enforce_foreground_status_get_status";
    static final String KEY_MEASUREMENT_ENABLE_XNA = "measurement_enable_xna";
    static final String KEY_MEASUREMENT_ENABLE_DEBUG_REPORT = "measurement_enable_debug_report";
    static final String KEY_MEASUREMENT_ENABLE_SOURCE_DEBUG_REPORT =
            "measurement_enable_source_debug_report";
    static final String KEY_MEASUREMENT_ENABLE_TRIGGER_DEBUG_REPORT =
            "measurement_enable_trigger_debug_report";
    static final String KEY_MEASUREMENT_DATA_EXPIRY_WINDOW_MS = "measurement_data_expiry_window_ms";

    static final String KEY_MEASUREMENT_MAX_REGISTRATION_REDIRECTS =
            "measurement_max_registration_redirects";

    static final String KEY_MEASUREMENT_MAX_REGISTRATIONS_PER_JOB_INVOCATION =
            "measurement_max_registration_per_job_invocation";

    static final String KEY_MEASUREMENT_MAX_RETRIES_PER_REGISTRATION_REQUEST =
            "measurement_max_retries_per_registration_request";

    static final String KEY_MEASUREMENT_REGISTRATION_JOB_TRIGGER_DELAY_MS =
            "measurement_registration_job_trigger_delay_ms";

    static final String KEY_MEASUREMENT_REGISTRATION_JOB_TRIGGER_MAX_DELAY_MS =
            "measurement_registration_job_trigger_max_delay_ms";

    static final String KEY_MEASUREMENT_ATTRIBUTION_FALLBACK_JOB_KILL_SWITCH =
            "measurement_attribution_fallback_job_kill_switch";

    static final String KEY_MEASUREMENT_ATTRIBUTION_FALLBACK_JOB_PERIOD_MS =
            "measurement_attribution_fallback_job_period_ms";

    static final String KEY_MEASUREMENT_MAX_ATTRIBUTION_PER_RATE_LIMIT_WINDOW =
            "measurement_max_attribution_per_rate_limit_window";

    static final String KEY_MEASUREMENT_MAX_DISTINCT_ENROLLMENTS_IN_ATTRIBUTION =
            "measurement_max_distinct_enrollments_in_attribution";

    static final String KEY_MEASUREMENT_MAX_DISTINCT_DESTINATIONS_IN_ACTIVE_SOURCE =
            "measurement_max_distinct_destinations_in_active_source";

    static final String KEY_MEASUREMENT_ENABLE_COARSE_EVENT_REPORT_DESTINATIONS =
            "measurement_enable_coarse_event_report_destinations";

    static final String KEY_MEASUREMENT_ENABLE_VTC_CONFIGURABLE_MAX_EVENT_REPORTS =
            "measurement_enable_vtc_configurable_max_event_reports_count";

    static final String KEY_MEASUREMENT_VTC_CONFIGURABLE_MAX_EVENT_REPORTS_COUNT =
            "measurement_vtc_configurable_max_event_reports_count";

    static final String ADSERVICES_CONSENT_MIGRATION_LOGGING_ENABLED =
            "adservices_consent_migration_logging_enabled";

    // FLEDGE Custom Audience keys
    static final String KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_COUNT = "fledge_custom_audience_max_count";
    static final String KEY_FLEDGE_CUSTOM_AUDIENCE_PER_APP_MAX_COUNT =
            "fledge_custom_audience_per_app_max_count";
    static final String KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_OWNER_COUNT =
            "fledge_custom_audience_max_owner_count";
    static final String KEY_FLEDGE_CUSTOM_AUDIENCE_DEFAULT_EXPIRE_IN_MS =
            "fledge_custom_audience_default_expire_in_days";
    static final String KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_ACTIVATION_DELAY_IN_MS =
            "fledge_custom_audience_max_activate_in_days";
    static final String KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_EXPIRE_IN_MS =
            "fledge_custom_audience_max_expire_in_days";
    static final String KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_NAME_SIZE_B =
            "key_fledge_custom_audience_max_name_size_b";
    static final String KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_DAILY_UPDATE_URI_SIZE_B =
            "key_fledge_custom_audience_max_daily_update_uri_size_b";
    static final String KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_BIDDING_LOGIC_URI_SIZE_B =
            "key_fledge_custom_audience_max_bidding_logic_uri_size_b";
    static final String KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_USER_BIDDING_SIGNALS_SIZE_B =
            "fledge_custom_audience_max_user_bidding_signals_size_b";
    static final String KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_TRUSTED_BIDDING_DATA_SIZE_B =
            "fledge_custom_audience_max_trusted_bidding_data_size_b";
    static final String KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_ADS_SIZE_B =
            "fledge_custom_audience_max_ads_size_b";
    static final String KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_NUM_ADS =
            "fledge_custom_audience_max_num_ads";
    static final String KEY_FLEDGE_CUSTOM_AUDIENCE_ACTIVE_TIME_WINDOW_MS =
            "fledge_custom_audience_active_time_window_ms";

    // FLEDGE Background Fetch keys
    static final String KEY_FLEDGE_BACKGROUND_FETCH_ENABLED = "fledge_background_fetch_enabled";
    static final String KEY_FLEDGE_BACKGROUND_FETCH_JOB_PERIOD_MS =
            "fledge_background_fetch_job_period_ms";
    static final String KEY_FLEDGE_BACKGROUND_FETCH_JOB_FLEX_MS =
            "fledge_background_fetch_job_flex_ms";
    static final String KEY_FLEDGE_BACKGROUND_FETCH_JOB_MAX_RUNTIME_MS =
            "fledge_background_fetch_job_max_runtime_ms";
    static final String KEY_FLEDGE_BACKGROUND_FETCH_MAX_NUM_UPDATED =
            "fledge_background_fetch_max_num_updated";
    static final String KEY_FLEDGE_BACKGROUND_FETCH_THREAD_POOL_SIZE =
            "fledge_background_fetch_thread_pool_size";
    static final String KEY_FLEDGE_BACKGROUND_FETCH_ELIGIBLE_UPDATE_BASE_INTERVAL_S =
            "fledge_background_fetch_eligible_update_base_interval_s";
    static final String KEY_FLEDGE_BACKGROUND_FETCH_NETWORK_CONNECT_TIMEOUT_MS =
            "fledge_background_fetch_network_connect_timeout_ms";
    static final String KEY_FLEDGE_BACKGROUND_FETCH_NETWORK_READ_TIMEOUT_MS =
            "fledge_background_fetch_network_read_timeout_ms";
    static final String KEY_FLEDGE_BACKGROUND_FETCH_MAX_RESPONSE_SIZE_B =
            "fledge_background_fetch_max_response_size_b";

    // FLEDGE Ad Selection keys
    static final String KEY_FLEDGE_AD_SELECTION_MAX_CONCURRENT_BIDDING_COUNT =
            "fledge_ad_selection_max_concurrent_bidding_count";
    static final String KEY_FLEDGE_AD_SELECTION_BIDDING_TIMEOUT_PER_CA_MS =
            "fledge_ad_selection_bidding_timeout_per_ca_ms";
    static final String KEY_FLEDGE_AD_SELECTION_SCORING_TIMEOUT_MS =
            "fledge_ad_selection_scoring_timeout_ms";
    static final String KEY_FLEDGE_AD_SELECTION_SELECTING_OUTCOME_TIMEOUT_MS =
            "fledge_ad_selection_selecting_outcome_timeout_ms";
    static final String KEY_FLEDGE_AD_SELECTION_OVERALL_TIMEOUT_MS =
            "fledge_ad_selection_overall_timeout_ms";
    static final String KEY_FLEDGE_AD_SELECTION_FROM_OUTCOMES_OVERALL_TIMEOUT_MS =
            "fledge_ad_selection_from_outcomes_overall_timeout_ms";
    static final String KEY_FLEDGE_AD_SELECTION_EXPIRATION_WINDOW_S =
            "fledge_ad_selection_expiration_window_s";
    static final String KEY_FLEDGE_AD_SELECTION_FILTERING_ENABLED =
            "fledge_ad_selection_filtering_enabled";
    static final String KEY_FLEDGE_REPORT_IMPRESSION_OVERALL_TIMEOUT_MS =
            "fledge_report_impression_overall_timeout_ms";
    static final String KEY_FLEDGE_REPORT_IMPRESSION_MAX_REGISTERED_AD_BEACONS_TOTAL_COUNT =
            "fledge_report_impression_max_registered_ad_beacons_total_count";
    static final String KEY_FLEDGE_REPORT_IMPRESSION_MAX_REGISTERED_AD_BEACONS_PER_AD_TECH_COUNT =
            "fledge_report_impression_max_registered_ad_beacons_per_ad_tech_count";
    static final String
            KEY_FLEDGE_REPORT_IMPRESSION_REGISTERED_AD_BEACONS_MAX_INTERACTION_KEY_SIZE_B =
                    "fledge_report_impression_registered_ad_beacons_max_interaction_key_size_b";
    static final String KEY_FLEDGE_AD_SELECTION_BIDDING_TIMEOUT_PER_BUYER_MS =
            "fledge_ad_selection_bidding_timeout_per_buyer_ms";
    static final String KEY_FLEDGE_HTTP_CACHE_ENABLE = "fledge_http_cache_enable";
    static final String KEY_FLEDGE_HTTP_CACHE_ENABLE_JS_CACHING =
            "fledge_http_cache_enable_js_caching";
    static final String KEY_FLEDGE_HTTP_CACHE_DEFAULT_MAX_AGE_SECONDS =
            "fledge_http_cache_default_max_age_seconds";
    static final String KEY_FLEDGE_HTTP_CACHE_MAX_ENTRIES = "fledge_http_cache_max_entries";

    // FLEDGE Ad Counter Histogram keys
    static final String KEY_FLEDGE_AD_COUNTER_HISTOGRAM_ABSOLUTE_MAX_EVENT_COUNT =
            "fledge_ad_counter_histogram_absolute_max_event_count";
    static final String KEY_FLEDGE_AD_COUNTER_HISTOGRAM_LOWER_MAX_EVENT_COUNT =
            "fledge_ad_counter_histogram_lower_max_event_count";

    // FLEDGE Off device ad selection keys
    static final String KEY_FLEDGE_AD_SELECTION_OFF_DEVICE_OVERALL_TIMEOUT_MS =
            "fledge_ad_selection_off_device_overall_timeout_ms";
    static final String KEY_FLEDGE_AD_SELECTION_BIDDING_LOGIC_JS_VERSION =
            "fledge_ad_selection_bidding_logic_js_version";
    // Whether to call trusted servers for off device ad selection.
    static final String KEY_FLEDGE_AD_SELECTION_OFF_DEVICE_ENABLED =
            "fledge_ad_selection_off_device_enabled";
    static final String KEY_FLEDGE_AD_SELECTION_PREBUILT_URI_ENABLED =
            "fledge_ad_selection_ad_selection_prebuilt_uri_enabled";
    // Whether to compress the request object when calling trusted servers for off device ad
    // selection.
    static final String KEY_FLEDGE_AD_SELECTION_OFF_DEVICE_REQUEST_COMPRESSION_ENABLED =
            "fledge_ad_selection_off_device_request_compression_enabled";

    // Fledge invoking app status keys
    static final String KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_RUN_AD_SELECTION =
            "fledge_ad_selection_enforce_foreground_status_run_ad_selection";
    static final String KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_REPORT_IMPRESSION =
            "fledge_ad_selection_enforce_foreground_status_report_impression";
    static final String KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_REPORT_INTERACTION =
            "fledge_ad_selection_enforce_foreground_status_report_interaction";
    static final String KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_OVERRIDE =
            "fledge_ad_selection_enforce_foreground_status_ad_selection_override";
    static final String KEY_FOREGROUND_STATUS_LEVEL = "foreground_validation_status_level";
    static final String KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_CUSTOM_AUDIENCE =
            "fledge_ad_selection_enforce_foreground_status_custom_audience";

    // Topics invoking app status key.
    static final String KEY_ENFORCE_FOREGROUND_STATUS_TOPICS = "topics_enforce_foreground_status";

    // AdId invoking app status key.
    static final String KEY_ENFORCE_FOREGROUND_STATUS_ADID = "adid_enforce_foreground_status";

    // Fledge JS isolate setting keys
    static final String KEY_ENFORCE_ISOLATE_MAX_HEAP_SIZE =
            "fledge_js_isolate_enforce_max_heap_size";
    static final String KEY_ISOLATE_MAX_HEAP_SIZE_BYTES = "fledge_js_isolate_max_heap_size_bytes";

    // AppSetId invoking app status key.
    static final String KEY_ENFORCE_FOREGROUND_STATUS_APPSETID =
            "appsetid_enforce_foreground_status";

    // MDD keys.
    static final String KEY_DOWNLOADER_CONNECTION_TIMEOUT_MS = "downloader_connection_timeout_ms";
    static final String KEY_DOWNLOADER_READ_TIMEOUT_MS = "downloader_read_timeout_ms";
    static final String KEY_DOWNLOADER_MAX_DOWNLOAD_THREADS = "downloader_max_download_threads";
    static final String KEY_MDD_TOPICS_CLASSIFIER_MANIFEST_FILE_URL =
            "mdd_topics_classifier_manifest_file_url";

    // Killswitch keys
    static final String KEY_GLOBAL_KILL_SWITCH = "global_kill_switch";
    static final String KEY_MEASUREMENT_KILL_SWITCH = "measurement_kill_switch";
    static final String KEY_MEASUREMENT_API_DELETE_REGISTRATIONS_KILL_SWITCH =
            "measurement_api_delete_registrations_kill_switch";
    static final String KEY_MEASUREMENT_API_STATUS_KILL_SWITCH =
            "measurement_api_status_kill_switch";
    static final String KEY_MEASUREMENT_API_REGISTER_SOURCE_KILL_SWITCH =
            "measurement_api_register_source_kill_switch";
    static final String KEY_MEASUREMENT_API_REGISTER_TRIGGER_KILL_SWITCH =
            "measurement_api_register_trigger_kill_switch";
    static final String KEY_MEASUREMENT_API_REGISTER_WEB_SOURCE_KILL_SWITCH =
            "measurement_api_register_web_source_kill_switch";
    static final String KEY_MEASUREMENT_API_REGISTER_WEB_TRIGGER_KILL_SWITCH =
            "measurement_api_register_web_trigger_kill_switch";
    static final String KEY_MEASUREMENT_JOB_AGGREGATE_FALLBACK_REPORTING_KILL_SWITCH =
            "measurement_job_aggregate_fallback_reporting_kill_switch";
    static final String KEY_MEASUREMENT_JOB_AGGREGATE_REPORTING_KILL_SWITCH =
            "measurement_job_aggregate_reporting_kill_switch";
    static final String KEY_MEASUREMENT_JOB_ATTRIBUTION_KILL_SWITCH =
            "measurement_job_attribution_kill_switch";
    static final String KEY_MEASUREMENT_JOB_DELETE_EXPIRED_KILL_SWITCH =
            "measurement_job_delete_expired_kill_switch";
    static final String KEY_MEASUREMENT_JOB_DELETE_UNINSTALLED_KILL_SWITCH =
            "measurement_job_delete_uninstalled_kill_switch";
    static final String KEY_MEASUREMENT_JOB_EVENT_FALLBACK_REPORTING_KILL_SWITCH =
            "measurement_job_event_fallback_reporting_kill_switch";
    static final String KEY_MEASUREMENT_JOB_EVENT_REPORTING_KILL_SWITCH =
            "measurement_job_event_reporting_kill_switch";
    static final String KEY_MEASUREMENT_RECEIVER_INSTALL_ATTRIBUTION_KILL_SWITCH =
            "measurement_receiver_install_attribution_kill_switch";
    static final String KEY_MEASUREMENT_RECEIVER_DELETE_PACKAGES_KILL_SWITCH =
            "measurement_receiver_delete_packages_kill_switch";
    static final String KEY_MEASUREMENT_REGISTRATION_JOB_QUEUE_KILL_SWITCH =
            "measurement_job_registration_job_queue_kill_switch";

    static final String KEY_MEASUREMENT_REGISTRATION_FALLBACK_JOB_KILL_SWITCH =
            "measurement_job_registration_fallback_job_kill_switch";
    static final String KEY_MEASUREMENT_ROLLBACK_DELETION_KILL_SWITCH =
            "measurement_rollback_deletion_kill_switch";

    static final String KEY_MEASUREMENT_ROLLBACK_DELETION_APP_SEARCH_KILL_SWITCH =
            "measurement_rollback_deletion_app_search_kill_switch";
    static final String KEY_TOPICS_KILL_SWITCH = "topics_kill_switch";
    static final String KEY_TOPICS_ON_DEVICE_CLASSIFIER_KILL_SWITCH =
            "topics_on_device_classifier_kill_switch";
    static final String KEY_MDD_BACKGROUND_TASK_KILL_SWITCH = "mdd_background_task_kill_switch";
    static final String KEY_MDD_LOGGER_KILL_SWITCH = "mdd_logger_kill_switch";
    static final String KEY_ADID_KILL_SWITCH = "adid_kill_switch";
    static final String KEY_APPSETID_KILL_SWITCH = "appsetid_kill_switch";
    static final String KEY_FLEDGE_SELECT_ADS_KILL_SWITCH = "fledge_select_ads_kill_switch";
    static final String KEY_FLEDGE_CUSTOM_AUDIENCE_SERVICE_KILL_SWITCH =
            "fledge_custom_audience_service_kill_switch";

    static final String KEY_BACKGROUND_JOBS_LOGGING_KILL_SWITCH =
            "background_jobs_logging_kill_switch";

    // App/SDK AllowList/DenyList keys
    static final String KEY_PPAPI_APP_ALLOW_LIST = "ppapi_app_allow_list";
    static final String KEY_PPAPI_APP_SIGNATURE_ALLOW_LIST = "ppapi_app_signature_allow_list";

    // AdServices APK sha certs.
    static final String KEY_ADSERVICES_APK_SHA_CERTS = "adservices_apk_sha_certs";

    // Rate Limit keys
    static final String KEY_SDK_REQUEST_PERMITS_PER_SECOND = "sdk_request_permits_per_second";
    static final String KEY_ADID_REQUEST_PERMITS_PER_SECOND = "adid_request_permits_per_second";
    static final String KEY_APPSETID_REQUEST_PERMITS_PER_SECOND =
            "appsetid_request_permits_per_second";
    static final String KEY_MEASUREMENT_REGISTER_SOURCE_REQUEST_PERMITS_PER_SECOND =
            "measurement_register_source_request_permits_per_second";
    static final String KEY_MEASUREMENT_REGISTER_WEB_SOURCE_REQUEST_PERMITS_PER_SECOND =
            "measurement_register_web_source_request_permits_per_second";
    static final String KEY_TOPICS_API_APP_REQUEST_PERMITS_PER_SECOND =
            "topics_api_app_request_permits_per_second";
    static final String KEY_TOPICS_API_SDK_REQUEST_PERMITS_PER_SECOND =
            "topics_api_sdk_request_permits_per_second";
    static final String KEY_FLEDGE_REPORT_INTERACTION_REQUEST_PERMITS_PER_SECOND =
            "fledge_report_interaction_request_permits_per_second";

    // Adservices enable status keys.
    public static final String KEY_ADSERVICES_ENABLED = "adservice_enabled";

    // AdServices error logging enabled
    static final String KEY_ADSERVICES_ERROR_LOGGING_ENABLED = "adservice_error_logging_enabled";

    // Disable enrollment check
    static final String KEY_DISABLE_TOPICS_ENROLLMENT_CHECK = "disable_topics_enrollment_check";
    static final String KEY_DISABLE_FLEDGE_ENROLLMENT_CHECK = "disable_fledge_enrollment_check";

    // Disable Measurement enrollment check.
    static final String KEY_DISABLE_MEASUREMENT_ENROLLMENT_CHECK =
            "disable_measurement_enrollment_check";

    static final String KEY_ENABLE_ENROLLMENT_TEST_SEED = "enable_enrollment_test_seed";

    // SystemProperty prefix. We can use SystemProperty to override the AdService Configs.
    private static final String SYSTEM_PROPERTY_PREFIX = "debug.adservices.";

    // Consent Notification interval begin ms.
    static final String KEY_CONSENT_NOTIFICATION_INTERVAL_BEGIN_MS =
            "consent_notification_interval_begin_ms";

    // Consent Notification interval end ms.
    static final String KEY_CONSENT_NOTIFICATION_INTERVAL_END_MS =
            "consent_notification_interval_end_ms";

    // Consent Notification minimal delay before interval ms.
    static final String KEY_CONSENT_NOTIFICATION_MINIMAL_DELAY_BEFORE_INTERVAL_ENDS =
            "consent_notification_minimal_delay_before_interval_ends";

    // Consent Notification debug mode keys.
    public static final String KEY_CONSENT_NOTIFICATION_DEBUG_MODE =
            "consent_notification_debug_mode";

    // Consent Manager debug mode keys.
    static final String KEY_CONSENT_MANAGER_DEBUG_MODE = "consent_manager_debug_mode";

    // Source of truth to get consent for PPAPI
    static final String KEY_CONSENT_SOURCE_OF_TRUTH = "consent_source_of_truth";
    static final String KEY_BLOCKED_TOPICS_SOURCE_OF_TRUTH = "blocked_topics_source_of_truth";

    // App/SDK AllowList/DenyList keys that have access to the web registration APIs
    static final String KEY_WEB_CONTEXT_CLIENT_ALLOW_LIST = "web_context_client_allow_list";

    // Max response payload size allowed per source/trigger registration
    static final String KEY_MAX_RESPONSE_BASED_REGISTRATION_SIZE_BYTES =
            "max_response_based_registration_size_bytes";

    // UI keys
    static final String KEY_UI_FEATURE_TYPE_LOGGING_ENABLED = "ui_feature_type_logging_enabled";

    static final String KEY_IS_EEA_DEVICE_FEATURE_ENABLED = "is_eea_device_feature_enabled";

    static final String KEY_IS_EEA_DEVICE = "is_eea_device";

    static final String KEY_RECORD_MANUAL_INTERACTION_ENABLED = "record_manual_interaction_enabled";

    static final String KEY_IS_BACK_COMPACT_ACTIVITY_FEATURE_ENABLED =
            "is_check_activity_feature_enabled";

    static final String KEY_UI_OTA_STRINGS_MANIFEST_FILE_URL =
            "mdd_ui_ota_strings_manifest_file_url";

    static final String KEY_UI_OTA_STRINGS_FEATURE_ENABLED = "ui_ota_strings_feature_enabled";

    static final String KEY_UI_OTA_STRINGS_DOWNLOAD_DEADLINE = "ui_ota_strings_download_deadline";

    static final String KEY_UI_EEA_COUNTRIES = "ui_eea_countries";

    static final String KEY_UI_DIALOGS_FEATURE_ENABLED = "ui_dialogs_feature_enabled";

    static final String KEY_UI_DIALOG_FRAGMENT_ENABLED = "ui_dialog_fragment_enabled";

    public static final String KEY_GA_UX_FEATURE_ENABLED = "ga_ux_enabled";

    // Back-compat keys
    static final String KEY_COMPAT_LOGGING_KILL_SWITCH = "compat_logging_kill_switch";

    static final String KEY_ENABLE_BACK_COMPAT = "enable_back_compat";

    static final String KEY_ENABLE_APPSEARCH_CONSENT_DATA = "enable_appsearch_consent_data";

    // Maximum possible percentage for percentage variables
    static final int MAX_PERCENTAGE = 100;

    // Whether to call trusted servers for off device ad selection.
    static final String KEY_OFF_DEVICE_AD_SELECTION_ENABLED = "enable_off_device_ad_selection";

    // Interval in which to run Registration Job Queue Service.
    static final String KEY_ASYNC_REGISTRATION_JOB_QUEUE_INTERVAL_MS =
            "key_async_registration_job_queue_interval_ms";

    // Enrollment flags.
    static final String KEY_ENROLLMENT_BLOCKLIST_IDS = "enrollment_blocklist_ids";

    // New Feature Flags
    static final String KEY_FLEDGE_REGISTER_AD_BEACON_ENABLED = "fledge_register_ad_beacon_enabled";

    static final String KEY_MEASUREMENT_DEBUG_JOIN_KEY_HASH_LIMIT =
            "measurement_debug_join_key_hash_limit";

    static final String KEY_MEASUREMENT_DEBUG_JOIN_KEY_ENROLLMENT_ALLOWLIST =
            "measurement_debug_join_key_enrollment_allowlist";

    static final String KEY_MEASUREMENT_DEBUG_KEY_AD_ID_MATCHING_LIMIT =
            "measurement_debug_key_ad_id_matching_limit";
    static final String KEY_MEASUREMENT_DEBUG_KEY_AD_ID_MATCHING_ENROLLMENT_BLOCKLIST =
            "measurement_debug_key_ad_id_matching_enrollment_blocklist";

    static final String KEY_MEASUREMENT_FLEXIBLE_EVENT_REPORTING_API_ENABLED =
            "measurement_flexible_event_reporting_api_enabled";

    static final String KEY_MEASUREMENT_MAX_SOURCES_PER_PUBLISHER =
            "measurement_max_sources_per_publisher";

    static final String KEY_MEASUREMENT_MAX_TRIGGERS_PER_DESTINATION =
            "measurement_max_triggers_per_destination";

    static final String KEY_MEASUREMENT_MAX_AGGREGATE_REPORTS_PER_DESTINATION =
            "measurement_max_aggregate_reports_per_destination";

    static final String KEY_MEASUREMENT_MAX_EVENT_REPORTS_PER_DESTINATION =
            "measurement_max_event_reports_per_destination";

    static final String KEY_MEASUREMENT_ENABLE_CONFIGURABLE_EVENT_REPORTING_WINDOWS =
            "measurement_enable_configurable_event_reporting_windows";

    static final String KEY_MEASUREMENT_EVENT_REPORTS_VTC_EARLY_REPORTING_WINDOWS =
            "measurement_event_reports_vtc_early_reporting_windows";

    static final String KEY_MEASUREMENT_EVENT_REPORTS_CTC_EARLY_REPORTING_WINDOWS =
            "measurement_event_reports_ctc_early_reporting_windows";

    // AdServices Namespace String from DeviceConfig class not available in S Minus
    static final String NAMESPACE_ADSERVICES = "adservices";
    private static final PhFlags sSingleton = new PhFlags();

    /** Returns the singleton instance of the PhFlags. */
    @NonNull
    public static PhFlags getInstance() {
        return sSingleton;
    }

    @Override
    public long getAsyncRegistrationJobQueueIntervalMs() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_ASYNC_REGISTRATION_JOB_QUEUE_INTERVAL_MS,
                /* defaultValue */ ASYNC_REGISTRATION_JOB_QUEUE_INTERVAL_MS);
    }

    @Override
    public long getTopicsEpochJobPeriodMs() {
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        long topicsEpochJobPeriodMs =
                SystemProperties.getLong(
                        getSystemPropertyName(KEY_TOPICS_EPOCH_JOB_PERIOD_MS),
                        /* defaultValue */ DeviceConfig.getLong(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_TOPICS_EPOCH_JOB_PERIOD_MS,
                                /* defaultValue */ TOPICS_EPOCH_JOB_PERIOD_MS));
        if (topicsEpochJobPeriodMs <= 0) {
            throw new IllegalArgumentException("topicsEpochJobPeriodMs should > 0");
        }
        return topicsEpochJobPeriodMs;
    }

    @Override
    public long getTopicsEpochJobFlexMs() {
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        long topicsEpochJobFlexMs =
                SystemProperties.getLong(
                        getSystemPropertyName(KEY_TOPICS_EPOCH_JOB_FLEX_MS),
                        /* defaultValue */ DeviceConfig.getLong(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_TOPICS_EPOCH_JOB_FLEX_MS,
                                /* defaultValue */ TOPICS_EPOCH_JOB_FLEX_MS));
        if (topicsEpochJobFlexMs <= 0) {
            throw new IllegalArgumentException("topicsEpochJobFlexMs should > 0");
        }
        return topicsEpochJobFlexMs;
    }

    @Override
    public int getTopicsPercentageForRandomTopic() {
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        int topicsPercentageForRandomTopic =
                SystemProperties.getInt(
                        getSystemPropertyName(KEY_TOPICS_PERCENTAGE_FOR_RANDOM_TOPIC),
                        /* defaultValue */ DeviceConfig.getInt(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_TOPICS_PERCENTAGE_FOR_RANDOM_TOPIC,
                                /* defaultValue */ TOPICS_PERCENTAGE_FOR_RANDOM_TOPIC));
        if (topicsPercentageForRandomTopic < 0 || topicsPercentageForRandomTopic > MAX_PERCENTAGE) {
            throw new IllegalArgumentException(
                    "topicsPercentageForRandomTopic should be between 0 and 100");
        }
        return topicsPercentageForRandomTopic;
    }

    @Override
    public int getTopicsNumberOfTopTopics() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        int topicsNumberOfTopTopics =
                DeviceConfig.getInt(
                        NAMESPACE_ADSERVICES,
                        /* flagName */ KEY_TOPICS_NUMBER_OF_TOP_TOPICS,
                        /* defaultValue */ TOPICS_NUMBER_OF_TOP_TOPICS);
        if (topicsNumberOfTopTopics < 0) {
            throw new IllegalArgumentException("topicsNumberOfTopTopics should >= 0");
        }

        return topicsNumberOfTopTopics;
    }

    @Override
    public int getTopicsNumberOfRandomTopics() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        int topicsNumberOfTopTopics =
                DeviceConfig.getInt(
                        NAMESPACE_ADSERVICES,
                        /* flagName */ KEY_TOPICS_NUMBER_OF_RANDOM_TOPICS,
                        /* defaultValue */ TOPICS_NUMBER_OF_RANDOM_TOPICS);
        if (topicsNumberOfTopTopics < 0) {
            throw new IllegalArgumentException("topicsNumberOfTopTopics should >= 0");
        }

        return topicsNumberOfTopTopics;
    }

    @Override
    public int getTopicsNumberOfLookBackEpochs() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        int topicsNumberOfLookBackEpochs =
                DeviceConfig.getInt(
                        NAMESPACE_ADSERVICES,
                        /* flagName */ KEY_TOPICS_NUMBER_OF_LOOK_BACK_EPOCHS,
                        /* defaultValue */ TOPICS_NUMBER_OF_LOOK_BACK_EPOCHS);
        if (topicsNumberOfLookBackEpochs < 1) {
            throw new IllegalArgumentException("topicsNumberOfLookBackEpochs should  >= 1");
        }

        return topicsNumberOfLookBackEpochs;
    }

    @Override
    public int getClassifierType() {
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        return SystemProperties.getInt(
                getSystemPropertyName(KEY_CLASSIFIER_TYPE),
                DeviceConfig.getInt(
                        NAMESPACE_ADSERVICES,
                        /* flagName */ KEY_CLASSIFIER_TYPE,
                        /* defaultValue */ DEFAULT_CLASSIFIER_TYPE));
    }

    @Override
    public int getClassifierNumberOfTopLabels() {
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        return SystemProperties.getInt(
                getSystemPropertyName(KEY_CLASSIFIER_NUMBER_OF_TOP_LABELS),
                DeviceConfig.getInt(
                        NAMESPACE_ADSERVICES,
                        /* flagName */ KEY_CLASSIFIER_NUMBER_OF_TOP_LABELS,
                        /* defaultValue */ CLASSIFIER_NUMBER_OF_TOP_LABELS));
    }

    @Override
    public float getClassifierThreshold() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getFloat(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_CLASSIFIER_THRESHOLD,
                /* defaultValue */ CLASSIFIER_THRESHOLD);
    }

    @Override
    public int getClassifierDescriptionMaxWords() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_CLASSIFIER_DESCRIPTION_MAX_WORDS,
                /* defaultValue */ CLASSIFIER_DESCRIPTION_MAX_WORDS);
    }

    @Override
    public int getClassifierDescriptionMaxLength() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_CLASSIFIER_DESCRIPTION_MAX_LENGTH,
                /* defaultValue */ CLASSIFIER_DESCRIPTION_MAX_LENGTH);
    }

    @Override
    public boolean getClassifierForceUseBundledFiles() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_CLASSIFIER_FORCE_USE_BUNDLED_FILES,
                /* defaultValue */ CLASSIFIER_FORCE_USE_BUNDLED_FILES);
    }

    @Override
    public long getMaintenanceJobPeriodMs() {
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig) and then
        // hard-coded value.
        long maintenanceJobPeriodMs =
                SystemProperties.getLong(
                        getSystemPropertyName(KEY_MAINTENANCE_JOB_PERIOD_MS),
                        /* defaultValue */ DeviceConfig.getLong(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_MAINTENANCE_JOB_PERIOD_MS,
                                /* defaultValue */ MAINTENANCE_JOB_PERIOD_MS));
        if (maintenanceJobPeriodMs < 0) {
            throw new IllegalArgumentException("maintenanceJobPeriodMs should  >= 0");
        }
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return maintenanceJobPeriodMs;
    }

    @Override
    public long getMaintenanceJobFlexMs() {
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig) and then
        // hard-coded value.
        long maintenanceJobFlexMs =
                SystemProperties.getLong(
                        getSystemPropertyName(KEY_MAINTENANCE_JOB_FLEX_MS),
                        /* defaultValue */ DeviceConfig.getLong(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_MAINTENANCE_JOB_FLEX_MS,
                                /* defaultValue */ MAINTENANCE_JOB_FLEX_MS));

        if (maintenanceJobFlexMs <= 0) {
            throw new IllegalArgumentException("maintenanceJobFlexMs should  > 0");
        }

        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return maintenanceJobFlexMs;
    }

    @Override
    public long getMeasurementEventMainReportingJobPeriodMs() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_EVENT_MAIN_REPORTING_JOB_PERIOD_MS,
                /* defaultValue */ MEASUREMENT_EVENT_MAIN_REPORTING_JOB_PERIOD_MS);
    }

    @Override
    public long getMeasurementEventFallbackReportingJobPeriodMs() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_EVENT_FALLBACK_REPORTING_JOB_PERIOD_MS,
                /* defaultValue */ MEASUREMENT_EVENT_FALLBACK_REPORTING_JOB_PERIOD_MS);
    }

    @Override
    public String getMeasurementAggregateEncryptionKeyCoordinatorUrl() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getString(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_AGGREGATE_ENCRYPTION_KEY_COORDINATOR_URL,
                /* defaultValue */ MEASUREMENT_AGGREGATE_ENCRYPTION_KEY_COORDINATOR_URL);
    }

    @Override
    public long getMeasurementAggregateMainReportingJobPeriodMs() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_AGGREGATE_MAIN_REPORTING_JOB_PERIOD_MS,
                /* defaultValue */ MEASUREMENT_AGGREGATE_MAIN_REPORTING_JOB_PERIOD_MS);
    }

    @Override
    public long getMeasurementAggregateFallbackReportingJobPeriodMs() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_AGGREGATE_FALLBACK_REPORTING_JOB_PERIOD_MS,
                /* defaultValue */ MEASUREMENT_AGGREGATE_FALLBACK_REPORTING_JOB_PERIOD_MS);
    }

    @Override
    public int getMeasurementNetworkConnectTimeoutMs() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_NETWORK_CONNECT_TIMEOUT_MS,
                /* defaultValue */ MEASUREMENT_NETWORK_CONNECT_TIMEOUT_MS);
    }

    @Override
    public int getMeasurementNetworkReadTimeoutMs() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_NETWORK_READ_TIMEOUT_MS,
                /* defaultValue */ MEASUREMENT_NETWORK_READ_TIMEOUT_MS);
    }

    @Override
    public long getMeasurementDbSizeLimit() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_DB_SIZE_LIMIT,
                /* defaultValue */ MEASUREMENT_DB_SIZE_LIMIT);
    }

    @Override
    public String getMeasurementManifestFileUrl() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getString(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_MANIFEST_FILE_URL,
                /* defaultValue */ MEASUREMENT_MANIFEST_FILE_URL);
    }

    @Override
    public long getMeasurementRegistrationInputEventValidWindowMs() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_REGISTRATION_INPUT_EVENT_VALID_WINDOW_MS,
                /* defaultValue */ MEASUREMENT_REGISTRATION_INPUT_EVENT_VALID_WINDOW_MS);
    }

    @Override
    public boolean getMeasurementIsClickVerificationEnabled() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_IS_CLICK_VERIFICATION_ENABLED,
                /* defaultValue */ MEASUREMENT_IS_CLICK_VERIFICATION_ENABLED);
    }

    @Override
    public boolean getMeasurementIsClickVerifiedByInputEvent() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_IS_CLICK_VERIFIED_BY_INPUT_EVENT,
                /* defaultValue */ MEASUREMENT_IS_CLICK_VERIFIED_BY_INPUT_EVENT);
    }

    @Override
    public boolean getMeasurementEnableXNA() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_ENABLE_XNA,
                /* defaultValue */ MEASUREMENT_ENABLE_XNA);
    }

    @Override
    public boolean getMeasurementEnableDebugReport() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_ENABLE_DEBUG_REPORT,
                /* defaultValue */ MEASUREMENT_ENABLE_DEBUG_REPORT);
    }

    @Override
    public boolean getMeasurementEnableSourceDebugReport() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_ENABLE_SOURCE_DEBUG_REPORT,
                /* defaultValue */ MEASUREMENT_ENABLE_SOURCE_DEBUG_REPORT);
    }

    @Override
    public boolean getMeasurementEnableTriggerDebugReport() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_ENABLE_TRIGGER_DEBUG_REPORT,
                /* defaultValue */ MEASUREMENT_ENABLE_TRIGGER_DEBUG_REPORT);
    }

    @Override
    public long getMeasurementDataExpiryWindowMs() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_DATA_EXPIRY_WINDOW_MS,
                /* defaultValue */ MEASUREMENT_DATA_EXPIRY_WINDOW_MS);
    }

    @Override
    public int getMeasurementMaxRegistrationRedirects() {
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_MAX_REGISTRATION_REDIRECTS,
                /* defaultValue */ MEASUREMENT_MAX_REGISTRATION_REDIRECTS);
    }

    @Override
    public int getMeasurementMaxRegistrationsPerJobInvocation() {
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_MAX_REGISTRATIONS_PER_JOB_INVOCATION,
                /* defaultValue */ MEASUREMENT_MAX_REGISTRATIONS_PER_JOB_INVOCATION);
    }

    @Override
    public int getMeasurementMaxRetriesPerRegistrationRequest() {
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_MAX_RETRIES_PER_REGISTRATION_REQUEST,
                /* defaultValue */ MEASUREMENT_MAX_RETRIES_PER_REGISTRATION_REQUEST);
    }

    @Override
    public long getMeasurementRegistrationJobTriggerDelayMs() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_REGISTRATION_JOB_TRIGGER_DELAY_MS,
                /* defaultValue */ MEASUREMENT_REGISTRATION_JOB_TRIGGER_DELAY_MS);
    }

    @Override
    public long getMeasurementRegistrationJobTriggerMaxDelayMs() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_REGISTRATION_JOB_TRIGGER_MAX_DELAY_MS,
                /* defaultValue */ MEASUREMENT_REGISTRATION_JOB_TRIGGER_MAX_DELAY_MS);
    }

    @Override
    public boolean getMeasurementAttributionFallbackJobKillSwitch() {
        // We check the Global Killswitch first then Measurement Killswitch.
        // As a result, it overrides all other killswitches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        final String flagName = KEY_MEASUREMENT_ATTRIBUTION_FALLBACK_JOB_KILL_SWITCH;
        final boolean defaultValue = MEASUREMENT_ATTRIBUTION_FALLBACK_JOB_KILL_SWITCH;
        return getGlobalKillSwitch()
                || getMeasurementKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(flagName),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES, flagName, defaultValue));
    }

    @Override
    public long getMeasurementAttributionFallbackJobPeriodMs() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_ATTRIBUTION_FALLBACK_JOB_PERIOD_MS,
                /* defaultValue */ MEASUREMENT_ATTRIBUTION_FALLBACK_JOB_PERIOD_MS);
    }

    @Override
    public int getMeasurementMaxAttributionPerRateLimitWindow() {
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_MAX_ATTRIBUTION_PER_RATE_LIMIT_WINDOW,
                /* defaultValue */ MEASUREMENT_MAX_ATTRIBUTION_PER_RATE_LIMIT_WINDOW);
    }

    @Override
    public int getMeasurementMaxDistinctEnrollmentsInAttribution() {
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_MAX_DISTINCT_ENROLLMENTS_IN_ATTRIBUTION,
                /* defaultValue */ MEASUREMENT_MAX_DISTINCT_ENROLLMENTS_IN_ATTRIBUTION);
    }

    @Override
    public int getMeasurementMaxDistinctDestinationsInActiveSource() {
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_MAX_DISTINCT_DESTINATIONS_IN_ACTIVE_SOURCE,
                /* defaultValue */ MEASUREMENT_MAX_DISTINCT_DESTINATIONS_IN_ACTIVE_SOURCE);
    }

    @Override
    public long getFledgeCustomAudienceMaxCount() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_COUNT,
                /* defaultValue */ FLEDGE_CUSTOM_AUDIENCE_MAX_COUNT);
    }

    @Override
    public long getFledgeCustomAudiencePerAppMaxCount() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_CUSTOM_AUDIENCE_PER_APP_MAX_COUNT,
                /* defaultValue */ FLEDGE_CUSTOM_AUDIENCE_PER_APP_MAX_COUNT);
    }

    @Override
    public long getFledgeCustomAudienceMaxOwnerCount() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_OWNER_COUNT,
                /* defaultValue */ FLEDGE_CUSTOM_AUDIENCE_MAX_OWNER_COUNT);
    }

    @Override
    public long getFledgeCustomAudienceDefaultExpireInMs() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_CUSTOM_AUDIENCE_DEFAULT_EXPIRE_IN_MS,
                /* defaultValue */ FLEDGE_CUSTOM_AUDIENCE_DEFAULT_EXPIRE_IN_MS);
    }

    @Override
    public long getFledgeCustomAudienceMaxActivationDelayInMs() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_ACTIVATION_DELAY_IN_MS,
                /* defaultValue */ FLEDGE_CUSTOM_AUDIENCE_MAX_ACTIVATION_DELAY_IN_MS);
    }

    @Override
    public long getFledgeCustomAudienceMaxExpireInMs() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_EXPIRE_IN_MS,
                /* defaultValue */ FLEDGE_CUSTOM_AUDIENCE_MAX_EXPIRE_IN_MS);
    }

    @Override
    public int getFledgeCustomAudienceMaxNameSizeB() {
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_NAME_SIZE_B,
                /* defaultValue */ FLEDGE_CUSTOM_AUDIENCE_MAX_NAME_SIZE_B);
    }

    @Override
    public int getFledgeCustomAudienceMaxDailyUpdateUriSizeB() {
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_DAILY_UPDATE_URI_SIZE_B,
                /* defaultValue */ FLEDGE_CUSTOM_AUDIENCE_MAX_DAILY_UPDATE_URI_SIZE_B);
    }

    @Override
    public int getFledgeCustomAudienceMaxBiddingLogicUriSizeB() {
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_BIDDING_LOGIC_URI_SIZE_B,
                /* defaultValue */ FLEDGE_CUSTOM_AUDIENCE_MAX_BIDDING_LOGIC_URI_SIZE_B);
    }

    @Override
    public int getFledgeCustomAudienceMaxUserBiddingSignalsSizeB() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_USER_BIDDING_SIGNALS_SIZE_B,
                /* defaultValue */ FLEDGE_CUSTOM_AUDIENCE_MAX_USER_BIDDING_SIGNALS_SIZE_B);
    }

    @Override
    public int getFledgeCustomAudienceMaxTrustedBiddingDataSizeB() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_TRUSTED_BIDDING_DATA_SIZE_B,
                /* defaultValue */ FLEDGE_CUSTOM_AUDIENCE_MAX_TRUSTED_BIDDING_DATA_SIZE_B);
    }

    @Override
    public int getFledgeCustomAudienceMaxAdsSizeB() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_ADS_SIZE_B,
                /* defaultValue */ FLEDGE_CUSTOM_AUDIENCE_MAX_ADS_SIZE_B);
    }

    @Override
    public int getFledgeCustomAudienceMaxNumAds() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_NUM_ADS,
                /* defaultValue */ FLEDGE_CUSTOM_AUDIENCE_MAX_NUM_ADS);
    }

    @Override
    public long getFledgeCustomAudienceActiveTimeWindowInMs() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_CUSTOM_AUDIENCE_ACTIVE_TIME_WINDOW_MS,
                /* defaultValue */ FLEDGE_CUSTOM_AUDIENCE_ACTIVE_TIME_WINDOW_MS);
    }

    @Override
    public boolean getFledgeBackgroundFetchEnabled() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_BACKGROUND_FETCH_ENABLED,
                /* defaultValue */ FLEDGE_BACKGROUND_FETCH_ENABLED);
    }

    @Override
    public long getFledgeBackgroundFetchJobPeriodMs() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_BACKGROUND_FETCH_JOB_PERIOD_MS,
                /* defaultValue */ FLEDGE_BACKGROUND_FETCH_JOB_PERIOD_MS);
    }

    @Override
    public long getFledgeBackgroundFetchJobFlexMs() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_BACKGROUND_FETCH_JOB_FLEX_MS,
                /* defaultValue */ FLEDGE_BACKGROUND_FETCH_JOB_FLEX_MS);
    }

    @Override
    public long getFledgeBackgroundFetchJobMaxRuntimeMs() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_BACKGROUND_FETCH_JOB_MAX_RUNTIME_MS,
                /* defaultValue */ FLEDGE_BACKGROUND_FETCH_JOB_MAX_RUNTIME_MS);
    }

    @Override
    public long getFledgeBackgroundFetchMaxNumUpdated() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_BACKGROUND_FETCH_MAX_NUM_UPDATED,
                /* defaultValue */ FLEDGE_BACKGROUND_FETCH_MAX_NUM_UPDATED);
    }

    @Override
    public int getFledgeBackgroundFetchThreadPoolSize() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_BACKGROUND_FETCH_THREAD_POOL_SIZE,
                /* defaultValue */ FLEDGE_BACKGROUND_FETCH_THREAD_POOL_SIZE);
    }

    @Override
    public long getFledgeBackgroundFetchEligibleUpdateBaseIntervalS() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_BACKGROUND_FETCH_ELIGIBLE_UPDATE_BASE_INTERVAL_S,
                /* defaultValue */ FLEDGE_BACKGROUND_FETCH_ELIGIBLE_UPDATE_BASE_INTERVAL_S);
    }

    @Override
    public int getFledgeBackgroundFetchNetworkConnectTimeoutMs() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_BACKGROUND_FETCH_NETWORK_CONNECT_TIMEOUT_MS,
                /* defaultValue */ FLEDGE_BACKGROUND_FETCH_NETWORK_CONNECT_TIMEOUT_MS);
    }

    @Override
    public int getFledgeBackgroundFetchNetworkReadTimeoutMs() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_BACKGROUND_FETCH_NETWORK_READ_TIMEOUT_MS,
                /* defaultValue */ FLEDGE_BACKGROUND_FETCH_NETWORK_READ_TIMEOUT_MS);
    }

    @Override
    public int getFledgeBackgroundFetchMaxResponseSizeB() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_BACKGROUND_FETCH_MAX_RESPONSE_SIZE_B,
                /* defaultValue */ FLEDGE_BACKGROUND_FETCH_MAX_RESPONSE_SIZE_B);
    }

    @Override
    public int getAdSelectionMaxConcurrentBiddingCount() {
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_AD_SELECTION_MAX_CONCURRENT_BIDDING_COUNT,
                /* defaultValue */ FLEDGE_AD_SELECTION_MAX_CONCURRENT_BIDDING_COUNT);
    }

    @Override
    public long getAdSelectionBiddingTimeoutPerCaMs() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_AD_SELECTION_BIDDING_TIMEOUT_PER_CA_MS,
                /* defaultValue */ FLEDGE_AD_SELECTION_BIDDING_TIMEOUT_PER_CA_MS);
    }

    @Override
    public long getAdSelectionBiddingTimeoutPerBuyerMs() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_AD_SELECTION_BIDDING_TIMEOUT_PER_BUYER_MS,
                /* defaultValue */ FLEDGE_AD_SELECTION_BIDDING_TIMEOUT_PER_BUYER_MS);
    }

    @Override
    public long getAdSelectionScoringTimeoutMs() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_AD_SELECTION_SCORING_TIMEOUT_MS,
                /* defaultValue */ FLEDGE_AD_SELECTION_SCORING_TIMEOUT_MS);
    }

    @Override
    public long getAdSelectionSelectingOutcomeTimeoutMs() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_AD_SELECTION_SELECTING_OUTCOME_TIMEOUT_MS,
                /* defaultValue */ FLEDGE_AD_SELECTION_SELECTING_OUTCOME_TIMEOUT_MS);
    }

    @Override
    public long getAdSelectionOverallTimeoutMs() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_AD_SELECTION_OVERALL_TIMEOUT_MS,
                /* defaultValue */ FLEDGE_AD_SELECTION_OVERALL_TIMEOUT_MS);
    }

    @Override
    public long getAdSelectionFromOutcomesOverallTimeoutMs() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_AD_SELECTION_FROM_OUTCOMES_OVERALL_TIMEOUT_MS,
                /* defaultValue */ FLEDGE_AD_SELECTION_FROM_OUTCOMES_OVERALL_TIMEOUT_MS);
    }

    @Override
    public long getAdSelectionOffDeviceOverallTimeoutMs() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_AD_SELECTION_OFF_DEVICE_OVERALL_TIMEOUT_MS,
                /* defaultValue */ FLEDGE_AD_SELECTION_OFF_DEVICE_OVERALL_TIMEOUT_MS);
    }

    @Override
    public boolean getFledgeAdSelectionFilteringEnabled() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_AD_SELECTION_FILTERING_ENABLED,
                /* defaultValue */ FLEDGE_AD_SELECTION_FILTERING_ENABLED);
    }

    @Override
    public boolean getFledgeAdSelectionContextualAdsEnabled() {
        return DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ADSERVICES,
                // The key deliberately kept same as Filtering as the two features are coupled
                /* flagName */ KEY_FLEDGE_AD_SELECTION_FILTERING_ENABLED,
                /* defaultValue */ FLEDGE_AD_SELECTION_CONTEXTUAL_ADS_ENABLED);
    }

    @Override
    public long getFledgeAdSelectionBiddingLogicJsVersion() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_AD_SELECTION_BIDDING_LOGIC_JS_VERSION,
                /* defaultValue */ FLEDGE_AD_SELECTION_BIDDING_LOGIC_JS_VERSION);
    }

    @Override
    public long getReportImpressionOverallTimeoutMs() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_REPORT_IMPRESSION_OVERALL_TIMEOUT_MS,
                /* defaultValue */ FLEDGE_REPORT_IMPRESSION_OVERALL_TIMEOUT_MS);
    }

    @Override
    public long getFledgeReportImpressionMaxRegisteredAdBeaconsTotalCount() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_REPORT_IMPRESSION_MAX_REGISTERED_AD_BEACONS_TOTAL_COUNT,
                /* defaultValue */ FLEDGE_REPORT_IMPRESSION_MAX_REGISTERED_AD_BEACONS_TOTAL_COUNT);
    }

    @Override
    public long getFledgeReportImpressionMaxRegisteredAdBeaconsPerAdTechCount() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_REPORT_IMPRESSION_MAX_REGISTERED_AD_BEACONS_PER_AD_TECH_COUNT,
                /* defaultValue */ FLEDGE_REPORT_IMPRESSION_MAX_REGISTERED_AD_BEACONS_PER_AD_TECH_COUNT);
    }

    @Override
    public long getFledgeReportImpressionRegisteredAdBeaconsMaxInteractionKeySizeB() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */
                KEY_FLEDGE_REPORT_IMPRESSION_REGISTERED_AD_BEACONS_MAX_INTERACTION_KEY_SIZE_B,
                /* defaultValue */
                FLEDGE_REPORT_IMPRESSION_REGISTERED_AD_BEACONS_MAX_INTERACTION_KEY_SIZE_B);
    }

    @Override
    public boolean getFledgeHttpCachingEnabled() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_HTTP_CACHE_ENABLE,
                /* defaultValue */ FLEDGE_HTTP_CACHE_ENABLE);
    }

    @Override
    public boolean getFledgeHttpJsCachingEnabled() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_HTTP_CACHE_ENABLE_JS_CACHING,
                /* defaultValue */ FLEDGE_HTTP_CACHE_ENABLE_JS_CACHING);
    }

    @Override
    public long getFledgeHttpCacheMaxEntries() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_HTTP_CACHE_MAX_ENTRIES,
                /* defaultValue */ FLEDGE_HTTP_CACHE_MAX_ENTRIES);
    }

    @Override
    public long getFledgeHttpCacheMaxAgeSeconds() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_HTTP_CACHE_DEFAULT_MAX_AGE_SECONDS,
                /* defaultValue */ FLEDGE_HTTP_CACHE_DEFAULT_MAX_AGE_SECONDS);
    }

    @Override
    public int getFledgeAdCounterHistogramAbsoluteMaxEventCount() {
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_AD_COUNTER_HISTOGRAM_ABSOLUTE_MAX_EVENT_COUNT,
                /* defaultValue */ FLEDGE_AD_COUNTER_HISTOGRAM_ABSOLUTE_MAX_EVENT_COUNT);
    }

    @Override
    public int getFledgeAdCounterHistogramLowerMaxEventCount() {
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_AD_COUNTER_HISTOGRAM_LOWER_MAX_EVENT_COUNT,
                /* defaultValue */ FLEDGE_AD_COUNTER_HISTOGRAM_LOWER_MAX_EVENT_COUNT);
    }

    // MDD related flags.
    @Override
    public int getDownloaderConnectionTimeoutMs() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_DOWNLOADER_CONNECTION_TIMEOUT_MS,
                /* defaultValue */ DOWNLOADER_CONNECTION_TIMEOUT_MS);
    }

    @Override
    public int getDownloaderReadTimeoutMs() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_DOWNLOADER_READ_TIMEOUT_MS,
                /* defaultValue */ DOWNLOADER_READ_TIMEOUT_MS);
    }

    @Override
    public int getDownloaderMaxDownloadThreads() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_DOWNLOADER_MAX_DOWNLOAD_THREADS,
                /* defaultValue */ DOWNLOADER_MAX_DOWNLOAD_THREADS);
    }

    @Override
    public String getMddTopicsClassifierManifestFileUrl() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getString(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MDD_TOPICS_CLASSIFIER_MANIFEST_FILE_URL,
                /* defaultValue */ MDD_TOPICS_CLASSIFIER_MANIFEST_FILE_URL);
    }

    // Group of All Killswitches
    @Override
    public boolean getGlobalKillSwitch() {
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        return SystemProperties.getBoolean(
                        getSystemPropertyName(KEY_GLOBAL_KILL_SWITCH),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_GLOBAL_KILL_SWITCH,
                                /* defaultValue */ GLOBAL_KILL_SWITCH))
                || /* S Minus Kill Switch */ !(SdkLevel.isAtLeastT() || getEnableBackCompat());
    }

    // MEASUREMENT Killswitches
    @Override
    public boolean getMeasurementKillSwitch() {
        // We check the Global Killswitch first. As a result, it overrides all other killswitches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        return getGlobalKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(KEY_MEASUREMENT_KILL_SWITCH),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_MEASUREMENT_KILL_SWITCH,
                                /* defaultValue */ MEASUREMENT_KILL_SWITCH));
    }

    @Override
    public boolean getMeasurementApiDeleteRegistrationsKillSwitch() {
        // We check the Global Killswitch first then Measurement Killswitch.
        // As a result, it overrides all other killswitches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        final boolean defaultValue = MEASUREMENT_API_DELETE_REGISTRATIONS_KILL_SWITCH;
        return getGlobalKillSwitch()
                || getMeasurementKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(KEY_MEASUREMENT_API_DELETE_REGISTRATIONS_KILL_SWITCH),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_MEASUREMENT_API_DELETE_REGISTRATIONS_KILL_SWITCH,
                                defaultValue));
    }

    @Override
    public boolean getMeasurementApiStatusKillSwitch() {
        // We check the Global Killswitch first then Measurement Killswitch.
        // As a result, it overrides all other killswitches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        return getGlobalKillSwitch()
                || getMeasurementKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(KEY_MEASUREMENT_API_STATUS_KILL_SWITCH),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_MEASUREMENT_API_STATUS_KILL_SWITCH,
                                /* defaultValue */ MEASUREMENT_API_STATUS_KILL_SWITCH));
    }

    @Override
    public boolean getMeasurementApiRegisterSourceKillSwitch() {
        // We check the Global Killswitch first then Measurement Killswitch.
        // As a result, it overrides all other killswitches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        return getGlobalKillSwitch()
                || getMeasurementKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(KEY_MEASUREMENT_API_REGISTER_SOURCE_KILL_SWITCH),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_MEASUREMENT_API_REGISTER_SOURCE_KILL_SWITCH,
                                /* defaultValue */ MEASUREMENT_API_REGISTER_SOURCE_KILL_SWITCH));
    }

    @Override
    public boolean getMeasurementApiRegisterTriggerKillSwitch() {
        // We check the Global Killswitch first then Measurement Killswitch.
        // As a result, it overrides all other killswitches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        return getGlobalKillSwitch()
                || getMeasurementKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(KEY_MEASUREMENT_API_REGISTER_TRIGGER_KILL_SWITCH),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_MEASUREMENT_API_REGISTER_TRIGGER_KILL_SWITCH,
                                /* defaultValue */ MEASUREMENT_API_REGISTER_TRIGGER_KILL_SWITCH));
    }

    @Override
    public boolean getMeasurementApiRegisterWebSourceKillSwitch() {
        // We check the Global Killswitch first then Measurement Killswitch.
        // As a result, it overrides all other killswitches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        final boolean defaultValue = MEASUREMENT_API_REGISTER_WEB_SOURCE_KILL_SWITCH;
        return getGlobalKillSwitch()
                || getMeasurementKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(KEY_MEASUREMENT_API_REGISTER_WEB_SOURCE_KILL_SWITCH),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_MEASUREMENT_API_REGISTER_WEB_SOURCE_KILL_SWITCH,
                                defaultValue));
    }

    @Override
    public boolean getMeasurementApiRegisterWebTriggerKillSwitch() {
        // We check the Global Killswitch first then Measurement Killswitch.
        // As a result, it overrides all other killswitches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        final boolean defaultValue = MEASUREMENT_API_REGISTER_WEB_TRIGGER_KILL_SWITCH;
        return getGlobalKillSwitch()
                || getMeasurementKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(KEY_MEASUREMENT_API_REGISTER_WEB_TRIGGER_KILL_SWITCH),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_MEASUREMENT_API_REGISTER_WEB_TRIGGER_KILL_SWITCH,
                                defaultValue));
    }

    @Override
    public boolean getMeasurementJobAggregateFallbackReportingKillSwitch() {
        // We check the Global Killswitch first then Measurement Killswitch.
        // As a result, it overrides all other killswitches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        final String flagName = KEY_MEASUREMENT_JOB_AGGREGATE_FALLBACK_REPORTING_KILL_SWITCH;
        final boolean defaultValue = MEASUREMENT_JOB_AGGREGATE_FALLBACK_REPORTING_KILL_SWITCH;
        return getGlobalKillSwitch()
                || getMeasurementKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(flagName),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES, flagName, defaultValue));
    }

    @Override
    public boolean getMeasurementJobAggregateReportingKillSwitch() {
        // We check the Global Killswitch first then Measurement Killswitch.
        // As a result, it overrides all other killswitches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        final boolean defaultValue = MEASUREMENT_JOB_AGGREGATE_REPORTING_KILL_SWITCH;
        return getGlobalKillSwitch()
                || getMeasurementKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(KEY_MEASUREMENT_JOB_AGGREGATE_REPORTING_KILL_SWITCH),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_MEASUREMENT_JOB_AGGREGATE_REPORTING_KILL_SWITCH,
                                defaultValue));
    }

    @Override
    public boolean getMeasurementJobAttributionKillSwitch() {
        // We check the Global Killswitch first then Measurement Killswitch.
        // As a result, it overrides all other killswitches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        return getGlobalKillSwitch()
                || getMeasurementKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(KEY_MEASUREMENT_JOB_ATTRIBUTION_KILL_SWITCH),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_MEASUREMENT_JOB_ATTRIBUTION_KILL_SWITCH,
                                /* defaultValue */ MEASUREMENT_JOB_ATTRIBUTION_KILL_SWITCH));
    }

    @Override
    public boolean getMeasurementJobDeleteExpiredKillSwitch() {
        // We check the Global Killswitch first then Measurement Killswitch.
        // As a result, it overrides all other killswitches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        return getGlobalKillSwitch()
                || getMeasurementKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(KEY_MEASUREMENT_JOB_DELETE_EXPIRED_KILL_SWITCH),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_MEASUREMENT_JOB_DELETE_EXPIRED_KILL_SWITCH,
                                /* defaultValue */ MEASUREMENT_JOB_DELETE_EXPIRED_KILL_SWITCH));
    }

    @Override
    public boolean getMeasurementJobDeleteUninstalledKillSwitch() {
        // We check the Global Killswitch first then Measurement Killswitch.
        // As a result, it overrides all other killswitches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        return getGlobalKillSwitch()
                || getMeasurementKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(KEY_MEASUREMENT_JOB_DELETE_UNINSTALLED_KILL_SWITCH),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_MEASUREMENT_JOB_DELETE_UNINSTALLED_KILL_SWITCH,
                                /* defaultValue */ MEASUREMENT_JOB_DELETE_UNINSTALLED_KILL_SWITCH));
    }

    @Override
    public boolean getMeasurementJobEventFallbackReportingKillSwitch() {
        // We check the Global Killswitch first then Measurement Killswitch.
        // As a result, it overrides all other killswitches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        final String flagName = KEY_MEASUREMENT_JOB_EVENT_FALLBACK_REPORTING_KILL_SWITCH;
        final boolean defaultValue = MEASUREMENT_JOB_EVENT_FALLBACK_REPORTING_KILL_SWITCH;
        return getGlobalKillSwitch()
                || getMeasurementKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(flagName),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES, flagName, defaultValue));
    }

    @Override
    public boolean getMeasurementJobEventReportingKillSwitch() {
        // We check the Global Killswitch first then Measurement Killswitch.
        // As a result, it overrides all other killswitches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        return getGlobalKillSwitch()
                || getMeasurementKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(KEY_MEASUREMENT_JOB_EVENT_REPORTING_KILL_SWITCH),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_MEASUREMENT_JOB_EVENT_REPORTING_KILL_SWITCH,
                                /* defaultValue */ MEASUREMENT_JOB_EVENT_REPORTING_KILL_SWITCH));
    }

    @Override
    public boolean getAsyncRegistrationJobQueueKillSwitch() {
        // We check the Global Killswitch first then Measurement Killswitch.
        // As a result, it overrides all other killswitches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        final String flagName = KEY_MEASUREMENT_REGISTRATION_JOB_QUEUE_KILL_SWITCH;
        final boolean defaultValue = MEASUREMENT_REGISTRATION_JOB_QUEUE_KILL_SWITCH;
        return getGlobalKillSwitch()
                || getMeasurementKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(flagName),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES, flagName, defaultValue));
    }

    @Override
    public boolean getAsyncRegistrationFallbackJobKillSwitch() {
        // We check the Global Killswitch first then Measurement Killswitch.
        // As a result, it overrides all other killswitches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        final String flagName = KEY_MEASUREMENT_REGISTRATION_FALLBACK_JOB_KILL_SWITCH;
        return getGlobalKillSwitch()
                || getMeasurementKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(flagName),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES,
                                flagName,
                                MEASUREMENT_REGISTRATION_FALLBACK_JOB_KILL_SWITCH));
    }

    @Override
    public boolean getMeasurementReceiverInstallAttributionKillSwitch() {
        // We check the Global Killswitch first then Measurement Killswitch.
        // As a result, it overrides all other killswitches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        final String flagName = KEY_MEASUREMENT_RECEIVER_INSTALL_ATTRIBUTION_KILL_SWITCH;
        final boolean defaultValue = MEASUREMENT_RECEIVER_INSTALL_ATTRIBUTION_KILL_SWITCH;
        return getGlobalKillSwitch()
                || getMeasurementKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(flagName),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES, flagName, defaultValue));
    }

    @Override
    public boolean getMeasurementReceiverDeletePackagesKillSwitch() {
        // We check the Global Killswitch first then Measurement Killswitch.
        // As a result, it overrides all other killswitches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        final boolean defaultValue = MEASUREMENT_RECEIVER_DELETE_PACKAGES_KILL_SWITCH;
        return getGlobalKillSwitch()
                || getMeasurementKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(KEY_MEASUREMENT_RECEIVER_DELETE_PACKAGES_KILL_SWITCH),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_MEASUREMENT_RECEIVER_DELETE_PACKAGES_KILL_SWITCH,
                                defaultValue));
    }

    @Override
    public boolean getMeasurementRollbackDeletionKillSwitch() {
        final boolean defaultValue = MEASUREMENT_ROLLBACK_DELETION_KILL_SWITCH;
        return getGlobalKillSwitch()
                || getMeasurementKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(KEY_MEASUREMENT_ROLLBACK_DELETION_KILL_SWITCH),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_MEASUREMENT_ROLLBACK_DELETION_KILL_SWITCH,
                                defaultValue));
    }

    @Override
    public boolean getMeasurementRollbackDeletionAppSearchKillSwitch() {
        final boolean defaultValue = MEASUREMENT_ROLLBACK_DELETION_APP_SEARCH_KILL_SWITCH;
        return SystemProperties.getBoolean(
                getSystemPropertyName(KEY_MEASUREMENT_ROLLBACK_DELETION_APP_SEARCH_KILL_SWITCH),
                /* def= */ DeviceConfig.getBoolean(
                        NAMESPACE_ADSERVICES,
                        /* name= */ KEY_MEASUREMENT_ROLLBACK_DELETION_APP_SEARCH_KILL_SWITCH,
                        defaultValue));
    }

    @Override
    public String getMeasurementDebugJoinKeyEnrollmentAllowlist() {
        return DeviceConfig.getString(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_DEBUG_JOIN_KEY_ENROLLMENT_ALLOWLIST,
                /* defaultValue */ DEFAULT_MEASUREMENT_DEBUG_JOIN_KEY_ENROLLMENT_ALLOWLIST);
    }

    @Override
    public String getMeasurementPlatformDebugAdIdMatchingEnrollmentBlocklist() {
        return DeviceConfig.getString(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_DEBUG_KEY_AD_ID_MATCHING_ENROLLMENT_BLOCKLIST,
                /* defaultValue */ DEFAULT_MEASUREMENT_PLATFORM_DEBUG_AD_ID_MATCHING_BLOCKLIST);
    }

    // ADID Killswitches
    @Override
    public boolean getAdIdKillSwitch() {
        // Ignore Global Killswitch for adid.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        return SystemProperties.getBoolean(
                getSystemPropertyName(KEY_ADID_KILL_SWITCH),
                /* defaultValue */ DeviceConfig.getBoolean(
                        NAMESPACE_ADSERVICES,
                        /* flagName */ KEY_ADID_KILL_SWITCH,
                        /* defaultValue */ ADID_KILL_SWITCH));
    }

    // APPSETID Killswitch.
    @Override
    public boolean getAppSetIdKillSwitch() {
        // Ignore Global Killswitch for appsetid.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        return SystemProperties.getBoolean(
                getSystemPropertyName(KEY_APPSETID_KILL_SWITCH),
                /* defaultValue */ DeviceConfig.getBoolean(
                        NAMESPACE_ADSERVICES,
                        /* flagName */ KEY_APPSETID_KILL_SWITCH,
                        /* defaultValue */ APPSETID_KILL_SWITCH));
    }

    // TOPICS Killswitches
    @Override
    public boolean getTopicsKillSwitch() {
        // We check the Global Killswitch first. As a result, it overrides all other killswitches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        return getGlobalKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(KEY_TOPICS_KILL_SWITCH),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_TOPICS_KILL_SWITCH,
                                /* defaultValue */ TOPICS_KILL_SWITCH));
    }

    @Override
    public boolean getTopicsOnDeviceClassifierKillSwitch() {
        // We check the Global Killswitch first. As a result, it overrides all other killswitches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        return SystemProperties.getBoolean(
                getSystemPropertyName(KEY_TOPICS_ON_DEVICE_CLASSIFIER_KILL_SWITCH),
                /* defaultValue */ DeviceConfig.getBoolean(
                        NAMESPACE_ADSERVICES,
                        /* flagName */ KEY_TOPICS_ON_DEVICE_CLASSIFIER_KILL_SWITCH,
                        /* defaultValue */ TOPICS_ON_DEVICE_CLASSIFIER_KILL_SWITCH));
    }

    // MDD Killswitches
    @Override
    public boolean getMddBackgroundTaskKillSwitch() {
        // We check the Global Killswitch first. As a result, it overrides all other killswitches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        return getGlobalKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(KEY_MDD_BACKGROUND_TASK_KILL_SWITCH),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_MDD_BACKGROUND_TASK_KILL_SWITCH,
                                /* defaultValue */ MDD_BACKGROUND_TASK_KILL_SWITCH));
    }

    // MDD Logger Killswitches
    @Override
    public boolean getMddLoggerKillSwitch() {
        // We check the Global Killswitch first. As a result, it overrides all other killswitches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        return getGlobalKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(KEY_MDD_LOGGER_KILL_SWITCH),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_MDD_LOGGER_KILL_SWITCH,
                                /* defaultValue */ MDD_LOGGER_KILL_SWITCH));
    }

    // FLEDGE Kill switches

    @Override
    public boolean getFledgeSelectAdsKillSwitch() {
        // We check the Global Kill switch first. As a result, it overrides all other kill switches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        return getGlobalKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(KEY_FLEDGE_SELECT_ADS_KILL_SWITCH),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_FLEDGE_SELECT_ADS_KILL_SWITCH,
                                /* defaultValue */ FLEDGE_SELECT_ADS_KILL_SWITCH));
    }

    @Override
    public boolean getFledgeCustomAudienceServiceKillSwitch() {
        // We check the Global Kill switch first. As a result, it overrides all other kill switches.
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        return getGlobalKillSwitch()
                || SystemProperties.getBoolean(
                        getSystemPropertyName(KEY_FLEDGE_CUSTOM_AUDIENCE_SERVICE_KILL_SWITCH),
                        /* defaultValue */ DeviceConfig.getBoolean(
                                NAMESPACE_ADSERVICES,
                                /* flagName */ KEY_FLEDGE_CUSTOM_AUDIENCE_SERVICE_KILL_SWITCH,
                                /* defaultValue */ FLEDGE_CUSTOM_AUDIENCE_SERVICE_KILL_SWITCH));
    }

    @Override
    public String getPpapiAppAllowList() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getString(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_PPAPI_APP_ALLOW_LIST,
                /* defaultValue */ PPAPI_APP_ALLOW_LIST);
    }

    // AdServices APK SHA certs.
    @Override
    public String getAdservicesApkShaCertificate() {
        return DeviceConfig.getString(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_ADSERVICES_APK_SHA_CERTS,
                /* defaultValue */ ADSERVICES_APK_SHA_CERTIFICATE);
    }

    // PPAPI Signature allow-list.
    @Override
    public String getPpapiAppSignatureAllowList() {
        return DeviceConfig.getString(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_PPAPI_APP_SIGNATURE_ALLOW_LIST,
                /* defaultValue */ PPAPI_APP_SIGNATURE_ALLOW_LIST);
    }

    // Rate Limit Flags.
    @Override
    public float getSdkRequestPermitsPerSecond() {
        return getPermitsPerSecond(
                KEY_SDK_REQUEST_PERMITS_PER_SECOND, SDK_REQUEST_PERMITS_PER_SECOND);
    }

    @Override
    public float getAdIdRequestPermitsPerSecond() {
        return getPermitsPerSecond(
                KEY_ADID_REQUEST_PERMITS_PER_SECOND, ADID_REQUEST_PERMITS_PER_SECOND);
    }

    @Override
    public float getAppSetIdRequestPermitsPerSecond() {
        return getPermitsPerSecond(
                KEY_APPSETID_REQUEST_PERMITS_PER_SECOND, APPSETID_REQUEST_PERMITS_PER_SECOND);
    }

    @Override
    public float getMeasurementRegisterSourceRequestPermitsPerSecond() {
        return getPermitsPerSecond(
                KEY_MEASUREMENT_REGISTER_SOURCE_REQUEST_PERMITS_PER_SECOND,
                MEASUREMENT_REGISTER_SOURCE_REQUEST_PERMITS_PER_SECOND);
    }

    @Override
    public float getMeasurementRegisterWebSourceRequestPermitsPerSecond() {
        return getPermitsPerSecond(
                KEY_MEASUREMENT_REGISTER_WEB_SOURCE_REQUEST_PERMITS_PER_SECOND,
                MEASUREMENT_REGISTER_WEB_SOURCE_REQUEST_PERMITS_PER_SECOND);
    }

    @Override
    public float getTopicsApiAppRequestPermitsPerSecond() {
        return getPermitsPerSecond(
                KEY_TOPICS_API_APP_REQUEST_PERMITS_PER_SECOND,
                TOPICS_API_APP_REQUEST_PERMITS_PER_SECOND);
    }

    @Override
    public float getTopicsApiSdkRequestPermitsPerSecond() {
        return getPermitsPerSecond(
                KEY_TOPICS_API_SDK_REQUEST_PERMITS_PER_SECOND,
                TOPICS_API_SDK_REQUEST_PERMITS_PER_SECOND);
    }

    @Override
    public float getFledgeReportInteractionRequestPermitsPerSecond() {
        return getPermitsPerSecond(
                KEY_FLEDGE_REPORT_INTERACTION_REQUEST_PERMITS_PER_SECOND,
                FLEDGE_REPORT_INTERACTION_REQUEST_PERMITS_PER_SECOND);
    }

    private float getPermitsPerSecond(String flagName, float defaultValue) {
        // The priority of applying the flag values: SystemProperties, PH (DeviceConfig), then
        // hard-coded value.
        try {
            final String permitString = SystemProperties.get(getSystemPropertyName(flagName));
            if (!TextUtils.isEmpty(permitString)) {
                return parseFloat(permitString);
            }
        } catch (NumberFormatException e) {
            LogUtil.e(e, "Failed to parse %s", flagName);
            return defaultValue;
        }

        return DeviceConfig.getFloat(NAMESPACE_ADSERVICES, flagName, defaultValue);
    }

    @Override
    public String getUiOtaStringsManifestFileUrl() {
        return SystemProperties.get(
                getSystemPropertyName(KEY_UI_OTA_STRINGS_MANIFEST_FILE_URL),
                /* defaultValue */ DeviceConfig.getString(
                        NAMESPACE_ADSERVICES,
                        /* flagName */ KEY_UI_OTA_STRINGS_MANIFEST_FILE_URL,
                        /* defaultValue */ UI_OTA_STRINGS_MANIFEST_FILE_URL));
    }

    @Override
    public boolean getUiOtaStringsFeatureEnabled() {
        if (getGlobalKillSwitch()) {
            return false;
        }
        return SystemProperties.getBoolean(
                getSystemPropertyName(KEY_UI_OTA_STRINGS_FEATURE_ENABLED),
                /* defaultValue */ DeviceConfig.getBoolean(
                        NAMESPACE_ADSERVICES,
                        /* flagName */ KEY_UI_OTA_STRINGS_FEATURE_ENABLED,
                        /* defaultValue */ UI_OTA_STRINGS_FEATURE_ENABLED));
    }

    @Override
    public long getUiOtaStringsDownloadDeadline() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_UI_OTA_STRINGS_DOWNLOAD_DEADLINE,
                /* defaultValue */ UI_OTA_STRINGS_DOWNLOAD_DEADLINE);
    }

    @Override
    public boolean isUiFeatureTypeLoggingEnabled() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_UI_FEATURE_TYPE_LOGGING_ENABLED,
                /* defaultValue */ UI_FEATURE_TYPE_LOGGING_ENABLED);
    }

    @Override
    public boolean getAdServicesEnabled() {
        // if the global kill switch is enabled, feature should be disabled.
        if (getGlobalKillSwitch()) {
            return false;
        }
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_ADSERVICES_ENABLED,
                /* defaultValue */ ADSERVICES_ENABLED);
    }

    @Override
    public boolean getAdServicesErrorLoggingEnabled() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_ADSERVICES_ERROR_LOGGING_ENABLED,
                /* defaultValue */ ADSERVICES_ERROR_LOGGING_ENABLED);
    }

    @Override
    public int getNumberOfEpochsToKeepInHistory() {
        int numberOfEpochsToKeepInHistory =
                DeviceConfig.getInt(
                        NAMESPACE_ADSERVICES,
                        /* flagName */ KEY_NUMBER_OF_EPOCHS_TO_KEEP_IN_HISTORY,
                        /* defaultValue */ NUMBER_OF_EPOCHS_TO_KEEP_IN_HISTORY);

        if (numberOfEpochsToKeepInHistory < 1) {
            throw new IllegalArgumentException("numberOfEpochsToKeepInHistory should  >= 0");
        }

        return numberOfEpochsToKeepInHistory;
    }

    @Override
    public boolean getAdSelectionOffDeviceEnabled() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                KEY_FLEDGE_AD_SELECTION_OFF_DEVICE_ENABLED,
                FLEDGE_AD_SELECTION_OFF_DEVICE_ENABLED);
    }

    @Override
    public boolean getFledgeAdSelectionPrebuiltUriEnabled() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                KEY_FLEDGE_AD_SELECTION_PREBUILT_URI_ENABLED,
                FLEDGE_AD_SELECTION_PREBUILT_URI_ENABLED);
    }

    @Override
    public boolean getAdSelectionOffDeviceRequestCompressionEnabled() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                KEY_FLEDGE_AD_SELECTION_OFF_DEVICE_REQUEST_COMPRESSION_ENABLED,
                FLEDGE_AD_SELECTION_OFF_DEVICE_REQUEST_COMPRESSION_ENABLED);
    }

    @Override
    public boolean isDisableTopicsEnrollmentCheck() {
        return SystemProperties.getBoolean(
                getSystemPropertyName(KEY_DISABLE_TOPICS_ENROLLMENT_CHECK),
                /* defaultValue */ DeviceConfig.getBoolean(
                        NAMESPACE_ADSERVICES,
                        /* flagName */ KEY_DISABLE_TOPICS_ENROLLMENT_CHECK,
                        /* defaultValue */ DISABLE_TOPICS_ENROLLMENT_CHECK));
    } //

    @Override
    public boolean isDisableMeasurementEnrollmentCheck() {
        return SystemProperties.getBoolean(
                getSystemPropertyName(KEY_DISABLE_MEASUREMENT_ENROLLMENT_CHECK),
                /* defaultValue */ DeviceConfig.getBoolean(
                        NAMESPACE_ADSERVICES,
                        /* flagName */ KEY_DISABLE_MEASUREMENT_ENROLLMENT_CHECK,
                        /* defaultValue */ DISABLE_MEASUREMENT_ENROLLMENT_CHECK));
    }

    @Override
    public boolean isEnableEnrollmentTestSeed() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_ENABLE_ENROLLMENT_TEST_SEED,
                /* defaultValue */ ENABLE_ENROLLMENT_TEST_SEED);
    }

    @Override
    public boolean getDisableFledgeEnrollmentCheck() {
        return SystemProperties.getBoolean(
                getSystemPropertyName(KEY_DISABLE_FLEDGE_ENROLLMENT_CHECK),
                /* defaultValue */ DeviceConfig.getBoolean(
                        NAMESPACE_ADSERVICES,
                        /* flagName */ KEY_DISABLE_FLEDGE_ENROLLMENT_CHECK,
                        /* defaultValue */ DISABLE_FLEDGE_ENROLLMENT_CHECK));
    }

    @Override
    public boolean getEnforceForegroundStatusForTopics() {
        return SystemProperties.getBoolean(
                getSystemPropertyName(KEY_ENFORCE_FOREGROUND_STATUS_TOPICS),
                /* defaultValue */ DeviceConfig.getBoolean(
                        NAMESPACE_ADSERVICES,
                        /* flagName */ KEY_ENFORCE_FOREGROUND_STATUS_TOPICS,
                        /* defaultValue */ ENFORCE_FOREGROUND_STATUS_TOPICS));
    }

    @Override
    public boolean getEnforceForegroundStatusForAdId() {
        return SystemProperties.getBoolean(
                getSystemPropertyName(KEY_ENFORCE_FOREGROUND_STATUS_ADID),
                /* defaultValue */ DeviceConfig.getBoolean(
                        NAMESPACE_ADSERVICES,
                        /* flagName */ KEY_ENFORCE_FOREGROUND_STATUS_ADID,
                        /* defaultValue */ ENFORCE_FOREGROUND_STATUS_ADID));
    }

    @Override
    public boolean getEnforceForegroundStatusForAppSetId() {
        return SystemProperties.getBoolean(
                getSystemPropertyName(KEY_ENFORCE_FOREGROUND_STATUS_APPSETID),
                /* defaultValue */ DeviceConfig.getBoolean(
                        NAMESPACE_ADSERVICES,
                        /* flagName */ KEY_ENFORCE_FOREGROUND_STATUS_APPSETID,
                        /* defaultValue */ ENFORCE_FOREGROUND_STATUS_APPSETID));
    }

    @Override
    public boolean getEnforceForegroundStatusForFledgeRunAdSelection() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_RUN_AD_SELECTION,
                /* defaultValue */ ENFORCE_FOREGROUND_STATUS_FLEDGE_RUN_AD_SELECTION);
    }

    @Override
    public boolean getEnforceForegroundStatusForFledgeReportImpression() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_REPORT_IMPRESSION,
                /* defaultValue */ ENFORCE_FOREGROUND_STATUS_FLEDGE_REPORT_IMPRESSION);
    }

    @Override
    public boolean getEnforceForegroundStatusForFledgeReportInteraction() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_REPORT_INTERACTION,
                /* defaultValue */ ENFORCE_FOREGROUND_STATUS_FLEDGE_REPORT_INTERACTION);
    }

    @Override
    public int getForegroundStatuslLevelForValidation() {
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FOREGROUND_STATUS_LEVEL,
                /* defaultValue */ FOREGROUND_STATUS_LEVEL);
    }

    @Override
    public boolean getEnforceForegroundStatusForFledgeOverrides() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_OVERRIDE,
                /* defaultValue */ ENFORCE_FOREGROUND_STATUS_FLEDGE_OVERRIDES);
    }

    @Override
    public boolean getEnforceForegroundStatusForFledgeCustomAudience() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_CUSTOM_AUDIENCE,
                /* defaultValue */ ENFORCE_FOREGROUND_STATUS_FLEDGE_CUSTOM_AUDIENCE);
    }

    @Override
    public boolean getFledgeRegisterAdBeaconEnabled() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_REGISTER_AD_BEACON_ENABLED,
                /* defaultValue */ FLEDGE_REGISTER_AD_BEACON_ENABLED);
    }

    @Override
    public boolean getEnforceForegroundStatusForMeasurementDeleteRegistrations() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_ENFORCE_FOREGROUND_STATUS_DELETE_REGISTRATIONS,
                /* defaultValue */ MEASUREMENT_ENFORCE_FOREGROUND_STATUS_DELETE_REGISTRATIONS);
    }

    @Override
    public boolean getEnforceForegroundStatusForMeasurementRegisterSource() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_ENFORCE_FOREGROUND_STATUS_REGISTER_SOURCE,
                /* defaultValue */ MEASUREMENT_ENFORCE_FOREGROUND_STATUS_REGISTER_SOURCE);
    }

    @Override
    public boolean getEnforceForegroundStatusForMeasurementRegisterTrigger() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_ENFORCE_FOREGROUND_STATUS_REGISTER_TRIGGER,
                /* defaultValue */ MEASUREMENT_ENFORCE_FOREGROUND_STATUS_REGISTER_TRIGGER);
    }

    @Override
    public boolean getEnforceForegroundStatusForMeasurementRegisterWebSource() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_ENFORCE_FOREGROUND_STATUS_REGISTER_WEB_SOURCE,
                /* defaultValue */ MEASUREMENT_ENFORCE_FOREGROUND_STATUS_REGISTER_WEB_SOURCE);
    }

    @Override
    public boolean getEnforceForegroundStatusForMeasurementRegisterWebTrigger() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_ENFORCE_FOREGROUND_STATUS_REGISTER_WEB_TRIGGER,
                /* defaultValue */ MEASUREMENT_ENFORCE_FOREGROUND_STATUS_REGISTER_WEB_TRIGGER);
    }

    @Override
    public boolean getEnforceForegroundStatusForMeasurementStatus() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_ENFORCE_FOREGROUND_STATUS_GET_STATUS,
                /* defaultValue */ MEASUREMENT_ENFORCE_FOREGROUND_STATUS_GET_STATUS);
    }

    @Override
    public boolean getEnforceEnrollmentOriginMatch() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_ENFORCE_ENROLLMENT_ORIGIN_MATCH,
                /* defaultValue */ MEASUREMENT_ENFORCE_ENROLLMENT_ORIGIN_MATCH);
    }

    @Override
    public boolean getEnforceIsolateMaxHeapSize() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_ENFORCE_ISOLATE_MAX_HEAP_SIZE,
                /* defaultValue */ ENFORCE_ISOLATE_MAX_HEAP_SIZE);
    }

    @Override
    public long getIsolateMaxHeapSizeBytes() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_ISOLATE_MAX_HEAP_SIZE_BYTES,
                /* defaultValue */ ISOLATE_MAX_HEAP_SIZE_BYTES);
    }

    @Override
    public String getWebContextClientAppAllowList() {
        return DeviceConfig.getString(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_WEB_CONTEXT_CLIENT_ALLOW_LIST,
                /* defaultValue */ WEB_CONTEXT_CLIENT_ALLOW_LIST);
    }

    @Override
    public long getConsentNotificationIntervalBeginMs() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_CONSENT_NOTIFICATION_INTERVAL_BEGIN_MS,
                /* defaultValue */ CONSENT_NOTIFICATION_INTERVAL_BEGIN_MS);
    }

    @Override
    public long getConsentNotificationIntervalEndMs() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_CONSENT_NOTIFICATION_INTERVAL_END_MS,
                /* defaultValue */ CONSENT_NOTIFICATION_INTERVAL_END_MS);
    }

    @Override
    public long getConsentNotificationMinimalDelayBeforeIntervalEnds() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_CONSENT_NOTIFICATION_MINIMAL_DELAY_BEFORE_INTERVAL_ENDS,
                /* defaultValue */ CONSENT_NOTIFICATION_MINIMAL_DELAY_BEFORE_INTERVAL_ENDS);
    }

    @Override
    public boolean getConsentNotificationDebugMode() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_CONSENT_NOTIFICATION_DEBUG_MODE,
                /* defaultValue */ CONSENT_NOTIFICATION_DEBUG_MODE);
    }

    @Override
    public boolean getConsentManagerDebugMode() {
        return SystemProperties.getBoolean(
                getSystemPropertyName(KEY_CONSENT_MANAGER_DEBUG_MODE),
                /* defaultValue */ CONSENT_MANAGER_DEBUG_MODE);
    }

    @Override
    public int getConsentSourceOfTruth() {
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_CONSENT_SOURCE_OF_TRUTH,
                /* defaultValue */ DEFAULT_CONSENT_SOURCE_OF_TRUTH);
    }

    @Override
    public int getBlockedTopicsSourceOfTruth() {
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_BLOCKED_TOPICS_SOURCE_OF_TRUTH,
                /* defaultValue */ DEFAULT_BLOCKED_TOPICS_SOURCE_OF_TRUTH);
    }

    @Override
    public long getMaxResponseBasedRegistrationPayloadSizeBytes() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MAX_RESPONSE_BASED_REGISTRATION_SIZE_BYTES,
                /* defaultValue */ MAX_RESPONSE_BASED_REGISTRATION_SIZE_BYTES);
    }

    @VisibleForTesting
    static String getSystemPropertyName(String key) {
        return SYSTEM_PROPERTY_PREFIX + key;
    }

    @Override
    public boolean getUIDialogsFeatureEnabled() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return SystemProperties.getBoolean(
                getSystemPropertyName(KEY_UI_DIALOGS_FEATURE_ENABLED),
                /* defaultValue */ DeviceConfig.getBoolean(
                        NAMESPACE_ADSERVICES,
                        /* flagName */ KEY_UI_DIALOGS_FEATURE_ENABLED,
                        /* defaultValue */ UI_DIALOGS_FEATURE_ENABLED));
    }

    @Override
    public boolean getUiDialogFragmentEnabled() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return SystemProperties.getBoolean(
                getSystemPropertyName(KEY_UI_DIALOG_FRAGMENT_ENABLED),
                /* defaultValue */ DeviceConfig.getBoolean(
                        NAMESPACE_ADSERVICES,
                        /* flagName */ KEY_UI_DIALOG_FRAGMENT_ENABLED,
                        /* defaultValue */ UI_DIALOG_FRAGMENT));
    }

    @Override
    public boolean isEeaDeviceFeatureEnabled() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                KEY_IS_EEA_DEVICE_FEATURE_ENABLED,
                IS_EEA_DEVICE_FEATURE_ENABLED);
    }

    @Override
    public boolean isEeaDevice() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getBoolean(NAMESPACE_ADSERVICES, KEY_IS_EEA_DEVICE, IS_EEA_DEVICE);
    }

    @Override
    public boolean getRecordManualInteractionEnabled() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                KEY_RECORD_MANUAL_INTERACTION_ENABLED,
                RECORD_MANUAL_INTERACTION_ENABLED);
    }

    @Override
    public boolean isBackCompatActivityFeatureEnabled() {
        // Check if enable Back compat is true first and then check flag value
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return getEnableBackCompat()
                && DeviceConfig.getBoolean(
                        DeviceConfig.NAMESPACE_ADSERVICES,
                        /* flagName */ KEY_IS_BACK_COMPACT_ACTIVITY_FEATURE_ENABLED,
                        /* defaultValue */ IS_BACK_COMPACT_ACTIVITY_FEATURE_ENABLED);
    }

    @Override
    public String getUiEeaCountries() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        String uiEeaCountries =
                DeviceConfig.getString(
                        NAMESPACE_ADSERVICES, KEY_UI_EEA_COUNTRIES, UI_EEA_COUNTRIES);
        return uiEeaCountries;
    }

    @Override
    public boolean getGaUxFeatureEnabled() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return SystemProperties.getBoolean(
                getSystemPropertyName(KEY_GA_UX_FEATURE_ENABLED),
                /* defaultValue */ DeviceConfig.getBoolean(
                        NAMESPACE_ADSERVICES,
                        /* flagName */ KEY_GA_UX_FEATURE_ENABLED,
                        /* defaultValue */ GA_UX_FEATURE_ENABLED));
    }

    @Override
    public long getAdSelectionExpirationWindowS() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_FLEDGE_AD_SELECTION_EXPIRATION_WINDOW_S,
                /* defaultValue */ FLEDGE_AD_SELECTION_EXPIRATION_WINDOW_S);
    }

    @Override
    public boolean getMeasurementFlexibleEventReportingAPIEnabled() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_FLEXIBLE_EVENT_REPORTING_API_ENABLED,
                /* defaultValue */ MEASUREMENT_FLEXIBLE_EVENT_REPORTING_API_ENABLED);
    }

    @Override
    public int getMeasurementMaxSourcesPerPublisher() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_MAX_SOURCES_PER_PUBLISHER,
                /* defaultValue */ MEASUREMENT_MAX_SOURCES_PER_PUBLISHER);
    }

    @Override
    public int getMeasurementMaxTriggersPerDestination() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_MAX_TRIGGERS_PER_DESTINATION,
                /* defaultValue */ MEASUREMENT_MAX_TRIGGERS_PER_DESTINATION);
    }

    @Override
    public int getMeasurementMaxAggregateReportsPerDestination() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_MAX_AGGREGATE_REPORTS_PER_DESTINATION,
                /* defaultValue */ MEASUREMENT_MAX_AGGREGATE_REPORTS_PER_DESTINATION);
    }

    @Override
    public int getMeasurementMaxEventReportsPerDestination() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_MAX_EVENT_REPORTS_PER_DESTINATION,
                /* defaultValue */ MEASUREMENT_MAX_EVENT_REPORTS_PER_DESTINATION);
    }

    @Override
    public boolean getMeasurementEnableConfigurableEventReportingWindows() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_ENABLE_CONFIGURABLE_EVENT_REPORTING_WINDOWS,
                /* defaultValue */ MEASUREMENT_ENABLE_CONFIGURABLE_EVENT_REPORTING_WINDOWS);
    }

    @Override
    public String getMeasurementEventReportsVtcEarlyReportingWindows() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getString(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_EVENT_REPORTS_VTC_EARLY_REPORTING_WINDOWS,
                /* defaultValue */ MEASUREMENT_EVENT_REPORTS_VTC_EARLY_REPORTING_WINDOWS);
    }

    @Override
    public String getMeasurementEventReportsCtcEarlyReportingWindows() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getString(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_EVENT_REPORTS_CTC_EARLY_REPORTING_WINDOWS,
                /* defaultValue */ MEASUREMENT_EVENT_REPORTS_CTC_EARLY_REPORTING_WINDOWS);
    }

    @Override
    public boolean isEnrollmentBlocklisted(String enrollmentId) {
        return getEnrollmentBlocklist().contains(enrollmentId);
    }

    @Override
    public void dump(@NonNull PrintWriter writer, @Nullable String[] args) {
        writer.println(
                "\t" + KEY_ENABLE_AD_SERVICES_SYSTEM_API + " = " + getEnableAdServicesSystemApi());
        writer.println("\t" + KEY_U18_UX_ENABLED + " = " + getU18UxEnabled());
        writer.println("==== AdServices PH Flags Dump Enrollment ====");
        writer.println(
                "\t"
                        + KEY_DISABLE_TOPICS_ENROLLMENT_CHECK
                        + " = "
                        + isDisableTopicsEnrollmentCheck());
        writer.println(
                "\t"
                        + KEY_DISABLE_FLEDGE_ENROLLMENT_CHECK
                        + " = "
                        + getDisableFledgeEnrollmentCheck());
        writer.println(
                "\t"
                        + KEY_DISABLE_MEASUREMENT_ENROLLMENT_CHECK
                        + " = "
                        + isDisableMeasurementEnrollmentCheck());

        writer.println(
                "\t" + KEY_ENABLE_ENROLLMENT_TEST_SEED + " = " + isEnableEnrollmentTestSeed());

        writer.println("==== AdServices PH Flags Dump killswitches ====");
        writer.println("\t" + KEY_GLOBAL_KILL_SWITCH + " = " + getGlobalKillSwitch());
        writer.println("\t" + KEY_TOPICS_KILL_SWITCH + " = " + getTopicsKillSwitch());
        writer.println(
                "\t"
                        + KEY_TOPICS_ON_DEVICE_CLASSIFIER_KILL_SWITCH
                        + " = "
                        + getTopicsOnDeviceClassifierKillSwitch());
        writer.println("\t" + KEY_ADID_KILL_SWITCH + " = " + getAdIdKillSwitch());
        writer.println("\t" + KEY_APPSETID_KILL_SWITCH + " = " + getAppSetIdKillSwitch());
        writer.println(
                "\t"
                        + KEY_SDK_REQUEST_PERMITS_PER_SECOND
                        + " = "
                        + getSdkRequestPermitsPerSecond());

        writer.println(
                "\t"
                        + KEY_MDD_BACKGROUND_TASK_KILL_SWITCH
                        + " = "
                        + getMddBackgroundTaskKillSwitch());
        writer.println("\t" + KEY_MDD_LOGGER_KILL_SWITCH + " = " + getMddLoggerKillSwitch());

        writer.println("==== AdServices PH Flags Dump AllowList ====");
        writer.println(
                "\t"
                        + KEY_PPAPI_APP_SIGNATURE_ALLOW_LIST
                        + " = "
                        + getPpapiAppSignatureAllowList());
        writer.println("\t" + KEY_PPAPI_APP_ALLOW_LIST + " = " + getPpapiAppAllowList());

        writer.println("==== AdServices PH Flags Dump MDD related flags: ====");
        writer.println(
                "\t" + KEY_MEASUREMENT_MANIFEST_FILE_URL + " = " + getMeasurementManifestFileUrl());
        writer.println(
                "\t"
                        + KEY_UI_OTA_STRINGS_MANIFEST_FILE_URL
                        + " = "
                        + getUiOtaStringsManifestFileUrl());
        writer.println(
                "\t"
                        + KEY_DOWNLOADER_CONNECTION_TIMEOUT_MS
                        + " = "
                        + getDownloaderConnectionTimeoutMs());
        writer.println(
                "\t" + KEY_DOWNLOADER_READ_TIMEOUT_MS + " = " + getDownloaderReadTimeoutMs());
        writer.println(
                "\t"
                        + KEY_DOWNLOADER_MAX_DOWNLOAD_THREADS
                        + " = "
                        + getDownloaderMaxDownloadThreads());
        writer.println(
                "\t"
                        + KEY_MDD_TOPICS_CLASSIFIER_MANIFEST_FILE_URL
                        + " = "
                        + getMddTopicsClassifierManifestFileUrl());
        writer.println("==== AdServices PH Flags Dump Topics related flags ====");
        writer.println("\t" + KEY_TOPICS_EPOCH_JOB_PERIOD_MS + " = " + getTopicsEpochJobPeriodMs());
        writer.println("\t" + KEY_TOPICS_EPOCH_JOB_FLEX_MS + " = " + getTopicsEpochJobFlexMs());
        writer.println(
                "\t"
                        + KEY_TOPICS_PERCENTAGE_FOR_RANDOM_TOPIC
                        + " = "
                        + getTopicsPercentageForRandomTopic());
        writer.println(
                "\t" + KEY_TOPICS_NUMBER_OF_TOP_TOPICS + " = " + getTopicsNumberOfTopTopics());
        writer.println(
                "\t"
                        + KEY_TOPICS_NUMBER_OF_RANDOM_TOPICS
                        + " = "
                        + getTopicsNumberOfRandomTopics());
        writer.println(
                "\t"
                        + KEY_TOPICS_NUMBER_OF_LOOK_BACK_EPOCHS
                        + " = "
                        + getTopicsNumberOfLookBackEpochs());
        writer.println("\t" + KEY_GLOBAL_BLOCKED_TOPIC_IDS + " = " + getGlobalBlockedTopicIds());

        writer.println("==== AdServices PH Flags Dump Topics Classifier related flags ====");
        writer.println(
                "\t"
                        + KEY_CLASSIFIER_NUMBER_OF_TOP_LABELS
                        + " = "
                        + getClassifierNumberOfTopLabels());
        writer.println("\t" + KEY_CLASSIFIER_TYPE + " = " + getClassifierType());
        writer.println("\t" + KEY_CLASSIFIER_THRESHOLD + " = " + getClassifierThreshold());
        writer.println(
                "\t"
                        + KEY_CLASSIFIER_DESCRIPTION_MAX_LENGTH
                        + " = "
                        + getClassifierDescriptionMaxLength());
        writer.println(
                "\t"
                        + KEY_CLASSIFIER_DESCRIPTION_MAX_WORDS
                        + " = "
                        + getClassifierDescriptionMaxWords());
        writer.println(
                "\t"
                        + KEY_CLASSIFIER_FORCE_USE_BUNDLED_FILES
                        + " = "
                        + getClassifierForceUseBundledFiles());

        writer.println(
                "\t"
                        + KEY_ENFORCE_FOREGROUND_STATUS_TOPICS
                        + " = "
                        + getEnforceForegroundStatusForTopics());

        writer.println("==== AdServices PH Flags Dump Measurement related flags: ====");
        writer.println("\t" + KEY_MEASUREMENT_DB_SIZE_LIMIT + " = " + getMeasurementDbSizeLimit());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_EVENT_MAIN_REPORTING_JOB_PERIOD_MS
                        + " = "
                        + getMeasurementEventMainReportingJobPeriodMs());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_EVENT_FALLBACK_REPORTING_JOB_PERIOD_MS
                        + " = "
                        + getMeasurementEventFallbackReportingJobPeriodMs());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_AGGREGATE_ENCRYPTION_KEY_COORDINATOR_URL
                        + " = "
                        + getMeasurementAggregateEncryptionKeyCoordinatorUrl());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_AGGREGATE_MAIN_REPORTING_JOB_PERIOD_MS
                        + " = "
                        + getMeasurementAggregateMainReportingJobPeriodMs());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_AGGREGATE_FALLBACK_REPORTING_JOB_PERIOD_MS
                        + " = "
                        + getMeasurementAggregateFallbackReportingJobPeriodMs());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_NETWORK_CONNECT_TIMEOUT_MS
                        + " = "
                        + getMeasurementNetworkConnectTimeoutMs());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_NETWORK_READ_TIMEOUT_MS
                        + " = "
                        + getMeasurementNetworkReadTimeoutMs());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_IS_CLICK_VERIFICATION_ENABLED
                        + " = "
                        + getMeasurementIsClickVerificationEnabled());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_REGISTRATION_INPUT_EVENT_VALID_WINDOW_MS
                        + " = "
                        + getMeasurementRegistrationInputEventValidWindowMs());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_ENFORCE_FOREGROUND_STATUS_DELETE_REGISTRATIONS
                        + " = "
                        + getEnforceForegroundStatusForMeasurementDeleteRegistrations());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_ENFORCE_FOREGROUND_STATUS_GET_STATUS
                        + " = "
                        + getEnforceForegroundStatusForMeasurementStatus());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_ENFORCE_FOREGROUND_STATUS_REGISTER_SOURCE
                        + " = "
                        + getEnforceForegroundStatusForMeasurementRegisterSource());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_ENFORCE_FOREGROUND_STATUS_REGISTER_TRIGGER
                        + " = "
                        + getEnforceForegroundStatusForMeasurementRegisterTrigger());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_ENFORCE_FOREGROUND_STATUS_REGISTER_WEB_SOURCE
                        + " = "
                        + getEnforceForegroundStatusForMeasurementRegisterWebSource());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_ENFORCE_FOREGROUND_STATUS_REGISTER_WEB_TRIGGER
                        + " = "
                        + getEnforceForegroundStatusForMeasurementRegisterWebTrigger());
        writer.println("\t" + KEY_MEASUREMENT_ENABLE_XNA + " = " + getMeasurementEnableXNA());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_ENFORCE_ENROLLMENT_ORIGIN_MATCH
                        + " = "
                        + getEnforceEnrollmentOriginMatch());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_ENABLE_DEBUG_REPORT
                        + " = "
                        + getMeasurementEnableDebugReport());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_ENABLE_SOURCE_DEBUG_REPORT
                        + " = "
                        + getMeasurementEnableSourceDebugReport());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_ENABLE_TRIGGER_DEBUG_REPORT
                        + " = "
                        + getMeasurementEnableTriggerDebugReport());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_DATA_EXPIRY_WINDOW_MS
                        + " = "
                        + getMeasurementDataExpiryWindowMs());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_ROLLBACK_DELETION_KILL_SWITCH
                        + " = "
                        + getMeasurementRollbackDeletionKillSwitch());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_DEBUG_JOIN_KEY_HASH_LIMIT
                        + " = "
                        + getMeasurementDebugJoinKeyHashLimit());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_DEBUG_KEY_AD_ID_MATCHING_LIMIT
                        + " = "
                        + getMeasurementPlatformDebugAdIdMatchingLimit());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_DEBUG_KEY_AD_ID_MATCHING_ENROLLMENT_BLOCKLIST
                        + " = "
                        + getMeasurementPlatformDebugAdIdMatchingEnrollmentBlocklist());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_FLEXIBLE_EVENT_REPORTING_API_ENABLED
                        + " = "
                        + getMeasurementFlexibleEventReportingAPIEnabled());
        writer.println(
                "\t"
                        + KEY_WEB_CONTEXT_CLIENT_ALLOW_LIST
                        + " = "
                        + getWebContextClientAppAllowList());
        writer.println(
                "\t"
                        + KEY_MAX_RESPONSE_BASED_REGISTRATION_SIZE_BYTES
                        + " = "
                        + getMaxResponseBasedRegistrationPayloadSizeBytes());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_MAX_REGISTRATION_REDIRECTS
                        + " = "
                        + getMeasurementMaxRegistrationRedirects());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_MAX_REGISTRATIONS_PER_JOB_INVOCATION
                        + " = "
                        + getMeasurementMaxRegistrationsPerJobInvocation());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_MAX_RETRIES_PER_REGISTRATION_REQUEST
                        + " = "
                        + getMeasurementMaxRetriesPerRegistrationRequest());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_REGISTRATION_JOB_TRIGGER_DELAY_MS
                        + " = "
                        + getMeasurementRegistrationJobTriggerDelayMs());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_REGISTRATION_JOB_TRIGGER_MAX_DELAY_MS
                        + " = "
                        + getMeasurementRegistrationJobTriggerMaxDelayMs());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_REGISTRATION_JOB_QUEUE_KILL_SWITCH
                        + " = "
                        + getAsyncRegistrationJobQueueKillSwitch());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_REGISTRATION_FALLBACK_JOB_KILL_SWITCH
                        + " = "
                        + getAsyncRegistrationFallbackJobKillSwitch());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_ATTRIBUTION_FALLBACK_JOB_KILL_SWITCH
                        + " = "
                        + getMeasurementAttributionFallbackJobKillSwitch());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_ATTRIBUTION_FALLBACK_JOB_PERIOD_MS
                        + " = "
                        + getMeasurementAttributionFallbackJobPeriodMs());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_MAX_SOURCES_PER_PUBLISHER
                        + " = "
                        + getMeasurementMaxSourcesPerPublisher());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_MAX_TRIGGERS_PER_DESTINATION
                        + " = "
                        + getMeasurementMaxTriggersPerDestination());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_MAX_AGGREGATE_REPORTS_PER_DESTINATION
                        + " = "
                        + getMeasurementMaxAggregateReportsPerDestination());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_MAX_EVENT_REPORTS_PER_DESTINATION
                        + " = "
                        + getMeasurementMaxEventReportsPerDestination());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_ENABLE_CONFIGURABLE_EVENT_REPORTING_WINDOWS
                        + " = "
                        + getMeasurementEnableConfigurableEventReportingWindows());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_EVENT_REPORTS_VTC_EARLY_REPORTING_WINDOWS
                        + " = "
                        + getMeasurementEventReportsVtcEarlyReportingWindows());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_EVENT_REPORTS_CTC_EARLY_REPORTING_WINDOWS
                        + " = "
                        + getMeasurementEventReportsCtcEarlyReportingWindows());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_MAX_ATTRIBUTION_PER_RATE_LIMIT_WINDOW
                        + " = "
                        + getMeasurementMaxAttributionPerRateLimitWindow());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_MAX_DISTINCT_ENROLLMENTS_IN_ATTRIBUTION
                        + " = "
                        + getMeasurementMaxDistinctEnrollmentsInAttribution());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_MAX_DISTINCT_DESTINATIONS_IN_ACTIVE_SOURCE
                        + " = "
                        + getMeasurementMaxDistinctDestinationsInActiveSource());

        writer.println(
                "\t"
                        + KEY_MEASUREMENT_ENABLE_COARSE_EVENT_REPORT_DESTINATIONS
                        + " = "
                        + getMeasurementEnableCoarseEventReportDestinations());
        writer.println("==== AdServices PH Flags Dump FLEDGE related flags: ====");
        writer.println(
                "\t" + KEY_FLEDGE_SELECT_ADS_KILL_SWITCH + " = " + getFledgeSelectAdsKillSwitch());
        writer.println(
                "\t"
                        + KEY_FLEDGE_CUSTOM_AUDIENCE_SERVICE_KILL_SWITCH
                        + " = "
                        + getFledgeCustomAudienceServiceKillSwitch());
        writer.println(
                "\t"
                        + KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_COUNT
                        + " = "
                        + getFledgeCustomAudienceMaxCount());
        writer.println(
                "\t"
                        + KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_OWNER_COUNT
                        + " = "
                        + getFledgeCustomAudienceMaxOwnerCount());
        writer.println(
                "\t"
                        + KEY_FLEDGE_CUSTOM_AUDIENCE_PER_APP_MAX_COUNT
                        + " = "
                        + getFledgeCustomAudiencePerAppMaxCount());
        writer.println(
                "\t"
                        + KEY_FLEDGE_CUSTOM_AUDIENCE_DEFAULT_EXPIRE_IN_MS
                        + " = "
                        + getFledgeCustomAudienceDefaultExpireInMs());
        writer.println(
                "\t"
                        + KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_ACTIVATION_DELAY_IN_MS
                        + " = "
                        + getFledgeCustomAudienceMaxActivationDelayInMs());
        writer.println(
                "\t"
                        + KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_EXPIRE_IN_MS
                        + " = "
                        + getFledgeCustomAudienceMaxExpireInMs());
        writer.println(
                "\t"
                        + KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_NAME_SIZE_B
                        + " = "
                        + getFledgeCustomAudienceMaxNameSizeB());
        writer.println(
                "\t"
                        + KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_DAILY_UPDATE_URI_SIZE_B
                        + " = "
                        + getFledgeCustomAudienceMaxDailyUpdateUriSizeB());
        writer.println(
                "\t"
                        + KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_BIDDING_LOGIC_URI_SIZE_B
                        + " = "
                        + getFledgeCustomAudienceMaxBiddingLogicUriSizeB());
        writer.println(
                "\t"
                        + KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_USER_BIDDING_SIGNALS_SIZE_B
                        + " = "
                        + getFledgeCustomAudienceMaxUserBiddingSignalsSizeB());
        writer.println(
                "\t"
                        + KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_TRUSTED_BIDDING_DATA_SIZE_B
                        + " = "
                        + getFledgeCustomAudienceMaxTrustedBiddingDataSizeB());
        writer.println(
                "\t"
                        + KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_ADS_SIZE_B
                        + " = "
                        + getFledgeCustomAudienceMaxAdsSizeB());
        writer.println(
                "\t"
                        + KEY_FLEDGE_CUSTOM_AUDIENCE_ACTIVE_TIME_WINDOW_MS
                        + " = "
                        + getFledgeCustomAudienceActiveTimeWindowInMs());
        writer.println(
                "\t"
                        + KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_NUM_ADS
                        + " = "
                        + getFledgeCustomAudienceMaxNumAds());
        writer.println("\t" + KEY_FLEDGE_HTTP_CACHE_ENABLE + " = " + getFledgeHttpCachingEnabled());
        writer.println(
                "\t"
                        + KEY_FLEDGE_HTTP_CACHE_ENABLE_JS_CACHING
                        + " = "
                        + getFledgeHttpJsCachingEnabled());
        writer.println(
                "\t" + KEY_FLEDGE_HTTP_CACHE_MAX_ENTRIES + " = " + getFledgeHttpCacheMaxEntries());
        writer.println(
                "\t"
                        + KEY_FLEDGE_HTTP_CACHE_DEFAULT_MAX_AGE_SECONDS
                        + " = "
                        + getFledgeHttpCacheMaxAgeSeconds());
        writer.println(
                "\t"
                        + KEY_FLEDGE_AD_COUNTER_HISTOGRAM_ABSOLUTE_MAX_EVENT_COUNT
                        + " = "
                        + getFledgeAdCounterHistogramAbsoluteMaxEventCount());
        writer.println(
                "\t"
                        + KEY_FLEDGE_AD_COUNTER_HISTOGRAM_LOWER_MAX_EVENT_COUNT
                        + " = "
                        + getFledgeAdCounterHistogramLowerMaxEventCount());
        writer.println(
                "\t"
                        + KEY_FLEDGE_BACKGROUND_FETCH_ENABLED
                        + " = "
                        + getFledgeBackgroundFetchEnabled());
        writer.println(
                "\t"
                        + KEY_FLEDGE_BACKGROUND_FETCH_JOB_PERIOD_MS
                        + " = "
                        + getFledgeBackgroundFetchJobPeriodMs());
        writer.println(
                "\t"
                        + KEY_FLEDGE_BACKGROUND_FETCH_JOB_FLEX_MS
                        + " = "
                        + getFledgeBackgroundFetchJobFlexMs());
        writer.println(
                "\t"
                        + KEY_FLEDGE_BACKGROUND_FETCH_MAX_NUM_UPDATED
                        + " = "
                        + getFledgeBackgroundFetchMaxNumUpdated());
        writer.println(
                "\t"
                        + KEY_FLEDGE_BACKGROUND_FETCH_THREAD_POOL_SIZE
                        + " = "
                        + getFledgeBackgroundFetchThreadPoolSize());
        writer.println(
                "\t"
                        + KEY_FLEDGE_BACKGROUND_FETCH_ELIGIBLE_UPDATE_BASE_INTERVAL_S
                        + " = "
                        + getFledgeBackgroundFetchEligibleUpdateBaseIntervalS());
        writer.println(
                "\t"
                        + KEY_FLEDGE_BACKGROUND_FETCH_NETWORK_CONNECT_TIMEOUT_MS
                        + " = "
                        + getFledgeBackgroundFetchNetworkConnectTimeoutMs());
        writer.println(
                "\t"
                        + KEY_FLEDGE_BACKGROUND_FETCH_NETWORK_READ_TIMEOUT_MS
                        + " = "
                        + getFledgeBackgroundFetchNetworkReadTimeoutMs());
        writer.println(
                "\t"
                        + KEY_FLEDGE_BACKGROUND_FETCH_MAX_RESPONSE_SIZE_B
                        + " = "
                        + getFledgeBackgroundFetchMaxResponseSizeB());
        writer.println(
                "\t"
                        + KEY_FLEDGE_AD_SELECTION_MAX_CONCURRENT_BIDDING_COUNT
                        + " = "
                        + getAdSelectionMaxConcurrentBiddingCount());
        writer.println(
                "\t"
                        + KEY_FLEDGE_AD_SELECTION_BIDDING_TIMEOUT_PER_CA_MS
                        + " = "
                        + getAdSelectionBiddingTimeoutPerCaMs());
        writer.println(
                "\t"
                        + KEY_FLEDGE_AD_SELECTION_BIDDING_TIMEOUT_PER_BUYER_MS
                        + " = "
                        + getAdSelectionBiddingTimeoutPerBuyerMs());
        writer.println(
                "\t"
                        + KEY_FLEDGE_AD_SELECTION_SCORING_TIMEOUT_MS
                        + " = "
                        + getAdSelectionScoringTimeoutMs());
        writer.println(
                "\t"
                        + KEY_FLEDGE_AD_SELECTION_SELECTING_OUTCOME_TIMEOUT_MS
                        + " = "
                        + getAdSelectionSelectingOutcomeTimeoutMs());
        writer.println(
                "\t"
                        + KEY_FLEDGE_AD_SELECTION_OVERALL_TIMEOUT_MS
                        + " = "
                        + getAdSelectionOverallTimeoutMs());
        writer.println(
                "\t"
                        + KEY_FLEDGE_AD_SELECTION_FROM_OUTCOMES_OVERALL_TIMEOUT_MS
                        + " = "
                        + getAdSelectionFromOutcomesOverallTimeoutMs());
        writer.println(
                "\t"
                        + KEY_FLEDGE_AD_SELECTION_OFF_DEVICE_OVERALL_TIMEOUT_MS
                        + " = "
                        + getAdSelectionOffDeviceOverallTimeoutMs());
        writer.println(
                "\t"
                        + KEY_FLEDGE_AD_SELECTION_FILTERING_ENABLED
                        + " = "
                        + getFledgeAdSelectionFilteringEnabled());
        writer.println(
                "\t"
                        + KEY_FLEDGE_AD_SELECTION_FILTERING_ENABLED
                        + " = "
                        + getFledgeAdSelectionContextualAdsEnabled());
        writer.println(
                "\t"
                        + KEY_FLEDGE_AD_SELECTION_BIDDING_LOGIC_JS_VERSION
                        + " = "
                        + getFledgeAdSelectionBiddingLogicJsVersion());
        writer.println(
                "\t"
                        + KEY_FLEDGE_REPORT_IMPRESSION_OVERALL_TIMEOUT_MS
                        + " = "
                        + getReportImpressionOverallTimeoutMs());
        writer.println(
                "\t"
                        + KEY_FLEDGE_REPORT_IMPRESSION_MAX_REGISTERED_AD_BEACONS_TOTAL_COUNT
                        + " = "
                        + getFledgeReportImpressionMaxRegisteredAdBeaconsTotalCount());
        writer.println(
                "\t"
                        + KEY_FLEDGE_REPORT_IMPRESSION_MAX_REGISTERED_AD_BEACONS_PER_AD_TECH_COUNT
                        + " = "
                        + getFledgeReportImpressionMaxRegisteredAdBeaconsPerAdTechCount());
        writer.println(
                "\t"
                        + KEY_FLEDGE_REPORT_IMPRESSION_REGISTERED_AD_BEACONS_MAX_INTERACTION_KEY_SIZE_B
                        + " = "
                        + getFledgeReportImpressionRegisteredAdBeaconsMaxInteractionKeySizeB());
        writer.println(
                "\t"
                        + KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_OVERRIDE
                        + " = "
                        + getEnforceForegroundStatusForFledgeOverrides());
        writer.println(
                "\t"
                        + KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_REPORT_IMPRESSION
                        + " = "
                        + getEnforceForegroundStatusForFledgeReportImpression());
        writer.println(
                "\t"
                        + KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_REPORT_INTERACTION
                        + " = "
                        + getEnforceForegroundStatusForFledgeReportInteraction());
        writer.println(
                "\t"
                        + KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_RUN_AD_SELECTION
                        + " = "
                        + getEnforceForegroundStatusForFledgeRunAdSelection());
        writer.println(
                "\t"
                        + KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_CUSTOM_AUDIENCE
                        + " = "
                        + getEnforceForegroundStatusForFledgeCustomAudience());
        writer.println(
                "\t"
                        + KEY_FOREGROUND_STATUS_LEVEL
                        + " = "
                        + getForegroundStatuslLevelForValidation());
        writer.println(
                "\t"
                        + KEY_FLEDGE_AD_SELECTION_OFF_DEVICE_ENABLED
                        + " = "
                        + getAdSelectionOffDeviceEnabled());
        writer.println(
                "\t"
                        + KEY_FLEDGE_AD_SELECTION_OFF_DEVICE_REQUEST_COMPRESSION_ENABLED
                        + " = "
                        + getAdSelectionOffDeviceRequestCompressionEnabled());

        writer.println(
                "\t" + KEY_ENFORCE_ISOLATE_MAX_HEAP_SIZE + " = " + getEnforceIsolateMaxHeapSize());

        writer.println(
                "\t" + KEY_ISOLATE_MAX_HEAP_SIZE_BYTES + " = " + getIsolateMaxHeapSizeBytes());
        writer.println(
                "\t"
                        + KEY_FLEDGE_AD_SELECTION_EXPIRATION_WINDOW_S
                        + " = "
                        + getAdSelectionExpirationWindowS());
        writer.println("==== AdServices PH Flags Throttling Related Flags ====");
        writer.println(
                "\t"
                        + KEY_FLEDGE_REPORT_INTERACTION_REQUEST_PERMITS_PER_SECOND
                        + " = "
                        + getFledgeReportInteractionRequestPermitsPerSecond());
        writer.println("==== AdServices PH Flags Error Logging ====");
        writer.println(
                "\t"
                        + KEY_ADSERVICES_ERROR_LOGGING_ENABLED
                        + " = "
                        + getAdServicesErrorLoggingEnabled());
        writer.println(
                "\t" + KEY_ERROR_CODE_LOGGING_DENY_LIST + " = " + getErrorCodeLoggingDenyList());

        writer.println("==== AdServices PH Flags Dump UI Related Flags ====");
        writer.println(
                "\t" + KEY_EU_NOTIF_FLOW_CHANGE_ENABLED + " = " + getEuNotifFlowChangeEnabled());
        writer.println(
                "\t"
                        + KEY_UI_FEATURE_TYPE_LOGGING_ENABLED
                        + " = "
                        + isUiFeatureTypeLoggingEnabled());
        writer.println(
                "\t" + KEY_UI_DIALOGS_FEATURE_ENABLED + " = " + getUIDialogsFeatureEnabled());
        writer.println(
                "\t" + KEY_IS_EEA_DEVICE_FEATURE_ENABLED + " = " + isEeaDeviceFeatureEnabled());
        writer.println("\t" + KEY_IS_EEA_DEVICE + " = " + isEeaDevice());
        writer.println(
                "\t"
                        + KEY_IS_BACK_COMPACT_ACTIVITY_FEATURE_ENABLED
                        + " = "
                        + isBackCompatActivityFeatureEnabled());
        writer.println("\t" + KEY_UI_EEA_COUNTRIES + " = " + getUiEeaCountries());
        writer.println(
                "\t"
                        + KEY_NOTIFICATION_DISMISSED_ON_CLICK
                        + " = "
                        + getNotificationDismissedOnClick());
        writer.println(
                "\t"
                        + KEY_UI_OTA_STRINGS_FEATURE_ENABLED
                        + " = "
                        + getUiOtaStringsFeatureEnabled());
        writer.println(
                "\t"
                        + KEY_UI_OTA_STRINGS_DOWNLOAD_DEADLINE
                        + " = "
                        + getUiOtaStringsDownloadDeadline());
        writer.println("==== AdServices New Feature Flags ====");
        writer.println(
                "\t"
                        + KEY_FLEDGE_REGISTER_AD_BEACON_ENABLED
                        + " = "
                        + getFledgeRegisterAdBeaconEnabled());
        writer.println("==== AdServices PH Flags Dump STATUS ====");
        writer.println("\t" + KEY_ADSERVICES_ENABLED + " = " + getAdServicesEnabled());
        writer.println(
                "\t"
                        + KEY_FOREGROUND_STATUS_LEVEL
                        + " = "
                        + getForegroundStatuslLevelForValidation());
        writer.println("==== AdServices Consent Dump STATUS ====");
        writer.println("\t" + KEY_CONSENT_SOURCE_OF_TRUTH + " = " + getConsentSourceOfTruth());
        writer.println(
                "\t"
                        + KEY_BLOCKED_TOPICS_SOURCE_OF_TRUTH
                        + " = "
                        + getBlockedTopicsSourceOfTruth());
        writer.println("==== Back-Compat PH Flags Dump STATUS ====");
        writer.println(
                "\t" + KEY_COMPAT_LOGGING_KILL_SWITCH + " = " + getCompatLoggingKillSwitch());
        writer.println("==== Enable Back-Compat PH Flags Dump STATUS ====");
        writer.println("\t" + KEY_ENABLE_BACK_COMPAT + " = " + getEnableBackCompat());
        writer.println(
                "\t" + KEY_ENABLE_APPSEARCH_CONSENT_DATA + " = " + getEnableAppsearchConsentData());
        writer.println(
                "\t"
                        + KEY_MEASUREMENT_ROLLBACK_DELETION_APP_SEARCH_KILL_SWITCH
                        + " = "
                        + getMeasurementRollbackDeletionAppSearchKillSwitch());
        writer.println(
                "\t"
                        + ADSERVICES_CONSENT_MIGRATION_LOGGING_ENABLED
                        + " = "
                        + getAdservicesConsentMigrationLoggingEnabled());
    }

    @VisibleForTesting
    @Override
    public ImmutableList<String> getEnrollmentBlocklist() {
        String blocklistFlag =
                DeviceConfig.getString(NAMESPACE_ADSERVICES, KEY_ENROLLMENT_BLOCKLIST_IDS, "");
        if (TextUtils.isEmpty(blocklistFlag)) {
            return ImmutableList.of();
        }
        String[] blocklistList = blocklistFlag.split(",");
        return ImmutableList.copyOf(blocklistList);
    }

    @Override
    public boolean getCompatLoggingKillSwitch() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_COMPAT_LOGGING_KILL_SWITCH,
                /* defaultValue */ COMPAT_LOGGING_KILL_SWITCH);
    }

    @Override
    public boolean getBackgroundJobsLoggingKillSwitch() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_BACKGROUND_JOBS_LOGGING_KILL_SWITCH,
                /* defaultValue */ BACKGROUND_JOBS_LOGGING_KILL_SWITCH);
    }

    @Override
    public boolean getEnableBackCompat() {
        // If SDK is T+, the value should always be false
        // Check the flag value for S Minus
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return !SdkLevel.isAtLeastT()
                && DeviceConfig.getBoolean(
                        NAMESPACE_ADSERVICES,
                        /* flagName */ KEY_ENABLE_BACK_COMPAT,
                        /* defaultValue */ ENABLE_BACK_COMPAT);
    }

    @Override
    public boolean getEnableAppsearchConsentData() {
        // The priority of applying the flag values: PH (DeviceConfig) and then hard-coded value.
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_ENABLE_APPSEARCH_CONSENT_DATA,
                /* defaultValue */ ENABLE_APPSEARCH_CONSENT_DATA);
    }

    @Override
    public ImmutableList<Integer> getGlobalBlockedTopicIds() {
        String defaultGlobalBlockedTopicIds =
                TOPICS_GLOBAL_BLOCKED_TOPIC_IDS.stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(","));

        String globalBlockedTopicIds =
                DeviceConfig.getString(
                        NAMESPACE_ADSERVICES,
                        KEY_GLOBAL_BLOCKED_TOPIC_IDS,
                        defaultGlobalBlockedTopicIds);
        if (TextUtils.isEmpty(globalBlockedTopicIds)) {
            return ImmutableList.of();
        }
        globalBlockedTopicIds = globalBlockedTopicIds.trim();
        String[] globalBlockedTopicIdsList = globalBlockedTopicIds.split(",");

        List<Integer> globalBlockedTopicIdsIntList = new ArrayList<>();

        for (String blockedTopicId : globalBlockedTopicIdsList) {
            try {
                int topicIdInteger = Integer.parseInt(blockedTopicId.trim());
                globalBlockedTopicIdsIntList.add(topicIdInteger);
            } catch (NumberFormatException e) {
                LogUtil.e("Parsing global blocked topic ids failed for " + globalBlockedTopicIds);
                return TOPICS_GLOBAL_BLOCKED_TOPIC_IDS;
            }
        }
        return ImmutableList.copyOf(globalBlockedTopicIdsIntList);
    }

    @Override
    public ImmutableList<Integer> getErrorCodeLoggingDenyList() {
        String defaultErrorCodeLoggingDenyStr =
                ERROR_CODE_LOGGING_DENY_LIST.stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(","));

        String errorCodeLoggingDenyStr =
                DeviceConfig.getString(
                        NAMESPACE_ADSERVICES,
                        KEY_ERROR_CODE_LOGGING_DENY_LIST,
                        defaultErrorCodeLoggingDenyStr);
        if (TextUtils.isEmpty(errorCodeLoggingDenyStr)) {
            return ImmutableList.of();
        }
        errorCodeLoggingDenyStr = errorCodeLoggingDenyStr.trim();
        String[] errorCodeLoggingDenyStrList = errorCodeLoggingDenyStr.split(",");

        List<Integer> errorCodeLoggingDenyIntList = new ArrayList<>();

        for (String errorCode : errorCodeLoggingDenyStrList) {
            try {
                int errorCodeInteger = Integer.parseInt(errorCode.trim());
                errorCodeLoggingDenyIntList.add(errorCodeInteger);
            } catch (NumberFormatException e) {
                LogUtil.e("Parsing denied error code logging failed for " + errorCode);
                // TODO (b/283323414) : Add CEL for this.
            }
        }
        return ImmutableList.copyOf(errorCodeLoggingDenyIntList);
    }

    @Override
    public long getMeasurementDebugJoinKeyHashLimit() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_DEBUG_JOIN_KEY_HASH_LIMIT,
                /* defaultValue */ DEFAULT_MEASUREMENT_DEBUG_JOIN_KEY_HASH_LIMIT);
    }

    @Override
    public long getMeasurementPlatformDebugAdIdMatchingLimit() {
        return DeviceConfig.getLong(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_DEBUG_KEY_AD_ID_MATCHING_LIMIT,
                /* defaultValue */ DEFAULT_MEASUREMENT_PLATFORM_DEBUG_AD_ID_MATCHING_LIMIT);
    }

    static final String KEY_EU_NOTIF_FLOW_CHANGE_ENABLED = "eu_notif_flow_change_enabled";

    @Override
    public boolean getEuNotifFlowChangeEnabled() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_EU_NOTIF_FLOW_CHANGE_ENABLED,
                /* defaultValue */ DEFAULT_EU_NOTIF_FLOW_CHANGE_ENABLED);
    }

    static final String KEY_NOTIFICATION_DISMISSED_ON_CLICK = "notification_dmsmissed_on_click";

    @Override
    public boolean getNotificationDismissedOnClick() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_NOTIFICATION_DISMISSED_ON_CLICK,
                /* defaultValue */ DEFAULT_NOTIFICATION_DISMISSED_ON_CLICK);
    }

    public static final String KEY_U18_UX_ENABLED = "u18_ux_enabled";

    @Override
    public boolean getU18UxEnabled() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_U18_UX_ENABLED,
                /* defaultValue */ DEFAULT_U18_UX_ENABLED);
    }

    static final String KEY_ENABLE_AD_SERVICES_SYSTEM_API = "enable_ad_services_system_api";

    @Override
    public boolean getEnableAdServicesSystemApi() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_ENABLE_AD_SERVICES_SYSTEM_API,
                /* defaultValue */ DEFAULT_ENABLE_AD_SERVICES_SYSTEM_API);
    }

    @Override
    public Map<String, Boolean> getUxFlags() {
        Map<String, Boolean> uxMap = new HashMap<>();
        uxMap.put(KEY_UI_DIALOGS_FEATURE_ENABLED, getUIDialogsFeatureEnabled());
        uxMap.put(KEY_UI_DIALOG_FRAGMENT_ENABLED, getUiDialogFragmentEnabled());
        uxMap.put(KEY_IS_EEA_DEVICE_FEATURE_ENABLED, isEeaDeviceFeatureEnabled());
        uxMap.put(KEY_IS_EEA_DEVICE, isEeaDevice());
        uxMap.put(KEY_RECORD_MANUAL_INTERACTION_ENABLED, getRecordManualInteractionEnabled());
        uxMap.put(KEY_GA_UX_FEATURE_ENABLED, getGaUxFeatureEnabled());
        uxMap.put(KEY_UI_OTA_STRINGS_FEATURE_ENABLED, getUiOtaStringsFeatureEnabled());
        uxMap.put(KEY_UI_FEATURE_TYPE_LOGGING_ENABLED, isUiFeatureTypeLoggingEnabled());
        uxMap.put(KEY_ADSERVICES_ENABLED, getAdServicesEnabled());
        uxMap.put(KEY_CONSENT_NOTIFICATION_DEBUG_MODE, getConsentNotificationDebugMode());
        return uxMap;
    }

    @Override
    public boolean getMeasurementEnableCoarseEventReportDestinations() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_ENABLE_COARSE_EVENT_REPORT_DESTINATIONS,
                /* defaultValue */ DEFAULT_MEASUREMENT_ENABLE_COARSE_EVENT_REPORT_DESTINATIONS);
    }

    @Override
    public boolean getMeasurementEnableVtcConfigurableMaxEventReports() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_ENABLE_VTC_CONFIGURABLE_MAX_EVENT_REPORTS,
                /* defaultValue */
                DEFAULT_MEASUREMENT_ENABLE_VTC_CONFIGURABLE_MAX_EVENT_REPORTS);
    }

    @Override
    public int getMeasurementVtcConfigurableMaxEventReportsCount() {
        return DeviceConfig.getInt(
                NAMESPACE_ADSERVICES,
                /* flagName */ KEY_MEASUREMENT_VTC_CONFIGURABLE_MAX_EVENT_REPORTS_COUNT,
                /* defaultValue */ DEFAULT_MEASUREMENT_VTC_CONFIGURABLE_MAX_EVENT_REPORTS_COUNT);
    }

    @Override
    public boolean getAdservicesConsentMigrationLoggingEnabled() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ADSERVICES,
                /* flagName */ ADSERVICES_CONSENT_MIGRATION_LOGGING_ENABLED,
                /* defaultValue */ DEFAULT_ADSERVICES_CONSENT_MIGRATION_LOGGING_ENABLED);
    }
}
