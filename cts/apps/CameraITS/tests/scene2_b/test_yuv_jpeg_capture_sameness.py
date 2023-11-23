# Copyright 2022 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Verifies JPEG and YUV still capture images are pixel-wise matching."""


import logging
import os.path
from mobly import test_runner

import its_base_test
import camera_properties_utils
import capture_request_utils
import image_processing_utils
import its_session_utils

_MAX_IMG_SIZE = (1920, 1440)
_NAME = os.path.splitext(os.path.basename(__file__))[0]
_THRESHOLD_MAX_RMS_DIFF = 0.01
_USE_CASE_STILL_CAPTURE = 2


class YuvJpegCaptureSamenessTest(its_base_test.ItsBaseTest):
  """Test capturing a single frame as both YUV and JPEG outputs."""

  def test_yuv_jpeg_capture_sameness(self):
    logging.debug('Starting %s', _NAME)
    with its_session_utils.ItsSession(
        device_id=self.dut.serial,
        camera_id=self.camera_id,
        hidden_physical_id=self.hidden_physical_id) as cam:
      props = cam.get_camera_properties()
      props = cam.override_with_hidden_physical_camera_props(props)
      log_path = self.log_path

      # check SKIP conditions
      camera_properties_utils.skip_unless(
          camera_properties_utils.stream_use_case(props))

      # Load chart for scene
      its_session_utils.load_scene(
          cam, props, self.scene, self.tablet, self.chart_distance)

      # Create requests
      max_jpeg_size = capture_request_utils.get_available_output_sizes(
          'jpeg', props)[0]
      if capture_request_utils.is_common_aspect_ratio(max_jpeg_size):
        w, h = capture_request_utils.get_available_output_sizes(
            'yuv', props, _MAX_IMG_SIZE, max_jpeg_size)[0]
      else:
        w, h = capture_request_utils.get_available_output_sizes(
            'yuv', props, max_size=_MAX_IMG_SIZE)[0]
      fmt_yuv = {'format': 'yuv', 'width': w, 'height': h,
                 'useCase': _USE_CASE_STILL_CAPTURE}
      fmt_jpg = {'format': 'jpeg', 'width': w, 'height': h,
                 'useCase': _USE_CASE_STILL_CAPTURE}
      logging.debug('YUV & JPEG stream width: %d, height: %d', w, h)

      cam.do_3a()
      req = capture_request_utils.auto_capture_request()
      req['android.jpeg.quality'] = 100

      cap_yuv, cap_jpg = cam.do_capture(req, [fmt_yuv, fmt_jpg])
      rgb_yuv = image_processing_utils.convert_capture_to_rgb_image(
          cap_yuv, True)
      file_stem = os.path.join(log_path, _NAME)
      image_processing_utils.write_image(rgb_yuv, f'{file_stem}_yuv.jpg')
      rgb_jpg = image_processing_utils.convert_capture_to_rgb_image(
          cap_jpg, True)
      image_processing_utils.write_image(rgb_jpg, f'{file_stem}_jpg.jpg')

      rms_diff = image_processing_utils.compute_image_rms_difference_3d(
          rgb_yuv, rgb_jpg)
      msg = f'RMS diff: {rms_diff:.4f}'
      logging.debug('%s', msg)
      if rms_diff >= _THRESHOLD_MAX_RMS_DIFF:
        raise AssertionError(msg + f', spec: {_THRESHOLD_MAX_RMS_DIFF}')

if __name__ == '__main__':
  test_runner.main()
