# Copyright 2016 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Utility functions for AFE-based interactions.

NOTE: This module should only be used in the context of a running test. Any
      utilities that require accessing the AFE, should do so by creating
      their own instance of the AFE client and interact with it directly.
"""

import common
from autotest_lib.client.common_lib import global_config
from autotest_lib.server.cros import autoupdater
from autotest_lib.server.cros import provision
from autotest_lib.server.cros.dynamic_suite import frontend_wrappers


AFE = frontend_wrappers.RetryingAFE(timeout_min=5, delay_sec=10)
_CROS_VERSION_MAP = AFE.get_stable_version_map(AFE.CROS_IMAGE_TYPE)
_FIRMWARE_VERSION_MAP = AFE.get_stable_version_map(AFE.FIRMWARE_IMAGE_TYPE)
_FAFT_VERSION_MAP = AFE.get_stable_version_map(AFE.FAFT_IMAGE_TYPE)

_CONFIG = global_config.global_config
ENABLE_DEVSERVER_TRIGGER_AUTO_UPDATE = _CONFIG.get_config_value(
        'CROS', 'enable_devserver_trigger_auto_update', type=bool,
        default=False)


def _host_in_lab(host):
    """Check if the host is in the lab and an object the AFE knows.

    This check ensures that autoserv and the host's current job is running
    inside a fully Autotest instance, aka a lab environment. If this is the
    case it then verifies the host is registed with the configured AFE
    instance.

    @param host: Host object to verify.

    @returns The host model object.
    """
    if not host.job or not host.job.in_lab:
        return False
    return host._afe_host


def get_stable_cros_image_name(board):
    """Retrieve the Chrome OS stable image name for a given board.

    @param board: Board to lookup.

    @returns Name of a Chrome OS image to be installed in order to
            repair the given board.
    """
    return _CROS_VERSION_MAP.get_image_name(board)


def get_stable_firmware_version(model):
    """Retrieve the stable firmware version for a given model.

    @param model: Model to lookup.

    @returns A version of firmware to be installed via
             `chromeos-firmwareupdate` from a repair build.
    """
    return _FIRMWARE_VERSION_MAP.get_version(model)


def get_stable_faft_version(board):
    """Retrieve the stable firmware version for FAFT DUTs.

    @param board: Board to lookup.

    @returns A version of firmware to be installed in order to
            repair firmware on a DUT used for FAFT testing.
    """
    return _FAFT_VERSION_MAP.get_version(board)


def _clear_host_attributes_before_provision(host, info):
    """Clear host attributes before provision, e.g., job_repo_url.

    @param host: A Host object to clear attributes before provision.
    @param info: A HostInfo to update the attributes in.
    """
    attributes = host.get_attributes_to_clear_before_provision()
    if not attributes:
        return

    for key in attributes:
        info.attributes.pop(key, None)


def machine_install_and_update_labels(host, update_url,
                                      use_quick_provision=False,
                                      with_cheets=False):
    """Install a build and update the version labels on a host.

    @param host: Host object where the build is to be installed.
    @param update_url: URL of the build to install.
    @param use_quick_provision:  If true, then attempt to use
        quick-provision for the update.
    @param with_cheets: If true, installation is for a specific, custom
        version of Android for a target running ARC.
    """
    info = host.host_info_store.get()
    info.clear_version_labels()
    _clear_host_attributes_before_provision(host, info)
    host.host_info_store.commit(info)
    updater = autoupdater.ChromiumOSUpdater(
            update_url, host=host, use_quick_provision=use_quick_provision)
    image_name, host_attributes = updater.run_update()
    info = host.host_info_store.get()
    info.attributes.update(host_attributes)
    if with_cheets:
        image_name += provision.CHEETS_SUFFIX
    info.set_version_label(host.VERSION_PREFIX, image_name)
    host.host_info_store.commit(info)
