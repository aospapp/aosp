# Copyright (c) 2012 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import socket
import time
import urllib2

import common

from autotest_lib.client.common_lib import error
from autotest_lib.client.cros import routing


def CheckInterfaceForDestination(host, expected_interface):
    """
    Checks that routes for host go through a given interface.

    The concern here is that our network setup may have gone wrong
    and our test connections may go over some other network than
    the one we're trying to test.  So we take all the IP addresses
    for the supplied host and make sure they go through the given
    network interface.

    @param host: Destination host
    @param expected_interface: Expected interface name
    @raises: error.TestFail if the routes for the given host go through
            a different interface than the expected one.

    """
    # addrinfo records: (family, type, proto, canonname, (addr, port))
    server_addresses = [record[4][0]
                        for record in socket.getaddrinfo(host, 80)]

    route_found = False
    routes = routing.NetworkRoutes()
    for address in server_addresses:
        route = routes.getRouteFor(address)
        if not route:
            continue

        route_found = True

        interface = route.interface
        logging.info('interface for %s: %s', address, interface)
        if interface != expected_interface:
            raise error.TestFail('Target server %s uses interface %s'
                                 '(%s expected).' %
                                 (address, interface, expected_interface))

    if not route_found:
        raise error.TestFail('No route found for "%s".' % host)

FETCH_URL_PATTERN_FOR_TEST = \
    'http://testing-chargen.appspot.com/download?size=%d'

def FetchUrl(url_pattern, bytes_to_fetch=10, fetch_timeout=10):
    """
    Fetches a specified number of bytes from a URL.

    @param url_pattern: URL pattern for fetching a specified number of bytes.
            %d in the pattern is to be filled in with the number of bytes to
            fetch.
    @param bytes_to_fetch: Number of bytes to fetch.
    @param fetch_timeout: Number of seconds to wait for the fetch to complete
            before it times out.
    @return: The time in seconds spent for fetching the specified number of
            bytes.
    @raises: error.TestError if one of the following happens:
            - The fetch takes no time.
            - The number of bytes fetched differs from the specified
              number.

    """
    # Limit the amount of bytes to read at a time.
    _MAX_FETCH_READ_BYTES = 1024 * 1024

    url = url_pattern % bytes_to_fetch
    logging.info('FetchUrl %s', url)
    start_time = time.time()
    result = urllib2.urlopen(url, timeout=fetch_timeout)
    bytes_fetched = 0
    while bytes_fetched < bytes_to_fetch:
        bytes_left = bytes_to_fetch - bytes_fetched
        bytes_to_read = min(bytes_left, _MAX_FETCH_READ_BYTES)
        bytes_read = len(result.read(bytes_to_read))
        bytes_fetched += bytes_read
        if bytes_read != bytes_to_read:
            raise error.TestError('FetchUrl tried to read %d bytes, but got '
                                  '%d bytes instead.' %
                                  (bytes_to_read, bytes_read))
        fetch_time = time.time() - start_time
        if fetch_time > fetch_timeout:
            raise error.TestError('FetchUrl exceeded timeout.')

    return fetch_time
