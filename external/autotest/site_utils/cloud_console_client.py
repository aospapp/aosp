# Copyright 2016 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Chrome OS Parnter Concole remote actions."""

from __future__ import print_function

import base64
import logging

import common

from autotest_lib.client.common_lib import global_config
from autotest_lib.client.common_lib import utils
from autotest_lib.server.hosts import moblab_host
from autotest_lib.site_utils import pubsub_utils


_PUBSUB_TOPIC = global_config.global_config.get_config_value(
        'CROS', 'cloud_notification_topic', default=None)

# Test upload pubsub notification attributes
NOTIFICATION_ATTR_VERSION = 'version'
NOTIFICATION_ATTR_GCS_URI = 'gcs_uri'
NOTIFICATION_ATTR_MOBLAB_MAC = 'moblab_mac_address'
NOTIFICATION_ATTR_MOBLAB_ID = 'moblab_id'
NOTIFICATION_VERSION = '1'
# the message data for new test result notification.
NEW_TEST_RESULT_MESSAGE = 'NEW_TEST_RESULT'


ALERT_CRITICAL = 'Critical'
ALERT_MAJOR = 'Major'
ALERT_MINOR = 'Minor'

LOG_INFO = 'Info'
LOG_WARNING = 'Warning'
LOG_SEVERE = 'Severe'
LOG_FATAL = 'Fatal'


def is_cloud_notification_enabled():
    """Checks if cloud pubsub notification is enabled.

    @returns: True if cloud pubsub notification is enabled. Otherwise, False.
    """
    return  global_config.global_config.get_config_value(
        'CROS', 'cloud_notification_enabled', type=bool, default=False)


class CloudConsoleClient(object):
    """The remote interface to the Cloud Console."""
    def send_heartbeat(self):
        """Sends a heartbeat.

        @returns True if the notification is successfully sent.
            Otherwise, False.
        """
        pass

    def send_event(self, event_type=None, event_data=None):
        """Sends an event notification to the remote console.

        @param event_type: The event type that is defined in the protobuffer
            file 'cloud_console.proto'.
        @param event_data: The event data.

        @returns True if the notification is successfully sent.
            Otherwise, False.
        """
        pass

    def send_log(self, msg, level=LOG_INFO, session_id=None):
        """Sends a log message to the remote console.

        @param msg: The log message.
        @param level: The logging level as string.
        @param session_id: The current session id.

        @returns True if the notification is successfully sent.
            Otherwise, False.
        """
        pass

    def send_alert(self, msg, level=ALERT_MINOR, session_id=None):
        """Sends an alert to the remote console.

        @param msg: The alert message.
        @param level: The logging level as string.
        @param session_id: The current session id.

        @returns True if the notification is successfully sent.
            Otherwise, False.
        """
        pass

    def send_test_job_offloaded_message(self, gcs_uri):
        """Sends a test job offloaded message to the remote console.

        @param gcs_uri: The test result Google Cloud Storage URI.

        @returns True if the notification is successfully sent.
            Otherwise, False.
        """
        pass


# Make it easy to mock out
def _create_pubsub_client(credential):
    return pubsub_utils.PubSubClient(credential)


class PubSubBasedClient(CloudConsoleClient):
    """A Cloud PubSub based implementation of the CloudConsoleClient interface.
    """
    def __init__(
            self,
            credential=moblab_host.MOBLAB_SERVICE_ACCOUNT_LOCATION,
            pubsub_topic=_PUBSUB_TOPIC):
        """Constructor.

        @param credential: The service account credential filename. Default to
            '/home/moblab/.service_account.json'.
        @param pubsub_topic: The cloud pubsub topic name to use.
        """
        super(PubSubBasedClient, self).__init__()
        self._pubsub_client = _create_pubsub_client(credential)
        self._pubsub_topic = pubsub_topic


    def _create_notification_message(self, data, msg_attributes):
        """Creates a cloud pubsub notification object.

        @param data: The message data as a string.
        @param msg_attributes: The message attribute map.

        @returns: A pubsub message object with data and attributes.
        """
        message = {'data': data}
        message['attributes'] = msg_attributes
        return message

    def _create_notification_attributes(self):
        """Creates a cloud pubsub notification message attribute map.

        Fills in the version, moblab mac address, and moblab id information
        as attributes.

        @returns: A pubsub messsage attribute map.
        """
        msg_attributes = {}
        msg_attributes[NOTIFICATION_ATTR_VERSION] = NOTIFICATION_VERSION
        msg_attributes[NOTIFICATION_ATTR_MOBLAB_MAC] = (
                utils.get_default_interface_mac_address())
        msg_attributes[NOTIFICATION_ATTR_MOBLAB_ID] = utils.get_moblab_id()
        return msg_attributes

    def _create_test_result_notification(self, gcs_uri):
        """Construct a test result notification.

        @param gcs_uri: The test result Google Cloud Storage URI.

        @returns The notification message.
        """
        data = base64.b64encode(NEW_TEST_RESULT_MESSAGE)
        msg_attributes = self._create_notification_attributes()
        msg_attributes[NOTIFICATION_ATTR_GCS_URI] = gcs_uri

        return self._create_notification_message(data, msg_attributes)


    def send_test_job_offloaded_message(self, gcs_uri):
        """Notify the cloud console a test job is offloaded.

        @param gcs_uri: The test result Google Cloud Storage URI.

        @returns True if the notification is successfully sent.
            Otherwise, False.
        """
        logging.info('Notification on gcs_uri %s', gcs_uri)
        message = self._create_test_result_notification(gcs_uri)
        msg_ids = self._pubsub_client.publish_notifications(
                self._pubsub_topic, [message])
        if msg_ids:
            return True
        logging.warning('Failed to send notification on gcs_uri %s', gcs_uri)
        return False

