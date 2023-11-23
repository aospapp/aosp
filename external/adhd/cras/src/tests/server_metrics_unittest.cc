// Copyright 2016 The Chromium OS Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include <stdio.h>
#include <gtest/gtest.h>

extern "C" {
#include "cras_server_metrics.c"
#include "cras_main_message.h"
#include "cras_rstream.h"
}

static enum CRAS_MAIN_MESSAGE_TYPE type_set;
static struct cras_server_metrics_message *sent_msg;

void ResetStubData() {
  type_set = (enum CRAS_MAIN_MESSAGE_TYPE)0;
}

namespace {

TEST(ServerMetricsTestSuite, Init) {
  ResetStubData();

  cras_server_metrics_init();

  EXPECT_EQ(type_set, CRAS_MAIN_METRICS);
}

TEST(ServerMetricsTestSuite, SetMetricHighestHardwareLevel) {
  ResetStubData();
  unsigned int hw_level = 1000;
  sent_msg = (struct cras_server_metrics_message *)calloc(1, sizeof(*sent_msg));

  cras_server_metrics_highest_hw_level(hw_level, CRAS_STREAM_INPUT);

  EXPECT_EQ(sent_msg->header.type, CRAS_MAIN_METRICS);
  EXPECT_EQ(sent_msg->header.length, sizeof(*sent_msg));
  EXPECT_EQ(sent_msg->metrics_type, HIGHEST_INPUT_HW_LEVEL);
  EXPECT_EQ(sent_msg->data.value, hw_level);

  free(sent_msg);

  sent_msg = (struct cras_server_metrics_message *)calloc(1, sizeof(*sent_msg));

  cras_server_metrics_highest_hw_level(hw_level, CRAS_STREAM_OUTPUT);

  EXPECT_EQ(sent_msg->header.type, CRAS_MAIN_METRICS);
  EXPECT_EQ(sent_msg->header.length, sizeof(*sent_msg));
  EXPECT_EQ(sent_msg->metrics_type, HIGHEST_OUTPUT_HW_LEVEL);
  EXPECT_EQ(sent_msg->data.value, hw_level);

  free(sent_msg);
}

TEST(ServerMetricsTestSuite, SetMetricsLongestFetchDelay) {
  ResetStubData();
  unsigned int delay = 100;
  sent_msg = (struct cras_server_metrics_message *)calloc(1, sizeof(*sent_msg));

  cras_server_metrics_longest_fetch_delay(delay);

  EXPECT_EQ(sent_msg->header.type, CRAS_MAIN_METRICS);
  EXPECT_EQ(sent_msg->header.length, sizeof(*sent_msg));
  EXPECT_EQ(sent_msg->metrics_type, LONGEST_FETCH_DELAY);
  EXPECT_EQ(sent_msg->data.value, delay);

  free(sent_msg);
}

TEST(ServerMetricsTestSuite, SetMetricsNumUnderruns) {
  ResetStubData();
  unsigned int underrun = 10;
  sent_msg = (struct cras_server_metrics_message *)calloc(1, sizeof(*sent_msg));

  cras_server_metrics_num_underruns(underrun);

  EXPECT_EQ(sent_msg->header.type, CRAS_MAIN_METRICS);
  EXPECT_EQ(sent_msg->header.length, sizeof(*sent_msg));
  EXPECT_EQ(sent_msg->metrics_type, NUM_UNDERRUNS);
  EXPECT_EQ(sent_msg->data.value, underrun);

  free(sent_msg);
}

TEST(ServerMetricsTestSuite, SetMetricsStreamConfig) {
  ResetStubData();
  struct cras_rstream_config config;
  struct cras_audio_format format;
  sent_msg = (struct cras_server_metrics_message *)calloc(1, sizeof(*sent_msg));

  config.cb_threshold = 1024;
  config.flags = BULK_AUDIO_OK;
  format.format = SND_PCM_FORMAT_S16_LE;
  format.frame_rate = 48000;

  config.format = &format;
  cras_server_metrics_stream_config(&config);

  EXPECT_EQ(sent_msg->header.type, CRAS_MAIN_METRICS);
  EXPECT_EQ(sent_msg->header.length, sizeof(*sent_msg));
  EXPECT_EQ(sent_msg->metrics_type, STREAM_CONFIG);
  EXPECT_EQ(sent_msg->data.stream_config.cb_threshold, 1024);
  EXPECT_EQ(sent_msg->data.stream_config.flags, BULK_AUDIO_OK);
  EXPECT_EQ(sent_msg->data.stream_config.format, SND_PCM_FORMAT_S16_LE);
  EXPECT_EQ(sent_msg->data.stream_config.rate, 48000);

  free(sent_msg);
}

extern "C" {

int cras_main_message_add_handler(enum CRAS_MAIN_MESSAGE_TYPE type,
                                  cras_message_callback callback,
                                  void *callback_data) {
  type_set = type;
  return 0;
}

void cras_metrics_log_histogram(const char *name, int sample, int min,
                                int max, int nbuckets) {
}

void cras_metrics_log_sparse_histogram(const char *name, int sample)
{
}

int cras_main_message_send(struct cras_main_message *msg) {
  // Copy the sent message so we can examine it in the test later.
  memcpy(sent_msg, msg, sizeof(*sent_msg));
  return 0;
};

}  // extern "C"
}  // namespace

int main(int argc, char **argv) {
  ::testing::InitGoogleTest(&argc, argv);
  int rc = RUN_ALL_TESTS();

  return rc;
}
