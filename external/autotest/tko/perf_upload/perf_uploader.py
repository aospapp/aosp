# Lint as: python2, python3
# Copyright (c) 2013 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Uploads performance data to the performance dashboard.

Performance tests may output data that needs to be displayed on the performance
dashboard.  The autotest TKO parser invokes this module with each test
associated with a job.  If a test has performance data associated with it, it
is uploaded to the performance dashboard.  The performance dashboard is owned
by Chrome team and is available here: https://chromeperf.appspot.com/.  Users
must be logged in with an @google.com account to view chromeOS perf data there.

"""

import six.moves.http_client
import json
import os
import re
from six.moves import urllib

import common
from autotest_lib.tko import utils as tko_utils

_ROOT_DIR = os.path.dirname(os.path.abspath(__file__))

_OAUTH_IMPORT_OK = False
_OAUTH_CREDS = None
try:
    from google.oauth2 import service_account
    from google.auth.transport.requests import Request
    from google.auth.exceptions import RefreshError
    _OAUTH_IMPORT_OK = True
except ImportError as e:
    tko_utils.dprint('Failed to import google-auth:\n%s' % e)

_DASHBOARD_UPLOAD_URL = 'https://chromeperf.appspot.com/add_point'
_OAUTH_SCOPES = ['https://www.googleapis.com/auth/userinfo.email']
_PRESENTATION_CONFIG_FILE = os.path.join(
        _ROOT_DIR, 'perf_dashboard_config.json')
_PRESENTATION_SHADOW_CONFIG_FILE = os.path.join(
        _ROOT_DIR, 'perf_dashboard_shadow_config.json')
_SERVICE_ACCOUNT_FILE = '/creds/service_accounts/skylab-drone.json'

# Format for Chrome and ChromeOS version strings.
VERSION_REGEXP = r'^(\d+)\.(\d+)\.(\d+)\.(\d+)$'


class PerfUploadingError(Exception):
    """Exception raised in perf_uploader"""
    pass


def _parse_config_file(config_file):
    """Parses a presentation config file and stores the info into a dict.

    The config file contains information about how to present the perf data
    on the perf dashboard.  This is required if the default presentation
    settings aren't desired for certain tests.

    @param config_file: Path to the configuration file to be parsed.

    @returns A dictionary mapping each unique autotest name to a dictionary
        of presentation config information.

    @raises PerfUploadingError if config data or main name for the test
        is missing from the config file.

    """
    json_obj = []
    if os.path.exists(config_file):
        with open(config_file, 'r') as fp:
            json_obj = json.load(fp)
    config_dict = {}
    for entry in json_obj:
        if 'autotest_regex' in entry:
            config_dict[entry['autotest_regex']] = entry
        else:
            config_dict['^' + re.escape(entry['autotest_name']) + '$'] = entry
    return config_dict


def _gather_presentation_info(config_data, test_name):
    """Gathers presentation info from config data for the given test name.

    @param config_data: A dictionary of dashboard presentation info for all
        tests, as returned by _parse_config_file().  Info is keyed by autotest
        name.
    @param test_name: The name of an autotest.

    @return A dictionary containing presentation information extracted from
        |config_data| for the given autotest name.

    @raises PerfUploadingError if some required data is missing.
    """
    presentation_dict = None
    for regex in config_data:
        match = re.match(regex, test_name)
        if match:
            if presentation_dict:
                raise PerfUploadingError('Duplicate config data refer to the '
                                         'same test %s' % test_name)
            presentation_dict = config_data[regex]

    if not presentation_dict:
        raise PerfUploadingError(
                'No config data is specified for test %s in %s.' %
                (test_name, _PRESENTATION_CONFIG_FILE))
    try:
        main_name = presentation_dict['main_name']
    except KeyError:
        raise PerfUploadingError(
                'No main name is specified for test %s in %s.' %
                (test_name, _PRESENTATION_CONFIG_FILE))
    if 'dashboard_test_name' in presentation_dict:
        test_name = presentation_dict['dashboard_test_name']
    return {'main_name': main_name, 'test_name': test_name}


def _format_for_upload(board_name, cros_version, chrome_version,
                       hardware_id, hardware_hostname, perf_values,
                       presentation_info, jobname):
    """Formats perf data suitable to upload to the perf dashboard.

    The perf dashboard expects perf data to be uploaded as a
    specially-formatted JSON string.  In particular, the JSON object must be a
    dictionary with key "data", and value being a list of dictionaries where
    each dictionary contains all the information associated with a single
    measured perf value: main name, bot name, test name, perf value, error
    value, units, and build version numbers.

    @param board_name: The string name of the image board name.
    @param cros_version: The string chromeOS version number.
    @param chrome_version: The string chrome version number.
    @param hardware_id: String that identifies the type of hardware the test was
            executed on.
    @param hardware_hostname: String that identifies the name of the device the
            test was executed on.
    @param perf_values: A dictionary of measured perf data as computed by
            _compute_avg_stddev().
    @param presentation_info: A dictionary of dashboard presentation info for
            the given test, as identified by _gather_presentation_info().
    @param jobname: A string uniquely identifying the test run, this enables
            linking back from a test result to the logs of the test run.

    @return A dictionary containing the formatted information ready to upload
        to the performance dashboard.

    """
    # Client side case - server side comes with its own charts data section.
    if 'charts' not in perf_values:
        perf_values = {
          'format_version': '1.0',
          'benchmark_name': presentation_info['test_name'],
          'charts': perf_values,
        }

    # TODO b:169251326 terms below are set outside of this codebase and
    # should be updated when possible ("master" -> "main"). # nocheck
    # see catapult-project/catapult/dashboard/dashboard/add_point.py
    dash_entry = {
            'master': presentation_info['main_name'],  # nocheck
            'bot': 'cros-' + board_name,  # Prefix to clarify it's ChromeOS.
            'point_id': _get_id_from_version(chrome_version, cros_version),
            'versions': {
                    'cros_version': cros_version,
                    'chrome_version': chrome_version,
            },
            'supplemental': {
                    'default_rev': 'r_cros_version',
                    'hardware_identifier': hardware_id,
                    'hardware_hostname': hardware_hostname,
                    'jobname': jobname,
            },
            'chart_data': perf_values,
    }
    return {'data': json.dumps(dash_entry)}


def _get_version_numbers(test_attributes):
    """Gets the version numbers from the test attributes and validates them.

    @param test_attributes: The attributes property (which is a dict) of an
        autotest tko.models.test object.

    @return A pair of strings (ChromeOS version, Chrome version).

    @raises PerfUploadingError if a version isn't formatted as expected.
    """
    chrome_version = test_attributes.get('CHROME_VERSION', '')
    cros_version = test_attributes.get('CHROMEOS_RELEASE_VERSION', '')
    cros_milestone = test_attributes.get('CHROMEOS_RELEASE_CHROME_MILESTONE')
    # Use the release milestone as the milestone if present, othewise prefix the
    # cros version with the with the Chrome browser milestone.
    if cros_milestone:
        cros_version = "%s.%s" % (cros_milestone, cros_version)
    else:
        cros_version = chrome_version[:chrome_version.find('.') +
                                      1] + cros_version
    if not re.match(VERSION_REGEXP, cros_version):
        raise PerfUploadingError('CrOS version "%s" does not match expected '
                                 'format.' % cros_version)
    if not re.match(VERSION_REGEXP, chrome_version):
        raise PerfUploadingError('Chrome version "%s" does not match expected '
                                 'format.' % chrome_version)
    return (cros_version, chrome_version)


def _get_id_from_version(chrome_version, cros_version):
    """Computes the point ID to use, from Chrome and ChromeOS version numbers.

    For ChromeOS row data, data values are associated with both a Chrome
    version number and a ChromeOS version number (unlike for Chrome row data
    that is associated with a single revision number).  This function takes
    both version numbers as input, then computes a single, unique integer ID
    from them, which serves as a 'fake' revision number that can uniquely
    identify each ChromeOS data point, and which will allow ChromeOS data points
    to be sorted by Chrome version number, with ties broken by ChromeOS version
    number.

    To compute the integer ID, we take the portions of each version number that
    serve as the shortest unambiguous names for each (as described here:
    http://www.chromium.org/developers/version-numbers).  We then force each
    component of each portion to be a fixed width (padded by zeros if needed),
    concatenate all digits together (with those coming from the Chrome version
    number first), and convert the entire string of digits into an integer.
    We ensure that the total number of digits does not exceed that which is
    allowed by AppEngine NDB for an integer (64-bit signed value).

    For example:
      Chrome version: 27.0.1452.2 (shortest unambiguous name: 1452.2)
      ChromeOS version: 27.3906.0.0 (shortest unambiguous name: 3906.0.0)
      concatenated together with padding for fixed-width columns:
          ('01452' + '002') + ('03906' + '000' + '00') = '014520020390600000'
      Final integer ID: 14520020390600000

    @param chrome_ver: The Chrome version number as a string.
    @param cros_ver: The ChromeOS version number as a string.

    @return A unique integer ID associated with the two given version numbers.

    """

    # Number of digits to use from each part of the version string for Chrome
    # and ChromeOS versions when building a point ID out of these two versions.
    chrome_version_col_widths = [0, 0, 5, 3]
    cros_version_col_widths = [0, 5, 3, 2]

    def get_digits_from_version(version_num, column_widths):
        if re.match(VERSION_REGEXP, version_num):
            computed_string = ''
            version_parts = version_num.split('.')
            for i, version_part in enumerate(version_parts):
                if column_widths[i]:
                    computed_string += version_part.zfill(column_widths[i])
            return computed_string
        else:
            return None

    chrome_digits = get_digits_from_version(
            chrome_version, chrome_version_col_widths)
    cros_digits = get_digits_from_version(
            cros_version, cros_version_col_widths)
    if not chrome_digits or not cros_digits:
        return None
    result_digits = chrome_digits + cros_digits
    max_digits = sum(chrome_version_col_widths + cros_version_col_widths)
    if len(result_digits) > max_digits:
        return None
    return int(result_digits)


def _initialize_oauth():
    """Initialize oauth using local credentials and scopes.

    @return A boolean if oauth is apparently ready to use.
    """
    global _OAUTH_CREDS
    if _OAUTH_CREDS:
        return True
    if not _OAUTH_IMPORT_OK:
        return False
    try:
        _OAUTH_CREDS = (service_account.Credentials.from_service_account_file(
                        _SERVICE_ACCOUNT_FILE)
                        .with_scopes(_OAUTH_SCOPES))
        return True
    except Exception as e:
        tko_utils.dprint('Failed to initialize oauth credentials:\n%s' % e)
        return False


def _add_oauth_token(headers):
    """Add support for oauth2 via service credentials.

    This is currently best effort, we will silently not add the token
    for a number of possible reasons (missing service account, etc).

    TODO(engeg@): Once this is validated, make mandatory.

    @param headers: A map of request headers to add the token to.
    """
    if _initialize_oauth():
        if not _OAUTH_CREDS.valid:
            try:
                _OAUTH_CREDS.refresh(Request())
            except RefreshError as e:
                tko_utils.dprint('Failed to refresh oauth token:\n%s' % e)
                return
        _OAUTH_CREDS.apply(headers)


def _send_to_dashboard(data_obj):
    """Sends formatted perf data to the perf dashboard.

    @param data_obj: A formatted data object as returned by
        _format_for_upload().

    @raises PerfUploadingError if an exception was raised when uploading.

    """
    encoded = urllib.parse.urlencode(data_obj)
    req = urllib.request.Request(_DASHBOARD_UPLOAD_URL, encoded)
    _add_oauth_token(req.headers)
    try:
        urllib.request.urlopen(req)
    except urllib.error.HTTPError as e:
        raise PerfUploadingError('HTTPError: %d %s for JSON %s\n' % (
                e.code, e.msg, data_obj['data']))
    except urllib.error.URLError as e:
        raise PerfUploadingError(
                'URLError: %s for JSON %s\n' %
                (str(e.reason), data_obj['data']))
    except six.moves.http_client.HTTPException:
        raise PerfUploadingError(
                'HTTPException for JSON %s\n' % data_obj['data'])


def _get_image_board_name(platform, image):
    """Returns the board name of the tested image.

    Note that it can be different from the board name of DUTs the test was
    scheduled to.

    @param platform: The DUT platform in lab. eg. eve
    @param image: The image installed in the DUT. eg. eve-arcnext-release.
    @return: the image board name.
    """
    # This is a hacky way to resolve the mixture of reports in chromeperf
    # dashboard. This solution is copied from our other reporting
    # pipeline.
    image_board_name = platform

    suffixes = ['-arcnext', '-ndktranslation', '-arcvm', '-kernelnext']

    for suffix in suffixes:
        if not platform.endswith(suffix) and (suffix + '-') in image:
            image_board_name += suffix
    return image_board_name


def upload_test(job, test, jobname):
    """Uploads any perf data associated with a test to the perf dashboard.

    @param job: An autotest tko.models.job object that is associated with the
        given |test|.
    @param test: An autotest tko.models.test object that may or may not be
        associated with measured perf data.
    @param jobname: A string uniquely identifying the test run, this enables
            linking back from a test result to the logs of the test run.

    """

    # Format the perf data for the upload, then upload it.
    test_name = test.testname
    image_board_name = _get_image_board_name(
        job.machine_group, job.keyval_dict.get('build', job.machine_group))
    # Append the platform name with '.arc' if the suffix of the control
    # filename is '.arc'.
    if job.label and re.match('.*\.arc$', job.label):
        image_board_name += '.arc'
    hardware_id = test.attributes.get('hwid', '')
    hardware_hostname = test.machine
    config_data = _parse_config_file(_PRESENTATION_CONFIG_FILE)
    try:
        shadow_config_data = _parse_config_file(_PRESENTATION_SHADOW_CONFIG_FILE)
        config_data.update(shadow_config_data)
    except ValueError as e:
        tko_utils.dprint('Failed to parse config file %s: %s.' %
                         (_PRESENTATION_SHADOW_CONFIG_FILE, e))
    try:
        cros_version, chrome_version = _get_version_numbers(test.attributes)
        presentation_info = _gather_presentation_info(config_data, test_name)
        formatted_data = _format_for_upload(image_board_name, cros_version,
                                            chrome_version, hardware_id,
                                            hardware_hostname, test.perf_values,
                                            presentation_info, jobname)
        _send_to_dashboard(formatted_data)
    except PerfUploadingError as e:
        tko_utils.dprint('Warning: unable to upload perf data to the perf '
                         'dashboard for test %s: %s' % (test_name, e))
    else:
        tko_utils.dprint('Successfully uploaded perf data to the perf '
                         'dashboard for test %s.' % test_name)
