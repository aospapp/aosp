# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

from autotest_lib.server.cros.update_engine import update_engine_test

class autoupdate_Cellular(update_engine_test.UpdateEngineTest):
    """
    Tests auto updating over cellular.

    Usually with AU tests we use a lab devserver to hold the payload, and to be
    the omaha instance that DUTs ping. However, over cellular they will not be
    able to reach the devserver. So we will need to put the payload on a
    public google storage location. We will setup an omaha instance on the DUT
    (via autoupdate_CannedOmahaUpdate) that points to the payload on GStorage.

    """
    version = 1


    def cleanup(self):
        self._change_cellular_setting_in_update_engine(False)
        self._host.reboot()


    def run_once(self, job_repo_url=None, full_payload=True):
        update_url = self.get_update_url_for_test(job_repo_url,
                                                  full_payload=full_payload,
                                                  public=True)

        # gs://chromeos-image-archive does not contain a handy .json file
        # with the payloads size and SHA256 info like we have for payloads in
        # gs://chromeos-releases. So in order to get this information we need
        # to use a devserver to stage the payloads and then call the fileinfo
        # API with the staged file URL. This will return the fields we need.
        payload = self._get_payload_url(full_payload=full_payload)
        staged_url = self._stage_payload_by_uri(payload)
        file_info = self._get_staged_file_info(staged_url)

        self._change_cellular_setting_in_update_engine(True)
        self._run_client_test_and_check_result('autoupdate_CannedOmahaUpdate',
                                               image_url=update_url,
                                               image_size=file_info['size'],
                                               image_sha256=file_info['sha256'],
                                               use_cellular=True,
                                               is_delta=not full_payload)
        self._check_for_cellular_entries_in_update_log()
