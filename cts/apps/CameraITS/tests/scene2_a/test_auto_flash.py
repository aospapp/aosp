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
"""Verifies that flash is fired when lighting conditions are dark."""


import logging
import os.path

from mobly import test_runner

import its_base_test
import camera_properties_utils
import capture_request_utils
import image_processing_utils
import its_session_utils
import lighting_control_utils

_AE_MODES = {0: 'OFF', 1: 'ON', 2: 'ON_AUTO_FLASH', 3: 'ON_ALWAYS_FLASH',
             4: 'ON_AUTO_FLASH_REDEYE', 5: 'ON_EXTERNAL_FLASH'}
_AE_STATES = {0: 'INACTIVE', 1: 'SEARCHING', 2: 'CONVERGED', 3: 'LOCKED',
              4: 'FLASH_REQUIRED', 5: 'PRECAPTURE'}
_FLASH_STATES = {0: 'FLASH_STATE_UNAVAILABLE', 1: 'FLASH_STATE_CHARGING',
                 2: 'FLASH_STATE_READY', 3: 'FLASH_STATE_FIRED',
                 4: 'FLASH_STATE_PARTIAL'}
_GRAD_DELTA_ATOL = 15  # gradiant for tablets as screen aborbs energy
_MEAN_DELTA_ATOL = 15  # mean used for reflective charts

_FORMAT_NAMES = ('jpeg', 'yuv')
_IMG_SIZES = ((640, 480), (640, 360))
_VGA_SIZE = (640, 480)

_PATCH_H = 0.25  # center 25%
_PATCH_W = 0.25
_PATCH_X = 0.5 - _PATCH_W/2
_PATCH_Y = 0.5 - _PATCH_H/2
_TEST_NAME = os.path.splitext(os.path.basename(__file__))[0]
_CAPTURE_INTENT_STILL_CAPTURE = 2


class AutoFlashTest(its_base_test.ItsBaseTest):
  """Test that flash is fired when lighting conditions are dark."""

  def test_auto_flash(self):
    logging.debug('AE_MODES: %s', str(_AE_MODES))
    logging.debug('AE_STATES: %s', str(_AE_STATES))
    logging.debug('FLASH_STATES: %s', str(_FLASH_STATES))
    failure_messages = []

    with its_session_utils.ItsSession(
        device_id=self.dut.serial,
        camera_id=self.camera_id,
        hidden_physical_id=self.hidden_physical_id) as cam:
      props = cam.get_camera_properties()
      props = cam.override_with_hidden_physical_camera_props(props)
      test_name = os.path.join(self.log_path, _TEST_NAME)

      # check SKIP conditions
      vendor_api_level = its_session_utils.get_vendor_api_level(self.dut.serial)
      camera_properties_utils.skip_unless(
          camera_properties_utils.flash(props) and
          vendor_api_level >= its_session_utils.ANDROID13_API_LEVEL)

      # establish connection with lighting controller
      arduino_serial_port = lighting_control_utils.lighting_control(
          self.lighting_cntl, self.lighting_ch)

      # turn OFF lights to darken scene
      lighting_control_utils.set_lighting_state(
          arduino_serial_port, self.lighting_ch, 'OFF')

      # turn OFF tablet to darken scene
      if self.tablet:
        lighting_control_utils.turn_off_device(self.tablet)

      for fmt_name in _FORMAT_NAMES:
        for size in _IMG_SIZES:
          width, height = size
          if not (fmt_name == 'yuv' and size == _VGA_SIZE):
            output_sizes = capture_request_utils.get_available_output_sizes(
                fmt_name, props, match_ar_size=size)
            if not output_sizes:
              if size != _VGA_SIZE:
                logging.debug('No output sizes for format %s, size %sx%s',
                              fmt_name, width, height)
                continue
              else:
                raise AssertionError(f'No output sizes for format {fmt_name}, '
                                     f'size {width}x{height}')
            # pick smallest size out of available output sizes
            width, height = output_sizes[-1]
          out_surfaces = {'format': fmt_name, 'width': width, 'height': height}
          logging.debug('Testing %s format, size: %dx%d',
                        fmt_name, width, height)
          no_flash_exp_x_iso = 0
          no_flash_mean = 0
          no_flash_grad = 0
          flash_exp_x_iso = []

          # take capture with no flash as baseline
          logging.debug('Taking reference frame with no flash.')
          cam.do_3a(do_af=False)
          no_flash_req = capture_request_utils.auto_capture_request()
          no_flash_req[
              'android.control.captureIntent'] = _CAPTURE_INTENT_STILL_CAPTURE
          cap = cam.do_capture(no_flash_req, out_surfaces)
          metadata = cap['metadata']
          exp = int(metadata['android.sensor.exposureTime'])
          iso = int(metadata['android.sensor.sensitivity'])
          logging.debug('No auto_flash ISO: %d, exp: %d ns', iso, exp)
          logging.debug('AE_MODE (cap): %s',
                        _AE_MODES[metadata['android.control.aeMode']])
          logging.debug('AE_STATE (cap): %s',
                        _AE_STATES[metadata['android.control.aeState']])
          no_flash_exp_x_iso = exp * iso
          y, _, _ = image_processing_utils.convert_capture_to_planes(
              cap, props)
          patch = image_processing_utils.get_image_patch(
              y, _PATCH_X, _PATCH_Y, _PATCH_W, _PATCH_H)
          no_flash_mean = image_processing_utils.compute_image_means(
              patch)[0]*255
          no_flash_grad = image_processing_utils.compute_image_max_gradients(
              patch)[0]*255
          image_processing_utils.write_image(
              y, f'{test_name}_{fmt_name}_{width}x{height}_no_flash_Y.jpg')

          # log results
          logging.debug('No flash exposure X ISO %d', no_flash_exp_x_iso)
          logging.debug('No flash Y grad: %.4f', no_flash_grad)
          logging.debug('No flash Y mean: %.4f', no_flash_mean)

          # take capture with auto flash enabled
          logging.debug('Taking capture with auto flash enabled.')
          flash_fired = False

          cap = capture_request_utils.take_captures_with_flash(cam, out_surfaces)
          y, _, _ = image_processing_utils.convert_capture_to_planes(
              cap, props)
          # Save captured image
          image_processing_utils.write_image(
              y, f'{test_name}_{fmt_name}_{width}x{height}_flash_Y.jpg')
          # evaluate captured image
          metadata = cap['metadata']
          exp = int(metadata['android.sensor.exposureTime'])
          iso = int(metadata['android.sensor.sensitivity'])
          logging.debug('cap ISO: %d, exp: %d ns', iso, exp)
          logging.debug('AE_MODE (cap): %s',
                        _AE_MODES[metadata['android.control.aeMode']])
          ae_state = _AE_STATES[metadata['android.control.aeState']]
          logging.debug('AE_STATE (cap): %s', ae_state)
          flash_state = _FLASH_STATES[metadata['android.flash.state']]
          logging.debug('FLASH_STATE: %s', flash_state)
          if flash_state == 'FLASH_STATE_FIRED':
            logging.debug('Flash fired')
            flash_fired = True
            flash_exp_x_iso = exp*iso
            y, _, _ = image_processing_utils.convert_capture_to_planes(
                cap, props)
            patch = image_processing_utils.get_image_patch(
                y, _PATCH_X, _PATCH_Y, _PATCH_W, _PATCH_H)
            flash_mean = image_processing_utils.compute_image_means(
                patch)[0]*255
            flash_grad = image_processing_utils.compute_image_max_gradients(
                patch)[0]*255

          if not flash_fired:
            raise AssertionError('Flash was not fired. '
                                 f'Format: {fmt_name}, Size: {width}x{height}')

          # log results
          logging.debug('Flash exposure X ISO %d', flash_exp_x_iso)
          logging.debug('Flash frames Y grad: %.4f', flash_grad)
          logging.debug('Flash frames Y mean: %.4f', flash_mean)

          # document incorrect behavior
          grad_delta = flash_grad - no_flash_grad
          mean_delta = flash_mean - no_flash_mean
          if not (grad_delta > _GRAD_DELTA_ATOL or
                  mean_delta > _MEAN_DELTA_ATOL):
            failure_messages.append(
                f'format: {fmt_name}, size: {width}x{height}, '
                f'grad FLASH-OFF: {grad_delta:.3f}, ATOL: {_GRAD_DELTA_ATOL}, '
                f'mean FLASH-OFF: {mean_delta:.3f}, ATOL: {_MEAN_DELTA_ATOL}')

          # Ensure that the flash is turned OFF after flash was fired.
          req = capture_request_utils.auto_capture_request()
          req['android.control.captureIntent'] = _CAPTURE_INTENT_STILL_CAPTURE
          cap = cam.do_capture(req, out_surfaces)
          flash_state_after = _FLASH_STATES[cap['metadata']
                                            ['android.flash.state']]
          logging.debug('FLASH_STATE after flash fired: %s', flash_state_after)
          if flash_state_after != 'FLASH_STATE_READY':
            raise AssertionError('Flash should turn OFF after it was fired.')

      # turn lights back ON
      lighting_control_utils.set_lighting_state(
          arduino_serial_port, self.lighting_ch, 'ON')

      # assert correct behavior for all formats
      if failure_messages:
        raise AssertionError('\n'.join(failure_messages))

if __name__ == '__main__':
  test_runner.main()

