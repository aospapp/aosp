/* Copyright 2018 The Chromium OS Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */

#include <syslog.h>

#include "aec_config.h"
#include "iniparser_wrapper.h"

static const unsigned int MAX_INI_NAME_LEN = 63;

#define AEC_CONFIG_NAME "aec.ini"

#define AEC_GET_INT(ini, category, key)			\
	iniparser_getint(				\
		ini, AEC_ ## category ## _ ## key,	\
		AEC_ ## category ## _ ## key ## _VALUE)

#define AEC_GET_FLOAT(ini, category, key)		\
	iniparser_getdouble(				\
		ini, AEC_ ## category ## _ ## key,	\
		AEC_ ## category ## _ ## key ## _VALUE)

struct aec_config *aec_config_get(const char *device_config_dir)
{
	struct aec_config *config;
	char ini_name[MAX_INI_NAME_LEN + 1];
	dictionary *ini;

	snprintf(ini_name, MAX_INI_NAME_LEN, "%s/%s",
		 device_config_dir, AEC_CONFIG_NAME);
	ini_name[MAX_INI_NAME_LEN] = '\0';

	ini = iniparser_load_wrapper(ini_name);
	if (ini == NULL) {
		syslog(LOG_DEBUG, "No ini file %s", ini_name);
		return NULL;
	}

	config = (struct aec_config *)calloc(1, sizeof(*config));

	config->delay.default_delay =
		AEC_GET_INT(ini, DELAY, DEFAULT_DELAY);
	config->delay.down_sampling_factor =
		AEC_GET_INT(ini, DELAY, DOWN_SAMPLING_FACTOR);
	config->delay.num_filters =
		AEC_GET_INT(ini, DELAY, NUM_FILTERS);
	config->delay.api_call_jitter_blocks =
		AEC_GET_INT(ini, DELAY, API_CALL_JITTER_BLOCKS);
	config->delay.min_echo_path_delay_blocks =
		AEC_GET_INT(ini, DELAY, MIN_ECHO_PATH_DELAY_BLOCKS);
	config->delay.delay_headroom_blocks =
		AEC_GET_INT(ini, DELAY, DELAY_HEADROOM_BLOCKS);
	config->delay.hysteresis_limit_1_blocks =
		AEC_GET_INT(ini, DELAY, HYSTERESIS_LIMIT_1_BLOCKS);
	config->delay.hysteresis_limit_2_blocks =
		AEC_GET_INT(ini, DELAY, HYSTERESIS_LIMIT_2_BLOCKS);
	config->delay.skew_hysteresis_blocks =
		AEC_GET_INT(ini, DELAY, SKEW_HYSTERESIS_BLOCKS);
	config->delay.fixed_capture_delay_samples =
		AEC_GET_INT(ini, DELAY, FIXED_CAPTURE_DELAY_SAMPLES);

	config->filter.main.length_blocks =
		AEC_GET_INT(ini, FILTER_MAIN, LENGTH_BLOCKS);
	config->filter.main.leakage_converged =
		AEC_GET_FLOAT(ini, FILTER_MAIN, LEAKAGE_CONVERGED);
	config->filter.main.leakage_diverged =
		AEC_GET_FLOAT(ini, FILTER_MAIN, LEAKAGE_DIVERGED);
	config->filter.main.error_floor =
		AEC_GET_FLOAT(ini, FILTER_MAIN, ERROR_FLOOR);
	config->filter.main.noise_gate =
		AEC_GET_FLOAT(ini, FILTER_MAIN, NOISE_GATE);

	config->filter.shadow.length_blocks =
		AEC_GET_INT(ini, FILTER_SHADOW, LENGTH_BLOCKS);
	config->filter.shadow.rate =
		AEC_GET_FLOAT(ini, FILTER_SHADOW, RATE);
	config->filter.shadow.noise_gate =
		AEC_GET_FLOAT(ini, FILTER_SHADOW, NOISE_GATE);

	config->filter.main_initial.length_blocks =
		AEC_GET_INT(ini, FILTER_MAIN_INIT, LENGTH_BLOCKS);
	config->filter.main_initial.leakage_converged =
		AEC_GET_FLOAT(ini, FILTER_MAIN_INIT, LEAKAGE_CONVERGED);
	config->filter.main_initial.leakage_diverged =
		AEC_GET_FLOAT(ini, FILTER_MAIN_INIT, LEAKAGE_DIVERGED);
	config->filter.main_initial.error_floor =
		AEC_GET_FLOAT(ini, FILTER_MAIN_INIT, ERROR_FLOOR);
	config->filter.main_initial.noise_gate =
		AEC_GET_FLOAT(ini, FILTER_MAIN_INIT, NOISE_GATE);

	config->filter.shadow_initial.length_blocks =
		AEC_GET_INT(ini, FILTER_SHADOW_INIT, LENGTH_BLOCKS);
	config->filter.shadow_initial.rate =
		AEC_GET_FLOAT(ini, FILTER_SHADOW_INIT, RATE);
	config->filter.shadow_initial.noise_gate =
		AEC_GET_FLOAT(ini, FILTER_SHADOW_INIT, NOISE_GATE);

	config->filter.config_change_duration_blocks =
		AEC_GET_INT(ini, FILTER, CONFIG_CHANGE_DURATION_BLOCKS);
	config->filter.initial_state_seconds =
		AEC_GET_FLOAT(ini, FILTER, INITIAL_STATE_SECONDS);
	config->filter.conservative_initial_phase =
		AEC_GET_INT(ini, FILTER, CONSERVATIVE_INITIAL_PHASE);
	config->filter.enable_shadow_filter_output_usage =
		AEC_GET_INT(ini, FILTER, ENABLE_SHADOW_FILTER_OUTPUT_USAGE);

	config->erle.min =
		AEC_GET_FLOAT(ini, ERLE, MIN);
	config->erle.max_l =
		AEC_GET_FLOAT(ini, ERLE, MAX_L);
	config->erle.max_h =
		AEC_GET_FLOAT(ini, ERLE, MAX_H);
	config->erle.onset_detection =
		AEC_GET_INT(ini, ERLE, ONSET_DETECTION);

	config->ep_strength.lf =
		AEC_GET_FLOAT(ini, EP_STRENGTH, LF);
	config->ep_strength.mf =
		AEC_GET_FLOAT(ini, EP_STRENGTH, MF);
	config->ep_strength.hf =
		AEC_GET_FLOAT(ini, EP_STRENGTH, HF);
	config->ep_strength.default_len =
		AEC_GET_FLOAT(ini, EP_STRENGTH, DEFAULT_LEN);
	config->ep_strength.reverb_based_on_render =
		AEC_GET_INT(ini, EP_STRENGTH, REVERB_BASED_ON_RENDER);
	config->ep_strength.bounded_erl =
		AEC_GET_INT(ini, EP_STRENGTH, BOUNDED_ERL);
	config->ep_strength.echo_can_saturate =
		AEC_GET_INT(ini, EP_STRENGTH, ECHO_CAN_SATURATE);

	config->gain_mask.m0 =
		AEC_GET_FLOAT(ini, GAIN_MASK, M0);
	config->gain_mask.m1 =
		AEC_GET_FLOAT(ini, GAIN_MASK, M1);
	config->gain_mask.m2 =
		AEC_GET_FLOAT(ini, GAIN_MASK, M2);
	config->gain_mask.m3 =
		AEC_GET_FLOAT(ini, GAIN_MASK, M3);
	config->gain_mask.m5 =
		AEC_GET_FLOAT(ini, GAIN_MASK, M5);
	config->gain_mask.m6 =
		AEC_GET_FLOAT(ini, GAIN_MASK, M6);
	config->gain_mask.m7 =
		AEC_GET_FLOAT(ini, GAIN_MASK, M7);
	config->gain_mask.m8 =
		AEC_GET_FLOAT(ini, GAIN_MASK, M8);
	config->gain_mask.m9 =
		AEC_GET_FLOAT(ini, GAIN_MASK, M9);
	config->gain_mask.gain_curve_offset =
		AEC_GET_FLOAT(ini, GAIN_MASK, GAIN_CURVE_OFFSET);
	config->gain_mask.gain_curve_slope =
		AEC_GET_FLOAT(ini, GAIN_MASK, GAIN_CURVE_SLOPE);
	config->gain_mask.temporal_masking_lf =
		AEC_GET_FLOAT(ini, GAIN_MASK, TEMPORAL_MASKING_LF);
	config->gain_mask.temporal_masking_hf =
		AEC_GET_FLOAT(ini, GAIN_MASK, TEMPORAL_MASKING_HF);
	config->gain_mask.temporal_masking_lf_bands =
		AEC_GET_INT(ini, GAIN_MASK, TEMPORAL_MASKING_LF_BANDS);

	config->echo_audibility.low_render_limit =
		AEC_GET_FLOAT(ini, ECHO_AUDIBILITY, LOW_RENDER_LIMIT);
	config->echo_audibility.normal_render_limit =
		AEC_GET_FLOAT(ini, ECHO_AUDIBILITY, NORMAL_RENDER_LIMIT);
	config->echo_audibility.floor_power =
		AEC_GET_FLOAT(ini, ECHO_AUDIBILITY, FLOOR_POWER);
	config->echo_audibility.audibility_threshold_lf =
		AEC_GET_FLOAT(ini, ECHO_AUDIBILITY, AUDIBILITY_THRESHOLD_LF);
	config->echo_audibility.audibility_threshold_mf =
		AEC_GET_FLOAT(ini, ECHO_AUDIBILITY, AUDIBILITY_THRESHOLD_MF);
	config->echo_audibility.audibility_threshold_hf =
		AEC_GET_FLOAT(ini, ECHO_AUDIBILITY, AUDIBILITY_THRESHOLD_HF);
	config->echo_audibility.use_stationary_properties =
		AEC_GET_INT(ini, ECHO_AUDIBILITY, USE_STATIONARY_PROPERTIES);

	config->render_levels.active_render_limit =
		AEC_GET_FLOAT(ini, RENDER_LEVELS, ACTIVE_RENDER_LIMIT);
	config->render_levels.poor_excitation_render_limit =
		AEC_GET_FLOAT(ini, RENDER_LEVELS, POOR_EXCITATION_RENDER_LIMIT);
	config->render_levels.poor_excitation_render_limit_ds8 =
		AEC_GET_FLOAT(ini, RENDER_LEVELS, POOR_EXCITATION_RENDER_LIMIT_DS8);

	config->echo_removal_control.gain_rampup.initial_gain =
		AEC_GET_FLOAT(ini, ECHO_REMOVAL_CTL, INITIAL_GAIN);
	config->echo_removal_control.gain_rampup.first_non_zero_gain =
		AEC_GET_FLOAT(ini, ECHO_REMOVAL_CTL, FIRST_NON_ZERO_GAIN);
	config->echo_removal_control.gain_rampup.non_zero_gain_blocks =
		AEC_GET_INT(ini, ECHO_REMOVAL_CTL, NON_ZERO_GAIN_BLOCKS);
	config->echo_removal_control.gain_rampup.full_gain_blocks =
		AEC_GET_INT(ini, ECHO_REMOVAL_CTL, FULL_GAIN_BLOCKS);
	config->echo_removal_control.has_clock_drift =
		AEC_GET_INT(ini, ECHO_REMOVAL_CTL, HAS_CLOCK_DRIFT);
	config->echo_removal_control.linear_and_stable_echo_path =
		AEC_GET_INT(ini, ECHO_REMOVAL_CTL, LINEAR_AND_STABLE_ECHO_PATH);

	config->echo_model.noise_floor_hold =
		AEC_GET_INT(ini, ECHO_MODEL, NOISE_FLOOR_HOLD);
	config->echo_model.min_noise_floor_power =
		AEC_GET_FLOAT(ini, ECHO_MODEL, MIN_NOISE_FLOOR_POWER);
	config->echo_model.stationary_gate_slope =
		AEC_GET_FLOAT(ini, ECHO_MODEL, STATIONARY_GATE_SLOPE);
	config->echo_model.noise_gate_power =
		AEC_GET_FLOAT(ini, ECHO_MODEL, NOISE_GATE_POWER);
	config->echo_model.noise_gate_slope =
		AEC_GET_FLOAT(ini, ECHO_MODEL, NOISE_GATE_SLOPE);
	config->echo_model.render_pre_window_size =
		AEC_GET_INT(ini, ECHO_MODEL, RENDER_PRE_WINDOW_SIZE);
	config->echo_model.render_post_window_size =
		AEC_GET_INT(ini, ECHO_MODEL, RENDER_POST_WINDOW_SIZE);
	config->echo_model.render_pre_window_size_init =
		AEC_GET_INT(ini, ECHO_MODEL, RENDER_PRE_WINDOW_SIZE_INIT);
	config->echo_model.render_post_window_size_init =
		AEC_GET_INT(ini, ECHO_MODEL, RENDER_POST_WINDOW_SIZE_INIT);
	config->echo_model.nonlinear_hold =
		AEC_GET_FLOAT(ini, ECHO_MODEL, NONLINEAR_HOLD);
	config->echo_model.nonlinear_release =
		AEC_GET_FLOAT(ini, ECHO_MODEL, NONLINEAR_RELEASE);

	config->suppressor.nearend_average_blocks =
		AEC_GET_INT(ini, SUPPRESSOR, NEAREND_AVERAGE_BLOCKS);

	config->suppressor.normal_tuning.mask_lf.enr_transparent =
		AEC_GET_FLOAT(ini, SUPPRESSOR_NORMAL_TUNING,
			      MASK_LF_ENR_TRANSPARENT);
	config->suppressor.normal_tuning.mask_lf.enr_suppress =
		AEC_GET_FLOAT(ini, SUPPRESSOR_NORMAL_TUNING,
			      MASK_LF_ENR_SUPPRESS);
	config->suppressor.normal_tuning.mask_lf.emr_transparent =
		AEC_GET_FLOAT(ini, SUPPRESSOR_NORMAL_TUNING,
			      MASK_LF_EMR_TRANSPARENT);
	config->suppressor.normal_tuning.mask_hf.enr_transparent =
		AEC_GET_FLOAT(ini, SUPPRESSOR_NORMAL_TUNING,
			      MASK_HF_ENR_TRANSPARENT);
	config->suppressor.normal_tuning.mask_hf.enr_suppress =
		AEC_GET_FLOAT(ini, SUPPRESSOR_NORMAL_TUNING,
			      MASK_HF_ENR_SUPPRESS);
	config->suppressor.normal_tuning.mask_hf.emr_transparent =
		AEC_GET_FLOAT(ini, SUPPRESSOR_NORMAL_TUNING,
			      MASK_HF_EMR_TRANSPARENT);
	config->suppressor.normal_tuning.max_inc_factor =
		AEC_GET_FLOAT(ini, SUPPRESSOR_NORMAL_TUNING, MAX_INC_FACTOR);
	config->suppressor.normal_tuning.max_dec_factor_lf =
		AEC_GET_FLOAT(ini, SUPPRESSOR_NORMAL_TUNING, MAX_DEC_FACTOR_LF);

	config->suppressor.nearend_tuning.mask_lf.enr_transparent =
		AEC_GET_FLOAT(ini, SUPPRESSOR_NEAREND_TUNING,
			      MASK_LF_ENR_TRANSPARENT);
	config->suppressor.nearend_tuning.mask_lf.enr_suppress =
		AEC_GET_FLOAT(ini, SUPPRESSOR_NEAREND_TUNING,
			      MASK_LF_ENR_SUPPRESS);
	config->suppressor.nearend_tuning.mask_lf.emr_transparent =
		AEC_GET_FLOAT(ini, SUPPRESSOR_NEAREND_TUNING,
			      MASK_LF_EMR_TRANSPARENT);
	config->suppressor.nearend_tuning.mask_hf.enr_transparent =
		AEC_GET_FLOAT(ini, SUPPRESSOR_NEAREND_TUNING,
			      MASK_HF_ENR_TRANSPARENT);
	config->suppressor.nearend_tuning.mask_hf.enr_suppress =
		AEC_GET_FLOAT(ini, SUPPRESSOR_NEAREND_TUNING,
			      MASK_HF_ENR_SUPPRESS);
	config->suppressor.nearend_tuning.mask_hf.emr_transparent =
		AEC_GET_FLOAT(ini, SUPPRESSOR_NEAREND_TUNING,
			      MASK_HF_EMR_TRANSPARENT);
	config->suppressor.nearend_tuning.max_inc_factor =
		AEC_GET_FLOAT(ini, SUPPRESSOR_NEAREND_TUNING, MAX_INC_FACTOR);
	config->suppressor.nearend_tuning.max_dec_factor_lf =
		AEC_GET_FLOAT(ini, SUPPRESSOR_NEAREND_TUNING, MAX_DEC_FACTOR_LF);

	config->suppressor.dominant_nearend_detection.enr_threshold =
		AEC_GET_FLOAT(ini, SUPPRESSOR_DOMINANT_NEAREND_DETECTION,
			ENR_THRESHOLD);
	config->suppressor.dominant_nearend_detection.snr_threshold =
		AEC_GET_FLOAT(ini, SUPPRESSOR_DOMINANT_NEAREND_DETECTION,
			SNR_THRESHOLD);
	config->suppressor.dominant_nearend_detection.hold_duration =
		AEC_GET_INT(ini, SUPPRESSOR_DOMINANT_NEAREND_DETECTION,
			HOLD_DURATION);
	config->suppressor.dominant_nearend_detection.trigger_threshold =
		AEC_GET_INT(ini, SUPPRESSOR_DOMINANT_NEAREND_DETECTION,
			TRIGGER_THRESHOLD);

	config->suppressor.high_bands_suppression.enr_threshold =
		AEC_GET_FLOAT(ini, SUPPRESSOR_HIGH_BANDS_SUPPRESSION,
			ENR_THRESHOLD);
	config->suppressor.high_bands_suppression.max_gain_during_echo =
		AEC_GET_FLOAT(ini, SUPPRESSOR_HIGH_BANDS_SUPPRESSION,
			MAX_GAIN_DURING_ECHO);

	config->suppressor.floor_first_increase =
		AEC_GET_FLOAT(ini, SUPPRESSOR, FLOOR_FIRST_INCREASE);
	config->suppressor.enforce_transparent =
		AEC_GET_INT(ini, SUPPRESSOR, ENFORCE_TRANSPARENT);
	config->suppressor.enforce_empty_higher_bands =
		AEC_GET_INT(ini, SUPPRESSOR, ENFORCE_EMPTY_HIGHER_BANDS);

	return config;
}

void aec_config_dump(struct aec_config *config)
{
	syslog(LOG_ERR, "---- aec config dump ----");
	syslog(LOG_ERR, "Delay:");
	syslog(LOG_ERR, "    default_delay %zu sampling_factor %zu num_filters %zu",
			config->delay.default_delay,
			config->delay.down_sampling_factor,
			config->delay.num_filters);
	syslog(LOG_ERR, "    api_call_jitter_blocks %zu, min_echo_path_delay_blocks %zu",
			config->delay.api_call_jitter_blocks,
			config->delay.min_echo_path_delay_blocks);
	syslog(LOG_ERR, "    delay_headroom_blocks %zu, hysteresis_limit_1_blocks %zu",
			config->delay.delay_headroom_blocks,
			config->delay.hysteresis_limit_1_blocks);
	syslog(LOG_ERR, "    hysteresis_limit_2_blocks %zu, skew_hysteresis_blocks %zu",
			config->delay.hysteresis_limit_2_blocks,
			config->delay.skew_hysteresis_blocks);
	syslog(LOG_ERR, "    fixed_capture_delay_samples %zu",
			config->delay.fixed_capture_delay_samples);

	syslog(LOG_ERR, "Filter main configuration:");
	syslog(LOG_ERR, "    length_blocks %zu, leakage_converged %f, leakage_diverged %f",
			config->filter.main.length_blocks,
			config->filter.main.leakage_converged,
			config->filter.main.leakage_diverged);
	syslog(LOG_ERR, "    error_floor %f, noise_gate %f",
			config->filter.main.error_floor,
			config->filter.main.noise_gate);
	syslog(LOG_ERR, "Filter shadow configuration:");
	syslog(LOG_ERR, "    length_blocks %zu, rate %f, noise_gate %f",
			config->filter.shadow.length_blocks,
			config->filter.shadow.rate,
			config->filter.shadow.noise_gate);
	syslog(LOG_ERR, "Filter main initial configuration:");
	syslog(LOG_ERR, "    length_blocks %zu, leakage_converged %f",
			config->filter.main_initial.length_blocks,
			config->filter.main_initial.leakage_converged);
	syslog(LOG_ERR, "    leakage_diverged %f, error_floor %f, noise_gate %f",
			config->filter.main_initial.leakage_diverged,
			config->filter.main_initial.error_floor,
			config->filter.main_initial.noise_gate);
	syslog(LOG_ERR, "Filter shadow initial configuration:");
	syslog(LOG_ERR, "    length_blocks %zu, rate %f, noise_gate %f",
			config->filter.shadow_initial.length_blocks,
			config->filter.shadow_initial.rate,
			config->filter.shadow_initial.noise_gate);
	syslog(LOG_ERR, "Filter:    config_change_duration_blocks %d",
			config->filter.config_change_duration_blocks);
	syslog(LOG_ERR, "    initial_state_seconds %f",
			config->filter.initial_state_seconds);
	syslog(LOG_ERR, "    conservative_initial_phase %d",
			config->filter.conservative_initial_phase);
	syslog(LOG_ERR, "    enable_shadow_filter_output_usage %d",
			config->filter.enable_shadow_filter_output_usage);
	syslog(LOG_ERR, "Erle: min %f max_l %f max_h %f onset_detection %d",
			config->erle.min, config->erle.max_l,
			config->erle.max_h, config->erle.onset_detection);
	syslog(LOG_ERR, "Ep strength: lf %f mf %f hf %f default_len %f",
			config->ep_strength.lf,
			config->ep_strength.mf,
			config->ep_strength.hf,
			config->ep_strength.default_len);
	syslog(LOG_ERR, "    echo_can_saturate %d, bounded_erl %d,"
			"    ep_strength.reverb_based_on_render %d",
			config->ep_strength.echo_can_saturate,
			config->ep_strength.bounded_erl,
			config->ep_strength.reverb_based_on_render);
	syslog(LOG_ERR, "Gain mask: m0 %f m1 %f m2 %f m3 %f m5 %f",
			config->gain_mask.m0,
			config->gain_mask.m1,
			config->gain_mask.m2,
			config->gain_mask.m3,
			config->gain_mask.m5);
	syslog(LOG_ERR, "    m6 %f m7 %f m8 %f m9 %f",
			config->gain_mask.m6,
			config->gain_mask.m7,
			config->gain_mask.m8,
			config->gain_mask.m9);
	syslog(LOG_ERR, "    gain_curve offset %f, gain_curve_slope %f",
			config->gain_mask.gain_curve_offset,
			config->gain_mask.gain_curve_slope);
	syslog(LOG_ERR, "    temporal_masking_lf %f, temporal_masking_hf %f",
			config->gain_mask.temporal_masking_lf,
			config->gain_mask.temporal_masking_hf);
	syslog(LOG_ERR, "    temporal_masking_lf_bands %zu",
			config->gain_mask.temporal_masking_lf_bands);
	syslog(LOG_ERR, "Echo audibility:");
	syslog(LOG_ERR, "    low_render_limit %f, normal_render_limit %f",
			config->echo_audibility.low_render_limit,
			config->echo_audibility.normal_render_limit);
	syslog(LOG_ERR, "    floor_power %f, audibility_threshold_lf %f",
			config->echo_audibility.floor_power,
			config->echo_audibility.audibility_threshold_lf);
	syslog(LOG_ERR, "    audibility_threshold_mf %f",
			config->echo_audibility.audibility_threshold_mf);
	syslog(LOG_ERR, "    audibility_threshold_hf %f",
			config->echo_audibility.audibility_threshold_hf);
	syslog(LOG_ERR, "    use_stationary_properties %d",
			config->echo_audibility.use_stationary_properties);
	syslog(LOG_ERR, "Render levels:");
	syslog(LOG_ERR, "    active_render_limit %f",
			config->render_levels.active_render_limit);
	syslog(LOG_ERR, "    poor_excitation_render_limit %f",
			config->render_levels.poor_excitation_render_limit);
	syslog(LOG_ERR, "    poor_excitation_render_limit_ds8 %f",
			config->render_levels.poor_excitation_render_limit_ds8);
	syslog(LOG_ERR, "Echo removal control:");
	syslog(LOG_ERR, "    gain rampup:");
	syslog(LOG_ERR, "        initial_gain %f, first_non_zero_gain %f",
			config->echo_removal_control.gain_rampup.initial_gain,
			config->echo_removal_control.gain_rampup.first_non_zero_gain);
	syslog(LOG_ERR, "        non_zero_gain_blocks %d, full_gain_blocks %d",
			config->echo_removal_control.gain_rampup.non_zero_gain_blocks,
			config->echo_removal_control.gain_rampup.full_gain_blocks);
	syslog(LOG_ERR, "    has_clock_drift %d",
			config->echo_removal_control.has_clock_drift);
	syslog(LOG_ERR, "    linear_and_stable_echo_path %d",
			config->echo_removal_control.linear_and_stable_echo_path);
	syslog(LOG_ERR, "Echo model:");
	syslog(LOG_ERR, "    noise_floor_hold %zu, min_noise_floor_power %f",
			config->echo_model.noise_floor_hold,
			config->echo_model.min_noise_floor_power);
	syslog(LOG_ERR, "    stationary_gate_slope %f, noise_gate_power %f",
			config->echo_model.stationary_gate_slope,
			config->echo_model.noise_gate_power);
	syslog(LOG_ERR, "    noise_gate_slope %f, render_pre_window_size %zu",
			config->echo_model.noise_gate_slope,
			config->echo_model.render_pre_window_size);
	syslog(LOG_ERR, "    render_post_window_size %zu nonlinear_hold %f",
			config->echo_model.render_post_window_size,
			config->echo_model.nonlinear_hold);
	syslog(LOG_ERR, "    render_pre_window_size_init %u, "
			"render_post_window_size_init %u",
			config->echo_model.render_pre_window_size_init,
			config->echo_model.render_post_window_size_init);
	syslog(LOG_ERR, "    nonlinear_release %f",
			config->echo_model.nonlinear_release);
	syslog(LOG_ERR, "Suppressor:");
	syslog(LOG_ERR, "    nearend_average_blocks %u",
			config->suppressor.nearend_average_blocks);
	syslog(LOG_ERR, "    Normal tuning, mask_lf %f %f %f",
			config->suppressor.normal_tuning.mask_lf.enr_transparent,
			config->suppressor.normal_tuning.mask_lf.enr_suppress,
			config->suppressor.normal_tuning.mask_lf.emr_transparent);
	syslog(LOG_ERR, "                   mask_hf %f %f %f",
			config->suppressor.normal_tuning.mask_hf.enr_transparent,
			config->suppressor.normal_tuning.mask_hf.enr_suppress,
			config->suppressor.normal_tuning.mask_hf.emr_transparent);
	syslog(LOG_ERR, "                   max_inc_factor %f max_dec_factor_lf %f",
			config->suppressor.normal_tuning.max_inc_factor,
			config->suppressor.normal_tuning.max_dec_factor_lf);
	syslog(LOG_ERR, "    Nearend tuning, mask_lf %f %f %f",
			config->suppressor.nearend_tuning.mask_lf.enr_transparent,
			config->suppressor.nearend_tuning.mask_lf.enr_suppress,
			config->suppressor.nearend_tuning.mask_lf.emr_transparent);
	syslog(LOG_ERR, "                   mask_hf %f %f %f",
			config->suppressor.nearend_tuning.mask_hf.enr_transparent,
			config->suppressor.nearend_tuning.mask_hf.enr_suppress,
			config->suppressor.nearend_tuning.mask_hf.emr_transparent);
	syslog(LOG_ERR, "                   max_inc_factor %f max_dec_factor_lf %f",
			config->suppressor.nearend_tuning.max_inc_factor,
			config->suppressor.nearend_tuning.max_dec_factor_lf);
	syslog(LOG_ERR, "    Dominant nearend detection:");
	syslog(LOG_ERR, "        enr_threshold %f",
			config->suppressor.dominant_nearend_detection.enr_threshold);
	syslog(LOG_ERR, "        snr_threshold %f",
			config->suppressor.dominant_nearend_detection.snr_threshold);
	syslog(LOG_ERR, "        hold_duration %d",
			config->suppressor.dominant_nearend_detection.hold_duration);
	syslog(LOG_ERR, "        trigger_threshold %d",
			config->suppressor.dominant_nearend_detection.trigger_threshold);
	syslog(LOG_ERR, "    High bands suppression:");
	syslog(LOG_ERR, "        enr_threshold %f max_gain_during_echo %f",
			config->suppressor.high_bands_suppression.enr_threshold,
			config->suppressor.high_bands_suppression.max_gain_during_echo);
	syslog(LOG_ERR, "    floor_first_increase %f, enforce_transparent %d",
			config->suppressor.floor_first_increase,
			config->suppressor.enforce_transparent);
	syslog(LOG_ERR, "    enforce_empty_higher_bands %f",
			config->suppressor.enforce_empty_higher_bands);
}
