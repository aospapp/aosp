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
"""Verify preview is stable during phone movement."""

import logging
import multiprocessing
import os
import time

from mobly import test_runner

import its_base_test
import camera_properties_utils
import image_processing_utils
import its_session_utils
import sensor_fusion_utils
import video_processing_utils

_ARDUINO_ANGLES = (10, 25)  # degrees
_ARDUINO_MOVE_TIME = 0.30  # seconds
_ARDUINO_SERVO_SPEED = 10
_IMG_FORMAT = 'png'
_MIN_PHONE_MOVEMENT_ANGLE = 5  # degrees
_NAME = os.path.splitext(os.path.basename(__file__))[0]
_NUM_ROTATIONS = 12
_START_FRAME = 30  # give 3A some frames to warm up
_VIDEO_DELAY_TIME = 5.5  # seconds
_VIDEO_DURATION = 5.5  # seconds
_VIDEO_STABILIZATION_FACTOR = 0.7  # 70% of gyro movement allowed
_PREVIEW_STABILIZATION_MODE_PREVIEW = 2


def _collect_data(cam, video_size, rot_rig):
  """Capture a new set of data from the device.

  Captures camera preview frames while the user is moving the device in
  the prescribed manner.

  Args:
    cam: camera object
    video_size: str; video resolution. ex. '1920x1080'
    rot_rig: dict with 'cntl' and 'ch' defined

  Returns:
    recording object as described by cam.do_preview_recording
  """

  logging.debug('Starting sensor event collection')

  # Start camera vibration
  p = multiprocessing.Process(
      target=sensor_fusion_utils.rotation_rig,
      args=(
          rot_rig['cntl'],
          rot_rig['ch'],
          _NUM_ROTATIONS,
          _ARDUINO_ANGLES,
          _ARDUINO_SERVO_SPEED,
          _ARDUINO_MOVE_TIME,
      ),
  )
  p.start()

  cam.start_sensor_events()
  # Record video and return recording object
  time.sleep(_VIDEO_DELAY_TIME)  # allow time for rig to start moving

  recording_obj = cam.do_preview_recording(video_size, _VIDEO_DURATION, True)
  logging.debug('Recorded output path: %s', recording_obj['recordedOutputPath'])
  logging.debug('Tested quality: %s', recording_obj['quality'])

  # Wait for vibration to stop
  p.join()

  return recording_obj


class PreviewStabilizationTest(its_base_test.ItsBaseTest):
  """Tests if preview is stabilized.

  Camera is moved in sensor fusion rig on an arc of 15 degrees.
  Speed is set to mimic hand movement (and not be too fast).
  Preview is captured after rotation rig starts moving, and the
  gyroscope data is dumped.

  The recorded preview is processed to dump all of the frames to
  PNG files. Camera movement is extracted from frames by determining
  max angle of deflection in video movement vs max angle of deflection
  in gyroscope movement. Test is a PASS if rotation is reduced in video.
  """

  def test_preview_stability(self):
    rot_rig = {}
    log_path = self.log_path

    with its_session_utils.ItsSession(
        device_id=self.dut.serial,
        camera_id=self.camera_id,
        hidden_physical_id=self.hidden_physical_id) as cam:

      props = cam.get_camera_properties()
      props = cam.override_with_hidden_physical_camera_props(props)
      first_api_level = its_session_utils.get_first_api_level(self.dut.serial)
      camera_properties_utils.skip_unless(
          first_api_level >= its_session_utils.ANDROID13_API_LEVEL,
          'First API level should be {} or higher. Found {}.'.format(
              its_session_utils.ANDROID13_API_LEVEL, first_api_level))

      supported_stabilization_modes = props[
          'android.control.availableVideoStabilizationModes'
      ]

      camera_properties_utils.skip_unless(
          supported_stabilization_modes is not None
          and _PREVIEW_STABILIZATION_MODE_PREVIEW
          in supported_stabilization_modes,
          'Preview Stabilization not supported',
      )

      # Raise error if not FRONT or REAR facing camera
      facing = props['android.lens.facing']
      if (facing != camera_properties_utils.LENS_FACING_BACK
          and facing != camera_properties_utils.LENS_FACING_FRONT):
        raise AssertionError('Unknown lens facing: {facing}.')

      # Initialize rotation rig
      rot_rig['cntl'] = self.rotator_cntl
      rot_rig['ch'] = self.rotator_ch
      if rot_rig['cntl'].lower() != 'arduino':
        raise AssertionError(f'You must use the arduino controller for {_NAME}.')

      # List of video resolutions to test
      supported_preview_sizes = cam.get_supported_preview_sizes(self.camera_id)
      supported_preview_sizes.remove(video_processing_utils.QCIF_SIZE)
      logging.debug('Supported preview resolutions: %s',
                    supported_preview_sizes)

      max_camera_angles = []
      max_gyro_angles = []

      for video_size in supported_preview_sizes:
        recording_obj = _collect_data(cam, video_size, rot_rig)

        # Grab the video from the save location on DUT
        self.dut.adb.pull([recording_obj['recordedOutputPath'], log_path])
        file_name = recording_obj['recordedOutputPath'].split('/')[-1]
        logging.debug('recorded file name: %s', file_name)

        # Get gyro events
        logging.debug('Reading out inertial sensor events')
        gyro_events = cam.get_sensor_events()['gyro']
        logging.debug('Number of gyro samples %d', len(gyro_events))

        # Get all frames from the video
        file_list = video_processing_utils.extract_all_frames_from_video(
            log_path, file_name, _IMG_FORMAT
        )
        frames = []

        logging.debug('Number of frames %d', len(file_list))
        for file in file_list:
          img = image_processing_utils.convert_image_to_numpy_array(
              os.path.join(log_path, file)
          )
          frames.append(img / 255)
        frame_shape = frames[0].shape
        logging.debug('Frame size %d x %d', frame_shape[1], frame_shape[0])

        # Extract camera rotations
        img_h = frames[0].shape[0]
        file_name_stem = f'{os.path.join(log_path, _NAME)}_{video_size}'
        cam_rots = sensor_fusion_utils.get_cam_rotations(
            frames[_START_FRAME : len(frames)],
            facing,
            img_h,
            file_name_stem,
            _START_FRAME,
        )
        sensor_fusion_utils.plot_camera_rotations(cam_rots, _START_FRAME,
                                                  video_size, file_name_stem)
        max_camera_angles.append(
            sensor_fusion_utils.calc_max_rotation_angle(cam_rots, 'Camera')
        )

        # Extract gyro rotations
        sensor_fusion_utils.plot_gyro_events(
            gyro_events, f'{_NAME}_{video_size}', log_path
        )
        gyro_rots = sensor_fusion_utils.conv_acceleration_to_movement(
            gyro_events, _VIDEO_DELAY_TIME
        )
        max_gyro_angles.append(
            sensor_fusion_utils.calc_max_rotation_angle(gyro_rots, 'Gyro')
        )
        logging.debug(
            'Max deflection (degrees) %s: video: %.3f, gyro: %.3f ratio: %.4f',
            video_size, max_camera_angles[-1], max_gyro_angles[-1],
            max_camera_angles[-1] / max_gyro_angles[-1])

        # Assert phone is moved enough during test
        if max_gyro_angles[-1] < _MIN_PHONE_MOVEMENT_ANGLE:
          raise AssertionError(
              f'Phone not moved enough! Movement: {max_gyro_angles[-1]}, '
              f'THRESH: {_MIN_PHONE_MOVEMENT_ANGLE} degrees')

      # Assert PASS/FAIL criteria
      test_failures = []
      for i, max_camera_angle in enumerate(max_camera_angles):
        if max_camera_angle >= max_gyro_angles[i] * _VIDEO_STABILIZATION_FACTOR:
          test_failures.append(
              f'{supported_preview_sizes[i]} video not stabilized enough! '
              f'Max video angle:  {max_camera_angle:.3f}, '
              f'Max gyro angle: {max_gyro_angles[i]:.3f}, '
              f'ratio: {max_camera_angle/max_gyro_angles[i]:.4f} '
              f'THRESH: {_VIDEO_STABILIZATION_FACTOR}.')

      if test_failures:
        raise AssertionError(test_failures)


if __name__ == '__main__':
  test_runner.main()

