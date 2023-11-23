# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import re

from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import error
from autotest_lib.client.cros.update_engine import nano_omaha_devserver
from autotest_lib.client.cros.update_engine import update_engine_util

_MIN_BUILD = '1.1.1'
_MAX_BUILD = '999999.9.9'

# eventtype value sent by client in an AU request.  Indicates that
# device rebooted after an update since last check.
# TODO: remove this, crbug.com/879687
_EVENT_TYPE_REBOOTED_AFTER_UPDATE = '54'

class NanoOmahaEnterpriseAUContext(object):
    """
    Contains methods required for Enterprise AU tests using Nano Omaha.

    """
    def __init__(self, image_url, image_size, sha256, to_build=_MAX_BUILD,
                 from_build=_MIN_BUILD, is_rollback=False, is_critical=False):
        """
        Start a Nano Omaha instance and intialize variables.

        @param image_url: Url of update image.
        @param image_size: Size of the update.
        @param sha256: Sha256 hash of the update.
        @param to_build: String of the build number Nano Omaha should serve.
        @param from_build: String of the build number this device should say
                           it is on by setting lsb_release.
        @param is_rollback: whether the build should serve with the rollback
                            flag.
        @param is_critical: whether the build should serve marked as critical.

        """
        self._omaha = nano_omaha_devserver.NanoOmahaDevserver()
        self._omaha.set_image_params(image_url, image_size, sha256,
                                     build=to_build, is_rollback=is_rollback)
        self._omaha.start()

        self._au_util = update_engine_util.UpdateEngineUtil()

        update_url = self._omaha.get_update_url()
        self._au_util._create_custom_lsb_release(from_build, update_url)

        self._is_rollback = is_rollback
        self._is_critical = is_critical


    def update_and_poll_for_update_start(self, is_interactive=False):
        """
        Check for an update and wait until it starts.

        @param is_interactive: whether the request is interactive.

        @raises: error.TestFail when update does not start after timeout.

        """
        self._au_util._check_for_update(port=self._omaha.get_port(),
                                        interactive=is_interactive)

        def update_started():
            """Polling function: True or False if update has started."""
            status = self._au_util._get_update_engine_status()
            logging.info('Status: %s', status)
            return (status[self._au_util._CURRENT_OP]
                    == self._au_util._UPDATE_ENGINE_DOWNLOADING)

        utils.poll_for_condition(
                update_started,
                exception=error.TestFail('Update did not start!'))


    def get_update_requests(self):
        """
        Get the contents of all the update requests from the most recent log.

        @returns: a sequential list of <request> xml blocks or None if none.

        """
        return self._au_util._get_update_requests()


    def get_time_of_last_update_request(self):
        """
        Get the time of the last update request from most recent logfile.

        @returns: seconds since epoch of when last update request happened
                  (second accuracy), or None if no such timestamp exists.

        """
        return self._au_util._get_time_of_last_update_request()


    def get_latest_initial_request(self):
        """
        Return the most recent initial update request.

        AU requests occur in a chain of messages back and forth, e.g. the
        initial request for an update -> the reply with the update -> the
        report that install has started -> report that install has finished,
        etc.  This function finds the first request in the latest such chain.

        This message has no eventtype listed, or is rebooted_after_update
        type (as an artifact from a previous update since this one).
        Subsequent messages in the chain have different eventtype values.

        @returns: string of the entire update request or None.

        """
        requests = self.get_update_requests()
        if not requests:
            return None

        MATCH_STR = r'eventtype="(.*?)"'
        for i in xrange(len(requests) - 1, -1, -1):
            search = re.search(MATCH_STR, requests[i])
            if (not search or
                search.group(1) == _EVENT_TYPE_REBOOTED_AFTER_UPDATE):
                return requests[i]

        return None
