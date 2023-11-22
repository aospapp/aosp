# Copyright (c) 2018 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

AUTHOR = "brokk-team@google.com, brandstrom@google.com"
NAME = "video_VEAPerf.cfm"
PURPOSE = "Monitor the performance of the Chromium VEA."
CRITERIA = """
The test fails if the video_encode_accelerator_unittest fails or crashes.
"""
ATTRIBUTES = "suite:hotrod"
TIME = "MEDIUM"
TEST_CATEGORY = "Performance"
TEST_CLASS = "video"
TEST_TYPE = "client"
DEPENDENCIES = "hw_video_acc_enc_vp8"

DOC = """
This test measures the performance of the Chromium hardware VP8 Video Encode
Accelerator in CFM use cases.
"""

test_cases = [
#HD
    ('tulip2/tulip2-1280x720-1b95123232922fe0067869c74e19cd09.yuv', True, 1280, 720, 1200000, 11, 30),
    ('video_tests/webrtc/tulip2-1280x720-15fps-a99da6813fa301b30829d0a1453f8b04.yuv', True, 1280, 720, 1200000, 11, 30),
    ('video_tests/webrtc/tulip2-1280x720-7.5fps-148959fd8d1bad5f17fabeaa415c9fb6.yuv', True, 1280, 720, 1200000, 11, 30),
#VGA
    ('tulip2/tulip2-640x360-094bd827de18ca196a83cc6442b7b02f.yuv', True, 640, 360, 500000, 11, 30),
    ('video_tests/webrtc/tulip2-640x360-15fps-f8398a7fb80f20dec9888ac4ec209064.yuv', True, 640, 360, 500000, 11, 30),
    ('video_tests/webrtc/tulip2-640x360-7.5fps-382414b124a86397b5eb2b86f461d78a.yuv', True, 640, 360, 500000, 11, 30),
#QVGA
    ('tulip2/tulip2-320x180-55be7124b3aec1b72bfb57f433297193.yuv', True, 320, 180, 17300, 11, 30),
    ('video_tests/webrtc/tulip2-320x180-15fps-8ab55614f72125fe29107146d6f027b2.yuv', True, 320, 180, 17300, 11, 30),
    ('video_tests/webrtc/tulip2-320x180-7.5fps-7632e656ab8f25ad1c5fc2d877320a4c.yuv', True, 320, 180, 17300, 11, 30),
#Present
    ('video_tests/webrtc/present-c9fe328d47aab9e632659e9069907a5f.yuv', True, 1920, 1080, 4000000, 11, 30),
]

job.run_test('video_VEAPerf', test_cases=test_cases,
             required_cap='hw_enc_vp8_1080_30', tag='cfm')
