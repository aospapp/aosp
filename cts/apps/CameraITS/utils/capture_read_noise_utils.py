# Copyright 2022 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the 'License');
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an 'AS IS' BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Utility functions to enable capture read noise analysis."""

import csv
import logging
import math
import os
import pickle

import matplotlib.pyplot as plt
from matplotlib.ticker import NullLocator
from matplotlib.ticker import ScalarFormatter
import numpy as np

import camera_properties_utils
import capture_request_utils


_BAYER_COLOR_PLANE = ('red', 'green_r', 'blue', 'green_b')
_LINEAR_FIT_NUM_SAMPLES = 100  # Number of samples to plot for the linear fit
_PLOT_AXIS_TICKS = 5  # Number of ticks to display on the plot axis


def create_and_save_csv_from_results(rn_data, iso_low, iso_high, cmap, file):
  """Creates a .csv file for the read noise results.

  Args:
    rn_data:        Read noise data object from capture_read_noise_for_iso_range
    iso_low:        int; minimum iso value to include
    iso_high:       int; maximum iso value to include
    cmap:           str; string containing each color symbol
    file:           str; path to csv where this will be created
  """
  with open(file, 'w+') as f:
    writer = csv.writer(f)

    results = list(filter(
        lambda x: x[0]['iso'] >= iso_low and x[0]['iso'] <= iso_high, rn_data
    ))

    color_channels = range(len(cmap))

    # Create headers for csv file
    headers = ['iso', 'iso^2']
    headers.extend([f'mean_{cmap[i]}' for i in color_channels])
    headers.extend([f'var_{cmap[i]}' for i in color_channels])
    headers.extend([f'norm_var_{cmap[i]}' for i in color_channels])

    writer.writerow(headers)

    # Create data rows
    for data_row in results:
      row = [data_row[0]['iso']]
      row.append(data_row[0]['iso']**2)
      row.extend([data_row[i]['mean'] for i in color_channels])
      row.extend([data_row[i]['var'] for i in color_channels])
      row.extend([data_row[i]['norm_var'] for i in color_channels])

      writer.writerow(row)

    writer.writerow([])  # divider line

    # Create row containing the offset coefficients calculated by np.polyfit
    coeff_headers = ['', 'offset_coefficient_a', 'offset_coefficient_b']
    writer.writerow(coeff_headers)

    coeff_a, coeff_b = get_read_noise_coefficients(results)
    for i in range(len(cmap)):
      writer.writerow([cmap[i], coeff_a[i], coeff_b[i]])


def create_read_noise_plots_from_results(rn_data, iso_low, iso_high, cmap,
                                         file):
  """Plot the read noise data for the given ISO range.

  Args:
    rn_data:        Read noise data object from capture_read_noise_for_iso_range
    iso_low:        int; minimum iso value to include
    iso_high:       int; maximum iso value to include
    cmap:           str; string containing the Bayer format
    file:           str; file path for the plot image
  """
  # Get a list of color names and plot color arrangements for the given cmap.
  # This will be used for chart labels and color schemes
  bayer_color_list = []
  plot_colors = ''
  if cmap.lower() == 'grbg':
    bayer_color_list = ['GR', 'R', 'B', 'GB']
    plot_colors = 'grby'
  elif cmap.lower() == 'rggb':
    bayer_color_list = ['R', 'GR', 'GB', 'B']
    plot_colors = 'rgyb'
  elif cmap.lower() == 'bggr':
    bayer_color_list = ['B', 'GB', 'GR', 'R']
    plot_colors = 'bygr'
  elif cmap.lower() == 'gbrg':
    bayer_color_list = ['GB', 'B', 'R', 'GR']
    plot_colors = 'ybrg'
  else:
    raise AssertionError('cmap parameter does not match any known Bayer format')

  # Create the figure for plotting the read noise to ISO^2 curve
  fig = plt.figure(figsize=(11, 11))
  fig.suptitle('Read Noise to ISO^2', x=0.54, y=0.99)

  iso_range = fig.add_subplot(111)

  iso_range.set_xlabel('ISO^2')
  iso_range.set_ylabel('Read Noise')

  # Get the ISO values for the current range
  current_range = list(filter(
      lambda x: x[0]['iso'] >= iso_low and x[0]['iso'] <= iso_high, rn_data
  ))

  # Get X-axis values (ISO^2) for current_range
  iso_sq = [data[0]['iso']**2 for data in current_range]

  # Get X-axis values for the calculated linear fit for the read noise
  iso_sq_values = np.linspace(iso_low**2, iso_high**2, _LINEAR_FIT_NUM_SAMPLES)

  # Get the line fit coeff for plotting the linear fit of read noise to iso^2
  coeff_a, coeff_b = get_read_noise_coefficients(current_range)

  # Plot the read noise to iso^2 data
  for pidx in range(len(bayer_color_list)):
    norm_vars = [data[pidx]['norm_var'] for data in current_range]

    # Plot the measured read noise to ISO^2 values
    iso_range.plot(iso_sq, norm_vars, plot_colors[pidx]+'o',
                   label=f'{bayer_color_list[pidx]}', alpha=0.3)

    # Plot the line fit calculated from the read noise values
    iso_range.plot(iso_sq_values, coeff_a[pidx]*iso_sq_values + coeff_b[pidx],
                   color=plot_colors[pidx])

  # Create a numpy array containing all normalized variance values for the
  # current range, this will be used for labelling the X-axis
  y_values = np.array(
      [[color['norm_var'] for color in x] for x in current_range])

  x_ticks = np.linspace(iso_low**2, iso_high**2, _PLOT_AXIS_TICKS)
  y_ticks = np.linspace(np.min(y_values), np.max(y_values), _PLOT_AXIS_TICKS)

  iso_range.set_xticks(x_ticks)
  iso_range.xaxis.set_minor_locator(NullLocator())
  iso_range.xaxis.set_major_formatter(ScalarFormatter())

  iso_range.set_yticks(y_ticks)
  iso_range.yaxis.set_minor_locator(NullLocator())
  iso_range.yaxis.set_major_formatter(ScalarFormatter())

  iso_range.legend()

  fig.savefig(file)


def _generate_image_data_bayer(img, iso, white_level, cmap):
  """Generates read noise data for a given image.

  Each element in the list corresponds to each color channel, and each dict
  contains information relevant to the read noise calculation.

  Args:
    img:          np.array; image for the given iso
    iso:          float; iso value which the
    white_level:  int; white level value for the sensor
    cmap:         str; color map of the sensor
  Returns:
    list(dict)    list containing information for each color channel
  """
  result = []

  color_channel_img = np.empty((len(_BAYER_COLOR_PLANE),
                                int(img.shape[0]/2),
                                int(img.shape[1]/2)))

  # Create a dict of read noise values for each color channel in the image
  for i, color_plane in enumerate(_BAYER_COLOR_PLANE):
    color_channel_img[i] = _subsample(img, color_plane, cmap)
    var = np.var(color_channel_img[i])
    mean = np.mean(color_channel_img[i])
    norm_var = var / ((white_level - mean)**2)
    result.append({
        'iso': iso,
        'mean': mean,
        'var': var,
        'norm_var': norm_var
    })

  return result


def _subsample(img, color_plane, cmap):
  """Subsample image array based on color_plane.

  Args:
      img:            2-D numpy array of image
      color_plane:    string; color to extract
      cmap:           list; color map of the sensor
  Returns:
      img_subsample:  2-D numpy subarray of image with only color plane
  """
  subsample_img_2x = lambda img, x, h, v: img[int(x / 2):v:2, x % 2:h:2]
  size_h = img.shape[1]
  size_v = img.shape[0]
  if color_plane == 'red':
    cmap_index = cmap.index('R')
  elif color_plane == 'blue':
    cmap_index = cmap.index('B')
  elif color_plane == 'green_r':
    color_plane_map_index = {
        'GRBG': 0,
        'RGGB': 1,
        'BGGR': 2,
        'GBRG': 3
    }
    cmap_index = color_plane_map_index[cmap]
  elif color_plane == 'green_b':
    color_plane_map_index = {
        'GBRG': 0,
        'BGGR': 1,
        'RGGB': 2,
        'GRBG': 3
    }
    cmap_index = color_plane_map_index[cmap]
  else:
    logging.error('Wrong color_plane entered!')
    return None

  return subsample_img_2x(img, cmap_index, size_h, size_v)


def get_read_noise_coefficients(rn_data, iso_low=0, iso_high=1000000):
  """Calculate the read noise coefficients from the read noise data.

  Args:
    rn_data:       Read noise data object from capture_read_noise_for_iso_range
    iso_low:        int; minimum iso value to include
    iso_high:       int; maximum iso value to include
  Returns:
    (list, list)   Offset coefficients for the linear fit to read noise data
  """
  # Filter the values by the given ISO range
  iso_range = list(filter(
      lambda x: x[0]['iso'] >= iso_low and x[0]['iso'] <= iso_high, rn_data
  ))

  read_noise_coefficients_a = []
  read_noise_coefficients_b = []

  # Get ISO^2 values used for X-axis in polyfit
  iso_sq = [data[0]['iso']**2 for data in iso_range]

  # Find the linear equation coefficients for each color channel
  for i in range(len(iso_range[0])):
    norm_var = [data[i]['norm_var'] for data in iso_range]

    coeffs = np.polyfit(iso_sq, norm_var, 1)

    read_noise_coefficients_a.append(coeffs[0])
    read_noise_coefficients_b.append(coeffs[1])

  return read_noise_coefficients_a, read_noise_coefficients_b


def capture_read_noise_for_iso_range(cam, low_iso, high_iso, steps, cmap,
                                     dest_file):
  """Captures read noise data at the lowest advertised exposure value.

  Args:
    cam:         ItsSession; camera for the current ItsSession
    low_iso:     int; lowest iso value in range
    high_iso:    int; highest iso value in range
    steps:       int; steps to take per stop
    cmap:        str; color map of the sensor
    dest_file:   str; path where the results should be saved
  Returns:
    list(list(dict))  Read noise results for each frame
  """
  props = cam.get_camera_properties()
  props = cam.override_with_hidden_physical_camera_props(props)
  camera_properties_utils.skip_unless(
      camera_properties_utils.raw16(props) and
      camera_properties_utils.manual_sensor(props) and
      camera_properties_utils.read_3a(props) and
      camera_properties_utils.per_frame_control(props))

  min_exposure_ns, _ = props['android.sensor.info.exposureTimeRange']
  min_fd = props['android.lens.info.minimumFocusDistance']
  white_level = props['android.sensor.info.whiteLevel']

  iso = low_iso

  results = []

  # This operation can last a very long time, if it happens to fail halfway
  # through, this section of code will allow us to pick up where we left off
  if os.path.exists(dest_file):
    # If there already exists a results file, retrieve them
    with open(dest_file, 'rb') as f:
      results = pickle.load(f)
    # Set the starting iso to the last iso of results
    iso = results[-1][0]['iso']
    iso *= math.pow(2, 1.0/steps)

  while int(round(iso)) <= high_iso:
    iso_int = int(iso)
    req = capture_request_utils.manual_capture_request(iso_int, min_exposure_ns)
    req['android.lens.focusDistance'] = min_fd
    fmt = {'format': 'raw'}
    cap = cam.do_capture(req, fmt)
    w = cap['width']
    h = cap['height']

    img = np.ndarray(shape=(h*w,), dtype='<u2', buffer=cap['data'][0:w*h*2])
    img = img.astype(dtype=np.uint16).reshape(h, w)

    # Add values to results, organized as a dictionary
    results.append(_generate_image_data_bayer(img, iso, white_level, cmap))

    logging.info('iso: %.2f, mean: %.2f, var: %.2f, min: %d, max: %d', iso,
                 np.mean(img), np.var(img), np.min(img), np.max(img))

    with open(dest_file, 'wb+') as f:
      pickle.dump(results, f)

    iso *= math.pow(2, 1.0/steps)

  logging.info('Results pickled into file %s', dest_file)

  return results
