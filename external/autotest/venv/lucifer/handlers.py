# Copyright 2017 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Event handlers."""

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

import datetime
import logging
import time

from lucifer import autotest
from lucifer import jobx

logger = logging.getLogger(__name__)


class EventHandler(object):
    """Event handling dispatcher.

    Event handlers are implemented as methods named _handle_<event value>.

    Each handler method must handle its exceptions accordingly.  If an
    exception escapes, the job dies on the spot.

    Instances have one public attribute completed.  completed is set to
    True once the final COMPLETED event is received and the handler
    finishes.
    """

    def __init__(self, metrics, job, autoserv_exit, results_dir):
        """Initialize instance.

        @param metrics: Metrics instance
        @param job: frontend.afe.models.Job instance to own
        @param hqes: list of HostQueueEntry instances for the job
        @param autoserv_exit: autoserv exit status
        @param results_dir: Job results directory
        """
        self.completed = False
        self._metrics = metrics
        self._job = job
        # TODO(crbug.com/748234): autoserv not implemented yet.
        self._autoserv_exit = autoserv_exit
        self._results_dir = results_dir

    def __call__(self, event, msg):
        logger.debug('Received event %r with message %r', event.name, msg)
        method_name = '_handle_%s' % event.value
        try:
            handler = getattr(self, method_name)
        except AttributeError:
            raise NotImplementedError('%s is not implemented for handling %s',
                                      method_name, event.name)
        _retry_db_errors(lambda: handler(msg))

    def _handle_starting(self, msg):
        # TODO(crbug.com/748234): No event update needed yet.
        pass

    def _handle_running(self, _msg):
        models = autotest.load('frontend.afe.models')
        self._job.hostqueueentry_set.all().update(
                status=models.HostQueueEntry.Status.RUNNING,
                started_on=datetime.datetime.now())

    def _handle_gathering(self, _msg):
        models = autotest.load('frontend.afe.models')
        self._job.hostqueueentry_set.all().update(
                status=models.HostQueueEntry.Status.GATHERING)

    def _handle_parsing(self, _msg):
        models = autotest.load('frontend.afe.models')
        self._job.hostqueueentry_set.all().update(
                status=models.HostQueueEntry.Status.PARSING)

    def _handle_aborted(self, _msg):
        for hqe in self._job.hostqueueentry_set.all().prefetch_related('host'):
            _mark_hqe_aborted(hqe)
        jobx.write_aborted_keyvals_and_status(self._job, self._results_dir)

    def _handle_completed(self, _msg):
        self._mark_job_complete()
        self.completed = True

    def _handle_test_passed(self, msg):
        if msg == 'autoserv':
            self._autoserv_exit = 0

    def _handle_test_failed(self, msg):
        if msg == 'autoserv':
            self._autoserv_exit = 1

    def _handle_host_running(self, msg):
        models = autotest.load('frontend.afe.models')
        host = models.Host.objects.get(hostname=msg)
        host.status = models.Host.Status.RUNNING
        host.dirty = 1
        host.save(update_fields=['status', 'dirty'])
        self._metrics.send_host_status(host)

    def _handle_host_ready(self, msg):
        models = autotest.load('frontend.afe.models')
        host = models.Host.objects.get(hostname=msg)
        host.status = models.Host.Status.READY
        host.save(update_fields=['status'])
        self._metrics.send_host_status(host)

    def _handle_host_needs_cleanup(self, msg):
        models = autotest.load('frontend.afe.models')
        host = models.Host.objects.get(hostname=msg)
        models.SpecialTask.objects.create(
                host_id=host.id,
                task=models.SpecialTask.Task.CLEANUP,
                requested_by=models.User.objects.get(login=self._job.owner))

    def _handle_host_needs_reset(self, msg):
        models = autotest.load('frontend.afe.models')
        host = models.Host.objects.get(hostname=msg)
        models.SpecialTask.objects.create(
                host_id=host.id,
                task=models.SpecialTask.Task.RESET,
                requested_by=models.User.objects.get(login=self._job.owner))

    def _handle_x_tests_done(self, msg):
        """Taken from GatherLogsTask.epilog."""
        autoserv_exit, failures = (int(x) for x in msg.split(','))
        logger.debug('Got autoserv_exit=%d, failures=%d',
                     autoserv_exit, failures)
        success = (autoserv_exit == 0 and failures == 0)
        reset_after_failure = not self._job.run_reset and not success
        hqes = self._job.hostqueueentry_set.all().prefetch_related('host')
        if self._should_reboot_duts(autoserv_exit, failures,
                                    reset_after_failure):
            logger.debug('Creating cleanup jobs for hosts')
            for entry in hqes:
                self._handle_host_needs_cleanup(entry.host.hostname)
        else:
            logger.debug('Not creating cleanup jobs for hosts')
            for entry in hqes:
                self._handle_host_ready(entry.host.hostname)
        if not reset_after_failure:
            logger.debug('Skipping reset because reset_after_failure is False')
            return
        logger.debug('Creating reset jobs for hosts')
        self._metrics.send_reset_after_failure(autoserv_exit, failures)
        for entry in hqes:
            self._handle_host_needs_reset(entry.host.hostname)

    def _should_reboot_duts(self, autoserv_exit, failures, reset_after_failure):
        models = autotest.load('frontend.afe.models')
        reboot_after = self._job.reboot_after
        if self._final_status() == models.HostQueueEntry.Status.ABORTED:
            logger.debug('Should reboot because reboot_after=ABORTED')
            return True
        elif reboot_after == models.Job.RebootAfter.ALWAYS:
            logger.debug('Should reboot because reboot_after=ALWAYS')
            return True
        elif (reboot_after == models.Job.RebootAfter.IF_ALL_TESTS_PASSED
              and autoserv_exit == 0 and failures == 0):
            logger.debug('Should reboot because'
                         ' reboot_after=IF_ALL_TESTS_PASSED')
            return True
        else:
            return failures > 0 and not reset_after_failure

    def _mark_job_complete(self):
        """Perform Autotest operations needed for job completion."""
        final_status = self._final_status()
        self._mark_hqes_complete(final_status)
        self._stop_job_if_necessary(final_status)
        self._release_job_if_sharded()

    def _mark_hqes_complete(self, final_status):
        """Perform Autotest HQE operations needed for job completion."""
        for hqe in self._job.hostqueueentry_set.all():
            self._set_completed_status(hqe, final_status)

    def _stop_job_if_necessary(self, final_status):
        """Equivalent to scheduler.modes.Job.stop_if_necessary().

        The name isn't informative, but this will stop pre-job tasks as
        necessary.
        """
        models = autotest.load('frontend.afe.models')
        if final_status is not models.HostQueueEntry.Status.ABORTED:
            _stop_prejob_hqes(self._job)

    def _release_job_if_sharded(self):
        if self._job.shard_id is not None:
            # If shard_id is None, the job will be synced back to the master
            self._job.shard_id = None
            self._job.save(update_fields=['shard_id'])

    def _final_status(self):
        models = autotest.load('frontend.afe.models')
        Status = models.HostQueueEntry.Status
        if jobx.is_aborted(self._job):
            return Status.ABORTED
        if self._autoserv_exit == 0:
            return Status.COMPLETED
        return Status.FAILED

    def _set_completed_status(self, hqe, status):
        """Set completed status of HQE.

        This is a cleaned up version of the one in scheduler_models to work
        with Django models.
        """
        hqe.status = status
        hqe.active = False
        hqe.complete = True
        if hqe.started_on:
            hqe.finished_on = datetime.datetime.now()
        hqe.save(update_fields=['status', 'active', 'complete', 'finished_on'])
        self._metrics.send_hqe_completion(hqe)
        self._metrics.send_hqe_duration(hqe)


class Metrics(object):

    """Class for sending job metrics."""

    def __init__(self):
        # Metrics
        metrics = autotest.chromite_load('metrics')
        self._hqe_completion_metric = metrics.Counter(
                'chromeos/autotest/scheduler/hqe_completion_count')
        self._reset_after_failure_metric = metrics.Counter(
                'chromeos/autotest/scheduler/postjob_tasks/'
                'reset_after_failure')
        self._host_status_metric = metrics.Boolean(
                'chromeos/autotest/dut_status', reset_after=True)

    def send_host_status(self, host):
        """Send ts_mon metrics for host status.

        @param host: frontend.afe.models.Host instance
        """
        labellib = autotest.load('utils.labellib')
        labels = labellib.LabelsMapping.from_host(host)
        fields = {
                'dut_host_name': host.hostname,
                'board': labels['board'],
                'model': labels['model'],
        }
        # As each device switches state, indicate that it is not in any
        # other state.  This allows Monarch queries to avoid double counting
        # when additional points are added by the Window Align operation.
        for s in host.Status.names:
            fields['status'] = s
            self._host_status_metric.set(s == host.status, fields=fields)

    def send_hqe_completion(self, hqe):
        """Send ts_mon metrics for HQE completion."""
        fields = {
                'status': hqe.status.lower(),
                'board': 'NO_HOST',
                'pool': 'NO_HOST',
        }
        if hqe.host:
            labellib = autotest.load('utils.labellib')
            labels = labellib.LabelsMapping.from_host(hqe.host)
            fields['board'] = labels.get('board', '')
            fields['pool'] = labels.get('pool', '')
        self._hqe_completion_metric.increment(fields=fields)

    def send_hqe_duration(self, hqe):
        """Send CloudTrace metrics for HQE duration."""
        if not (hqe.started_on and hqe.finished_on):
            return
        scheduler_models = autotest.load('scheduler.scheduler_models')
        cloud_trace = autotest.chromite_load('cloud_trace')
        types = autotest.deps_load('google.protobuf.internal.well_known_types')
        hqe_trace_id = scheduler_models.hqe_trace_id

        span = cloud_trace.Span(
                'HQE', spanId='0', traceId=hqe_trace_id(hqe.id))
        span.startTime = types.Timestamp()
        span.startTime.FromDatetime(hqe.started_on)
        span.endTime = types.Timestamp()
        span.endTime.FromDatetime(hqe.finished_on)
        cloud_trace.LogSpan(span)

    def send_reset_after_failure(self, autoserv_exit, failures):
        """Send reset_after_failure metric."""
        self._reset_after_failure_metric.increment(
                fields={'autoserv_process_success': autoserv_exit == 0,
                        # Yes, this is a boolean
                        'num_tests_failed': failures > 0})


def _mark_hqe_aborted(hqe):
    """Perform Autotest operations needed for HQE abortion.

    This also operates on the HQE's host, so prefetch it when possible.

    This logic is from scheduler_models.HostQueueEntry.abort().
    """
    models = autotest.load('frontend.afe.models')
    transaction = autotest.deps_load('django.db.transaction')
    Status = models.HostQueueEntry.Status
    with transaction.commit_on_success():
        if hqe.status in (Status.GATHERING, Status.PARSING):
            return
        if hqe.status in (Status.STARTING, Status.PENDING, Status.RUNNING):
            if hqe.host is None:
                return
            hqe.host.status = models.Host.Status.READY
            hqe.host.save(update_fields=['status'])
        hqe.status = Status.ABORTED
        hqe.save(update_fields=['status'])


def _stop_prejob_hqes(job):
    """Stop pending HQEs for a job (for synch_count)."""
    models = autotest.load('frontend.afe.models')
    HQEStatus = models.HostQueueEntry.Status
    HostStatus = models.Host.Status
    not_yet_run = _get_prejob_hqes(job)
    if not_yet_run.count() == job.synch_count:
        return
    entries_to_stop = _get_prejob_hqes(job, include_active=False)
    for hqe in entries_to_stop:
        if hqe.status == HQEStatus.PENDING:
            hqe.host.status = HostStatus.READY
            hqe.host.save(update_fields=['status'])
        hqe.status = HQEStatus.STOPPED
        hqe.save(update_fields=['status'])


def _get_prejob_hqes(job, include_active=True):
    """Return a queryset of not run HQEs for the job (for synch_count)."""
    models = autotest.load('frontend.afe.models')
    if include_active:
        statuses = list(models.HostQueueEntry.PRE_JOB_STATUSES)
    else:
        statuses = list(models.HostQueueEntry.IDLE_PRE_JOB_STATUSES)
    return models.HostQueueEntry.objects.filter(
            job=job, status__in=statuses)


def _retry_db_errors(func):
    """Call func, retrying multiple times if database errors are raised.

    crbug.com/863504
    """
    django = autotest.deps_load('django')
    MySQLdb = autotest.deps_load('MySQLdb')
    max_retries = 10
    # n ... 0 means n + 1 tries, or 1 try plus n retries
    for i in xrange(max_retries, -1, -1):
        try:
            func()
        except (django.db.utils.DatabaseError, MySQLdb.OperationalError) as e:
            if i == 0:
                raise
            logger.debug('Got database error %s, retrying', e)
            django.db.close_connection()
            time.sleep(5)
        else:
            break
