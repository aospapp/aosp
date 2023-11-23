# Copyright 2017 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Extra functions for frontend.afe.models.Job objects.

Most of these exist in tightly coupled forms in legacy Autotest code
(e.g., part of methods with completely unrelated names on Task objects
under multiple layers of abstract classes).  These are defined here to
sanely reuse without having to commit to a long refactor of legacy code
that is getting deleted soon.

It's not really a good idea to define these on the Job class either;
they are specialized and the Job class already suffers from method
bloat.
"""

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

import os
import time
import urllib

from lucifer import autotest
from lucifer import results


def is_hostless(job):
    """Return True if the job is hostless.

    @param job: frontend.afe.models.Job instance
    """
    return not hostnames(job)


def hostnames(job):
    """Return a list of hostnames for a job.

    @param job: frontend.afe.models.Job instance
    """
    hqes = job.hostqueueentry_set.all().prefetch_related('host')
    return [hqe.host.hostname for hqe in hqes if hqe.host is not None]


def is_aborted(job):
    """Return if the job is aborted.

    (This means the job is marked for abortion; the job can still be
    running.)

    @param job: frontend.afe.models.Job instance
    """
    return job.hostqueueentry_set.filter(aborted=True).exists()


def is_server_job(job):
    """Return whether the job is a server job.

    @param job: frontend.afe.models.Job instance
    """
    return not is_client_job(job)


def is_client_job(job):
    """Return whether the job is a client job.

    If the job is not a client job, it is a server job.

    (In theory a job can be neither.  I have no idea what you should do
    in that case.)

    @param job: frontend.afe.models.Job instance
    """
    CONTROL_TYPE = autotest.load('client.common_lib.control_data').CONTROL_TYPE
    return CONTROL_TYPE.get_value(job.control_type) == CONTROL_TYPE.CLIENT


def needs_ssp(job):
    """Return whether the job needs SSP.

    This also looks up the config for jobs that do not have a value
    specified.

    @param job: frontend.afe.models.Job instance
    """
    return (_ssp_enabled()
            and is_server_job(job)
            # None is the same as True.
            and job.require_ssp != False)


def _ssp_enabled():
    """Return whether SSP is enabled in the config."""
    global_config = autotest.load('client.common_lib.global_config')
    return global_config.global_config.get_config_value(
            'AUTOSERV', 'enable_ssp_container', type=bool,
            default=True)


def control_file_path(workdir):
    """Path to control file for a job.

    This makes more sense in the old Autotest drone world.  The
    scheduler has to copy the control file to the drone.  It goes to a
    temporary path `drone_tmp/attach.N`.

    The drone is then able to run `autoserv <args> drone_tmp/attach.N`.

    But in the Lucifer world, we are already running on the drone, so we
    don't need to rendezvous with a temporary directory through
    drone_manager first.

    So pick an arbitrary filename to plop into the workdir.  autoserv
    will later copy this back to the standard control/control.srv.
    """
    return os.path.join(workdir, 'lucifer', 'control_attach')


def prepare_control_file(job, workdir):
    """Prepare control file for a job."""
    with open(control_file_path(workdir), 'w') as f:
        f.write(job.control_file)


def prepare_keyvals_files(job, workdir):
    """Prepare Autotest keyvals files for a job."""
    keyvals = job.keyval_dict()
    keyvals['job_queued'] = \
            int(time.mktime(job.created_on.timetuple()))
    results.write_keyvals(workdir, keyvals)
    if is_hostless(job):
        return
    for hqe in job.hostqueueentry_set.all().prefetch_related('host'):
        results.write_host_keyvals(
                workdir, hqe.host.hostname, _host_keyvals(hqe.host))


def write_aborted_keyvals_and_status(job, workdir):
    """Write the keyvals and status for an aborted job."""
    aborted_by = 'autotest_system'
    aborted_on = int(time.time())
    for hqe in job.hostqueueentry_set.all():
        if not hasattr(hqe, 'abortedhostqueueentry'):
            continue
        ahqe = hqe.abortedhostqueueentry
        aborted_by = ahqe.aborted_by
        aborted_on = int(time.mktime(ahqe.aborted_on.timetuple()))
        break
    results.write_keyvals(workdir, {
            'aborted_by': aborted_by,
            'aborted_on': aborted_on,
    })
    results.write_status_comment(
            workdir, 'Job aborted by %s on %s' % (aborted_by, aborted_on))


def _host_keyvals(host):
    """Return keyvals dict for a host.

    @param host: frontend.afe.models.Host instance
    """
    labels = list(_host_labels(host))
    platform = None
    for label in labels:
        if label.platform:
            platform = label.name
    return {
            'platform': platform,
            'labels': ','.join(urllib.quote(label.name) for label in labels),
    }


def _host_labels(host):
    """Return an iterable of labels for a host.

    @param host: frontend.afe.models.Host instance
    """
    if autotest.load('scheduler.scheduler_models').RESPECT_STATIC_LABELS:
        return _host_labels_with_static(host)
    else:
        return host.labels.all()


def _host_labels_with_static(host):
    """Return a generator of labels for a host, respecting static labels.

    @param host: frontend.afe.models.Host instance
    """
    models = autotest.load('frontend.afe.models')
    replaced_label_ids = frozenset(models.ReplacedLabel.objects.all()
                                   .values_list('label_id', flat=True))
    shadowed_labels = set()
    for label in host.labels.all():
        if label.id in replaced_label_ids:
            shadowed_labels.add(label.name)
        else:
            yield label
    for label in host.static_labels.all():
        if label.name in shadowed_labels:
            yield label
