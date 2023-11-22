# Copyright (c) 2012 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

from datetime import datetime, timedelta
import collections
import json
import logging
import os
import time
import urlparse

from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import dev_server
from autotest_lib.server import autotest, test
from autotest_lib.server.cros.dynamic_suite import tools


def snippet(text):
    """Returns the text with start/end snip markers around it.

    @param text: The snippet text.

    @return The text with start/end snip markers around it.
    """
    snip = '---8<---' * 10
    start = '-- START -'
    end = '-- END -'
    return ('%s%s\n%s\n%s%s' %
            (start, snip[len(start):], text, end, snip[len(end):]))

UPDATE_ENGINE_PERF_PATH = '/mnt/stateful_partition/unencrypted/preserve'
UPDATE_ENGINE_PERF_SCRIPT = 'update_engine_performance_monitor.py'
UPDATE_ENGINE_PERF_RESULTS_FILE = 'perf_data_results.json'

# Update event types.
EVENT_TYPE_DOWNLOAD_COMPLETE = '1'
EVENT_TYPE_INSTALL_COMPLETE = '2'
EVENT_TYPE_UPDATE_COMPLETE = '3'
EVENT_TYPE_DOWNLOAD_STARTED = '13'
EVENT_TYPE_DOWNLOAD_FINISHED = '14'
EVENT_TYPE_REBOOTED_AFTER_UPDATE = '54'

# Update event results.
EVENT_RESULT_ERROR = '0'
EVENT_RESULT_SUCCESS = '1'
EVENT_RESULT_SUCCESS_REBOOT = '2'
EVENT_RESULT_UPDATE_DEFERRED = '9'

# Omaha event types/results, from update_engine/omaha_request_action.h
# These are stored in dict form in order to easily print out the keys.
EVENT_TYPE_DICT = {
        EVENT_TYPE_DOWNLOAD_COMPLETE: 'download_complete',
        EVENT_TYPE_INSTALL_COMPLETE: 'install_complete',
        EVENT_TYPE_UPDATE_COMPLETE: 'update_complete',
        EVENT_TYPE_DOWNLOAD_STARTED: 'download_started',
        EVENT_TYPE_DOWNLOAD_FINISHED: 'download_finished',
        EVENT_TYPE_REBOOTED_AFTER_UPDATE: 'rebooted_after_update'
}

EVENT_RESULT_DICT = {
        EVENT_RESULT_ERROR: 'error',
        EVENT_RESULT_SUCCESS: 'success',
        EVENT_RESULT_SUCCESS_REBOOT: 'success_reboot',
        EVENT_RESULT_UPDATE_DEFERRED: 'update_deferred'
}


class ExpectedUpdateEventChainFailed(error.TestFail):
    """Raised if we fail to receive an expected event in a chain."""


class ExpectedUpdateEvent(object):
    """Defines an expected event in an update process."""

    _ATTR_NAME_DICT_MAP = {
            'event_type': EVENT_TYPE_DICT,
            'event_result': EVENT_RESULT_DICT,
    }

    _VALID_TYPES = set(EVENT_TYPE_DICT.keys())
    _VALID_RESULTS = set(EVENT_RESULT_DICT.keys())

    def __init__(self, event_type=None, event_result=None, version=None,
                 previous_version=None, on_error=None):
        """Initializes an event expectation.

        @param event_type: Expected event type.
        @param event_result: Expected event result code.
        @param version: Expected reported image version.
        @param previous_version: Expected reported previous image version.
        @param on_error: This is either an object to be returned when a received
                         event mismatches the expectation, or a callable used
                         for generating one. In the latter case, takes as
                         input two attribute dictionaries (expected and actual)
                         and an iterable of mismatched keys. If None, a generic
                         message is returned.
        """
        if event_type and event_type not in self._VALID_TYPES:
            raise ValueError('event_type %s is not valid.' % event_type)

        if event_result and event_result not in self._VALID_RESULTS:
            raise ValueError('event_result %s is not valid.' % event_result)

        self._expected_attrs = {
            'event_type': event_type,
            'event_result': event_result,
            'version': version,
            'previous_version': previous_version,
        }
        self._on_error = on_error


    @staticmethod
    def _attr_val_str(attr_val, helper_dict, default=None):
        """Returns an enriched attribute value string, or default."""
        if not attr_val:
            return default

        s = str(attr_val)
        if helper_dict:
            s += ':%s' % helper_dict.get(attr_val, 'unknown')

        return s


    def _attr_name_and_values(self, attr_name, expected_attr_val,
                              actual_attr_val=None):
        """Returns an attribute name, expected and actual value strings.

        This will return (name, expected, actual); the returned value for
        actual will be None if its respective input is None/empty.

        """
        helper_dict = self._ATTR_NAME_DICT_MAP.get(attr_name)
        expected_attr_val_str = self._attr_val_str(expected_attr_val,
                                                   helper_dict,
                                                   default='any')
        actual_attr_val_str = self._attr_val_str(actual_attr_val, helper_dict)

        return attr_name, expected_attr_val_str, actual_attr_val_str


    def _attrs_to_str(self, attrs_dict):
        return ' '.join(['%s=%s' %
                         self._attr_name_and_values(attr_name, attr_val)[0:2]
                         for attr_name, attr_val in attrs_dict.iteritems()])


    def __str__(self):
        return self._attrs_to_str(self._expected_attrs)


    def verify(self, actual_event):
        """Verify the attributes of an actual event.

        @param actual_event: a dictionary containing event attributes

        @return An error message, or None if all attributes as expected.

        """
        mismatched_attrs = [
            attr_name for attr_name, expected_attr_val
            in self._expected_attrs.iteritems()
            if (expected_attr_val and
                not self._verify_attr(attr_name, expected_attr_val,
                                      actual_event.get(attr_name)))]
        if not mismatched_attrs:
            return None
        if callable(self._on_error):
            return self._on_error(self._expected_attrs, actual_event,
                                  mismatched_attrs)
        if self._on_error is None:
            return ('Received event (%s) does not match expectation (%s)' %
                    (self._attrs_to_str(actual_event), self))
        return self._on_error


    def _verify_attr(self, attr_name, expected_attr_val, actual_attr_val):
        """Verifies that an actual log event attributes matches expected on.

        @param attr_name: name of the attribute to verify
        @param expected_attr_val: expected attribute value
        @param actual_attr_val: actual attribute value

        @return True if actual value is present and matches, False otherwise.

        """
        # None values are assumed to be missing and non-matching.
        if actual_attr_val is None:
            logging.error('No value found for %s (expected %s)',
                          *self._attr_name_and_values(attr_name,
                                                      expected_attr_val)[0:2])
            return False

        # We allow expected version numbers (e.g. 2940.0.0) to be contained in
        # actual values (2940.0.0-a1); this is necessary for the test to pass
        # with developer / non-release images.
        if (actual_attr_val == expected_attr_val or
            ('version' in attr_name and expected_attr_val in actual_attr_val)):
            return True

        return False


    def get_attrs(self):
        """Returns a dictionary of expected attributes."""
        return dict(self._expected_attrs)


class ExpectedUpdateEventChain(object):
    """Defines a chain of expected update events."""
    def __init__(self):
        self._expected_events_chain = []
        self._current_timestamp = None


    def add_event(self, expected_events, timeout, on_timeout=None):
        """Adds an expected event to the chain.

        @param expected_events: The ExpectedEvent, or a list thereof, to wait
                                for. If a list is passed, it will wait for *any*
                                of the provided events, but only one of those.
        @param timeout: A timeout (in seconds) to wait for the event.
        @param on_timeout: An error string to use if the event times out. If
                           None, a generic message is used.
        """
        if isinstance(expected_events, ExpectedUpdateEvent):
            expected_events = [expected_events]
        self._expected_events_chain.append(
                (expected_events, timeout, on_timeout))


    @staticmethod
    def _format_event_with_timeout(expected_events, timeout):
        """Returns a string representation of the event, with timeout."""
        until = 'within %s seconds' % timeout if timeout else 'indefinitely'
        return '%s, %s' % (' OR '.join(map(str, expected_events)), until)


    def __str__(self):
        return ('[%s]' %
                ', '.join(
                    [self._format_event_with_timeout(expected_events, timeout)
                     for expected_events, timeout, _
                     in self._expected_events_chain]))


    def __repr__(self):
        return str(self._expected_events_chain)


    def verify(self, get_next_event):
        """Verifies that an actual stream of events complies.

        @param get_next_event: a function returning the next event

        @raises ExpectedUpdateEventChainFailed if we failed to verify an event.

        """
        for expected_events, timeout, on_timeout in self._expected_events_chain:
            logging.info('Expecting %s',
                         self._format_event_with_timeout(expected_events,
                                                         timeout))
            err_msg = self._verify_event_with_timeout(
                    expected_events, timeout, on_timeout, get_next_event)
            if err_msg is not None:
                logging.error('Failed expected event: %s', err_msg)
                raise ExpectedUpdateEventChainFailed(err_msg)


    def _verify_event_with_timeout(self, expected_events, timeout, on_timeout,
                                   get_next_event):
        """Verify an expected event occurs within a given timeout.

        @param expected_events: the list of possible events expected next
        @param timeout: specified in seconds
        @param on_timeout: A string to return if timeout occurs, or None.
        @param get_next_event: function returning the next event in a stream

        @return None if event complies, an error string otherwise.

        """
        new_event = get_next_event()
        if new_event:
            # If this is the first event, set it as the current time
            if self._current_timestamp is None:
                self._current_timestamp = datetime.strptime(new_event[
                                                                'timestamp'],
                                                            '%Y-%m-%d %H:%M:%S')

            # Get the time stamp for the current event and convert to datetime
            timestamp = new_event['timestamp']
            event_timestamp = datetime.strptime(timestamp, '%Y-%m-%d %H:%M:%S')

            # Add the timeout onto the timestamp to get its expiry
            event_timeout = self._current_timestamp + timedelta(seconds=timeout)

            # If the event happened before the timeout
            if event_timestamp < event_timeout:
                difference = event_timestamp - self._current_timestamp
                logging.info('Event took %s seconds to fire during the '
                             'update', difference.seconds)
                results = [event.verify(new_event) for event in expected_events]
                self._current_timestamp = event_timestamp
                return None if None in results else ' AND '.join(results)

        logging.error('Timeout expired')
        if on_timeout is None:
            return ('Waiting for event %s timed out after %d seconds' %
                    (' OR '.join(map(str, expected_events)), timeout))
        return on_timeout


class UpdateEventLogVerifier(object):
    """Verifies update event chains on a devserver update log."""
    def __init__(self, event_log_filename):
        self._event_log_filename = event_log_filename
        self._event_log = []
        self._num_consumed_events = 0


    def verify_expected_events_chain(self, expected_event_chain):
        """Verify a given event chain.

        @param expected_event_chain: instance of expected event chain.

        @raises ExpectedUpdateEventChainFailed if we failed to verify the an
                event.
        """
        expected_event_chain.verify(self._get_next_log_event)


    def _get_next_log_event(self):
        """Returns the next event in an event log.

        Uses the filename handed to it during initialization to read the
        host log from devserver used during the update.

        @return The next new event in the host log, as reported by devserver;
                None if no such event was found or an error occurred.

        """
        # (Re)read event log from hostlog file, if necessary.
        if len(self._event_log) <= self._num_consumed_events:
            try:
                with open(self._event_log_filename, 'r') as out_log:
                  self._event_log = json.loads(out_log.read())
            except Exception as e:
                raise error.TestFail('Error while reading the hostlogs '
                                     'from devserver: %s' % e)

        # Return next new event, if one is found.
        if len(self._event_log) > self._num_consumed_events:
            new_event = {
                    key: str(val) for key, val
                    in self._event_log[self._num_consumed_events].iteritems()
            }
            self._num_consumed_events += 1
            logging.info('Consumed new event: %s', new_event)
            return new_event


class TestPlatform(object):
    """An interface and factory for platform-dependent functionality."""

    # Named tuple containing urls for staged urls needed for test.
    # source_url: url to find the update payload for the source image.
    # source_stateful_url: url to find the stateful payload for the source
    #                      image.
    # target_url: url to find the update payload for the target image.
    # target_stateful_url: url to find the stateful payload for the target
    #                      image.
    StagedURLs = collections.namedtuple(
            'StagedURLs',
            ['source_url', 'source_stateful_url', 'target_url',
             'target_stateful_url'])


    def __init__(self):
        assert False, 'Cannot instantiate this interface'


    @staticmethod
    def create(host):
        """Returns a TestPlatform implementation based on the host type.

        *DO NOT* override this method.

        @param host: a host object representing the DUT

        @return A TestPlatform implementation.
        """
        os_type = host.get_os_type()
        if os_type in ('cros', 'moblab'):
            return ChromiumOSTestPlatform(host)

        raise error.TestError('Unknown OS type reported by host: %s' % os_type)


    def initialize(self, autotest_devserver):
        """Initialize the object.

        @param autotest_devserver: Instance of client.common_lib.dev_server to
                                   use to reach the devserver instance for this
                                   build.
        """
        raise NotImplementedError


    def prep_artifacts(self, test_conf):
        """Prepares update artifacts for the test.

        The test config must include 'source_payload_uri' and
        'target_payload_uri'. In addition, it may include platform-specific
        values as determined by the test control file.

        @param test_conf: Dictionary containing the test configuration.

        @return A tuple of staged URLs.

        @raise error.TestError on failure.
        """
        raise NotImplementedError


    def reboot_device(self):
        """Reboots the device."""
        raise NotImplementedError


    def prep_device_for_update(self, source_payload_uri):
        """Prepares the device for update.

        @param source_payload_uri: Source payload GS URI to install.

        @raise error.TestError on failure.
        """
        raise NotImplementedError


    def get_active_slot(self):
        """Returns the active boot slot of the device."""
        raise NotImplementedError


    def start_update_perf(self, bindir):
        """Starts performance monitoring (if available).

        @param bindir: Directory containing test binary files.
        """
        raise NotImplementedError


    def stop_update_perf(self, resultdir):
        """Stops performance monitoring and returns data (if available).

        @param resultdir: Directory containing test result files.
        @return Dictionary containing performance attributes.
        """
        raise NotImplementedError


    def trigger_update(self, target_payload_uri):
        """Kicks off an update.

        @param target_payload_uri: The GS URI to use for the update.
        """
        raise NotImplementedError


    def finalize_update(self):
        """Performs post-update procedures."""
        raise NotImplementedError


    def get_update_log(self, num_lines):
        """Returns the update log.

        @param num_lines: Number of log lines to return (tail), zero for all.

        @return String containing the last |num_lines| from the update log.
        """
        raise NotImplementedError


    def check_device_after_update(self):
        """Runs final sanity checks.

        @raise error.TestError on failure.
        """
        raise NotImplementedError


    def oobe_triggers_update(self):
        """Returns True if this host has an OOBE flow during which
        it will perform an update check and perhaps an update.
        One example of such a flow is Hands-Off Zero-Touch Enrollment.

        @return Boolean indicating whether the DUT's OOBE triggers an update.
        """
        raise NotImplementedError


    def verify_version(self, version):
        """Compares the OS version on the DUT with the provided version.

        @param version: The version to compare with (string).
        @raise error.TestFail if the versions differ.
        """
        actual_version = self._host.get_release_version()
        if actual_version != version:
            err_msg = 'Failed to verify OS version. Expected %s, was %s' % (
                version, actual_version)
            logging.error(err_msg)
            raise error.TestFail(err_msg)


class ChromiumOSTestPlatform(TestPlatform):
    """A TestPlatform implementation for Chromium OS."""

    _STATEFUL_UPDATE_FILENAME = 'stateful.tgz'

    def __init__(self, host):
        self._host = host
        self._autotest_devserver = None
        self._staged_urls = None
        self._perf_mon_pid = None


    def _stage_payload(self, build_name, filename, archive_url=None):
        """Stage the given payload onto the devserver.

        Works for either a stateful or full/delta test payload. Expects the
        gs_path or a combo of build_name + filename.

        @param build_name: The build name e.g. x86-mario-release/<version>.
                           If set, assumes default gs archive bucket and
                           requires filename to be specified.
        @param filename: In conjunction with build_name, this is the file you
                         are downloading.
        @param archive_url: An optional GS archive location, if not using the
                            devserver's default.

        @return URL of the staged payload on the server.

        @raise error.TestError if there's a problem with staging.

        """
        try:
            self._autotest_devserver.stage_artifacts(image=build_name,
                                                     files=[filename],
                                                     archive_url=archive_url)
            return self._autotest_devserver.get_staged_file_url(filename,
                                                                build_name)
        except dev_server.DevServerException, e:
            raise error.TestError('Failed to stage payload: %s' % e)


    def _stage_payload_by_uri(self, payload_uri):
        """Stage a payload based on its GS URI.

        This infers the build's label, filename and GS archive from the
        provided GS URI.

        @param payload_uri: The full GS URI of the payload.

        @return URL of the staged payload on the server.

        @raise error.TestError if there's a problem with staging.

        """
        archive_url, _, filename = payload_uri.rpartition('/')
        build_name = urlparse.urlsplit(archive_url).path.strip('/')
        return self._stage_payload(build_name, filename,
                                   archive_url=archive_url)


    def _get_stateful_uri(self, build_uri):
        """Returns a complete GS URI of a stateful update given a build path."""
        return '/'.join([build_uri.rstrip('/'), self._STATEFUL_UPDATE_FILENAME])


    def _payload_to_stateful_uri(self, payload_uri):
        """Given a payload GS URI, returns the corresponding stateful URI."""
        build_uri = payload_uri.rpartition('/')[0]
        if build_uri.endswith('payloads'):
            build_uri = build_uri.rpartition('/')[0]
        return self._get_stateful_uri(build_uri)


    @staticmethod
    def _get_update_parameters_from_uri(payload_uri):
        """Extract the two vars needed for cros_au from the Google Storage URI.

        dev_server.auto_update needs two values from this test:
        (1) A build_name string e.g samus-release/R60-9583.0.0
        (2) A filename of the exact payload file to use for the update. This
        payload needs to have already been staged on the devserver.

        This function extracts those two values from a Google Storage URI.

        @param payload_uri: Google Storage URI to extract values from
        """
        archive_url, _, payload_file = payload_uri.rpartition('/')
        build_name = urlparse.urlsplit(archive_url).path.strip('/')

        # This test supports payload uris from two Google Storage buckets.
        # They store their payloads slightly differently. One stores them in
        # a separate payloads directory. E.g
        # gs://chromeos-image-archive/samus-release/R60-9583.0.0/blah.bin
        # gs://chromeos-releases/dev-channel/samus/9334.0.0/payloads/blah.bin
        if build_name.endswith('payloads'):
            build_name = build_name.rpartition('/')[0]
            payload_file = 'payloads/' + payload_file

        logging.debug('Extracted build_name: %s, payload_file: %s from %s.',
                      build_name, payload_file, payload_uri)
        return build_name, payload_file


    def _install_version(self, payload_uri, clobber_stateful=False):
        """Install the specified host with the image given by the url.

        @param payload_uri: GS URI used to compute values for devserver cros_au
        @param clobber_stateful: force a reinstall of the stateful image.
        """

        build_name, payload_file = self._get_update_parameters_from_uri(
            payload_uri)
        logging.info('Installing image %s on the DUT', payload_uri)

        try:
            ds = self._autotest_devserver
            _, pid =  ds.auto_update(host_name=self._host.hostname,
                                     build_name=build_name,
                                     force_update=True,
                                     full_update=True,
                                     log_dir=self._results_dir,
                                     payload_filename=payload_file,
                                     clobber_stateful=clobber_stateful)
        except:
            logging.fatal('ERROR: Failed to install image on the DUT.')
            raise
        return pid


    def _stage_artifacts_onto_devserver(self, test_conf):
        """Stages artifacts that will be used by the test onto the devserver.

        @param test_conf: a dictionary containing test configuration values

        @return a StagedURLs tuple containing the staged urls.
        """
        logging.info('Staging images onto autotest devserver (%s)',
                     self._autotest_devserver.url())

        staged_source_url = None
        source_payload_uri = test_conf['source_payload_uri']

        if source_payload_uri:
            staged_source_url = self._stage_payload_by_uri(source_payload_uri)

            # In order to properly install the source image using a full
            # payload we'll also need the stateful update that comes with it.
            # In general, tests may have their source artifacts in a different
            # location than their payloads. This is determined by whether or
            # not the source_archive_uri attribute is set; if it isn't set,
            # then we derive it from the dirname of the source payload.
            source_archive_uri = test_conf.get('source_archive_uri')
            if source_archive_uri:
                source_stateful_uri = self._get_stateful_uri(source_archive_uri)
            else:
                source_stateful_uri = self._payload_to_stateful_uri(
                        source_payload_uri)

            staged_source_stateful_url = self._stage_payload_by_uri(
                    source_stateful_uri)

            # Log source image URLs.
            logging.info('Source full payload from %s staged at %s',
                         source_payload_uri, staged_source_url)
            if staged_source_stateful_url:
                logging.info('Source stateful update from %s staged at %s',
                             source_stateful_uri, staged_source_stateful_url)

        target_payload_uri = test_conf['target_payload_uri']
        staged_target_url = self._stage_payload_by_uri(target_payload_uri)
        target_stateful_uri = None
        staged_target_stateful_url = None
        target_archive_uri = test_conf.get('target_archive_uri')
        if target_archive_uri:
            target_stateful_uri = self._get_stateful_uri(target_archive_uri)
        else:
            target_stateful_uri = self._payload_to_stateful_uri(
                    target_payload_uri)

        if not staged_target_stateful_url and target_stateful_uri:
            staged_target_stateful_url = self._stage_payload_by_uri(
                    target_stateful_uri)

        # Log target payload URLs.
        logging.info('%s test payload from %s staged at %s',
                     test_conf['update_type'], target_payload_uri,
                     staged_target_url)
        logging.info('Target stateful update from %s staged at %s',
                     target_stateful_uri, staged_target_stateful_url)

        return self.StagedURLs(staged_source_url, staged_source_stateful_url,
                               staged_target_url, staged_target_stateful_url)


    def _run_login_test(self, tag):
        """Runs login_LoginSuccess test on the DUT."""
        client_at = autotest.Autotest(self._host)
        client_at.run_test('login_LoginSuccess', tag=tag)


    # Interface overrides.
    #
    def initialize(self, autotest_devserver, results_dir):
        self._autotest_devserver = autotest_devserver
        self._results_dir = results_dir


    def reboot_device(self):
        self._host.reboot()


    def prep_artifacts(self, test_conf):
        self._staged_urls = self._stage_artifacts_onto_devserver(test_conf)
        return self._staged_urls


    def prep_device_for_update(self, source_payload_uri):
        # Install the source version onto the DUT.
        if self._staged_urls.source_url:
            self._install_version(source_payload_uri, clobber_stateful=True)

        # Make sure we can login before the target update.
        self._run_login_test('source_update')


    def get_active_slot(self):
        return self._host.run('rootdev -s').stdout.strip()


    def start_update_perf(self, bindir):
        """Copy performance monitoring script to DUT.

        The updater will kick off the script during the update.
        """
        path = os.path.join(bindir, UPDATE_ENGINE_PERF_SCRIPT)
        self._host.send_file(path, UPDATE_ENGINE_PERF_PATH)


    def stop_update_perf(self, resultdir):
        """ Copy the performance metrics back from the DUT."""
        try:
            path = os.path.join('/var/log', UPDATE_ENGINE_PERF_RESULTS_FILE)
            self._host.get_file(path, resultdir)
            self._host.run('rm %s' % path)
            script = os.path.join(UPDATE_ENGINE_PERF_PATH,
                                  UPDATE_ENGINE_PERF_SCRIPT)
            self._host.run('rm %s' % script)
            return os.path.join(resultdir, UPDATE_ENGINE_PERF_RESULTS_FILE)
        except:
            logging.debug('Failed to copy performance metrics from DUT.')
            return None


    def trigger_update(self, target_payload_uri):
        logging.info('Updating device to target image.')
        return self._install_version(target_payload_uri)

    def finalize_update(self):
        # Stateful update is controlled by cros_au
        pass


    def get_update_log(self, num_lines):
        return self._host.run_output(
                'tail -n %d /var/log/update_engine.log' % num_lines,
                stdout_tee=None)


    def check_device_after_update(self):
        # Make sure we can login after update.
        self._run_login_test('target_update')


    def oobe_triggers_update(self):
        return self._host.oobe_triggers_update()


class autoupdate_EndToEndTest(test.test):
    """Complete update test between two Chrome OS releases.

    Performs an end-to-end test of updating a ChromeOS device from one version
    to another. The test performs the following steps:

      1. Stages the source (full) and target update payload on the central
         devserver.
      2. Installs a source image on the DUT (if provided) and reboots to it.
      3. Then starts the target update by calling cros_au RPC on the devserver.
      4. This copies the devserver code and all payloads to the DUT.
      5. Starts a devserver on the DUT.
      6. Starts an update pointing to this local devserver.
      7. Watches as the DUT applies the update to rootfs and stateful.
      8. Reboots and repeats steps 5-6, ensuring that the next update check
         shows the new image version.
      9. Returns the hostlogs collected during each update check for
         verification against expected update events.

    Some notes on naming:
      devserver: Refers to a machine running the Chrome OS Update Devserver.
      autotest_devserver: An autotest wrapper to interact with a devserver.
                          Can be used to stage artifacts to a devserver. While
                          this can also be used to update a machine, we do not
                          use it for that purpose in this test as we manage
                          updates with out own devserver instances (see below).
      *staged_url's: In this case staged refers to the fact that these items
                     are available to be downloaded statically from these urls
                     e.g. 'localhost:8080/static/my_file.gz'. These are usually
                     given after staging an artifact using a autotest_devserver
                     though they can be re-created given enough assumptions.
    """
    version = 1

    # Timeout periods, given in seconds.
    _WAIT_FOR_INITIAL_UPDATE_CHECK_SECONDS = 12 * 60
    # TODO(sosa): Investigate why this needs to be so long (this used to be
    # 120 and regressed).
    _WAIT_FOR_DOWNLOAD_STARTED_SECONDS = 4 * 60
    # See https://crbug.com/731214 before changing WAIT_FOR_DOWNLOAD
    _WAIT_FOR_DOWNLOAD_COMPLETED_SECONDS = 20 * 60
    _WAIT_FOR_UPDATE_COMPLETED_SECONDS = 4 * 60
    _WAIT_FOR_UPDATE_CHECK_AFTER_REBOOT_SECONDS = 15 * 60

    # Logs and their whereabouts.
    _WHERE_UPDATE_LOG = ('update_engine log (in sysinfo or on the DUT, also '
                         'included in the test log)')
    _WHERE_OMAHA_LOG = 'Omaha-devserver log (included in the test log)'


    def initialize(self):
        """Sets up variables that will be used by test."""
        self._host = None
        self._omaha_devserver = None
        self._source_image_installed = False


    def cleanup(self):
        """Kill the omaha devserver if it's still around."""
        if self._omaha_devserver:
            self._omaha_devserver.stop_devserver()

        self._omaha_devserver = None


    def _get_hostlog_file(self, filename, pid):
        """Return the hostlog file location.

        @param filename: The partial filename to look for.
        @param pid: The pid of the update.

        """
        hosts = [self._host.hostname, self._host.ip]
        for host in hosts:
            hostlog = '%s_%s_%s' % (filename, host, pid)
            file_url = os.path.join(self.job.resultdir,
                                    dev_server.AUTO_UPDATE_LOG_DIR,
                                    hostlog)
            if os.path.exists(file_url):
                return file_url
        raise error.TestFail('Could not find %s for pid %s' % (filename, pid))


    def _dump_update_engine_log(self, test_platform):
        """Dumps relevant AU error log."""
        try:
            error_log = test_platform.get_update_log(80)
            logging.error('Dumping snippet of update_engine log:\n%s',
                          snippet(error_log))
        except Exception:
            # Mute any exceptions we get printing debug logs.
            pass


    def _report_perf_data(self, perf_file):
        """Reports performance and resource data.

        Currently, performance attributes are expected to include 'rss_peak'
        (peak memory usage in bytes).

        @param perf_file: A file with performance metrics.
        """
        logging.debug('Reading perf results from %s.' % perf_file)
        try:
            with open(perf_file, 'r') as perf_file_handle:
                perf_data = json.loads(perf_file_handle.read())
        except Exception as e:
            logging.warning('Error while reading the perf data file: %s' % e)
            return

        rss_peak = perf_data.get('rss_peak')
        if rss_peak:
            rss_peak_kib = rss_peak / 1024
            logging.info('Peak memory (RSS) usage on DUT: %d KiB', rss_peak_kib)
            self.output_perf_value(description='mem_usage_peak',
                                   value=int(rss_peak_kib),
                                   units='KiB',
                                   higher_is_better=False)
        else:
            logging.warning('No rss_peak key in JSON returned by %s',
                            UPDATE_ENGINE_PERF_SCRIPT)


    def _error_initial_check(self, expected, actual, mismatched_attrs):
        if 'version' in mismatched_attrs:
            err_msg = ('Initial update check was received but the reported '
                       'version is different from what was expected.')
            if self._source_image_installed:
                err_msg += (' The source payload we installed was probably '
                            'incorrect or corrupt.')
            else:
                err_msg += (' The DUT is probably not running the correct '
                            'source image.')
            return err_msg

        return 'A test bug occurred; inspect the test log.'


    def _error_intermediate(self, expected, actual, mismatched_attrs, action,
                            problem):
        if 'event_result' in mismatched_attrs:
            event_result = actual.get('event_result')
            reported = (('different than expected (%s)' %
                         EVENT_RESULT_DICT[event_result])
                        if event_result else 'missing')
            return ('The updater reported result code is %s. This could be an '
                    'updater bug or a connectivity problem; check the %s. For '
                    'a detailed log of update events, check the %s.' %
                    (reported, self._WHERE_UPDATE_LOG, self._WHERE_OMAHA_LOG))
        if 'event_type' in mismatched_attrs:
            event_type = actual.get('event_type')
            reported = ('different (%s)' % EVENT_TYPE_DICT[event_type]
                        if event_type else 'missing')
            return ('Expected the updater to %s (%s) but received event type '
                    'is %s. This could be an updater %s; check the '
                    '%s. For a detailed log of update events, check the %s.' %
                    (action, EVENT_TYPE_DICT[expected['event_type']], reported,
                     problem, self._WHERE_UPDATE_LOG, self._WHERE_OMAHA_LOG))
        if 'version' in mismatched_attrs:
            return ('The updater reported an unexpected version despite '
                    'previously reporting the correct one. This is most likely '
                    'a bug in update engine; check the %s.' %
                    self._WHERE_UPDATE_LOG)

        return 'A test bug occurred; inspect the test log.'


    def _error_download_started(self, expected, actual, mismatched_attrs):
        return self._error_intermediate(expected, actual, mismatched_attrs,
                                        'begin downloading',
                                        'bug, crash or provisioning error')


    def _error_download_finished(self, expected, actual, mismatched_attrs):
        return self._error_intermediate(expected, actual, mismatched_attrs,
                                        'finish downloading', 'bug or crash')


    def _error_update_complete(self, expected, actual, mismatched_attrs):
        return self._error_intermediate(expected, actual, mismatched_attrs,
                                        'complete the update', 'bug or crash')


    def _error_reboot_after_update(self, expected, actual, mismatched_attrs):
        if 'event_result' in mismatched_attrs:
            event_result = actual.get('event_result')
            reported = ('different (%s)' % EVENT_RESULT_DICT[event_result]
                        if event_result else 'missing')
            return ('The updater was expected to reboot (%s) but reported '
                    'result code is %s. This could be a failure to reboot, an '
                    'updater bug or a connectivity problem; check the %s and '
                    'the system log. For a detailed log of update events, '
                    'check the %s.' %
                    (EVENT_RESULT_DICT[expected['event_result']], reported,
                     self._WHERE_UPDATE_LOG, self._WHERE_OMAHA_LOG))
        if 'event_type' in mismatched_attrs:
            event_type = actual.get('event_type')
            reported = ('different (%s)' % EVENT_TYPE_DICT[event_type]
                        if event_type else 'missing')
            return ('Expected to successfully reboot into the new image (%s) '
                    'but received event type is %s. This probably means that '
                    'the new image failed to verify after reboot, possibly '
                    'because the payload is corrupt. This might also be an '
                    'updater bug or crash; check the %s. For a detailed log of '
                    'update events, check the %s.' %
                    (EVENT_TYPE_DICT[expected['event_type']], reported,
                     self._WHERE_UPDATE_LOG, self._WHERE_OMAHA_LOG))
        if 'version' in mismatched_attrs:
            return ('The DUT rebooted after the update but reports a different '
                    'image version than the one expected. This probably means '
                    'that the payload we applied was incorrect or corrupt.')
        if 'previous_version' in mismatched_attrs:
            return ('The DUT rebooted after the update and reports the '
                    'expected version. However, it reports a previous version '
                    'that is different from the one previously reported. This '
                    'is most likely a bug in update engine; check the %s.' %
                    self._WHERE_UPDATE_LOG)

        return 'A test bug occurred; inspect the test log.'


    def _timeout_err(self, desc, timeout, event_type=None):
        if event_type is not None:
            desc += ' (%s)' % EVENT_TYPE_DICT[event_type]
        return ('Failed to receive %s within %d seconds. This could be a '
                'problem with the updater or a connectivity issue. For more '
                'details, check the %s.' %
                (desc, timeout, self._WHERE_UPDATE_LOG))


    def run_update_test(self, test_platform, test_conf):
        """Runs the actual update test once preconditions are met.

        @param test_platform: TestPlatform implementation.
        @param test_conf: A dictionary containing test configuration values

        @raises ExpectedUpdateEventChainFailed if we failed to verify an update
                event.
        """

        # Record the active root partition.
        source_active_slot = test_platform.get_active_slot()
        logging.info('Source active slot: %s', source_active_slot)

        source_release = test_conf['source_release']
        target_release = test_conf['target_release']

        test_platform.start_update_perf(self.bindir)
        try:
            # Update the DUT to the target image.
            pid = test_platform.trigger_update(test_conf['target_payload_uri'])

            # Verify the host log that was returned from the update.
            file_url = self._get_hostlog_file('devserver_hostlog_rootfs', pid)

            logging.info('Checking update steps with devserver hostlog file: '
                         '%s' % file_url)
            log_verifier = UpdateEventLogVerifier(file_url)

            # Verify chain of events in a successful update process.
            chain = ExpectedUpdateEventChain()
            chain.add_event(
                    ExpectedUpdateEvent(
                        version=source_release,
                        on_error=self._error_initial_check),
                    self._WAIT_FOR_INITIAL_UPDATE_CHECK_SECONDS,
                    on_timeout=self._timeout_err(
                            'an initial update check',
                            self._WAIT_FOR_INITIAL_UPDATE_CHECK_SECONDS))
            chain.add_event(
                    ExpectedUpdateEvent(
                        event_type=EVENT_TYPE_DOWNLOAD_STARTED,
                        event_result=EVENT_RESULT_SUCCESS,
                        version=source_release,
                        on_error=self._error_download_started),
                    self._WAIT_FOR_DOWNLOAD_STARTED_SECONDS,
                    on_timeout=self._timeout_err(
                            'a download started notification',
                            self._WAIT_FOR_DOWNLOAD_STARTED_SECONDS,
                            event_type=EVENT_TYPE_DOWNLOAD_STARTED))
            chain.add_event(
                    ExpectedUpdateEvent(
                        event_type=EVENT_TYPE_DOWNLOAD_FINISHED,
                        event_result=EVENT_RESULT_SUCCESS,
                        version=source_release,
                        on_error=self._error_download_finished),
                    self._WAIT_FOR_DOWNLOAD_COMPLETED_SECONDS,
                    on_timeout=self._timeout_err(
                            'a download finished notification',
                            self._WAIT_FOR_DOWNLOAD_COMPLETED_SECONDS,
                            event_type=EVENT_TYPE_DOWNLOAD_FINISHED))
            chain.add_event(
                    ExpectedUpdateEvent(
                        event_type=EVENT_TYPE_UPDATE_COMPLETE,
                        event_result=EVENT_RESULT_SUCCESS,
                        version=source_release,
                        on_error=self._error_update_complete),
                    self._WAIT_FOR_UPDATE_COMPLETED_SECONDS,
                    on_timeout=self._timeout_err(
                            'an update complete notification',
                            self._WAIT_FOR_UPDATE_COMPLETED_SECONDS,
                            event_type=EVENT_TYPE_UPDATE_COMPLETE))

            log_verifier.verify_expected_events_chain(chain)

        except:
            logging.fatal('ERROR: Failure occurred during the target update.')
            raise

        perf_file = test_platform.stop_update_perf(self.job.resultdir)
        if perf_file is not None:
            self._report_perf_data(perf_file)

        if test_platform.oobe_triggers_update():
            # If DUT automatically checks for update during OOBE,
            # checking the post-update CrOS version and slot is sufficient.
            # This command checks the OS version.
            # The slot is checked a little later, after the else block.
            logging.info('Skipping post reboot update check.')
            test_platform.verify_version(target_release)
        else:
            # Observe post-reboot update check, which should indicate that the
            # image version has been updated.
            # Verify the host log that was returned from the update.
            file_url = self._get_hostlog_file('devserver_hostlog_reboot', pid)

            logging.info('Checking post-reboot devserver hostlogs: %s' %
                         file_url)
            log_verifier = UpdateEventLogVerifier(file_url)

            chain = ExpectedUpdateEventChain()
            expected_events = [
                ExpectedUpdateEvent(
                    event_type=EVENT_TYPE_UPDATE_COMPLETE,
                    event_result=EVENT_RESULT_SUCCESS_REBOOT,
                    version=target_release,
                    previous_version=source_release,
                    on_error=self._error_reboot_after_update),
                # Newer versions send a "rebooted_after_update" message
                # after reboot with the previous version instead of another
                # "update_complete".
                ExpectedUpdateEvent(
                    event_type=EVENT_TYPE_REBOOTED_AFTER_UPDATE,
                    event_result=EVENT_RESULT_SUCCESS,
                    version=target_release,
                    previous_version=source_release,
                    on_error=self._error_reboot_after_update),
            ]
            chain.add_event(
                    expected_events,
                    self._WAIT_FOR_UPDATE_CHECK_AFTER_REBOOT_SECONDS,
                    on_timeout=self._timeout_err(
                            'a successful reboot notification',
                            self._WAIT_FOR_UPDATE_CHECK_AFTER_REBOOT_SECONDS,
                            event_type=EVENT_TYPE_UPDATE_COMPLETE))

            log_verifier.verify_expected_events_chain(chain)

        # Make sure we're using a different slot after the update.
        target_active_slot = test_platform.get_active_slot()
        if target_active_slot == source_active_slot:
            err_msg = 'The active image slot did not change after the update.'
            if None in (source_release, target_release):
                err_msg += (' The DUT likely rebooted into the old image, which '
                            'probably means that the payload we applied was '
                            'corrupt. But since we did not check the source '
                            'and/or target version we cannot say for sure.')
            elif source_release == target_release:
                err_msg += (' Given that the source and target versions are '
                            'identical, the DUT likely rebooted into the old '
                            'image. This probably means that the payload we '
                            'applied was corrupt.')
            else:
                err_msg += (' This is strange since the DUT reported the '
                            'correct target version. This is probably a system '
                            'bug; check the DUT system log.')
            raise error.TestFail(err_msg)

        logging.info('Target active slot changed as expected: %s',
                     target_active_slot)

        logging.info('Update successful, test completed')


    def run_once(self, host, test_conf):
        """Performs a complete auto update test.

        @param host: a host object representing the DUT
        @param test_conf: a dictionary containing test configuration values

        @raise error.TestError if anything went wrong with setting up the test;
               error.TestFail if any part of the test has failed.
        """
        self._host = host
        logging.debug('The test configuration supplied: %s', test_conf)

        # Find a devserver to use. We first try to pick a devserver with the
        # least load. In case all devservers' load are higher than threshold,
        # fall back to the old behavior by picking a devserver based on the
        # payload URI, with which ImageServer.resolve will return a random
        # devserver based on the hash of the URI.
        # The picked devserver needs to respect the location of the host if
        # 'prefer_local_devserver' is set to True or 'restricted_subnets' is
        # set.
        hostname = self._host.hostname if self._host else None
        least_loaded_devserver = dev_server.get_least_loaded_devserver(
                hostname=hostname)
        if least_loaded_devserver:
            logging.debug('Choosing the least loaded devserver: %s',
                          least_loaded_devserver)
            autotest_devserver = dev_server.ImageServer(least_loaded_devserver)
        else:
            logging.warning('No devserver meets the maximum load requirement. '
                            'Picking a random devserver to use.')
            autotest_devserver = dev_server.ImageServer.resolve(
                    test_conf['target_payload_uri'], host.hostname)
        devserver_hostname = urlparse.urlparse(
                autotest_devserver.url()).hostname

        logging.info('Devserver chosen for this run: %s', devserver_hostname)

        # Obtain a test platform implementation.
        test_platform = TestPlatform.create(host)
        test_platform.initialize(autotest_devserver, self.job.resultdir)

        # Stage source images and update payloads onto the devserver.
        staged_urls = test_platform.prep_artifacts(test_conf)
        self._source_image_installed = bool(staged_urls.source_url)

        # Prepare the DUT (install source version etc).
        test_platform.prep_device_for_update(test_conf['source_payload_uri'])

        # Start the update.
        try:
            self.run_update_test(test_platform, test_conf)
        except ExpectedUpdateEventChainFailed:
            self._dump_update_engine_log(test_platform)
            raise

        test_platform.check_device_after_update()
