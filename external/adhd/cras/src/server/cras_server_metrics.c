/* Copyright (c) 2013 The Chromium OS Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */

#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <syslog.h>

#include "cras_metrics.h"
#include "cras_main_message.h"
#include "cras_rstream.h"

const char kHighestInputHardwareLevel[] = "Cras.HighestInputHardwareLevel";
const char kHighestOutputHardwareLevel[] = "Cras.HighestOutputHardwareLevel";
const char kNoCodecsFoundMetric[] = "Cras.NoCodecsFoundAtBoot";
const char kStreamTimeoutMilliSeconds[] = "Cras.StreamTimeoutMilliSeconds";
const char kStreamCallbackThreshold[] = "Cras.StreamCallbackThreshold";
const char kStreamFlags[] = "Cras.StreamFlags";
const char kStreamSamplingFormat[] = "Cras.StreamSamplingFormat";
const char kStreamSamplingRate[] = "Cras.StreamSamplingRate";
const char kUnderrunsPerDevice[] = "Cras.UnderrunsPerDevice";

/* Type of metrics to log. */
enum CRAS_SERVER_METRICS_TYPE {
	HIGHEST_INPUT_HW_LEVEL,
	HIGHEST_OUTPUT_HW_LEVEL,
	LONGEST_FETCH_DELAY,
	NUM_UNDERRUNS,
	STREAM_CONFIG
};

struct cras_server_metrics_stream_config {
	unsigned cb_threshold;
	unsigned flags;
	int format;
	unsigned rate;
};

union cras_server_metrics_data {
	unsigned value;
	struct cras_server_metrics_stream_config stream_config;
};

struct cras_server_metrics_message {
	struct cras_main_message header;
	enum CRAS_SERVER_METRICS_TYPE metrics_type;
	union cras_server_metrics_data data;
};

static void init_server_metrics_msg(
		struct cras_server_metrics_message *msg,
		enum CRAS_SERVER_METRICS_TYPE type,
		union cras_server_metrics_data data)
{
	memset(msg, 0, sizeof(*msg));
	msg->header.type = CRAS_MAIN_METRICS;
	msg->header.length = sizeof(*msg);
	msg->metrics_type = type;
	msg->data = data;
}

int cras_server_metrics_highest_hw_level(unsigned hw_level,
		enum CRAS_STREAM_DIRECTION direction)
{
	struct cras_server_metrics_message msg;
	union cras_server_metrics_data data;
	int err;

	data.value = hw_level;

	switch (direction) {
	case CRAS_STREAM_INPUT:
		init_server_metrics_msg(&msg, HIGHEST_INPUT_HW_LEVEL, data);
		break;
	case CRAS_STREAM_OUTPUT:
		init_server_metrics_msg(&msg, HIGHEST_OUTPUT_HW_LEVEL, data);
		break;
	default:
		return 0;
	}

	err = cras_main_message_send((struct cras_main_message *)&msg);
	if (err < 0) {
		syslog(LOG_ERR,
		       "Failed to send metrics message: HIGHEST_HW_LEVEL");
		return err;
	}

	return 0;
}

int cras_server_metrics_longest_fetch_delay(unsigned delay_msec)
{
	struct cras_server_metrics_message msg;
	union cras_server_metrics_data data;
	int err;

	data.value = delay_msec;
	init_server_metrics_msg(&msg, LONGEST_FETCH_DELAY, data);
	err = cras_main_message_send((struct cras_main_message *)&msg);
	if (err < 0) {
		syslog(LOG_ERR,
		       "Failed to send metrics message: LONGEST_FETCH_DELAY");
		return err;
	}

	return 0;
}

int cras_server_metrics_num_underruns(unsigned num_underruns)
{
	struct cras_server_metrics_message msg;
	union cras_server_metrics_data data;
	int err;

	data.value = num_underruns;
	init_server_metrics_msg(&msg, NUM_UNDERRUNS, data);
	err = cras_main_message_send((struct cras_main_message *)&msg);
	if (err < 0) {
		syslog(LOG_ERR,
		       "Failed to send metrics message: NUM_UNDERRUNS");
		return err;
	}

	return 0;
}

int cras_server_metrics_stream_config(struct cras_rstream_config *config)
{
	struct cras_server_metrics_message msg;
	union cras_server_metrics_data data;
	int err;

	data.stream_config.cb_threshold = (unsigned)config->cb_threshold;
	data.stream_config.flags = (unsigned)config->flags;
	data.stream_config.format = (int)config->format->format;
	data.stream_config.rate = (unsigned)config->format->frame_rate;

	init_server_metrics_msg(&msg, STREAM_CONFIG, data);
	err = cras_main_message_send((struct cras_main_message *)&msg);
	if (err < 0) {
		syslog(LOG_ERR,
			"Failed to send metrics message: STREAM_CONFIG");
		return err;
	}

	return 0;
}

static void metrics_stream_config(
		struct cras_server_metrics_stream_config config)
{
	/* Logs stream callback threshold. */
	cras_metrics_log_sparse_histogram(kStreamCallbackThreshold,
					  config.cb_threshold);

	/* Logs stream flags. */
	cras_metrics_log_sparse_histogram(kStreamFlags,
					  config.flags);

	/* Logs stream sampling format. */
	cras_metrics_log_sparse_histogram(kStreamSamplingFormat,
					  config.format);

	/* Logs stream sampling rate. */
	cras_metrics_log_sparse_histogram(kStreamSamplingRate,
					  config.rate);
}

static void handle_metrics_message(struct cras_main_message *msg, void *arg)
{
	struct cras_server_metrics_message *metrics_msg =
			(struct cras_server_metrics_message *)msg;
	switch (metrics_msg->metrics_type) {
	case HIGHEST_INPUT_HW_LEVEL:
		cras_metrics_log_histogram(kHighestInputHardwareLevel,
				metrics_msg->data.value, 1, 10000, 20);
		break;
	case HIGHEST_OUTPUT_HW_LEVEL:
		cras_metrics_log_histogram(kHighestOutputHardwareLevel,
				metrics_msg->data.value, 1, 10000, 20);
		break;
	case LONGEST_FETCH_DELAY:
		cras_metrics_log_histogram(kStreamTimeoutMilliSeconds,
				metrics_msg->data.value, 1, 20000, 10);
		break;
	case NUM_UNDERRUNS:
		cras_metrics_log_histogram(kUnderrunsPerDevice,
				metrics_msg->data.value, 0, 1000, 10);
		break;
	case STREAM_CONFIG:
		metrics_stream_config(metrics_msg->data.stream_config);
		break;
	default:
		syslog(LOG_ERR, "Unknown metrics type %u",
		       metrics_msg->metrics_type);
		break;
	}

}

int cras_server_metrics_init() {
	cras_main_message_add_handler(CRAS_MAIN_METRICS,
				      handle_metrics_message, NULL);
	return 0;
}
