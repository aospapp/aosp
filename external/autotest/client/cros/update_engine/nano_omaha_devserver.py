# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import BaseHTTPServer
import base64
import binascii
import thread
import urlparse

from string import Template
from xml.dom import minidom

def _split_url(url):
    """Splits a URL into the URL base and path."""
    split_url = urlparse.urlsplit(url)
    url_base = urlparse.urlunsplit(
            (split_url.scheme, split_url.netloc, '', '', ''))
    url_path = split_url.path
    return url_base, url_path.lstrip('/')


class NanoOmahaDevserver(object):
    """A simple Omaha instance that can be setup on a DUT in client tests."""

    def __init__(self, eol=False, failures_per_url=1, backoff=False,
                 num_urls=2):
        """
        Create a nano omaha devserver.

        @param eol: True if we should return a response with _eol flag.
        @param failures_per_url: how many times each url can fail.
        @param backoff: Whether we should wait a while before trying to
                        update again after a failure.
        @param num_urls: The number of URLs in the omaha response.

        """
        self._eol = eol
        self._failures_per_url = failures_per_url
        self._backoff = backoff
        self._num_urls = num_urls


    def create_update_response(self, appid):
        """
        Create an update response using the values from set_image_params().

        @param appid: the appid parsed from the request.

        @returns: a string of the response this server should send.

        """
        EOL_TEMPLATE = Template("""
          <response protocol="3.0">
            <daystart elapsed_seconds="44801"/>
            <app appid="$appid" status="ok">
              <ping status="ok"/>
              <updatecheck _eol="eol" status="noupdate"/>
            </app>
          </response>
        """)

        RESPONSE_TEMPLATE = Template("""
          <response protocol="3.0">
            <daystart elapsed_seconds="44801"/>
              <app appid="$appid" status="ok">
              <ping status="ok"/>
                <updatecheck ${ROLLBACK_FLAGS}status="ok">
                <urls>
                  $PER_URL_TAGS
                </urls>
                <manifest version="$build_number">
                  <packages>
                    <package hash_sha256="$sha256" name="$image_name"
                    size="$image_size" required="true"/>
                  </packages>
                  <actions>
                    <action event="postinstall"
                    ChromeOSVersion="$build_number"
                    sha256="$sha256"
                    needsadmin="false"
                    IsDeltaPayload="$is_delta"
                    MaxFailureCountPerUrl="$failures_per_url"
                    DisablePayloadBackoff="$disable_backoff"
                    $OPTIONAL_ACTION_FLAGS
                    />
                  </actions>
                </manifest>
              </updatecheck>
            </app>
          </response>
        """)
        PER_URL_TEMPLATE = Template('<url codebase="$base/"/>')
        FLAG_TEMPLATE = Template('$key="$value"')
        ROLLBACK_TEMPLATE = Template("""
                _firmware_version="$fw"
                _firmware_version_0="$fw0"
                _firmware_version_1="$fw1"
                _firmware_version_2="$fw2"
                _firmware_version_3="$fw3"
                _firmware_version_4="$fw4"
                _kernel_version="$kern"
                _kernel_version_0="$kern0"
                _kernel_version_1="$kern1"
                _kernel_version_2="$kern2"
                _kernel_version_3="$kern3"
                _kernel_version_4="$kern4"
                _rollback="$is_rollback"
                """)

        # IF EOL, return a simplified response with _eol tag.
        if self._eol:
            return EOL_TEMPLATE.substitute(appid=appid)

        template_keys = {}
        template_keys['is_delta'] = str(self._is_delta).lower()
        template_keys['build_number'] = self._build
        template_keys['sha256'] = (
            binascii.hexlify(base64.b64decode(self._sha256)))
        template_keys['image_size'] = self._image_size
        template_keys['failures_per_url'] = self._failures_per_url
        template_keys['disable_backoff'] = str(not self._backoff).lower()
        template_keys['num_urls'] = self._num_urls
        template_keys['appid'] = appid

        (base, name) = _split_url(self._image_url)
        template_keys['base'] = base
        template_keys['image_name'] = name

        # For now, set all version flags to the same value.
        if self._is_rollback:
            fw_val = '5'
            k_val = '7'
            rollback_flags = ROLLBACK_TEMPLATE.substitute(
                fw=fw_val, fw0=fw_val, fw1=fw_val, fw2=fw_val, fw3=fw_val,
                fw4=fw_val, kern=k_val, kern0=k_val, kern1=k_val, kern2=k_val,
                kern3=k_val, kern4=k_val, is_rollback='true')
        else:
            rollback_flags = ''
        template_keys['ROLLBACK_FLAGS'] = rollback_flags

        per_url = ''
        for i in xrange(self._num_urls):
            per_url += PER_URL_TEMPLATE.substitute(template_keys)
        template_keys['PER_URL_TAGS'] = per_url

        action_flags = []
        def add_action_flag(key, value):
            """Helper function for the OPTIONAL_ACTION_FLAGS parameter."""
            action_flags.append(
                    FLAG_TEMPLATE.substitute(key=key, value=value))
        if self._critical:
            add_action_flag('deadline', 'now')
        if self._metadata_size:
            add_action_flag('MetadataSize', self._metadata_size)
        if self._metadata_signature:
            add_action_flag('MetadataSignatureRsa', self._metadata_signature)
        if self._public_key:
            add_action_flag('PublicKeyRsa', self._public_key)
        template_keys['OPTIONAL_ACTION_FLAGS'] = (
                '\n                    '.join(action_flags))

        return RESPONSE_TEMPLATE.substitute(template_keys)


    class Handler(BaseHTTPServer.BaseHTTPRequestHandler):
        """Inner class for handling HTTP requests."""
        def do_POST(self):
            """Handler for POST requests."""
            if self.path == '/update':
                # Parse the app id from the request to use in the response.
                content_len = int(self.headers.getheader('content-length'))
                request_string = self.rfile.read(content_len)
                request_dom = minidom.parseString(request_string)
                app = request_dom.firstChild.getElementsByTagName('app')[0]
                appid = app.getAttribute('appid')

                response = self.server._devserver.create_update_response(appid)

                self.send_response(200)
                self.send_header('Content-Type', 'application/xml')
                self.end_headers()
                self.wfile.write(response)
            else:
                self.send_response(500)

    def start(self):
        """Starts the server."""
        self._httpd = BaseHTTPServer.HTTPServer(('127.0.0.1', 0), self.Handler)
        self._httpd._devserver = self
        # Serve HTTP requests in a dedicated thread.
        thread.start_new_thread(self._httpd.serve_forever, ())
        self._port = self._httpd.socket.getsockname()[1]

    def stop(self):
        """Stops the server."""
        self._httpd.shutdown()

    def get_port(self):
        """Returns the TCP port number the server is listening on."""
        return self._port

    def get_update_url(self):
        """Returns the update url for this server."""
        return 'http://127.0.0.1:%d/update' % self._port

    def set_image_params(self, image_url, image_size, sha256,
                         metadata_size=None, metadata_signature=None,
                         public_key=None, is_delta=False, critical=True,
                         is_rollback=False, build='999999.0.0'):
        """
        Sets the values to return in the Omaha response.

        Only the |image_url|, |image_size| and |sha256| parameters are
        mandatory.

        @param image_url: the url of the image to install.
        @param image_size: the size of the image to install.
        @param sha256: the sha256 hash of the image to install.
        @param metadata_size: the size of the metadata.
        @param metadata_signature: the signature of the metadata.
        @param public_key: the public key.
        @param is_delta: True if image is a delta, False if a full payload.
        @param critical: True for forced update, False for regular update.
        @param is_rollback: True if image is for rollback, False if not.
        @param build: the build number the response should claim to have.

        """
        self._image_url = image_url
        self._image_size = image_size
        self._sha256 = sha256
        self._metadata_size = metadata_size
        self._metadata_signature = metadata_signature
        self._public_key = public_key
        self._is_delta = is_delta
        self._critical = critical
        self._is_rollback = is_rollback
        self._build = build
