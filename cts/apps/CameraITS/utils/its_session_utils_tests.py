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
"""Tests for its_session_utils."""

import logging
import unittest

import numpy

import its_session_utils


class ItsSessionUtilsTests(unittest.TestCase):
  """Run a suite of unit tests on this module."""

  _BRIGHTNESS_CHECKS = (0.0,
                        its_session_utils._VALIDATE_LIGHTING_THRESH-0.01,
                        its_session_utils._VALIDATE_LIGHTING_THRESH,
                        its_session_utils._VALIDATE_LIGHTING_THRESH+0.01,
                        1.0)
  _TEST_IMG_W = 640
  _TEST_IMG_H = 480

  def _generate_test_image(self, brightness):
    """Creates a Y plane array with pixel values of brightness.

    Args:
      brightness: float between [0.0, 1.0]

    Returns:
      Y plane array with elements of value brightness
    """
    test_image = numpy.zeros((self._TEST_IMG_W, self._TEST_IMG_H, 1),
                             dtype=float)
    test_image.fill(brightness)
    return test_image

  def test_validate_lighting(self):
    """Tests validate_lighting() works correctly."""
    # Run with different brightnesses to validate.
    for brightness in self._BRIGHTNESS_CHECKS:
      logging.debug('Testing validate_lighting with brightness %.1f',
                    brightness)
      test_image = self._generate_test_image(brightness)
      print(f'Testing brightness: {brightness}')
      if brightness <= its_session_utils._VALIDATE_LIGHTING_THRESH:
        self.assertRaises(
            AssertionError, its_session_utils.validate_lighting,
            test_image, 'unittest')
      else:
        self.assertTrue(its_session_utils.validate_lighting(
            test_image, 'unittest'), f'image value {brightness} should PASS')


if __name__ == '__main__':
  unittest.main()
