#!/usr/bin/env python3
#
#   Copyright 2023 - The Android Open Source Project
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#           http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

from acts import logger as acts_logger

logger = acts_logger.create_logger()


def on_emm_registered(callback):
    """Registers a callback to watch for EMM attach events.

    Args:
        callback: a callback to be invoked on EMM attach events.

    Returns:
        cancel: a callback to deregister the event watcher.
    """
    from rs_mrt.testenvironment.signaling.sri.nas.eps.pubsub import EmmAttachPub
    from rs_mrt.testenvironment.signaling.sri.nas.eps import EmmRegistrationState

    def wrapped(msg):
        logger.debug("CMX received EMM registration state: {}".format(
            msg.registration_state))
        if msg.registration_state in (
                EmmRegistrationState.COMBINED_REGISTERED, ):
            callback()

    sub = EmmAttachPub.multi_subscribe(callback=wrapped)

    return lambda: sub.unsubscribe()
