#[cxx::bridge(namespace = bluetooth::common::init_flags)]
mod ffi {
    struct InitFlagWithValue {
        flag: &'static str,
        value: String,
    }

    extern "Rust" {
        fn load(flags: Vec<String>);
        fn set_all_for_testing();

        fn dump() -> Vec<InitFlagWithValue>;

        fn always_send_services_if_gatt_disc_done_is_enabled() -> bool;
        fn always_use_private_gatt_for_debugging_is_enabled() -> bool;
        fn asynchronously_start_l2cap_coc_is_enabled() -> bool;
        fn btaa_hci_is_enabled() -> bool;
        fn bta_dm_clear_conn_id_on_client_close_is_enabled() -> bool;
        fn delay_hidh_cleanup_until_hidh_ready_start_is_enabled() -> bool;
        fn gd_hal_snoop_logger_filtering_is_enabled() -> bool;
        fn btm_dm_flush_discovery_queue_on_search_cancel_is_enabled() -> bool;
        fn classic_discovery_only_is_enabled() -> bool;
        fn clear_hidd_interrupt_cid_on_disconnect_is_enabled() -> bool;
        fn device_iot_config_logging_is_enabled() -> bool;
        fn dynamic_avrcp_version_enhancement_is_enabled() -> bool;
        fn finite_att_timeout_is_enabled() -> bool;
        fn gatt_robust_caching_client_is_enabled() -> bool;
        fn gatt_robust_caching_server_is_enabled() -> bool;
        fn gd_core_is_enabled() -> bool;
        fn gd_hal_snoop_logger_socket_is_enabled() -> bool;
        fn gd_l2cap_is_enabled() -> bool;
        fn gd_link_policy_is_enabled() -> bool;
        fn gd_remote_name_request_is_enabled() -> bool;
        fn get_default_log_level() -> i32;
        fn get_hci_adapter() -> i32;
        fn get_log_level_for_tag(tag: &str) -> i32;
        fn get_asha_packet_drop_frequency_threshold() -> i32;
        fn get_asha_phy_update_retry_limit() -> i32;
        fn hfp_dynamic_version_is_enabled() -> bool;
        fn irk_rotation_is_enabled() -> bool;
        fn leaudio_targeted_announcement_reconnection_mode_is_enabled() -> bool;
        fn pass_phy_update_callback_is_enabled() -> bool;
        fn pbap_pse_dynamic_version_upgrade_is_enabled() -> bool;
        fn periodic_advertising_adi_is_enabled() -> bool;
        fn private_gatt_is_enabled() -> bool;
        fn queue_l2cap_coc_while_encrypting_is_enabled() -> bool;
        fn read_encryption_key_size_is_enabled() -> bool;
        fn redact_log_is_enabled() -> bool;
        fn rust_event_loop_is_enabled() -> bool;
        fn sco_codec_select_lc3_is_enabled() -> bool;
        fn sco_codec_timeout_clear_is_enabled() -> bool;
        fn sdp_serialization_is_enabled() -> bool;
        fn sdp_skip_rnr_if_known_is_enabled() -> bool;
        fn bluetooth_quality_report_callback_is_enabled() -> bool;
        fn set_min_encryption_is_enabled() -> bool;
        fn subrating_is_enabled() -> bool;
        fn trigger_advertising_callbacks_on_first_resume_after_pause_is_enabled() -> bool;
        fn use_unified_connection_manager_is_enabled() -> bool;
        fn sdp_return_classic_services_when_le_discovery_fails_is_enabled() -> bool;
    }
}

use crate::init_flags::ffi::InitFlagWithValue;

fn dump() -> Vec<InitFlagWithValue> {
    bt_common::init_flags::dump()
        .into_iter()
        .map(|(flag, value)| InitFlagWithValue { flag, value })
        .collect()
}

use bt_common::init_flags::*;
