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
"""Verifies that autoframing can adjust fov to include all faces with different
skin tones."""


import logging
import os.path

from mobly import test_runner

import its_base_test
import camera_properties_utils
import capture_request_utils
import image_processing_utils
import its_session_utils
import opencv_processing_utils

_CV2_FACE_SCALE_FACTOR = 1.05  # 5% step for resizing image to find face
_CV2_FACE_MIN_NEIGHBORS = 4  # recommended 3-6: higher for less faces
_NUM_TEST_FRAMES = 20
_NUM_FACES = 3
_W, _H = 640, 480


class AutoframingTest(its_base_test.ItsBaseTest):
  """Test autoframing for faces with different skin tones.
  """

  def test_autoframing(self):
    """Test if fov gets adjusted to accommodate all the faces in the frame.

    Do a large zoom on scene2_a using do_3a so that none of that faces are
    visible initially, trigger autoframing, wait for the state to converge and
    make sure all the faces are found.
    """
    with its_session_utils.ItsSession(
        device_id=self.dut.serial,
        camera_id=self.camera_id,
        hidden_physical_id=self.hidden_physical_id) as cam:
      props = cam.get_camera_properties()
      props = cam.override_with_hidden_physical_camera_props(props)

      # Load chart for scene
      its_session_utils.load_scene(
          cam, props, self.scene, self.tablet, self.chart_distance,
          log_path=self.log_path)

      # Check SKIP conditions
      # Don't run autoframing if face detection or autoframing is not supported
      camera_properties_utils.skip_unless(
          camera_properties_utils.face_detect(props) and
          camera_properties_utils.autoframing(props))

      # Do max-ish zoom with the help of do_3a, keeping all the 'A's off. This
      # zooms into the scene so that none of the faces are in the view
      # initially - which gives room for autoframing to take place.
      max_zoom_ratio = camera_properties_utils.get_max_digital_zoom(props)
      cam.do_3a(do_af=False, zoom_ratio=max_zoom_ratio)
      cam.do_autoframing(zoom_ratio=max_zoom_ratio)

      req = capture_request_utils.auto_capture_request(
          do_autoframing=True, zoom_ratio=max_zoom_ratio)
      req['android.statistics.faceDetectMode'] = 1  # Simple
      fmt = {'format': 'yuv', 'width': _W, 'height': _H}
      caps = cam.do_capture([req]*_NUM_TEST_FRAMES, fmt)
      for i, cap in enumerate(caps):
        faces = cap['metadata']['android.statistics.faces']
        # Face detection and autoframing could take several frames to warm up,
        # but should detect the correct number of faces in last frame
        if i == _NUM_TEST_FRAMES - 1:
          num_faces_found = len(faces)
          if num_faces_found != _NUM_FACES:
            raise AssertionError('Wrong num of faces found! Found: '
                                 f'{num_faces_found}, expected: {_NUM_FACES}')

          # Also check the faces with open cv to make sure the scene is not
          # distored or anything.
          img = image_processing_utils.convert_capture_to_rgb_image(
              cap, props=props)
          opencv_faces = opencv_processing_utils.find_opencv_faces(
              img, _CV2_FACE_SCALE_FACTOR, _CV2_FACE_MIN_NEIGHBORS)
          num_opencv_faces = len(opencv_faces)
          if num_opencv_faces != _NUM_FACES:
            raise AssertionError('Wrong num of faces found with OpenCV! Found: '
                                 f'{num_opencv_faces}, expected: {_NUM_FACES}')

        if not faces:
          continue
        logging.debug('Frame %d face metadata:', i)
        logging.debug('Faces: %s', str(faces))


if __name__ == '__main__':
  test_runner.main()
