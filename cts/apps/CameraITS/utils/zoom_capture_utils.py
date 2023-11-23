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
"""Utility functions for zoom capture.
"""

import logging
import math

import camera_properties_utils
import capture_request_utils
import image_processing_utils
import opencv_processing_utils

_CIRCLE_COLOR = 0  # [0: black, 255: white]
_CIRCLE_AR_RTOL = 0.15  # contour width vs height (aspect ratio)
_CIRCLISH_RTOL = 0.05  # contour area vs ideal circle area pi*((w+h)/4)**2
_MIN_AREA_RATIO = 0.00015  # based on 2000/(4000x3000) pixels
_MIN_CIRCLE_PTS = 25
_MIN_FOCUS_DIST_TOL = 0.80  # allow charts a little closer than min
_OFFSET_ATOL = 10  # number of pixels
_OFFSET_RTOL_MIN_FD = 0.30
_RADIUS_RTOL_MIN_FD = 0.15
OFFSET_RTOL = 0.15
RADIUS_RTOL = 0.10
ZOOM_MAX_THRESH = 10.0


def get_test_tols_and_cap_size(cam, props, chart_distance, debug):
  """Determine the tolerance per camera based on test rig and camera params.

  Cameras are pre-filtered to only include supportable cameras.
  Supportable cameras are: YUV(RGB)

  Args:
    cam: camera object
    props: dict; physical camera properties dictionary
    chart_distance: float; distance to chart in cm
    debug: boolean; log additional data

  Returns:
    dict of TOLs with camera focal length as key
    largest common size across all cameras
  """
  ids = camera_properties_utils.logical_multi_camera_physical_ids(props)
  physical_props = {}
  physical_ids = []
  for i in ids:
    physical_props[i] = cam.get_camera_properties_by_id(i)
    # find YUV capable physical cameras
    if camera_properties_utils.backward_compatible(physical_props[i]):
      physical_ids.append(i)

  # find physical camera focal lengths that work well with rig
  chart_distance_m = abs(chart_distance)/100  # convert CM to M
  test_tols = {}
  test_yuv_sizes = []
  for i in physical_ids:
    yuv_sizes = capture_request_utils.get_available_output_sizes(
        'yuv', physical_props[i])
    test_yuv_sizes.append(yuv_sizes)
    if debug:
      logging.debug('cam[%s] yuv sizes: %s', i, str(yuv_sizes))

    # determine if minimum focus distance is less than rig depth
    min_fd = physical_props[i]['android.lens.info.minimumFocusDistance']
    for fl in physical_props[i]['android.lens.info.availableFocalLengths']:
      logging.debug('cam[%s] min_fd: %.3f (diopters), fl: %.2f', i, min_fd, fl)
      if (math.isclose(min_fd, 0.0, rel_tol=1E-6) or  # fixed focus
          (1.0/min_fd < chart_distance_m*_MIN_FOCUS_DIST_TOL)):
        test_tols[fl] = (RADIUS_RTOL, OFFSET_RTOL)
      else:
        test_tols[fl] = (_RADIUS_RTOL_MIN_FD, _OFFSET_RTOL_MIN_FD)
        logging.debug('loosening RTOL for cam[%s]: '
                      'min focus distance too large.', i)
  # find intersection of formats for max common format
  common_sizes = list(set.intersection(*[set(list) for list in test_yuv_sizes]))
  if debug:
    logging.debug('common_fmt: %s', max(common_sizes))

  return test_tols, max(common_sizes)


def get_center_circle(img, img_name, size, zoom_ratio, min_zoom_ratio, debug):
  """Find circle closest to image center for scene with multiple circles.

  If circle is not found due to zoom ratio being larger than ZOOM_MAX_THRESH
  or the circle being cropped, None is returned.

  Args:
    img: numpy img array with pixel values in [0,255].
    img_name: str file name for saved image
    size: width, height of the image
    zoom_ratio: zoom_ratio for the particular capture
    min_zoom_ratio: min_zoom_ratio supported by the camera device
    debug: boolean to save extra data

  Returns:
    circle: [center_x, center_y, radius] if found, else None
  """
  # convert [0, 1] image to [0, 255] and cast as uint8
  img = image_processing_utils.convert_image_to_uint8(img)

  # Find the center circle in img
  try:
    circle = opencv_processing_utils.find_center_circle(
        img, img_name, _CIRCLE_COLOR, circle_ar_rtol=_CIRCLE_AR_RTOL,
        circlish_rtol=_CIRCLISH_RTOL,
        min_area=_MIN_AREA_RATIO * size[0] * size[1] * zoom_ratio * zoom_ratio,
        min_circle_pts=_MIN_CIRCLE_PTS, debug=debug)
    if opencv_processing_utils.is_circle_cropped(circle, size):
      logging.debug('zoom %.2f is too large! Skip further captures', zoom_ratio)
      return None
  except AssertionError as e:
    if zoom_ratio / min_zoom_ratio >= ZOOM_MAX_THRESH:
      return None
    else:
      raise AssertionError(
          'No circle detected for zoom ratio <= '
          f'{ZOOM_MAX_THRESH}. '
          'Take pictures according to instructions carefully!') from e
  return circle


def verify_zoom_results(test_data, size, z_max, z_min):
  """Verify that the output images' zoom level reflects the correct zoom ratios.

  This test verifies that the center and radius of the circles in the output
  images reflects the zoom ratios being set. The larger the zoom ratio, the
  larger the circle. And the distance from the center of the circle to the
  center of the image is proportional to the zoom ratio as well.

  Args:
    test_data: dict; contains the detected circles for each zoom value
    size: array; the width and height of the images
    z_max: float; the maximum zoom ratio being tested
    z_min: float; the minimum zoom ratio being tested

  Returns:
    Boolean whether the test passes (True) or not (False)
  """
  # assert some range is tested before circles get too big
  test_failed = False
  zoom_max_thresh = ZOOM_MAX_THRESH
  z_max_ratio = z_max / z_min
  if z_max_ratio < ZOOM_MAX_THRESH:
    zoom_max_thresh = z_max_ratio
  test_data_max_z = (test_data[max(test_data.keys())]['z'] /
                     test_data[min(test_data.keys())]['z'])
  logging.debug('test zoom ratio max: %.2f', test_data_max_z)
  if test_data_max_z < zoom_max_thresh:
    test_failed = True
    e_msg = (f'Max zoom ratio tested: {test_data_max_z:.4f}, '
             f'range advertised min: {z_min}, max: {z_max} '
             f'THRESH: {zoom_max_thresh}')
    logging.error(e_msg)

  # initialize relative size w/ zoom[0] for diff zoom ratio checks
  radius_0 = float(test_data[0]['circle'][2])
  z_0 = float(test_data[0]['z'])

  for i, data in test_data.items():
    logging.debug('Zoom: %.2f, fl: %.2f', data['z'], data['fl'])
    offset_xy = [(data['circle'][0] - size[0] // 2),
                 (data['circle'][1] - size[1] // 2)]
    logging.debug('Circle r: %.1f, center offset x, y: %d, %d',
                  data['circle'][2], offset_xy[0], offset_xy[1])
    z_ratio = data['z'] / z_0

    # check relative size against zoom[0]
    radius_ratio = data['circle'][2] / radius_0
    logging.debug('r ratio req: %.3f, measured: %.3f',
                  z_ratio, radius_ratio)
    if not math.isclose(z_ratio, radius_ratio, rel_tol=data['r_tol']):
      test_failed = True
      e_msg = (f"Circle radius in capture taken at {z_0:.2f} "
               "was expected to increase in capture taken at "
               f"{data['z']:.2f} by {data['z']:.2f}/{z_0:.2f}="
               f"{z_ratio:.2f}, but it increased by "
               f"{radius_ratio:.2f}. RTOL: {data['r_tol']}")
      logging.error(e_msg)

    # check relative offset against init vals w/ no focal length change
    if i == 0 or test_data[i-1]['fl'] != data['fl']:  # set init values
      z_init = float(data['z'])
      offset_hypot_init = math.hypot(offset_xy[0], offset_xy[1])
      logging.debug('offset_hypot_init: %.3f', offset_hypot_init)
    else:  # check
      z_ratio = data['z'] / z_init
      offset_hypot_rel = math.hypot(offset_xy[0], offset_xy[1]) / z_ratio
      logging.debug('offset_hypot_rel: %.3f', offset_hypot_rel)

      rel_tol = data['o_tol']
      if not math.isclose(offset_hypot_init, offset_hypot_rel,
                          rel_tol=rel_tol, abs_tol=_OFFSET_ATOL):
        test_failed = True
        e_msg = (f"zoom: {data['z']:.2f}, "
                 f'offset init: {offset_hypot_init:.4f}, '
                 f'offset rel: {offset_hypot_rel:.4f}, '
                 f'RTOL: {rel_tol}, ATOL: {_OFFSET_ATOL}')
        logging.error(e_msg)

  return not test_failed

