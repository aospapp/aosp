#!/usr/bin/python3
# Copyright 2015 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""
Mail the content of standard input.

Example usage:
  Use pipe:
     $ echo "Some content" |./gmail_lib.py -s "subject" abc@bb.com xyz@gmail.com

  Manually input:
     $ ./gmail_lib.py -s "subject" abc@bb.com xyz@gmail.com
     > Line 1
     > Line 2
     Ctrl-D to end standard input.
"""
from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

import argparse
import base64
import httplib2
import logging
import sys
import random
from email.mime.text import MIMEText

import common
from autotest_lib.server import site_utils

try:
    from apiclient.discovery import build as apiclient_build
    from apiclient import errors as apiclient_errors
    from oauth2client import file as oauth_client_fileio
except ImportError as e:
    apiclient_build = None
    logging.debug("API client for gmail disabled. %s", e)


RETRY_DELAY = 5
RETRY_BACKOFF_FACTOR = 1.5
MAX_RETRY = 10
RETRIABLE_MSGS = [
        # User-rate limit exceeded
        r'HttpError 429',]

class GmailApiException(Exception):
    """Exception raised in accessing Gmail API."""


class Message():
    """An email message."""

    def __init__(self, to, subject, message_text):
        """Initialize a message.

        @param to: The recievers saperated by comma.
                   e.g. 'abc@gmail.com,xyz@gmail.com'
        @param subject: String, subject of the message
        @param message_text: String, content of the message.
        """
        self.to = to
        self.subject = subject
        self.message_text = message_text


    def get_payload(self):
        """Get the payload that can be sent to the Gmail API.

        @return: A dictionary representing the message.
        """
        message = MIMEText(self.message_text)
        message['to'] = self.to
        message['subject'] = self.subject
        return {'raw': base64.urlsafe_b64encode(message.as_string())}


class GmailApiClient():
    """Client that talks to Gmail API."""

    def __init__(self, oauth_credentials):
        """Init Gmail API client

        @param oauth_credentials: Path to the oauth credential token.
        """
        if not apiclient_build:
            raise GmailApiException('Cannot get apiclient library.')

        storage = oauth_client_fileio.Storage(oauth_credentials)
        credentials = storage.get()
        if not credentials or credentials.invalid:
            raise GmailApiException('Invalid credentials for Gmail API, '
                                    'could not send email.')
        http = credentials.authorize(httplib2.Http())
        self._service = apiclient_build('gmail', 'v1', http=http)


    def send_message(self, message, ignore_error=True):
        """Send an email message.

        @param message: Message to be sent.
        @param ignore_error: If True, will ignore any HttpError.
        """
        try:
            # 'me' represents the default authorized user.
            message = self._service.users().messages().send(
                    userId='me', body=message.get_payload()).execute()
            logging.debug('Email sent: %s' , message['id'])
        except apiclient_errors.HttpError as error:
            if ignore_error:
                logging.error('Failed to send email: %s', error)
            else:
                raise


def send_email(to, subject, message_text, retry=True, creds_path=None):
    """Send email.

    @param to: The recipients, separated by comma.
    @param subject: Subject of the email.
    @param message_text: Text to send.
    @param retry: If retry on retriable failures as defined in RETRIABLE_MSGS.
    @param creds_path: The credential path for gmail account, if None,
                       will use DEFAULT_CREDS_FILE.
    """
    # TODO(ayatane): Deprecated, not untangling imports now
    pass


if __name__ == '__main__':
    logging.basicConfig(level=logging.DEBUG)
    parser = argparse.ArgumentParser(
            description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    parser.add_argument('-s', '--subject', type=str, dest='subject',
                        required=True, help='Subject of the mail')
    parser.add_argument('-p', type=float, dest='probability',
                        required=False, default=0,
                        help='(optional) per-addressee probability '
                             'with which to send email. If not specified '
                             'all addressees will receive message.')
    parser.add_argument('recipients', nargs='*',
                        help='Email addresses separated by space.')
    args = parser.parse_args()
    if not args.recipients or not args.subject:
        print('Requires both recipients and subject.')
        sys.exit(1)

    message_text = sys.stdin.read()

    if args.probability:
        recipients = []
        for r in args.recipients:
            if random.random() < args.probability:
                recipients.append(r)
        if recipients:
            print('Randomly selected recipients %s' % recipients)
        else:
            print('Random filtering removed all recipients. Sending nothing.')
            sys.exit(0)
    else:
        recipients = args.recipients


    with site_utils.SetupTsMonGlobalState('gmail_lib', short_lived=True):
        send_email(','.join(recipients), args.subject , message_text)
