# Copyright 2023 The Android Open Source Project
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
"""Verify night extension is activated correctly when requested."""


import logging
import os.path
import time

from mobly import test_runner
import numpy as np

import its_base_test
import camera_properties_utils
import capture_request_utils
import image_processing_utils
import its_session_utils
import lighting_control_utils
import opencv_processing_utils

_NAME = os.path.splitext(os.path.basename(__file__))[0]
_EXTENSION_NIGHT = 4  # CameraExtensionCharacteristics.EXTENSION_NIGHT
_TAP_COORDINATES = (500, 500)  # Location to tap tablet screen via adb
_TEST_REQUIRED_MPC = 34
_MIN_AREA = 0
_WHITE = 255

_FMT_NAME = 'yuv'  # To detect noise without conversion to RGB
_WIDTH = 1920   # Most common 16/9 resolution width
_HEIGHT = 1080  # Most common 16/9 resolution height

_DOT_INTENSITY_DIFF_TOL = 20  # Min diff between dot/circle intensities [0:255]
_DURATION_DIFF_TOL = 0.5  # Night mode ON captures must take 0.5 seconds longer
_EDGE_NOISE_WIDTH = 0.1  # Edge is left 10% of image and right 10% of image
_EDGE_NOISE_IMPROVEMENT_TOL = 1.5  # Edge noise must be reduced by at least 67%
_INTENSITY_IMPROVEMENT_TOL = 1.1  # Night mode ON captures must be 10% brighter

_R_STRING = 'r'
_X_STRING = 'x'
_Y_STRING = 'y'


def _get_dots_from_circle(circle):
  """Calculates dot locations using the surrounding outer circle.

  Args:
    circle: dictionary; outer circle
  Returns:
    List of dict; inner circles (dots)
  """
  circle_x = int(circle[_X_STRING])
  circle_y = int(circle[_Y_STRING])
  offset = int(circle[_R_STRING] // 2)  # Dot location from scene definition
  dots = [
      {_X_STRING: circle_x + offset, _Y_STRING: circle_y - offset},
      {_X_STRING: circle_x - offset, _Y_STRING: circle_y - offset},
      {_X_STRING: circle_x - offset, _Y_STRING: circle_y + offset},
      {_X_STRING: circle_x + offset, _Y_STRING: circle_y + offset},
  ]
  return dots


def _convert_captures(cap, file_stem=None):
  """Obtains y plane and numpy image from a capture.

  Args:
    cap: A capture object as returned by its_session_utils.do_capture.
    file_stem: str; location and name to save files.
  Returns:
    Tuple of y_plane, numpy image.
  """
  y, _, _ = image_processing_utils.convert_capture_to_planes(cap)
  img = image_processing_utils.convert_capture_to_rgb_image(cap)
  if file_stem:
    image_processing_utils.write_image(img, f'{file_stem}.jpg')
  return y, image_processing_utils.convert_image_to_uint8(img)


def _get_left_patch(y):
  """Return the left edge of a y plane according to _EDGE_NOISE_WIDTH.

  Args:
    y: y_plane from a capture.
  Returns:
    Cropped y_plane on the left edge of the capture.
  """
  return image_processing_utils.get_image_patch(
      y, 0, 0, _EDGE_NOISE_WIDTH, 1)


def _get_right_patch(y):
  """Return the right edge of a y plane according to _EDGE_NOISE_WIDTH.

  Args:
    y: y_plane from a capture.
  Returns:
    Cropped y_plane on the right edge of the capture.
  """
  return image_processing_utils.get_image_patch(
      y, 1 - _EDGE_NOISE_WIDTH, 0, _EDGE_NOISE_WIDTH, 1)


def _get_edge_noise(night_y, no_night_y):
  """Computes the left and right edge noise in the y plane.

  Args:
    night_y: y_plane from a capture with night mode ON.
    no_night_y: y_plane from a capture with night mode OFF.
  Returns:
    float; ratio of left edge noise, float; ratio of right edge noise.
  """
  night_left_patch_std = np.std(_get_left_patch(night_y), axis=(0, 1))
  no_night_left_patch_std = np.std(_get_left_patch(no_night_y), axis=(0, 1))
  night_right_patch_std = np.std(_get_right_patch(night_y), axis=(0, 1))
  no_night_right_patch_std = np.std(_get_right_patch(no_night_y), axis=(0, 1))
  logging.debug('Night mode ON left patch noise: %.2f', night_left_patch_std)
  logging.debug('Night mode OFF left patch noise: %.2f',
                no_night_left_patch_std)
  logging.debug('Night mode ON right patch noise: %.2f', night_right_patch_std)
  logging.debug('Night mode OFF right patch noise: %.2f',
                no_night_right_patch_std)
  left_patch_ratio = no_night_left_patch_std / night_left_patch_std
  right_patch_ratio = no_night_right_patch_std / night_right_patch_std
  return left_patch_ratio, right_patch_ratio


def _check_overall_intensity(night_img, no_night_img):
  """Checks that overall intensity significantly improves with night mode ON.

  Args:
    night_img: numpy image taken with night mode ON
    no_night_img: numpy image taken with night mode OFF
  """
  night_mean = np.mean(night_img)
  no_night_mean = np.mean(no_night_img)
  overall_intensity_ratio = night_mean / no_night_mean
  logging.debug('Night mode ON overall mean: %.2f', night_mean)
  logging.debug('Night mode OFF overall mean: %.2f', no_night_mean)
  if overall_intensity_ratio < _INTENSITY_IMPROVEMENT_TOL:
    raise AssertionError('Night mode ON image was not significantly more '
                         'intense than night mode OFF image! '
                         f'Ratio: {overall_intensity_ratio:.2f}, '
                         f'Expected: {_INTENSITY_IMPROVEMENT_TOL}')


def _check_edge_noise(night_y, no_night_y):
  """Checks that noise at the edges significantly improves with night mode ON.

  Args:
    night_y: y_plane from a capture with night mode ON.
    no_night_y: y_plane from a capture with night mode OFF.
  """
  # Normalize both y planes to [0, 255]
  night_y *= 255
  no_night_y *= 255
  left_patch_ratio, right_patch_ratio = _get_edge_noise(
      night_y, no_night_y)
  logging.debug('Left edge of night mode OFF capture was %.2f times '
                'as noisy as night mode ON', left_patch_ratio)
  logging.debug('Right edge of night mode OFF capture was %.2f times '
                'as noisy as night mode ON', right_patch_ratio)
  if left_patch_ratio < _EDGE_NOISE_IMPROVEMENT_TOL:
    raise AssertionError('Left edge of night mode OFF capture was only '
                         f'{left_patch_ratio:.2f} as noisy as '
                         'night mode OFF, expected to be '
                         f'at least {_EDGE_NOISE_IMPROVEMENT_TOL}')
  if right_patch_ratio < _EDGE_NOISE_IMPROVEMENT_TOL:
    raise AssertionError('Right edge of night mode OFF capture was only '
                         f'{right_patch_ratio:.2f} as noisy as '
                         'night mode OFF, expected to be '
                         f'at least {_EDGE_NOISE_IMPROVEMENT_TOL}')


class NightExtensionTest(its_base_test.ItsBaseTest):
  """Tests night extension under dark lighting conditions.

  When lighting conditions are dark:
  1. Sets tablet to highest brightness where the orientation circle is visible.
  2. Takes capture with night extension ON using an auto capture request.
  3. Takes capture with night extension OFF using an auto capture request.
  Verifies that the capture with night mode ON:
    * takes longer
    * is brighter
    * contains less edge noise
  """

  def find_tablet_brightness(self, cam, default_brightness, file_stem,
                             use_extensions=True):
    """Find maximum brightness at which orientation circle in scene is visible.

    Uses binary search on a range of (0, default_brightness), where visibility
    is defined by an intensity comparison with the center of the outer circle.

    Args:
      cam: its_session_utils object.
      default_brightness: int; brightness set by config.yml.
      file_stem: str; location and name to save files.
      use_extensions: bool; whether extension capture should be used.
    Returns:
      int; brightness at which orientation circle in scene is visible.
    """
    min_brightness = 0
    max_brightness = default_brightness
    final_brightness = None
    out_surfaces = {'format': _FMT_NAME, 'width': _WIDTH, 'height': _HEIGHT}
    req = capture_request_utils.auto_capture_request()
    file_stem += '_night' if use_extensions else '_no_night'
    while min_brightness < max_brightness:
      brightness = (min_brightness + max_brightness) // 2
      self.set_screen_brightness(str(brightness))

      if use_extensions:
        logging.debug('Taking capture with night mode ON at brightness of %d',
                      brightness)
        cap = cam.do_capture_with_extensions(
            req, _EXTENSION_NIGHT, out_surfaces)
      else:
        logging.debug('Taking capture with night mode OFF at brightness of %d',
                      brightness)
        cap = cam.do_capture(req, out_surfaces)
      _, img = _convert_captures(cap, f'{file_stem}_brightness={brightness}')

      try:
        circle = opencv_processing_utils.find_circle(
            img,
            f'{file_stem}_center_circle_brightness={brightness}.png',
            _MIN_AREA, _WHITE)
        dots = _get_dots_from_circle(circle)
        # Compare orientation dot to surrounding circle center
        dot_mean = np.mean(img[dots[0][_Y_STRING], dots[0][_X_STRING]])
        circle_mean = np.mean(img[circle[_Y_STRING], circle[_X_STRING]])
        logging.debug('Dot mean: %.2f, center mean: %.2f',
                      dot_mean, circle_mean)
        difference = circle_mean - dot_mean
        if difference < _DOT_INTENSITY_DIFF_TOL:
          logging.debug('Orientation dot is washed out at brightness %d',
                        brightness)
          max_brightness = brightness
        else:
          logging.debug('Found orientation dot at brightness %d', brightness)
          min_brightness = brightness + 1
          final_brightness = brightness
      except AssertionError:
        logging.debug('Unable to find circle with brightness %d', brightness)
        max_brightness = brightness
    if not final_brightness:
      raise AssertionError('Unable to find a valid brightness for the tablet')
    return final_brightness

  def _time_and_take_captures(self, cam, req, out_surfaces,
                              use_extensions=True):
    """Find maximum brightness at which orientation circle in scene is visible.

    Uses binary search on a range of (0, default_brightness), where visibility
    is defined by an intensity comparison with the center of the outer circle.

    Args:
      cam: its_session_utils object.
      req: capture request.
      out_surfaces: dictionary of output surfaces.
      use_extensions: bool; whether extension capture should be used.
    Returns:
      Tuple of float; capture duration, capture object.
    """
    start_of_capture = time.time()
    if use_extensions:
      logging_prefix = 'Night mode ON'
      cap = cam.do_capture_with_extensions(req, _EXTENSION_NIGHT, out_surfaces)
    else:
      logging_prefix = 'Night mode OFF'
      cap = cam.do_capture(req, out_surfaces)
    end_of_capture = time.time()
    capture_duration = end_of_capture - start_of_capture
    logging.debug('%s capture took %f seconds',
                  logging_prefix, capture_duration)
    metadata = cap['metadata']
    logging.debug('%s exposure time: %s', logging_prefix,
                  metadata['android.sensor.exposureTime'])
    logging.debug('%s sensitivity: %s', logging_prefix,
                  metadata['android.sensor.sensitivity'])
    return capture_duration, cap

  def test_night_extension(self):
    # Handle subdirectory
    self.scene = 'scene_night'
    with its_session_utils.ItsSession(
        device_id=self.dut.serial,
        camera_id=self.camera_id,
        hidden_physical_id=self.hidden_physical_id) as cam:
      props = cam.get_camera_properties()
      props = cam.override_with_hidden_physical_camera_props(props)
      test_name = os.path.join(self.log_path, _NAME)

      # Determine camera supported extensions
      supported_extensions = cam.get_supported_extensions(self.camera_id)
      logging.debug('Supported extensions: %s', supported_extensions)

      # Check media performance class
      should_run = _EXTENSION_NIGHT in supported_extensions
      media_performance_class = its_session_utils.get_media_performance_class(
          self.dut.serial)
      if media_performance_class >= _TEST_REQUIRED_MPC and not should_run:
        its_session_utils.raise_mpc_assertion_error(
            _TEST_REQUIRED_MPC, _NAME, media_performance_class)

      # Check SKIP conditions
      camera_properties_utils.skip_unless(should_run)

      tablet_name_unencoded = self.tablet.adb.shell(
          ['getprop', 'ro.build.product']
      )
      tablet_name = str(tablet_name_unencoded.decode('utf-8')).strip()
      logging.debug('Tablet name: %s', tablet_name)

      if tablet_name == its_session_utils.LEGACY_TABLET_NAME:
        raise AssertionError(f'Incompatible tablet! Please use a tablet with '
                             'display brightness of at least '
                             f'{its_session_utils.DEFAULT_TABLET_BRIGHTNESS} '
                             'according to '
                             f'{its_session_utils.TABLET_REQUIREMENTS_URL}.')

      its_session_utils.load_scene(
          cam, props, self.scene, self.tablet, self.chart_distance,
          log_path=self.log_path)

      # Establish connection with lighting controller
      arduino_serial_port = lighting_control_utils.lighting_control(
          self.lighting_cntl, self.lighting_ch)

      # Turn OFF lights to darken scene
      lighting_control_utils.set_lighting_state(
          arduino_serial_port, self.lighting_ch, 'OFF')

      # Tap tablet to remove gallery buttons
      if self.tablet:
        self.tablet.adb.shell(
            f'input tap {_TAP_COORDINATES[0]} {_TAP_COORDINATES[1]}')

      # Set tablet brightness to darken scene
      file_stem = f'{test_name}_{_FMT_NAME}_{_WIDTH}x{_HEIGHT}'
      night_brightness = self.find_tablet_brightness(
          cam, self.tablet_screen_brightness, file_stem, use_extensions=True)
      logging.debug('Night mode ON brightness: %d', night_brightness)
      no_night_brightness = self.find_tablet_brightness(
          cam, self.tablet_screen_brightness, file_stem, use_extensions=False)
      logging.debug('Night mode OFF brightness: %d', no_night_brightness)
      brightness = min(night_brightness, no_night_brightness)
      self.set_screen_brightness(str(brightness))

      out_surfaces = {'format': _FMT_NAME, 'width': _WIDTH, 'height': _HEIGHT}
      req = capture_request_utils.auto_capture_request()

      # Take auto capture with night mode on
      logging.debug('Taking auto capture with night mode ON')
      cam.do_3a()
      night_capture_duration, night_cap = self._time_and_take_captures(
          cam, req, out_surfaces, use_extensions=True)
      night_y, night_img = _convert_captures(night_cap, f'{file_stem}_night')

      # Take auto capture with night mode OFF
      logging.debug('Taking auto capture with night mode OFF')
      cam.do_3a()
      no_night_capture_duration, no_night_cap = self._time_and_take_captures(
          cam, req, out_surfaces, use_extensions=False)
      no_night_y, no_night_img = _convert_captures(
          no_night_cap, f'{file_stem}_no_night')

      # Assert correct behavior
      logging.debug('Comparing capture time with night mode ON/OFF')
      duration_diff = night_capture_duration - no_night_capture_duration
      if duration_diff < _DURATION_DIFF_TOL:
        raise AssertionError('Night mode ON capture did not take '
                             'significantly more time than '
                             'night mode OFF capture! '
                             f'Difference: {duration_diff:.2f}, '
                             f'Expected: {_DURATION_DIFF_TOL}')

      logging.debug('Comparing overall intensity of capture with '
                    'night mode ON/OFF')
      _check_overall_intensity(night_img, no_night_img)

      logging.debug('Comparing edge noise of capture with night mode ON/OFF')
      _check_edge_noise(night_y, no_night_y)

if __name__ == '__main__':
  test_runner.main()
