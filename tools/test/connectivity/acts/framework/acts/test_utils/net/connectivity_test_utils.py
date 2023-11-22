#
#   Copyright 2018 - The Android Open Source Project
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

from acts import asserts

def start_natt_keepalive(ad, src_ip, src_port, dst_ip, interval = 10):
    """ Start NAT-T keep alive on dut """

    ad.log.info("Starting NATT Keepalive")
    status = None

    key = ad.droid.connectivityStartNattKeepalive(
        interval, src_ip, src_port, dst_ip)

    ad.droid.connectivityNattKeepaliveStartListeningForEvent(key, "Started")
    try:
        event = ad.ed.pop_event("PacketKeepaliveCallback")
        status = event["data"]["packetKeepaliveEvent"]
    except Empty:
        msg = "Failed to receive confirmation of starting natt keepalive"
        asserts.fail(msg)
    finally:
        ad.droid.connectivityNattKeepaliveStopListeningForEvent(
            key, "Started")

    if status != "Started":
        ad.log.error("Received keepalive status: %s" % status)
        ad.droid.connectivityRemovePacketKeepaliveReceiverKey(key)
        return None
    return key

def stop_natt_keepalive(ad, key):
    """ Stop NAT-T keep alive on dut """

    ad.log.info("Stopping NATT keepalive")
    status = False
    ad.droid.connectivityStopNattKeepalive(key)

    ad.droid.connectivityNattKeepaliveStartListeningForEvent(key, "Stopped")
    try:
        event = ad.ed.pop_event("PacketKeepaliveCallback")
        status = event["data"]["packetKeepaliveEvent"] == "Stopped"
    except Empty:
        msg = "Failed to receive confirmation of stopping natt keepalive"
        asserts.fail(msg)
    finally:
        ad.droid.connectivityNattKeepaliveStopListeningForEvent(
            key, "Stopped")

    ad.droid.connectivityRemovePacketKeepaliveReceiverKey(key)
    return status
