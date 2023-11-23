# Copyright (c) 2018 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

AUTHOR = "brokk-team@google.com, brandstrom@google.com"
NAME = "video_VDAPerf.cfm"
PURPOSE = "Monitor the performance of the Chromium hardware VDA."
CRITERIA = """
The test fails if the video_decode_accelerator_unittest fails or crashes.
"""
ATTRIBUTES = "suite:hotrod"
TIME = "MEDIUM"
TEST_CATEGORY = "Performance"
TEST_CLASS = "video"
TEST_TYPE = "client"

DOC = """
This test measures the performance of the Chromium hardware VP8 Video Decode 
Accelerator for VP8 in CFM use cases.
"""

# A list of test cases. Each entry consists of the following parts:
#   [download_path, width, height, frame_num, fragment_num, profile, fps, required_cap]
test_cases = [
    ['crowd/crowd1080-1edaaca36b67e549c51e5fea4ed545c3.vp8', 1920, 1080, 500, 539, 11, 50, 'hw_dec_vp8_1080_60'],
    ['crowd/crowd720-41e9a3e6a6b1644ebdb3f5723fce96e5.vp8', 1280, 720, 500, 534, 11, 50, 'hw_dec_vp8_1080_30'],
    ['crowd/crowd360-20dab55f01a63b8a0d12272c01bd6a97.vp8', 640, 360, 500, 532, 11, 50, 'hw_dec_vp8_1080_30'],
    ['crowd/crowd180-bde69ca71393033d9187a2833720c9f8.vp8', 320, 180, 500, 500, 11, 50, 'hw_dec_vp8_1080_30'],

]

job.run_test('video_VDAPerf', test_cases=test_cases, tag='cfm')
