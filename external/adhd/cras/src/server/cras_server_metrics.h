/* Copyright (c) 2013 The Chromium OS Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */

#ifndef CRAS_SERVER_METRICS_H_
#define CRAS_SERVER_METRICS_H_

#include "cras_rstream.h"

extern const char kNoCodecsFoundMetric[];
extern const char kHighestInputHardwareLevel[];
extern const char kHighestOutputHardwareLevel[];
extern const char kStreamTimeoutMilliSeconds[];
extern const char kStreamCallbackThreshold[];
extern const char kStreamFlags[];
extern const char kStreamSamplingFormat[];
extern const char kStreamSamplingRate[];
extern const char kUnderrunsPerDevice[];

/* Logs the highest hardware level of a device. */
int cras_server_metrics_highest_hw_level(unsigned hw_level,
		enum CRAS_STREAM_DIRECTION direction);

/* Logs the longest fetch delay of a stream in millisecond. */
int cras_server_metrics_longest_fetch_delay(int delay_msec);

/* Logs the number of underruns of a device. */
int cras_server_metrics_num_underruns(unsigned num_underruns);

/* Logs the stream configurations from clients. */
int cras_server_metrics_stream_config(struct cras_rstream_config *config);

/* Initialize metrics logging stuff. */
int cras_server_metrics_init();

#endif /* CRAS_SERVER_METRICS_H_ */

