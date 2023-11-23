# Copyright 2013 The Android Open Source Project
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
"""Verifies android.sensor.exposureTime parameter."""


import logging
import os.path
import matplotlib
from matplotlib import pylab
from mobly import test_runner

import its_base_test
import camera_properties_utils
import capture_request_utils
import image_processing_utils
import its_session_utils
import target_exposure_utils

_COLORS = ('R', 'G', 'B')
_EXP_MULT_FACTORS = (0.8, 0.9, 1.0, 1.1, 1.2)  # vary exposure +/- 20%
_NAME = os.path.splitext(os.path.basename(__file__))[0]
_PATCH_H = 0.1  # center 10%
_PATCH_W = 0.1
_PATCH_X = 0.5 - _PATCH_W/2
_PATCH_Y = 0.5 - _PATCH_H/2


class ParamExposureTimeTest(its_base_test.ItsBaseTest):
  """Test that the android.sensor.exposureTime parameter is applied."""

  def test_param_exposure_time(self):
    logging.debug('Starting %s', _NAME)
    exp_times = []
    r_means = []
    g_means = []
    b_means = []
    with its_session_utils.ItsSession(
        device_id=self.dut.serial,
        camera_id=self.camera_id,
        hidden_physical_id=self.hidden_physical_id) as cam:
      props = cam.get_camera_properties()
      props = cam.override_with_hidden_physical_camera_props(props)
      log_path = self.log_path
      name_with_log_path = os.path.join(log_path, _NAME)

      # check SKIP conditions
      camera_properties_utils.skip_unless(
          camera_properties_utils.compute_target_exposure(props))

      # Load chart for scene
      its_session_utils.load_scene(
          cam, props, self.scene, self.tablet,
          its_session_utils.CHART_DISTANCE_NO_SCALING)

      # Create requests
      sync_latency = camera_properties_utils.sync_latency(props)
      largest_yuv = capture_request_utils.get_largest_yuv_format(props)
      match_ar = (largest_yuv['width'], largest_yuv['height'])
      fmt = capture_request_utils.get_near_vga_yuv_format(
          props, match_ar=match_ar)
      e, s = target_exposure_utils.get_target_exposure_combos(
          log_path, cam)['midExposureTime']

      # Do captures & process images
      for i, e_mult in enumerate(_EXP_MULT_FACTORS):
        req = capture_request_utils.manual_capture_request(
            s, e * e_mult, 0.0, True, props)
        cap = its_session_utils.do_capture_with_latency(
            cam, req, sync_latency, fmt)
        img = image_processing_utils.convert_capture_to_rgb_image(cap)
        image_processing_utils.write_image(
            img, f'{name_with_log_path}_frame{i}.jpg')
        patch = image_processing_utils.get_image_patch(
            img, _PATCH_X, _PATCH_Y, _PATCH_W, _PATCH_H)
        rgb_means = image_processing_utils.compute_image_means(patch)
        logging.debug('RGB means: %s', str(rgb_means))
        exp_times.append(e * e_mult)
        r_means.append(rgb_means[0])
        g_means.append(rgb_means[1])
        b_means.append(rgb_means[2])

    # Draw plot
    pylab.figure(_NAME)
    for ch, means in enumerate([r_means, g_means, b_means]):
      pylab.plot(exp_times, means, '-'+'rgb'[ch]+'o')
    pylab.ylim([0, 1])
    pylab.title(_NAME)
    pylab.xlabel('Exposure times (ns)')
    pylab.ylabel('RGB means')
    matplotlib.pyplot.savefig(f'{name_with_log_path}_plot_means.png')

    # Assert each shot is brighter than previous.
    for ch, means in enumerate([r_means, g_means, b_means]):
      for i in range(len(_EXP_MULT_FACTORS)-1):
        if means[i+1] <= means[i]:
          raise AssertionError(f'{_COLORS[ch]} not increasing in brightness! '
                               f'{_COLORS[ch]}[i+1]: {means[i+1]:.4f}, '
                               f'{_COLORS[ch]}[i]: {means[i]:.4f}')

if __name__ == '__main__':
  test_runner.main()

