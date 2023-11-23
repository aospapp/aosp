# Copyright 2014 The Android Open Source Project
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
"""Verifies 3 faces with different skin tones are detected."""


import logging
import math
import os.path

import cv2
from mobly import test_runner

import its_base_test
import camera_properties_utils
import capture_request_utils
import image_processing_utils
import its_session_utils
import opencv_processing_utils

_CV2_FACE_SCALE_FACTOR = 1.05  # 5% step for resizing image to find face
_CV2_FACE_MIN_NEIGHBORS = 4  # recommended 3-6: higher for less faces
_CV2_GREEN = (0, 1, 0)
_CV2_RED = (1, 0, 0)
_FACE_CENTER_MATCH_TOL = 12  # 12 pixels or ~1% in 640x480 image
_FACE_CENTER_MIN_LOGGING_DIST = 50
_FD_MODE_OFF, _FD_MODE_SIMPLE, _FD_MODE_FULL = 0, 1, 2
_NAME = os.path.splitext(os.path.basename(__file__))[0]
_NUM_TEST_FRAMES = 20
_NUM_FACES = 3
_TEST_REQUIRED_MPC = 34
_W, _H = 640, 480


def match_face_locations(faces_cropped, faces_opencv, mode, img, img_name):
  """Assert face locations between two methods.

  Method determines if center of opencv face boxes is within face detection
  face boxes. Using math.hypot to measure the distance between the centers,
  as math.dist is not available for python versions before 3.8.

  Args:
    faces_cropped: list of lists with (l, r, t, b) for each face.
    faces_opencv: list of lists with (x, y, w, h) for each face.
    mode: int indicating face detection mode
    img: np image array
    img_name: text string with path to image file
  """
  # turn faces_opencv into list of center locations
  faces_opencv_centers = [(x+w//2, y+h//2) for (x, y, w, h) in faces_opencv]
  cropped_faces_centers = [
      ((l+r)//2, (t+b)//2) for (l, r, t, b) in faces_cropped]
  faces_opencv_centers.sort(key=lambda t: [t[1], t[0]])
  cropped_faces_centers.sort(key=lambda t: [t[1], t[0]])
  logging.debug('cropped face centers: %s', str(cropped_faces_centers))
  logging.debug('opencv face centers: %s', str(faces_opencv_centers))
  num_centers_aligned = 0
  for (x, y) in faces_opencv_centers:
    for (x1, y1) in cropped_faces_centers:
      centers_dist = math.hypot(x-x1, y-y1)
      if centers_dist < _FACE_CENTER_MIN_LOGGING_DIST:
        logging.debug('centers_dist: %.3f', centers_dist)
      if centers_dist < _FACE_CENTER_MATCH_TOL:
        num_centers_aligned += 1

  # If test failed, save image with green AND OpenCV red rectangles
  image_processing_utils.write_image(img, img_name)
  if num_centers_aligned != _NUM_FACES:
    for (x, y, w, h) in faces_opencv:
      cv2.rectangle(img, (x, y), (x+w, y+h), _CV2_RED, 2)
      image_processing_utils.write_image(img, img_name)
      logging.debug('centered: %s', str(num_centers_aligned))
    raise AssertionError(f'Mode {mode} face rectangles in wrong location(s)!. '
                         f'Found {num_centers_aligned} rectangles near cropped '
                         f'face centers, expected {_NUM_FACES}')


def check_face_bounding_box(rect, aw, ah, index):
  """Checks face bounding box is within the active array area.

  Args:
    rect: dict; with face bounding box information
    aw: int; active array width
    ah: int; active array height
    index: int to designate face number
  """
  logging.debug('Checking bounding box in face %d: %s', index, str(rect))
  if (rect['top'] >= rect['bottom'] or
      rect['left'] >= rect['right']):
    raise AssertionError('Face coordinates incorrect! '
                         f" t: {rect['top']}, b: {rect['bottom']}, "
                         f" l: {rect['left']}, r: {rect['right']}")
  if (not 0 <= rect['top'] <= ah or
      not 0 <= rect['bottom'] <= ah):
    raise AssertionError('Face top/bottom outside of image height! '
                         f"t: {rect['top']}, b: {rect['bottom']}, "
                         f"h: {ah}")
  if (not 0 <= rect['left'] <= aw or
      not 0 <= rect['right'] <= aw):
    raise AssertionError('Face left/right outside of image width! '
                         f"l: {rect['left']}, r: {rect['right']}, "
                         f" w: {aw}")


def check_face_landmarks(face, fd_mode, index):
  """Checks face landmarks fall within face bounding box.

  Face ID should be -1 for SIMPLE and unique for FULL
  Args:
    face: dict from face detection algorithm
    fd_mode: int of face detection mode
    index: int to designate face number
  """
  logging.debug('Checking landmarks in face %d: %s', index, str(face))
  if fd_mode == _FD_MODE_SIMPLE:
    if 'leftEye' in face or 'rightEye' in face:
      raise AssertionError('Eyes not supported in FD_MODE_SIMPLE.')
    if 'mouth' in face:
      raise AssertionError('Mouth not supported in FD_MODE_SIMPLE.')
    if face['id'] != -1:
      raise AssertionError('face_id should be -1 in FD_MODE_SIMPLE.')
  elif fd_mode == _FD_MODE_FULL:
    l, r = face['bounds']['left'], face['bounds']['right']
    t, b = face['bounds']['top'], face['bounds']['bottom']
    l_eye_x, l_eye_y = face['leftEye']['x'], face['leftEye']['y']
    r_eye_x, r_eye_y = face['rightEye']['x'], face['rightEye']['y']
    mouth_x, mouth_y = face['mouth']['x'], face['mouth']['y']
    if not l <= l_eye_x <= r:
      raise AssertionError(f'Face l: {l}, r: {r}, left eye x: {l_eye_x}')
    if not t <= l_eye_y <= b:
      raise AssertionError(f'Face t: {t}, b: {b}, left eye y: {l_eye_y}')
    if not l <= r_eye_x <= r:
      raise AssertionError(f'Face l: {l}, r: {r}, right eye x: {r_eye_x}')
    if not t <= r_eye_y <= b:
      raise AssertionError(f'Face t: {t}, b: {b}, right eye y: {r_eye_y}')
    if not l <= mouth_x <= r:
      raise AssertionError(f'Face l: {l}, r: {r}, mouth x: {mouth_x}')
    if not t <= mouth_y <= b:
      raise AssertionError(f'Face t: {t}, b: {b}, mouth y: {mouth_y}')
  else:
    raise AssertionError(f'Unknown face detection mode: {fd_mode}.')


def correct_faces_for_crop(faces, img, crop):
  """Correct face rectangles for sensor crop.

  Args:
    faces: list of dicts with face information
    img: np image array
    crop: dict of crop region size with 'top, right, left, bottom' as keys
  Returns:
    list of face locations (left, right, top, bottom) corrected
  """
  faces_corrected = []
  cw, ch = crop['right'] - crop['left'], crop['bottom'] - crop['top']
  logging.debug('crop region: %s', str(crop))
  w = img.shape[1]
  h = img.shape[0]
  for rect in [face['bounds'] for face in faces]:
    logging.debug('rect: %s', str(rect))
    left = int(round((rect['left'] - crop['left']) * w / cw))
    right = int(round((rect['right'] - crop['left']) * w / cw))
    top = int(round((rect['top'] - crop['top']) * h / ch))
    bottom = int(round((rect['bottom'] - crop['top']) * h / ch))
    faces_corrected.append([left, right, top, bottom])
  logging.debug('faces_corrected: %s', str(faces_corrected))
  return faces_corrected


class NumFacesTest(its_base_test.ItsBaseTest):
  """Test face detection with different skin tones.
  """

  def test_num_faces(self):
    """Test face detection."""
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

      # Check media performance class
      should_run = camera_properties_utils.face_detect(props)
      media_performance_class = its_session_utils.get_media_performance_class(
          self.dut.serial)
      if media_performance_class >= _TEST_REQUIRED_MPC and not should_run:
        its_session_utils.raise_mpc_assertion_error(
            _TEST_REQUIRED_MPC, _NAME, media_performance_class)

      # Check skip conditions
      camera_properties_utils.skip_unless(should_run)
      mono_camera = camera_properties_utils.mono_camera(props)
      fd_modes = props['android.statistics.info.availableFaceDetectModes']
      a = props['android.sensor.info.activeArraySize']
      aw, ah = a['right'] - a['left'], a['bottom'] - a['top']
      logging.debug('active array size: %s', str(a))
      file_name_stem = os.path.join(self.log_path, _NAME)

      cam.do_3a(mono_camera=mono_camera)

      for fd_mode in fd_modes:
        logging.debug('face detection mode: %d', fd_mode)
        if not _FD_MODE_OFF <= fd_mode <= _FD_MODE_FULL:
          raise AssertionError(f'FD mode {fd_mode} not in MODES! '
                               f'OFF: {_FD_MODE_OFF}, FULL: {_FD_MODE_FULL}')
        req = capture_request_utils.auto_capture_request()
        req['android.statistics.faceDetectMode'] = fd_mode
        fmt = {'format': 'yuv', 'width': _W, 'height': _H}
        caps = cam.do_capture([req]*_NUM_TEST_FRAMES, fmt)
        for i, cap in enumerate(caps):
          fd_mode_cap = cap['metadata']['android.statistics.faceDetectMode']
          if fd_mode_cap != fd_mode:
            raise AssertionError(f'metadata {fd_mode_cap} != req {fd_mode}')

          faces = cap['metadata']['android.statistics.faces']
          # 0 faces should be returned for OFF mode
          if fd_mode == _FD_MODE_OFF:
            if faces:
              raise AssertionError(f'Error: faces detected in OFF: {faces}')
            continue
          # Face detection could take several frames to warm up,
          # but should detect the correct number of faces in last frame
          if i == _NUM_TEST_FRAMES - 1:
            img = image_processing_utils.convert_capture_to_rgb_image(
                cap, props=props)
            fnd_faces = len(faces)
            logging.debug('Found %d face(s), expected %d.',
                          fnd_faces, _NUM_FACES)

            # draw boxes around faces in green
            crop_region = cap['metadata']['android.scaler.cropRegion']
            faces_cropped = correct_faces_for_crop(faces, img, crop_region)
            for (l, r, t, b) in faces_cropped:
              cv2.rectangle(img, (l, t), (r, b), _CV2_GREEN, 2)

            # Save image with green rectangles
            img_name = f'{file_name_stem}_fd_mode_{fd_mode}.jpg'
            image_processing_utils.write_image(img, img_name)
            if fnd_faces != _NUM_FACES:
              raise AssertionError('Wrong num of faces found! Found: '
                                   f'{fnd_faces}, expected: {_NUM_FACES}')
            # Reasonable scores for faces
            face_scores = [face['score'] for face in faces]
            for score in face_scores:
              if not 1 <= score <= 100:
                raise AssertionError(f'score not between [1:100]! {score}')

            # Face bounds should be within active array
            face_rectangles = [face['bounds'] for face in faces]
            for j, rect in enumerate(face_rectangles):
              check_face_bounding_box(rect, aw, ah, j)

            # Face landmarks (if provided) are within face bounding box
            vendor_api_level = its_session_utils.get_vendor_api_level(
                self.dut.serial)
            if vendor_api_level >= its_session_utils.ANDROID14_API_LEVEL:
              for k, face in enumerate(faces):
                check_face_landmarks(face, fd_mode, k)

            # Match location of opencv and face detection mode faces
            if self.scene == 'scene2_d':
              logging.debug('Match face centers between opencv & faces')
              faces_opencv = opencv_processing_utils.find_opencv_faces(
                  img, _CV2_FACE_SCALE_FACTOR, _CV2_FACE_MIN_NEIGHBORS)
              if fd_mode:  # non-zero value for ON
                match_face_locations(faces_cropped, faces_opencv,
                                     fd_mode, img, img_name)

          if not faces:
            continue
          logging.debug('Frame %d face metadata:', i)
          logging.debug(' Faces: %s', str(faces))


if __name__ == '__main__':
  test_runner.main()
