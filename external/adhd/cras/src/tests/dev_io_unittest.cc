// Copyright 2017 The Chromium OS Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include <memory>
#include <stdint.h>
#include <stdio.h>
#include <time.h>

#include <gtest/gtest.h>

extern "C" {
#include "dev_io.h" // tested
#include "dev_stream.h" // tested
#include "cras_rstream.h" // stubbed
#include "cras_iodev.h" // stubbed
#include "cras_shm.h"
#include "cras_types.h"
#include "utlist.h"

struct audio_thread_event_log* atlog;
}

#include "dev_io_stubs.h"
#include "iodev_stub.h"
#include "rstream_stub.h"

namespace {


class DevIoSuite : public testing::Test{
 protected:
  virtual void SetUp() {
    atlog = static_cast<audio_thread_event_log*>(calloc(1, sizeof(*atlog)));
    iodev_stub_reset();
    rstream_stub_reset();
  }

  virtual void TearDown() {
    free(atlog);
  }
};

TEST_F(DevIoSuite, SendCapturedFails) {
  const size_t cb_threshold = 480;

  cras_audio_format format;
  fill_audio_format(&format, 48000);

  StreamPtr stream =
      create_stream(1, 1, CRAS_STREAM_INPUT, cb_threshold, &format);
  // rstream's next callback is now and there is enough data to fill.
  struct timespec start;
  clock_gettime(CLOCK_MONOTONIC_RAW, &start);
  stream->rstream->next_cb_ts = start;
  AddFakeDataToStream(stream.get(), 480);

  struct open_dev* dev_list = NULL;
  DevicePtr dev = create_device(CRAS_STREAM_INPUT, cb_threshold,
                                &format, CRAS_NODE_TYPE_MIC);
  DL_APPEND(dev_list, dev->odev.get());
  add_stream_to_dev(dev->dev, stream);

  // Set failure response from frames_queued.
  iodev_stub_frames_queued(dev->dev.get(), -3, start);

  EXPECT_EQ(-3, dev_io_send_captured_samples(dev_list));
}

/* Stubs */
extern "C" {

int cras_server_metrics_highest_hw_level(unsigned hw_level,
		enum CRAS_STREAM_DIRECTION direction)
{
  return 0;
}

int cras_server_metrics_longest_fetch_delay(unsigned delay_msec)
{
  return 0;
}

int cras_server_metrics_num_underruns(unsigned num_underruns)
{
  return 0;
}

int input_data_get_for_stream(
		struct input_data *data,
		struct cras_rstream *stream,
		struct buffer_share *offsets,
		struct cras_audio_area **area,
		unsigned int *offset)
{
  return 0;
}

int input_data_put_for_stream(struct input_data *data,
			   struct cras_rstream *stream,
			   struct buffer_share *offsets,
			   unsigned int frames)
{
  return 0;
}
struct cras_audio_format *cras_rstream_post_processing_format(
    const struct cras_rstream *stream, void *dev_ptr)
{
  return NULL;
}
}  // extern "C"

}  //  namespace

int main(int argc, char** argv) {
  ::testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}
