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

import logging
import math
import os.path
from pathlib import Path
import pickle
import tempfile
import textwrap

import capture_read_noise_utils
import capture_request_utils
import image_processing_utils
import its_base_test
import its_session_utils
from matplotlib import pylab
import matplotlib.pyplot as plt
from matplotlib.ticker import NullLocator, ScalarFormatter
from mobly import test_runner
import numpy as np
import scipy.signal
import scipy.stats


_BAYER_LIST = ('R', 'GR', 'GB', 'B')  # List of Bayer colors
_BAYER_COLOR_FILTERS = [  # Bayer filters (SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
    'RGGB',  # 0
    'GRBG',  # 1
    'GBRG',  # 2
    'BGGR',  # 3
    'RBG',   # 4
    'MONO',  # 5
    'NIR'    # 6
]
_BRACKET_MAX = 8  # Exposure bracketing range in stops
_BRACKET_FACTOR = math.pow(2, _BRACKET_MAX)
_ISO_MAX_VALUE = None  # ISO range max value, uses sensor max if None
_ISO_MIN_VALUE = None  # ISO range min value, uses sensor min if None
_PLOT_COLORS = 'rygcbm'  # Colors used for plotting the data for each exposure.
_MAX_SCALE_FUDGE = 1.1
_MAX_SIGNAL_VALUE = 0.25  # Maximum value to allow mean of the tiles to go.
_NAME = os.path.basename(__file__).split('.')[0]
_NAME_READ_NOISE = os.path.join(tempfile.gettempdir(), 'CameraITS/ReadNoise')
_NAME_READ_NOISE_FILE = 'read_noise_results.pkl'
_OUTLIE_MEDIAN_ABS_DEVS = 10  # Defines the number of Median Absolute Deviations
                              # that consitutes acceptable data
_READ_NOISE_STEPS_PER_STOP = 12  # Sensitivities per stop to sample for read
                                 # noise
_REMOVE_OUTLIERS = False  # When True, filters the variance to remove outliers
_STEPS_PER_STOP = 3  # How many sensitivities per stop to sample.
_TILE_SIZE = 32  # Tile size to compute mean/variance. Large tiles may have
                 # their variance corrupted by low freq image changes.
_TILE_CROP_N = 0  # Number of tiles to crop from edge of image. Usually 0.
_TWO_STAGE_MODEL = False  # Require read noise data prior to running noise model
_ZOOM_RATIO = 1  # Zoom target to be used while running the model


def check_auto_exposure_targets(auto_e, sens_min, sens_max, props):
  """Checks if AE too bright for highest gain & too dark for lowest gain."""

  min_exposure_ns, max_exposure_ns = props[
      'android.sensor.info.exposureTimeRange']
  if auto_e < min_exposure_ns*sens_max:
    raise AssertionError('Scene is too bright to properly expose at highest '
                         f'sensitivity: {sens_max}')
  if auto_e*_BRACKET_FACTOR > max_exposure_ns*sens_min:
    raise AssertionError('Scene is too dark to properly expose at lowest '
                         f'sensitivity: {sens_min}')


def create_noise_model_code(noise_model_a, noise_model_b,
                            noise_model_c, noise_model_d,
                            sens_min, sens_max, digital_gain_cdef, log_path):
  """Creates the c file for the noise model."""

  noise_model_a_array = ','.join([str(i) for i in noise_model_a])
  noise_model_b_array = ','.join([str(i) for i in noise_model_b])
  noise_model_c_array = ','.join([str(i) for i in noise_model_c])
  noise_model_d_array = ','.join([str(i) for i in noise_model_d])
  code = textwrap.dedent(f"""\
          /* Generated test code to dump a table of data for external validation
           * of the noise model parameters.
           */
          #include <stdio.h>
          #include <assert.h>
          double compute_noise_model_entry_S(int plane, int sens);
          double compute_noise_model_entry_O(int plane, int sens);
          int main(void) {{
              for (int plane = 0; plane < {len(noise_model_a)}; plane++) {{
                  for (int sens = {sens_min}; sens <= {sens_max}; sens += 100) {{
                      double o = compute_noise_model_entry_O(plane, sens);
                      double s = compute_noise_model_entry_S(plane, sens);
                      printf("%d,%d,%lf,%lf\\n", plane, sens, o, s);
                  }}
              }}
              return 0;
          }}

          /* Generated functions to map a given sensitivity to the O and S noise
           * model parameters in the DNG noise model. The planes are in
           * R, Gr, Gb, B order.
           */
          double compute_noise_model_entry_S(int plane, int sens) {{
              static double noise_model_A[] = {{ {noise_model_a_array:s} }};
              static double noise_model_B[] = {{ {noise_model_b_array:s} }};
              double A = noise_model_A[plane];
              double B = noise_model_B[plane];
              double s = A * sens + B;
              return s < 0.0 ? 0.0 : s;
          }}

          double compute_noise_model_entry_O(int plane, int sens) {{
              static double noise_model_C[] = {{ {noise_model_c_array:s} }};
              static double noise_model_D[] = {{ {noise_model_d_array:s} }};
              double digital_gain = {digital_gain_cdef:s};
              double C = noise_model_C[plane];
              double D = noise_model_D[plane];
              double o = C * sens * sens + D * digital_gain * digital_gain;
              return o < 0.0 ? 0.0 : o;
          }}
          """)
  text_file = open(os.path.join(log_path, 'noise_model.c'), 'w')
  text_file.write(code)
  text_file.close()

  # Creates the noise profile C++ file
  code = textwrap.dedent(f"""\
          /* noise_profile.cc
             Note: gradient_slope --> gradient of API slope parameter
                   offset_slope --> offset of API slope parameter
                   gradient_intercept--> gradient of API intercept parameter
                   offset_intercept --> offset of API intercept parameter
             Note: SENSOR_NOISE_PROFILE in Android Developers doc uses
                   N(x) = sqrt(Sx + O), where 'S' is 'slope' & 'O' is 'intercept'
          */
          .noise_profile =
              {{.noise_coefficients_r = {{.gradient_slope = {noise_model_a[0]},
                                        .offset_slope = {noise_model_b[0]},
                                        .gradient_intercept = {noise_model_c[0]},
                                        .offset_intercept = {noise_model_d[0]}}},
               .noise_coefficients_gr = {{.gradient_slope = {noise_model_a[1]},
                                         .offset_slope = {noise_model_b[1]},
                                         .gradient_intercept = {noise_model_c[1]},
                                         .offset_intercept = {noise_model_d[1]}}},
               .noise_coefficients_gb = {{.gradient_slope = {noise_model_a[2]},
                                         .offset_slope = {noise_model_b[2]},
                                         .gradient_intercept = {noise_model_c[2]},
                                         .offset_intercept = {noise_model_d[2]}}},
               .noise_coefficients_b = {{.gradient_slope = {noise_model_a[3]},
                                        .offset_slope = {noise_model_b[3]},
                                        .gradient_intercept = {noise_model_c[3]},
                                        .offset_intercept = {noise_model_d[3]}}}}},
          """)
  text_file = open(os.path.join(log_path, 'noise_profile.cc'), 'w')
  text_file.write(code)
  text_file.close()


def outlier_removed_indices(data, deviations=3):
  """Removes outliers using median absolute deviation and returns indices kept.

  Args:
      data:             list to remove outliers from
      deviations:       number of deviations from median to keep
  Returns:
      keep_indices:     The indices of data which should be kept
  """
  std_dev = scipy.stats.median_abs_deviation(data, axis=None, scale=1)
  med = np.median(data)
  keep_indices = np.where(
      np.logical_and(data > med-deviations*std_dev,
                     data < med+deviations*std_dev))
  return keep_indices


def reorganize_read_noise_coeff_to_rggb(data, cmap):
  """Reorganize the list of read noise coeffs to RGGB Bayer form.

  Args:
    data:      list; List of color channel data
    cmap:      str; Color map filter of the given data
  Returns:
    list       Returns data reformatted in the correct RGGB order
  """
  if cmap not in _BAYER_COLOR_FILTERS:
    raise AssertionError(f'Unexpected color map {cmap}')

  if cmap.lower() == 'rggb':
    return data
  if cmap.lower() == 'grbg':
    return [data[1], data[0], data[3], data[2]]
  if cmap.lower() == 'gbrg':
    return [data[2], data[3], data[0], data[1]]
  if cmap.lower() == 'bggr':
    return [data[3], data[2], data[1], data[0]]
  else:
    raise AssertionError(
        'Currently only 4-channel filters supported for 2-Stage model')


class DngNoiseModel(its_base_test.ItsBaseTest):
  """Create DNG noise model.

  Captures RAW images with increasing analog gains to create the model.
  def requires 'test' in name to actually run.
  """

  def test_dng_noise_model_generation(self):
    read_noise_folder = ''
    read_noise_data = ''

    # If 2-Stage model is enabled, check/collect read noise data
    if _TWO_STAGE_MODEL:
      with its_session_utils.ItsSession(
          device_id=self.dut.serial,
          camera_id=self.camera_id,
          hidden_physical_id=self.hidden_physical_id) as cam:
        props = cam.get_camera_properties()
        props = cam.override_with_hidden_physical_camera_props(props)

        # Get sensor ISO range
        sens_min, _ = props['android.sensor.info.sensitivityRange']
        sens_max_analog = props['android.sensor.maxAnalogSensitivity']
        color_map_index = props['android.sensor.info.colorFilterArrangement']
        color_map = _BAYER_COLOR_FILTERS[color_map_index]

        sens_max_meas = sens_max_analog

        # Create the folder structure
        if self.hidden_physical_id:
          camera_name = f'{self.camera_id}.{self.hidden_physical_id}'
        else:
          camera_name = self.camera_id

        read_noise_folder = os.path.join(_NAME_READ_NOISE,
                                         self.dut.serial.replace(':', '_'),
                                         camera_name)
        read_noise_data = os.path.join(read_noise_folder,
                                       _NAME_READ_NOISE_FILE)

        if not os.path.exists(read_noise_folder):
          os.makedirs(read_noise_folder)

        logging.info('Read noise data folder: %s', read_noise_folder)
        # Collect or retrieve read noise data
        if not os.path.isfile(read_noise_data):
          # Wait until camera is repositioned for read noise data collection
          input(f'\nPress <ENTER> after concealing camera {self.camera_id} in'
                ' complete darkness.\n')
          logging.info('Collecting read noise data for %s', self.camera_id)
          # Results file does not exist, collect read noise data
          capture_read_noise_utils.capture_read_noise_for_iso_range(
              cam, sens_min, sens_max_meas, _READ_NOISE_STEPS_PER_STOP,
              color_map, read_noise_data)
        else:  # If data exists, check if it covers the full range
          with open(read_noise_data, 'rb') as f:
            results = pickle.load(f)
            # The +5 offset takes write to read error into account
            if results[-1][0]['iso'] + 50 < sens_max_meas:
              logging.info('\nNot enough ISO data points exist. '
                           '\nMax ISO measured: %.2f'
                           '\nMax ISO possible: %.2f',
                           results[-1][0]['iso'],
                           sens_max_meas)
              # Wait until camera is repositioned for read noise data collection
              input(f'\nPress <ENTER> after concealing camera {self.camera_id} '
                    'in complete darkness.\n')
              # Not all data points were captured, continue capture
              capture_read_noise_utils.capture_read_noise_for_iso_range(
                  cam, sens_min, sens_max_meas, _READ_NOISE_STEPS_PER_STOP,
                  color_map, read_noise_data)

    # Begin DNG Noise Model Calibration
    with its_session_utils.ItsSession(
        device_id=self.dut.serial,
        camera_id=self.camera_id,
        hidden_physical_id=self.hidden_physical_id) as cam:
      props = cam.get_camera_properties()
      props = cam.override_with_hidden_physical_camera_props(props)
      log_path = self.log_path
      name_with_log_path = os.path.join(log_path, _NAME)
      if self.hidden_physical_id:
        camera_name = f'{self.camera_id}.{self.hidden_physical_id}'
      else:
        camera_name = self.camera_id
      logging.info('Starting %s for camera %s', _NAME, camera_name)

      # Get basic properties we need.
      sens_min, sens_max = props['android.sensor.info.sensitivityRange']
      sens_max_analog = props['android.sensor.maxAnalogSensitivity']
      sens_max_meas = sens_max_analog
      white_level = props['android.sensor.info.whiteLevel']
      min_exposure_ns, _ = props[
          'android.sensor.info.exposureTimeRange']

      # Change the ISO min and/or max values if specified
      if _ISO_MIN_VALUE is not None:
        sens_min = _ISO_MIN_VALUE
      if _ISO_MAX_VALUE is not None:
        sens_max_meas = _ISO_MAX_VALUE

      # Wait until camera is repositioned for DNG noise model calibration
      input(f'\nPress <ENTER> after covering camera lense {self.camera_id} with'
            ' frosted glass diffuser, and facing lense at evenly illuminated'
            ' surface.\n')

      if _TWO_STAGE_MODEL:
        # Check if read noise results exist for this device and camera
        if not os.path.exists(read_noise_data):
          raise AssertionError(
              'Read noise results file does not exist for this device. Run '
              'capture_read_noise_data script to gather read noise data for '
              'current sensor')

        color_map_index = props['android.sensor.info.colorFilterArrangement']
        color_map = _BAYER_COLOR_FILTERS[color_map_index]

        with open(read_noise_data, 'rb') as f:
          read_noise_results = pickle.load(f)

        coeff_a, coeff_b = capture_read_noise_utils.get_read_noise_coefficients(
            read_noise_results, sens_min, sens_max_meas)

        coeff_a = reorganize_read_noise_coeff_to_rggb(coeff_a, color_map)
        coeff_b = reorganize_read_noise_coeff_to_rggb(coeff_b, color_map)

      logging.info('Sensitivity range: [%d, %d]', sens_min, sens_max)
      logging.info('Max analog sensitivity: %d', sens_max_analog)

      # Do AE to get a rough idea of where we are.
      iso_ae, exp_ae, _, _, _ = cam.do_3a(
          get_results=True, do_awb=False, do_af=False)

      # Underexpose to get more data for low signal levels.
      auto_e = iso_ae * exp_ae / _BRACKET_FACTOR
      check_auto_exposure_targets(auto_e, sens_min, sens_max_meas, props)

      # Focus at zero to intentionally blur the scene as much as possible.
      f_dist = 0.0

      # Start the sensitivities at the minimum.
      iso = sens_min
      samples = [[], [], [], []]
      plots = []
      measured_models = [[], [], [], []]
      color_plane_plots = {}
      isos = []
      fmt_raw = {'format': 'rawStats',
                 'gridWidth': _TILE_SIZE,
                 'gridHeight': _TILE_SIZE}

      while int(round(iso)) <= sens_max_meas:
        req = capture_request_utils.manual_capture_request(
            int(round(iso)), min_exposure_ns, f_dist)
        cap = cam.do_capture(req, fmt_raw)

        # Instead of raising an error when the sensitivity readback != requested
        # use the readback value for calculations instead
        iso_cap = cap['metadata']['android.sensor.sensitivity']
        isos.append(iso_cap)

        logging.info('ISO %d', iso_cap)

        fig, [[plt_r, plt_gr], [plt_gb, plt_b]] = plt.subplots(
            2, 2, figsize=(11, 11))
        fig.gca()
        color_plane_plots[iso_cap] = [plt_r, plt_gr, plt_gb, plt_b]
        fig.suptitle('ISO %d' % iso_cap, x=0.54, y=0.99)
        for i, plot in enumerate(color_plane_plots[iso_cap]):
          plot.set_title(_BAYER_LIST[i])
          plot.set_xlabel('Mean signal level')
          plot.set_ylabel('Variance')

        samples_s = [[], [], [], []]
        for b in range(_BRACKET_MAX):
          # Get the exposure for this sensitivity and exposure time.
          exposure = int(math.pow(2, b)*auto_e/iso)
          logging.info('exp %.3fms', round(exposure*1.0E-6, 3))
          req = capture_request_utils.manual_capture_request(iso_cap, exposure,
                                                             f_dist)
          req['android.control.zoomRatio'] = _ZOOM_RATIO
          fmt_raw = {'format': 'rawStats',
                     'gridWidth': _TILE_SIZE,
                     'gridHeight': _TILE_SIZE}
          cap = cam.do_capture(req, fmt_raw)
          if self.debug_mode:
            img = image_processing_utils.convert_capture_to_rgb_image(
                cap, props=props)
            image_processing_utils.write_image(
                img, f'{name_with_log_path}_{iso_cap}_{exposure}ns.jpg', True)

          mean_img, var_img = image_processing_utils.unpack_rawstats_capture(
              cap)
          idxs = image_processing_utils.get_canonical_cfa_order(props)
          raw_stats_size = mean_img.shape
          means = [mean_img[_TILE_CROP_N:raw_stats_size[0]-_TILE_CROP_N,
                            _TILE_CROP_N:raw_stats_size[1]-_TILE_CROP_N, i]
                   for i in idxs]
          vars_ = [var_img[_TILE_CROP_N:raw_stats_size[0]-_TILE_CROP_N,
                           _TILE_CROP_N:raw_stats_size[1]-_TILE_CROP_N, i]
                   for i in idxs]
          if self.debug_mode:
            logging.info('rawStats image size: %s', str(raw_stats_size))
            logging.info('R planes means image size: %s', str(means[0].shape))
            logging.info('means min: %.3f, median: %.3f, max: %.3f',
                         np.min(means), np.median(means), np.max(means))
            logging.info('vars_ min: %.4f, median: %.4f, max: %.4f',
                         np.min(vars_), np.median(vars_), np.max(vars_))

          # If remove outliers is True, we will filter the variance data
          if _REMOVE_OUTLIERS:
            means_filtered = []
            vars_filtered = []
            for pidx in range(len(means)):
              keep_indices = outlier_removed_indices(vars_[pidx],
                                                     _OUTLIE_MEDIAN_ABS_DEVS)
              means_filtered.append(means[pidx][keep_indices])
              vars_filtered.append(vars_[pidx][keep_indices])

            means = means_filtered
            vars_ = vars_filtered

          for pidx in range(len(means)):
            plot = color_plane_plots[iso_cap][pidx]

            # convert_capture_to_planes normalizes the range to [0, 1], but
            # without subtracting the black level.
            black_level = image_processing_utils.get_black_level(
                pidx, props, cap['metadata'])
            means_p = (means[pidx] - black_level)/(white_level - black_level)
            vars_p = vars_[pidx]/((white_level - black_level)**2)

            # TODO(dsharlet): It should be possible to account for low
            # frequency variation by looking at neighboring means, but I
            # have not been able to make this work.

            means_p = np.asarray(means_p).flatten()
            vars_p = np.asarray(vars_p).flatten()

            samples_e = []
            for (mean, var) in zip(means_p, vars_p):
              # Don't include the tile if it has samples that might be clipped.
              if mean + 2*math.sqrt(max(var, 0)) < _MAX_SIGNAL_VALUE:
                samples_e.append([mean, var])

            if samples_e:
              means_e, vars_e = zip(*samples_e)
              color_plane_plots[iso_cap][pidx].plot(
                  means_e, vars_e, _PLOT_COLORS[b%len(_PLOT_COLORS)] + '.',
                  alpha=0.5, markersize=1)
              samples_s[pidx].extend(samples_e)

        for (pidx, _) in enumerate(samples_s):
          [slope, intercept, rvalue, _, _] = scipy.stats.linregress(
              samples_s[pidx])

          measured_models[pidx].append([iso_cap, slope, intercept])
          logging.info('%s sensitivity %d: %e*y + %e (R=%f)',
                       _BAYER_LIST[pidx], iso_cap, slope, intercept, rvalue)

          # Add the samples for this sensitivity to the global samples list.
          samples[pidx].extend(
              [(iso_cap, mean, var) for (mean, var) in samples_s[pidx]])

          # Add the linear fit to subplot for this sensitivity.
          color_plane_plots[iso_cap][pidx].plot(
              [0, _MAX_SIGNAL_VALUE],
              [intercept, intercept + slope * _MAX_SIGNAL_VALUE],
              'rgkb'[pidx] + '--',
              label='Linear fit')

          xmax = max([max([x for (x, _) in p]) for p in samples_s
                     ]) * _MAX_SCALE_FUDGE
          ymax = (intercept + slope * xmax) * _MAX_SCALE_FUDGE
          color_plane_plots[iso_cap][pidx].set_xlim(xmin=0, xmax=xmax)
          color_plane_plots[iso_cap][pidx].set_ylim(ymin=0, ymax=ymax)
          color_plane_plots[iso_cap][pidx].legend()
          pylab.tight_layout()

        fig.savefig(f'{name_with_log_path}_samples_iso{iso_cap:04d}.png')
        plots.append([iso_cap, fig])

        # Move to the next sensitivity.
        iso *= math.pow(2, 1.0/_STEPS_PER_STOP)

    # Do model plots
    (fig, (plt_s, plt_o)) = plt.subplots(2, 1, figsize=(11, 8.5))
    plt_s.set_title('Noise model: N(x) = sqrt(Sx + O)')
    plt_s.set_ylabel('S')
    plt_o.set_xlabel('ISO')
    plt_o.set_ylabel('O')

    noise_model = []
    for (pidx, _) in enumerate(measured_models):
      # Grab the sensitivities and line parameters from each sensitivity.
      s_measured = [e[1] for e in measured_models[pidx]]
      o_measured = [e[2] for e in measured_models[pidx]]
      sens = np.asarray([e[0] for e in measured_models[pidx]])
      sens_sq = np.square(sens)

      # Use a global linear optimization to fit the noise model.
      gains = np.asarray([s[0] for s in samples[pidx]])
      means = np.asarray([s[1] for s in samples[pidx]])
      vars_ = np.asarray([s[2] for s in samples[pidx]])
      gains = gains.flatten()
      means = means.flatten()
      vars_ = vars_.flatten()

      # Define digital gain as the gain above the max analog gain
      # per the Camera2 spec. Also, define a corresponding C
      # expression snippet to use in the generated model code.
      digital_gains = np.maximum(gains/sens_max_analog, 1)
      if not np.all(digital_gains == 1):
        raise AssertionError(f'Digital gain! gains: {gains}, '
                             f'Max analog gain: {sens_max_analog}.')
      digital_gain_cdef = '(sens / %d.0) < 1.0 ? 1.0 : (sens / %d.0)' % (
          sens_max_analog, sens_max_analog)

      # Noise model function:
      # f(x) = scale * x + offset
      # Where:
      # scale = scale_a*analog_gain*digital_gain + scale_b
      # offset = (offset_a*analog_gain^2 + offset_b)*digital_gain^2
      #
      # Function f will be used to train the scale and offset coefficients,
      # scale_a, scale_b, offset_a, offset_b (a, b, c, d respectively)
      # Divide the whole system by gains*means.
      if _TWO_STAGE_MODEL:
        # For the two-stage model, we want to use the line fit coefficients
        # found from capturing read noise data (offset_a and offset_b) to
        # train the scale coefficients
        f = lambda x, a, b: (x[1]*a*x[0]+(x[1])*b+coeff_a[pidx]*(x[0]**2)+
                             coeff_b[pidx])/(x[0])
      else:
        f = lambda x, a, b, c, d: (x[1]*a*x[0]+x[1]*b + c*(x[0]**2)+d)/(x[0])
      result, _ = scipy.optimize.curve_fit(f, (gains, means), vars_/(gains))
      # result[0:4] = s_gradient, s_offset, o_gradient, o_offset
      # Note 'S' and 'O' are the API terms for the 2 model params.
      # The noise_profile.cc uses 'slope' for 'S' and 'intercept' for 'O'.
      # 'gradient' and 'offset' are used to describe the linear fit
      # parameters for 'S' and 'O'.

      # If using two-stage model, two of the coefficients calculated above are
      # constant, so we need to append them to the result ndarray
      if _TWO_STAGE_MODEL:
        result = np.append(result, coeff_a[pidx])
        result = np.append(result, coeff_b[pidx])

      noise_model.append(result[0:4])

      # Plot noise model components with the values predicted by the model.
      s_model = result[0]*sens + result[1]
      o_model = result[2]*sens_sq + result[3]*np.square(np.maximum(
          sens/sens_max_analog, 1))

      plt_s.loglog(sens, s_measured, 'rgkb'[pidx]+'+', base=10,
                   label='Measured')
      plt_s.loglog(sens, s_model, 'rgkb'[pidx]+'o', base=10,
                   label='Model', alpha=0.3)
      plt_o.loglog(sens, o_measured, 'rgkb'[pidx]+'+', base=10,
                   label='Measured')
      plt_o.loglog(sens, o_model, 'rgkb'[pidx]+'o', base=10,
                   label='Model', alpha=0.3)

    plt_s.set_xticks(isos)
    plt_s.xaxis.set_minor_locator(NullLocator())  # no minor ticks
    plt_s.xaxis.set_major_formatter(ScalarFormatter())
    plt_s.legend()

    plt_o.set_xticks(isos)
    plt_o.xaxis.set_minor_locator(NullLocator())  # no minor ticks
    plt_o.xaxis.set_major_formatter(ScalarFormatter())
    plt_o.legend()
    fig.savefig(f'{name_with_log_path}.png')

    # Generate individual noise model components
    noise_model_a, noise_model_b, noise_model_c, noise_model_d = zip(
        *noise_model)

    # Add models to subplots and re-save
    for [iso, fig] in plots:  # re-step through figs...
      dig_gain = max(iso/sens_max_analog, 1)
      fig.gca()
      for (pidx, _) in enumerate(measured_models):
        s = noise_model_a[pidx]*iso + noise_model_b[pidx]
        o = noise_model_c[pidx]*iso**2 + noise_model_d[pidx]*dig_gain**2
        color_plane_plots[iso][pidx].plot(
            [0, _MAX_SIGNAL_VALUE], [o, o+s*_MAX_SIGNAL_VALUE],
            'rgkb'[pidx]+'-', label='Model', alpha=0.5)
        color_plane_plots[iso][pidx].legend(loc='upper left')
      fig.savefig(f'{name_with_log_path}_samples_iso{iso:04d}.png')

    # Validity checks on model: read noise > 0, positive intercept gradient.
    for i, _ in enumerate(_BAYER_LIST):
      read_noise = noise_model_c[i] * sens_min * sens_min + noise_model_d[i]
      if read_noise <= 0:
        raise AssertionError(f'{_BAYER_LIST[i]} model min ISO noise < 0! '
                             f'API intercept gradient: {noise_model_c[i]:.4e}, '
                             f'API intercept offset: {noise_model_d[i]:.4e}, '
                             f'read_noise: {read_noise:.4e}')
      if noise_model_c[i] <= 0:
        raise AssertionError(f'{_BAYER_LIST[i]} model API intercept gradient '
                             f'is negative: {noise_model_c[i]:.4e}')

    # If 2-Stage model is enabled, save the read noise graph and csv data
    if _TWO_STAGE_MODEL:
      # Save the linear plot of the read noise data
      filename = f'{Path(_NAME_READ_NOISE_FILE).stem}.png'
      file_path = os.path.join(log_path, filename)
      capture_read_noise_utils.create_read_noise_plots_from_results(
          read_noise_results,
          sens_min,
          sens_max_meas,
          color_map,
          file_path)

      # Save the data as a csv file
      filename = f'{Path(_NAME_READ_NOISE_FILE).stem}.csv'
      file_path = os.path.join(log_path, filename)
      capture_read_noise_utils.create_and_save_csv_from_results(
          read_noise_results,
          sens_min,
          sens_max_meas,
          color_map,
          file_path)

    # Generate the noise model file.
    create_noise_model_code(
        noise_model_a, noise_model_b, noise_model_c, noise_model_d,
        sens_min, sens_max, digital_gain_cdef, log_path)

if __name__ == '__main__':
  test_runner.main()
