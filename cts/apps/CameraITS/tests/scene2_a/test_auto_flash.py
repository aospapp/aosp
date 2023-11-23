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
"""Verifies android.flash.mode parameters is applied when set."""


import logging
import os.path
from mobly import test_runner

import its_base_test
import camera_properties_utils
import capture_request_utils
import lighting_control_utils
import image_processing_utils
import its_session_utils

AE_MODES = {0: 'OFF', 1: 'ON', 2: 'ON_AUTO_FLASH', 3: 'ON_ALWAYS_FLASH',
            4: 'ON_AUTO_FLASH_REDEYE', 5: 'ON_EXTERNAL_FLASH'}
AE_STATES = {0: 'INACTIVE', 1: 'SEARCHING', 2: 'CONVERGED', 3: 'LOCKED',
             4: 'FLASH_REQUIRED', 5: 'PRECAPTURE'}
_GRAD_DELTA_ATOL = 50  # gradiant for tablets as screen aborbs energy
_MEAN_DELTA_ATOL = 50  # mean used for reflective charts
_NUM_FRAMES = 8
_PATCH_H = 0.25  # center 25%
_PATCH_W = 0.25
_PATCH_X = 0.5 - _PATCH_W/2
_PATCH_Y = 0.5 - _PATCH_H/2
_TEST_NAME = os.path.splitext(os.path.basename(__file__))[0]
VGA_W, VGA_H = 640, 480
_CAPTURE_INTENT_STILL_CAPTURE = 2
_AE_MODE_ON_AUTO_FLASH = 2


def take_captures(cam, auto_flash=False):
  req = capture_request_utils.auto_capture_request()
  req['android.control.captureIntent'] = _CAPTURE_INTENT_STILL_CAPTURE
  if auto_flash:
    req['android.control.aeMode'] = _AE_MODE_ON_AUTO_FLASH
  fmt = {'format': 'yuv', 'width': VGA_W, 'height': VGA_H}
  captures = []
  for _ in range(_NUM_FRAMES):
    one_capture = cam.do_capture(req, fmt)
    captures.append(one_capture)
  return captures


class AutoFlashTest(its_base_test.ItsBaseTest):
  """Test that the android.flash.mode parameter is applied."""

  def test_auto_flash(self):
    logging.debug('AE_MODES: %s', str(AE_MODES))
    logging.debug('AE_STATES: %s', str(AE_STATES))

    with its_session_utils.ItsSession(
        device_id=self.dut.serial,
        camera_id=self.camera_id,
        hidden_physical_id=self.hidden_physical_id) as cam:
      props = cam.get_camera_properties()
      props = cam.override_with_hidden_physical_camera_props(props)
      test_name = os.path.join(self.log_path, _TEST_NAME)

      # check SKIP conditions
      first_api_level = its_session_utils.get_first_api_level(self.dut.serial)
      camera_properties_utils.skip_unless(
          camera_properties_utils.flash(props) and
          first_api_level >= its_session_utils.ANDROID13_API_LEVEL)

      # establish connection with lighting controller
      arduino_serial_port = lighting_control_utils.lighting_control(
          self.lighting_cntl, self.lighting_ch)

      # turn OFF lights to darken scene
      lighting_control_utils.set_lighting_state(
          arduino_serial_port, self.lighting_ch, 'OFF')

      # turn OFF tablet to darken scene
      if self.tablet:
        output = self.tablet.adb.shell('dumpsys display | grep mScreenState')
        output_list = str(output.decode('utf-8')).strip().split(' ')
        for val in output_list:
          if 'ON' in val:
            self.tablet.adb.shell(['input', 'keyevent', 'KEYCODE_POWER'])

      no_flash_exp_x_iso = 0
      no_flash_mean = 0
      no_flash_grad = 0
      flash_exp_x_iso = []
      flash_means = []
      flash_grads = []

      # take captures with no flash as baseline: use last frame
      logging.debug('Taking reference frame(s) with no flash.')
      cam.do_3a(do_af=False)
      cap = take_captures(cam)[_NUM_FRAMES-1]
      metadata = cap['metadata']
      exp = int(metadata['android.sensor.exposureTime'])
      iso = int(metadata['android.sensor.sensitivity'])
      logging.debug('No auto_flash ISO: %d, exp: %d ns', iso, exp)
      logging.debug('AE_MODE (cap): %s',
                    AE_MODES[metadata['android.control.aeMode']])
      logging.debug('AE_STATE (cap): %s',
                    AE_STATES[metadata['android.control.aeState']])
      no_flash_exp_x_iso = exp * iso
      y, _, _ = image_processing_utils.convert_capture_to_planes(
          cap, props)
      patch = image_processing_utils.get_image_patch(
          y, _PATCH_X, _PATCH_Y, _PATCH_W, _PATCH_H)
      no_flash_mean = image_processing_utils.compute_image_means(
          patch)[0]*255
      no_flash_grad = image_processing_utils.compute_image_max_gradients(
          patch)[0]*255
      image_processing_utils.write_image(y, f'{test_name}_no_flash_Y.jpg')

      # log results
      logging.debug('No flash exposure X ISO %d', no_flash_exp_x_iso)
      logging.debug('No flash Y grad: %.4f', no_flash_grad)
      logging.debug('No flash Y mean: %.4f', no_flash_mean)

      # take captures with auto flash enabled
      logging.debug('Taking frames with auto flash enabled.')
      cam.do_3a(do_af=False, auto_flash=True)
      caps = take_captures(cam, auto_flash=True)

      # evaluate captured images
      for i in range(_NUM_FRAMES):
        logging.debug('frame # %d', i)
        metadata = caps[i]['metadata']
        exp = int(metadata['android.sensor.exposureTime'])
        iso = int(metadata['android.sensor.sensitivity'])
        logging.debug('ISO: %d, exp: %d ns', iso, exp)
        logging.debug('AE_MODE (cap): %s',
                      AE_MODES[metadata['android.control.aeMode']])
        ae_state = AE_STATES[metadata['android.control.aeState']]
        logging.debug('AE_STATE (cap): %s', ae_state)
        flash_exp_x_iso.append(exp*iso)
        y, _, _ = image_processing_utils.convert_capture_to_planes(
            caps[i], props)
        patch = image_processing_utils.get_image_patch(
            y, _PATCH_X, _PATCH_Y, _PATCH_W, _PATCH_H)
        flash_means.append(
            image_processing_utils.compute_image_means(patch)[0]*255)
        flash_grads.append(
            image_processing_utils.compute_image_max_gradients(patch)[0]*255)

        image_processing_utils.write_image(
            y, f'{test_name}_auto_flash_Y_{i}.jpg')

        if i == 0:
          if ae_state != AE_STATES[4]:  # FLASH_REQUIRED
            raise AssertionError('Scene not dark enough to trigger auto-flash. '
                                 'Check scene.')

      # log results
      logging.debug('Flash exposure X ISOs %s', str(flash_exp_x_iso))
      logging.debug('Flash frames Y grads: %s', str(flash_grads))
      logging.debug('Flash frames Y means: %s', str(flash_means))

      # turn lights back ON
      lighting_control_utils.set_lighting_state(
          arduino_serial_port, self.lighting_ch, 'ON')

      # assert correct behavior
      grad_delta = max(flash_grads) - no_flash_grad
      mean_delta = max(flash_means) - no_flash_mean
      if not (grad_delta > _GRAD_DELTA_ATOL or
              mean_delta > _MEAN_DELTA_ATOL):
        raise AssertionError(
            f'grad FLASH-OFF: {grad_delta:.3f}, ATOL: {_GRAD_DELTA_ATOL}, '
            f'mean FLASH-OFF: {mean_delta:.3f}, ATOL: {_MEAN_DELTA_ATOL}')

if __name__ == '__main__':
  test_runner.main()

