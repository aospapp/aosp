# Copyright (c) 2019 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import errno
import os

from autotest_lib.client.bin import test
from autotest_lib.client.common_lib import error
from autotest_lib.client.cros.networking import shill_context
from autotest_lib.client.cros.networking import shill_proxy

class network_ConfigureEthernetServiceForProfile(test.test):
    """
    Wipe the default profile, start shill, configure an Ethernet service with a
    custom name server, restart shill, and check that the service exists with
    the configured name server.
    """
    DEFAULT_PROFILE_PATH = '/var/cache/shill/default.profile'
    version = 1


    def run_once(self):
        """Test main loop."""
        with shill_context.stopped_shill():
            try:
                os.remove(self.DEFAULT_PROFILE_PATH)
            except OSError as e:
                if e.errno != errno.ENOENT:
                    raise e
        shill = shill_proxy.ShillProxy.get_proxy()
        if shill is None:
            raise error.TestFail('Could not connect to shill')

        path = shill.configure_service_for_profile('/profile/default', {
                shill.SERVICE_PROPERTY_TYPE: 'ethernet',
                shill.SERVICE_PROPERTY_STATIC_IP_NAMESERVERS: '8.8.8.8',
                })

        with shill_context.stopped_shill():
            # We don't actually need to do anything while shill is
            # stopped. We just want shill to be restarted.
            pass
        shill = shill_proxy.ShillProxy.get_proxy()
        if shill is None:
            raise error.TestFail('Could not connect to shill')

        service = shill.find_object('Service', {
                'Name': 'Ethernet',
                shill.SERVICE_PROPERTY_STATIC_IP_NAMESERVERS: '8.8.8.8',
                })
        if not service:
            raise error.TestFail('Network not found after restart.')

    def cleanup(self):
        """Cleanup function."""

        with shill_context.stopped_shill():
            # Remove non-default configurations set by this test
            try:
                os.remove(self.DEFAULT_PROFILE_PATH)
            except OSError as e:
                if e.errno != errno.ENOENT:
                    raise e
        super(network_ConfigureEthernetServiceForProfile, self).cleanup()