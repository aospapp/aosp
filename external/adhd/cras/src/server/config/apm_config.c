/* Copyright 2018 The Chromium OS Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */

#include <syslog.h>

#include "apm_config.h"
#include "iniparser_wrapper.h"

static const unsigned int MAX_INI_NAME_LEN = 63;

#define APM_CONFIG_NAME "apm.ini"

#define APM_GET_INT(ini, key)	\
	iniparser_getint(	\
		ini, key,	\
		key ## _VALUE)
#define APM_GET_FLOAT(ini, key)	\
	iniparser_getdouble(	\
		ini, key,	\
		key ## _VALUE)

struct apm_config *apm_config_get(const char *device_config_dir)
{
	struct apm_config *config;
	char ini_name[MAX_INI_NAME_LEN + 1];
	dictionary *ini;

	snprintf(ini_name, MAX_INI_NAME_LEN, "%s/%s",
		 device_config_dir, APM_CONFIG_NAME);
	ini_name[MAX_INI_NAME_LEN] = '\0';

	ini = iniparser_load_wrapper(ini_name);
	if (ini == NULL) {
		syslog(LOG_DEBUG, "No ini file %s", ini_name);
		return NULL;
	}

	config = (struct apm_config *)calloc(1, sizeof(*config));
	config->residual_echo_detector_enabled =
			APM_GET_INT(ini, APM_RESIDUAL_ECHO_DETECTOR_ENABLED);
	config->high_pass_filter_enabled =
			APM_GET_INT(ini, APM_HIGH_PASS_FILTER_ENABLED);
	config->pre_amplifier_enabled =
			APM_GET_INT(ini, APM_PRE_AMPLIFIER_ENABLED);
	config->pre_amplifier_fixed_gain_factor =
			APM_GET_FLOAT(ini, APM_PRE_AMPLIFIER_FIXED_GAIN_FACTOR);
	config->gain_controller2_enabled =
			APM_GET_INT(ini, APM_GAIN_CONTROLLER2_ENABLED);
	config->gain_controller2_fixed_gain_db =
			APM_GET_FLOAT(ini, APM_GAIN_CONTROLLER2_FIXED_GAIN_DB);
	config->gain_controller2_adaptive_digital_mode =
			APM_GET_INT(ini, APM_GAIN_CONTROLLER2_ADAPTIVE_DIGITAL_MODE);
	config->gain_control_compression_gain_db =
			APM_GET_INT(ini, APM_GAIN_CONTROL_COMPRESSION_GAIN_DB);
	config->agc_mode = (enum gain_control_mode)
			APM_GET_INT(ini, APM_GAIN_CONTROL_MODE);
	config->gain_control_enabled =
			APM_GET_INT(ini, APM_GAIN_CONTROL_ENABLED);
	config->ns_level = (enum noise_suppression_level)
			APM_GET_INT(ini, APM_NOISE_SUPPRESSION_LEVEL);
	config->noise_suppression_enabled =
			APM_GET_INT(ini, APM_NOISE_SUPPRESSION_ENABLED);

	return config;
}

void apm_config_dump(struct apm_config *config)
{
	syslog(LOG_ERR, "---- apm config dump ----");
	syslog(LOG_ERR, "residual_echo_detector_enabled %u",
		config->residual_echo_detector_enabled);
	syslog(LOG_ERR, "high_pass_filter_enabled %u",
		config->high_pass_filter_enabled);
	syslog(LOG_ERR, "pre_amplifier_enabled %u",
		config->pre_amplifier_enabled);
	syslog(LOG_ERR, "pre_amplifier_fixed_gain_factor %f",
		config->pre_amplifier_fixed_gain_factor);
	syslog(LOG_ERR, "gain_controller2_enabled %u",
		config->gain_controller2_enabled);
	syslog(LOG_ERR, "gain_controller2_fixed_gain_db %f",
		config->gain_controller2_fixed_gain_db);
	syslog(LOG_ERR, "gain_controller2_adaptive_digital_mode %d",
		config->gain_controller2_adaptive_digital_mode);
	syslog(LOG_ERR, "gain_control_compression_gain_db %u",
		config->gain_control_compression_gain_db);
	syslog(LOG_ERR, "gain_control_mode %u", config->agc_mode);
	syslog(LOG_ERR, "gain_control_enabled %u",
		config->gain_control_enabled);
	syslog(LOG_ERR, "noise_suppression_level %u", config->ns_level);
	syslog(LOG_ERR, "noise_suppression_enabled %u",
		config->noise_suppression_enabled);
}
