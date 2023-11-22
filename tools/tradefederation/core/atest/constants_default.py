# Copyright 2017, The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
Various globals used by atest.
"""


MODE = 'DEFAULT'

# Result server constants for atest_utils.
RESULT_SERVER = ''
RESULT_SERVER_ARGS = []
RESULT_SERVER_TIMEOUT = 5
# Result arguments if tests are configured in TEST_MAPPING.
TEST_MAPPING_RESULT_SERVER_ARGS = []

# Google service key for gts tests.
GTS_GOOGLE_SERVICE_ACCOUNT = ''

# Arg constants.
WAIT_FOR_DEBUGGER = 'WAIT_FOR_DEBUGGER'
DISABLE_INSTALL = 'DISABLE_INSTALL'
DISABLE_TEARDOWN = 'DISABLE_TEARDOWN'
PRE_PATCH_ITERATIONS = 'PRE_PATCH_ITERATIONS'
POST_PATCH_ITERATIONS = 'POST_PATCH_ITERATIONS'
PRE_PATCH_FOLDER = 'PRE_PATCH_FOLDER'
POST_PATCH_FOLDER = 'POST_PATCH_FOLDER'
SERIAL = 'SERIAL'
ALL_ABI = 'ALL_ABI'
HOST = 'HOST'
CUSTOM_ARGS = 'CUSTOM_ARGS'
DRY_RUN = 'DRY_RUN'
ANDROID_SERIAL = 'ANDROID_SERIAL'
INSTANT = 'INSTANT'

# Application exit codes.
EXIT_CODE_SUCCESS = 0
EXIT_CODE_ENV_NOT_SETUP = 1
EXIT_CODE_BUILD_FAILURE = 2
EXIT_CODE_ERROR = 3
EXIT_CODE_TEST_NOT_FOUND = 4
EXIT_CODE_TEST_FAILURE = 5

# Test finder constants.
MODULE_CONFIG = 'AndroidTest.xml'
MODULE_COMPATIBILITY_SUITES = 'compatibility_suites'
MODULE_NAME = 'module_name'
MODULE_PATH = 'path'
MODULE_CLASS = 'class'
MODULE_INSTALLED = 'installed'
MODULE_CLASS_ROBOLECTRIC = 'ROBOLECTRIC'
MODULE_CLASS_NATIVE_TESTS = 'NATIVE_TESTS'
MODULE_CLASS_JAVA_LIBRARIES = 'JAVA_LIBRARIES'
MODULE_TEST_CONFIG = 'test_config'

# Env constants
ANDROID_BUILD_TOP = 'ANDROID_BUILD_TOP'
ANDROID_OUT = 'OUT'
ANDROID_OUT_DIR = 'OUT_DIR'
ANDROID_HOST_OUT = 'ANDROID_HOST_OUT'
ANDROID_PRODUCT_OUT = 'ANDROID_PRODUCT_OUT'

# Test Info data keys
# Value of include-filter option.
TI_FILTER = 'filter'
TI_REL_CONFIG = 'rel_config'
TI_MODULE_CLASS = 'module_class'
# Value of module-arg option
TI_MODULE_ARG = 'module-arg'

# Google TF
GTF_MODULE = 'google-tradefed'
GTF_TARGET = 'google-tradefed-core'

# Test group for tests in TEST_MAPPING
TEST_GROUP_PRESUBMIT = 'presubmit'
TEST_GROUP_POSTSUBMIT = 'postsubmit'
TEST_GROUP_ALL = 'all'
# Key in TEST_MAPPING file for a list of imported TEST_MAPPING file
TEST_MAPPING_IMPORTS = 'imports'

# TradeFed command line args
TF_INCLUDE_FILTER_OPTION = 'include-filter'
TF_EXCLUDE_FILTER_OPTION = 'exclude-filter'
TF_INCLUDE_FILTER = '--include-filter'
TF_EXCLUDE_FILTER = '--exclude-filter'
TF_ATEST_INCLUDE_FILTER = '--atest-include-filter'
TF_ATEST_INCLUDE_FILTER_VALUE_FMT = '{test_name}:{test_filter}'
TF_MODULE_ARG = '--module-arg'
TF_MODULE_ARG_VALUE_FMT = '{test_name}:{option_name}:{option_value}'
TF_SUITE_FILTER_ARG_VALUE_FMT = '"{test_name} {option_value}"'

# Suite Plans
SUITE_PLANS = frozenset(['cts'])

# Constants used for AtestArgParser
HELP_DESC = 'Build, install and run Android tests locally.'
BUILD_STEP = 'build'
INSTALL_STEP = 'install'
TEST_STEP = 'test'
ALL_STEPS = [BUILD_STEP, INSTALL_STEP, TEST_STEP]
REBUILD_MODULE_INFO_FLAG = '--rebuild-module-info'

# ANSI code shift for colorful print
BLACK, RED, GREEN, YELLOW, BLUE, MAGENTA, CYAN, WHITE = range(8)

# Answers equivalent to YES!
AFFIRMATIVES = ['y', 'Y', 'yes', 'Yes', 'YES', '']
LD_RANGE = 2

# Types of Levenshetine Distance Cost
COST_TYPO = (1, 1, 1)
COST_SEARCH = (8, 1, 5)

# Value of TestInfo install_locations.
DEVICELESS_TEST = 'host'
DEVICE_TEST = 'device'
BOTH_TEST = 'both'

# Metrics
METRICS_URL = 'http://asuite-218222.appspot.com/atest/metrics'
EXTERNAL = 'EXTERNAL_RUN'
INTERNAL = 'INTERNAL_RUN'
INTERNAL_EMAIL = '@google.com'
CONTENT_LICENSES_URL = 'https://source.android.com/setup/start/licenses'
CONTRIBUTOR_AGREEMENT_URL = {
    'INTERNAL': 'https://cla.developers.google.com/',
    'EXTERNAL': 'https://opensource.google.com/docs/cla/'
}
PRIVACY_POLICY_URL = 'https://policies.google.com/privacy'
TERMS_SERVICE_URL = 'https://policies.google.com/terms'
TOOL_NAME = 'atest'

# VTS plans
VTS_STAGING_PLAN = 'vts-staging-default'

# TreeHugger TEST_MAPPING SUITE_PLANS
TEST_MAPPING_SUITES = ['device-tests', 'general-tests']

# VTS TF
VTS_TF_MODULE = 'vts-tradefed'

# ATest TF
ATEST_TF_MODULE = 'atest-tradefed'
