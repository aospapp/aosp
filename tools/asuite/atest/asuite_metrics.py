# Copyright 2018, The Android Open Source Project
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

"""Asuite simple Metrics Functions"""

import json
import logging
import os
import uuid

try:
    from urllib.request import Request
    from urllib.request import urlopen
except ImportError:
    # for compatibility of asuite_metrics_lib_tests and asuite_cc_lib_tests.
    from urllib2 import Request
    from urllib2 import urlopen


_JSON_HEADERS = {'Content-Type': 'application/json'}
_METRICS_RESPONSE = 'done'
_METRICS_TIMEOUT = 2 #seconds
_ANDROID_BUILD_TOP = 'ANDROID_BUILD_TOP'

UNUSED_UUID = '00000000-0000-4000-8000-000000000000'


#pylint: disable=broad-except
def log_event(metrics_url, unused_key_fallback=True, **kwargs):
    """Base log event function for asuite backend.

    Args:
        metrics_url: String, URL to report metrics to.
        unused_key_fallback: Boolean, If True and unable to get grouping key,
                            use a unused key otherwise return out. Sometimes we
                            don't want to return metrics for users we are
                            unable to identify. Default True.
        kwargs: Dict, additional fields we want to return metrics for.
    """
    try:
        try:
            key = str(_get_grouping_key())
        except Exception:
            if not unused_key_fallback:
                return
            key = UNUSED_UUID
        data = {'grouping_key': key,
                'run_id': str(uuid.uuid4())}
        if kwargs:
            data.update(kwargs)
        data = json.dumps(data)
        request = Request(metrics_url, data=data,
                          headers=_JSON_HEADERS)
        response = urlopen(request, timeout=_METRICS_TIMEOUT)
        content = response.read()
        if content != _METRICS_RESPONSE:
            raise Exception('Unexpected metrics response: %s' % content)
    except Exception as e:
        logging.debug('Exception sending metrics: %s', e)


def _get_grouping_key():
    """Get grouping key. Returns UUID.uuid5."""
    meta_file = os.path.join(os.path.expanduser('~'),
                             '.config', 'asuite', '.metadata')
    # (b/278503654) Treat non-human invocation as the same user when the email
    # is null.
    # Prevent circular import.
    #pylint: disable=import-outside-toplevel
    from atest.metrics import metrics_base
    key = uuid.uuid5(uuid.NAMESPACE_DNS, metrics_base.get_user_email())
    dir_path = os.path.dirname(meta_file)
    if os.path.isfile(dir_path):
        os.remove(dir_path)
    try:
        os.makedirs(dir_path)
    except OSError as e:
        if not os.path.isdir(dir_path):
            raise e
    with open(meta_file, 'w+') as f:
        f.write(str(key))
    return key
