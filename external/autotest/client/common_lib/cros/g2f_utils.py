# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import time

def StartU2fd(client):
    """Starts u2fd on the client.

    @param client: client object to run commands on.
    """
    client.run('stop u2fd', ignore_status=True)
    old_dev = client.run('ls /dev/hidraw*',
                          ignore_status=True).stdout.strip().split('\n')
    client.run_background('u2fd --force_g2f')

    # TODO(louiscollard): Replace this with something less fragile.
    cr50_dev = set()
    timeout_count = 0
    while (len(cr50_dev) == 0 and timeout_count < 5):
      time.sleep(1)
      timeout_count += 1
      new_dev = client.run('ls /dev/hidraw*',
                            ignore_status=True).stdout.strip().split('\n')
      cr50_dev = set(new_dev) - set(old_dev)

    return cr50_dev.pop()

def G2fRegister(client, dev, challenge, application, p1=0):
    """Returns a dictionary with TPM status.

    @param client: client object to run commands on.
    """
    return client.run('g2ftool --reg --dev=' + dev +
                      ' --challenge=' + challenge +
                      ' --application=' + application +
                      ' --p1=' + str(p1),
                      ignore_status=True)

def G2fAuth(client, dev, challenge, application, key_handle, p1=0):
    """Returns a dictionary with TPM status.

    @param client: client object to run commands on.
    """
    return client.run('g2ftool --auth --dev=' + dev +
                      ' --challenge=' + challenge +
                      ' --application=' + application +
                      ' --key_handle=' + key_handle +
                      ' --p1=' + str(p1),
                      ignore_status=True)
