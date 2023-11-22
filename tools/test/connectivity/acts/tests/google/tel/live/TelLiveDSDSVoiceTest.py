#!/usr/bin/env python3.4
#
#   Copyright 2019 - Google
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
"""
    Test Script for DSDS devices
"""

import time
from queue import Empty
from acts.test_decorators import test_tracker_info
from acts.test_utils.tel.TelephonyBaseTest import TelephonyBaseTest
from acts.test_utils.tel.tel_defines import DIRECTION_MOBILE_ORIGINATED
from acts.test_utils.tel.tel_defines import DIRECTION_MOBILE_TERMINATED
from acts.test_utils.tel.tel_defines import GEN_3G
from acts.test_utils.tel.tel_defines import GEN_4G
from acts.test_utils.tel.tel_defines import INVALID_WIFI_RSSI
from acts.test_utils.tel.tel_defines import MAX_WAIT_TIME_CALL_DROP
from acts.test_utils.tel.tel_defines import MAX_WAIT_TIME_NW_SELECTION
from acts.test_utils.tel.tel_defines import NETWORK_SERVICE_DATA
from acts.test_utils.tel.tel_defines import NETWORK_SERVICE_VOICE
from acts.test_utils.tel.tel_defines import PRECISE_CALL_STATE_LISTEN_LEVEL_BACKGROUND
from acts.test_utils.tel.tel_defines import PRECISE_CALL_STATE_LISTEN_LEVEL_FOREGROUND
from acts.test_utils.tel.tel_defines import PRECISE_CALL_STATE_LISTEN_LEVEL_RINGING
from acts.test_utils.tel.tel_defines import RAT_LTE
from acts.test_utils.tel.tel_defines import RAT_IWLAN
from acts.test_utils.tel.tel_defines import RAT_WCDMA
from acts.test_utils.tel.tel_defines import WAIT_TIME_BETWEEN_REG_AND_CALL
from acts.test_utils.tel.tel_defines import WAIT_TIME_IN_CALL
from acts.test_utils.tel.tel_defines import WAIT_TIME_WIFI_RSSI_CALIBRATION_SCREEN_ON
from acts.test_utils.tel.tel_defines import WAIT_TIME_WIFI_RSSI_CALIBRATION_WIFI_CONNECTED
from acts.test_utils.tel.tel_defines import WFC_MODE_CELLULAR_PREFERRED
from acts.test_utils.tel.tel_defines import WFC_MODE_DISABLED
from acts.test_utils.tel.tel_defines import WFC_MODE_WIFI_ONLY
from acts.test_utils.tel.tel_defines import WFC_MODE_WIFI_PREFERRED
from acts.test_utils.tel.tel_defines import WIFI_WEAK_RSSI_VALUE
from acts.test_utils.tel.tel_defines import EventNetworkCallback
from acts.test_utils.tel.tel_defines import NetworkCallbackAvailable
from acts.test_utils.tel.tel_defines import NetworkCallbackLost
from acts.test_utils.tel.tel_defines import SignalStrengthContainer
from acts.test_utils.tel.tel_test_utils import wifi_toggle_state
from acts.test_utils.tel.tel_test_utils import ensure_network_generation
from acts.test_utils.tel.tel_test_utils import ensure_phones_default_state
from acts.test_utils.tel.tel_test_utils import ensure_wifi_connected
from acts.test_utils.tel.tel_test_utils import get_network_rat
from acts.test_utils.tel.tel_test_utils import get_phone_number
from acts.test_utils.tel.tel_test_utils import get_phone_number_for_subscription
from acts.test_utils.tel.tel_test_utils import hangup_call
from acts.test_utils.tel.tel_test_utils import initiate_call
from acts.test_utils.tel.tel_test_utils import is_network_call_back_event_match
from acts.test_utils.tel.tel_test_utils import is_phone_in_call
from acts.test_utils.tel.tel_test_utils import is_phone_not_in_call
from acts.test_utils.tel.tel_test_utils import set_wfc_mode
from acts.test_utils.tel.tel_test_utils import toggle_airplane_mode
from acts.test_utils.tel.tel_test_utils import toggle_volte
from acts.test_utils.tel.tel_test_utils import wait_and_answer_call
from acts.test_utils.tel.tel_test_utils import wait_for_cell_data_connection
from acts.test_utils.tel.tel_test_utils import wait_for_droid_not_in_call
from acts.test_utils.tel.tel_test_utils import wait_for_wfc_disabled
from acts.test_utils.tel.tel_test_utils import wait_for_wfc_enabled
from acts.test_utils.tel.tel_test_utils import wait_for_wifi_data_connection
from acts.test_utils.tel.tel_test_utils import verify_http_connection
from acts.test_utils.tel.tel_test_utils import get_telephony_signal_strength
from acts.test_utils.tel.tel_test_utils import get_wifi_signal_strength
from acts.test_utils.tel.tel_test_utils import wait_for_state
from acts.test_utils.tel.tel_test_utils import is_phone_in_call
from acts.test_utils.tel.tel_voice_utils import is_phone_in_call_3g
from acts.test_utils.tel.tel_voice_utils import is_phone_in_call_2g
from acts.test_utils.tel.tel_voice_utils import is_phone_in_call_csfb
from acts.test_utils.tel.tel_voice_utils import is_phone_in_call_iwlan
from acts.test_utils.tel.tel_voice_utils import is_phone_in_call_not_iwlan
from acts.test_utils.tel.tel_voice_utils import is_phone_in_call_volte
from acts.test_utils.tel.tel_voice_utils import phone_setup_voice_general
from acts.test_utils.tel.tel_voice_utils import phone_setup_volte
from acts.test_utils.tel.tel_voice_utils import phone_setup_voice_3g
from acts.test_utils.tel.tel_voice_utils import phone_setup_voice_2g
from acts.test_utils.tel.tel_voice_utils import phone_idle_3g
from acts.test_utils.tel.tel_voice_utils import phone_idle_2g
from acts.test_utils.tel.tel_voice_utils import phone_idle_csfb
from acts.test_utils.tel.tel_voice_utils import phone_idle_iwlan
from acts.test_utils.tel.tel_voice_utils import phone_idle_not_iwlan
from acts.test_utils.tel.tel_voice_utils import phone_idle_volte
from acts.test_utils.tel.tel_subscription_utils import set_subid_for_outgoing_call
from acts.test_utils.tel.tel_subscription_utils import set_incoming_voice_sub_id
from acts.test_utils.tel.tel_subscription_utils import get_subid_from_slot_index


DEFAULT_LONG_DURATION_CALL_TOTAL_DURATION = 1 * 60 * 60  # default value 1 hour
DEFAULT_PING_DURATION = 120  # in seconds


class TelLiveDSDSVoiceTest(TelephonyBaseTest):
    def __init__(self, controllers):
        TelephonyBaseTest.__init__(self, controllers)
        self.number_of_devices = 2
        self.stress_test_number = self.get_stress_test_number()


    def _msim_call_sequence(self, ads, mo_mt, slot_id,
                            msim_phone_setup_func,
                            verify_msim_initial_idle_func,
                            verify_msim_in_call_state_func,
                            incall_msim_setting_check_func, expected_result):
        """_msim_call_sequence

        Args:
            ads: list of android devices. This list should have 2 ad.
            mo_mt: indicating this call sequence is MO or MT.
                Valid input: DIRECTION_MOBILE_ORIGINATED and
                DIRECTION_MOBILE_TERMINATED.
            slot_id: either 0 or 1

        Returns:
            if expected_result is True,
                Return True if call sequence finish without exception.
            if expected_result is string,
                Return True if expected exception happened. Otherwise False.

        """

        class _MSIMCallSequenceException(Exception):
            pass

        if (len(ads) != 2) or (mo_mt not in [
                DIRECTION_MOBILE_ORIGINATED, DIRECTION_MOBILE_TERMINATED
        ]):
            self.log.error("Invalid parameters.")
            return False

        sub_id = get_subid_from_slot_index(ads[0].log, ads[0], slot_id)
        if mo_mt == DIRECTION_MOBILE_ORIGINATED:
            ad_caller = ads[0]
            ad_callee = ads[1]
            set_subid_for_outgoing_call(ads[0], sub_id)
            caller_number = get_phone_number(self.log, ad_caller)
            callee_number = get_phone_number(self.log, ad_callee)
        else:
            ad_caller = ads[1]
            ad_callee = ads[0]
            caller_number = get_phone_number(self.log, ad_caller)
            callee_number = get_phone_number_for_subscription(ads[0].log,
                                                          ads[0], sub_id)
            setattr(ads[0], "incoming_voice_sub_id", sub_id)

        self.log.info("-->Begin msim_call_sequence: %s to %s<--",
                      caller_number, callee_number)

        try:
            # Setup
            if msim_phone_setup_func and not msim_phone_setup_func():
                raise _MSIMCallSequenceException("msim_phone_setup_func fail.")
            if not phone_setup_voice_general(self.log, ads[1]):
                raise _MSIMCallSequenceException(
                    "phone_setup_voice_general fail.")
            time.sleep(WAIT_TIME_BETWEEN_REG_AND_CALL)

            # Ensure idle status correct
            if verify_msim_initial_idle_func and not \
                verify_msim_initial_idle_func():
                raise _MSIMCallSequenceException(
                    "verify_msim_initial_idle_func fail.")

            # Make MO/MT call.
            if not initiate_call(self.log, ad_caller, callee_number):
                raise _MSIMCallSequenceException("initiate_call fail.")
            if not wait_and_answer_call(self.log, ad_callee, caller_number):
                raise _MSIMCallSequenceException("wait_and_answer_call fail.")
            time.sleep(1)

            # Check state, wait 30 seconds, check again.
            if (verify_msim_in_call_state_func and not
                    verify_msim_in_call_state_func()):
                raise _MSIMCallSequenceException(
                    "verify_msim_in_call_state_func fail.")

            if is_phone_not_in_call(self.log, ads[1]):
                raise _MSIMCallSequenceException("PhoneB not in call.")
            time.sleep(WAIT_TIME_IN_CALL)

            if (verify_msim_in_call_state_func and not
                    verify_msim_in_call_state_func()):
                raise _MSIMCallSequenceException(
                    "verify_msim_in_call_state_func fail after 30 seconds.")
            if is_phone_not_in_call(self.log, ads[1]):
                raise _MSIMCallSequenceException(
                    "PhoneB not in call after 30 seconds.")

            # in call change setting and check
            if (incall_msim_setting_check_func and not
                    incall_msim_setting_check_func()):
                raise _MSIMCallSequenceException(
                    "incall_msim_setting_check_func fail.")

            # Hangup call
            if is_phone_in_call(self.log, ads[0]):
                if not hangup_call(self.log, ads[0]):
                    raise _MSIMCallSequenceException("hangup_call fail.")
            else:
                if incall_msim_setting_check_func is None:
                    raise _MSIMCallSequenceException("Unexpected call drop.")

        except _MSIMCallSequenceException as e:
            if str(e) == expected_result:
                self.log.info("Expected exception: <%s>, return True.", e)
                return True
            else:
                self.log.info("Unexpected exception: <%s>, return False.", e)
                return False

        self.log.info("msim_call_sequence finished, return %s",
                      expected_result is True)
        return (expected_result is True)

    def _phone_idle_iwlan(self):
        return phone_idle_iwlan(self.log, self.android_devices[0])

    def _phone_idle_not_iwlan(self):
        return phone_idle_not_iwlan(self.log, self.android_devices[0])

    def _phone_idle_volte(self):
        return phone_idle_volte(self.log, self.android_devices[0])

    def _phone_idle_csfb(self):
        return phone_idle_csfb(self.log, self.android_devices[0])

    def _phone_idle_3g(self):
        return phone_idle_3g(self.log, self.android_devices[0])

    def _phone_idle_2g(self):
        return phone_idle_2g(self.log, self.android_devices[0])

    def _is_phone_in_call_iwlan(self):
        return is_phone_in_call_iwlan(self.log, self.android_devices[0])

    def _is_phone_in_call_not_iwlan(self):
        return is_phone_in_call_not_iwlan(self.log, self.android_devices[0])

    def _is_phone_not_in_call(self):
        if is_phone_in_call(self.log, self.android_devices[0]):
            self.log.info("{} in call.".format(self.android_devices[0].serial))
            return False
        self.log.info("{} not in call.".format(self.android_devices[0].serial))
        return True

    def _is_phone_in_call_volte(self):
        return is_phone_in_call_volte(self.log, self.android_devices[0])

    def _is_phone_in_call_3g(self):
        return is_phone_in_call_3g(self.log, self.android_devices[0])

    def _is_phone_in_call_2g(self):
        return is_phone_in_call_2g(self.log, self.android_devices[0])

    def _is_phone_in_call_csfb(self):
        return is_phone_in_call_csfb(self.log, self.android_devices[0])

    def _is_phone_in_call(self):
        return is_phone_in_call(self.log, self.android_devices[0])

    def _phone_setup_voice_general(self):
        return phone_setup_voice_general(self.log, self.android_devices[0])

    def _phone_setup_volte(self):
        return phone_setup_volte(self.log, self.android_devices[0])

    def _phone_setup_3g(self):
        return phone_setup_voice_3g(self.log, self.android_devices[0])

    def _phone_setup_2g(self):
        return phone_setup_voice_2g(self.log, self.android_devices[0])


    """ Tests Begin """


    @test_tracker_info(uuid="5a3ff3c0-5956-4b18-86a1-61ac60546330")
    @TelephonyBaseTest.tel_test_wrap
    def test_msim_mo_to_ssim_voice_general(self):
        """ Test MSIM to SSIM MO Voice General

        Make Sure PhoneA Slot0 is attached to Voice
        Make Sure PhoneB is able to make MO/MT call
        Call from PhoneA to PhoneB, call should succeed
        Make Sure PhoneA Slot1 is attached to Voice
        Call from PhoneA to PhoneB, call should succeed

        Returns:
            True if pass; False if fail.
        """
        ads = [self.android_devices[0], self.android_devices[1]]
        mo_result_0 = self._msim_call_sequence(
            ads, DIRECTION_MOBILE_ORIGINATED, 0,
            self._phone_setup_voice_general, None, self._is_phone_in_call,
            None, True)

        mo_result_1 = self._msim_call_sequence(
            ads, DIRECTION_MOBILE_ORIGINATED, 1,
            self._phone_setup_voice_general, None, self._is_phone_in_call,
            None, True)

        self.log.info("MO Slot0: %s, MO Slot1: %s", mo_result_0, mo_result_1)
        return ((mo_result_0 is True) and (mo_result_1 is True))


    @test_tracker_info(uuid="5adc17d4-8258-42aa-82ab-0ef92ac7b660")
    @TelephonyBaseTest.tel_test_wrap
    def test_msim_mt_to_ssim_voice_general(self):
        """ Test SSIM to MSIM MT Voice General

        Make Sure PhoneA Slot0 is attached to Voice
        Make Sure PhoneB is able to make MO/MT call
        Call from PhoneB to PhoneA, call should succeed
        Make Sure PhoneA Slot1 is attached to Voice
        Call from PhoneB to PhoneA, call should succeed

        Returns:
            True if pass; False if fail.
        """
        ads = [self.android_devices[0], self.android_devices[1]]
        mt_result_0 = self._msim_call_sequence(
            ads, DIRECTION_MOBILE_TERMINATED, 0,
            self._phone_setup_voice_general, None, self._is_phone_in_call,
            None, True)

        mt_result_1 = self._msim_call_sequence(
            ads, DIRECTION_MOBILE_TERMINATED, 1,
            self._phone_setup_voice_general, None, self._is_phone_in_call,
            None, True)

        self.log.info("MT Slot0: %s, MT Slot1: %s", mt_result_0, mt_result_1)
        return ((mt_result_0 is True) and (mt_result_1 is True))


    @test_tracker_info(uuid="3988e411-b9f8-4798-b3a6-dd3bf0d2035c")
    @TelephonyBaseTest.tel_test_wrap
    def test_msim_mo_to_ssim_volte(self):
        """ Test MSIM to SSIM MO VoLTE

        Make Sure PhoneA Slot0 is on VoLTE
        Make Sure PhoneB is able to make MO/MT call
        Call from PhoneA to PhoneB, call should succeed on VoLTE
        Make Sure PhoneA Slot1 is on VoLTE
        Call from PhoneA to PhoneB, call should succeed on VoLTE

        Returns:
            True if pass; False if fail.
        """
        ads = [self.android_devices[0], self.android_devices[1]]
        mo_result_0 = self._msim_call_sequence(
            ads, DIRECTION_MOBILE_ORIGINATED, 0, self._phone_setup_volte,
            self._phone_idle_volte, self._is_phone_in_call_volte, None, True)

        mo_result_1 = self._msim_call_sequence(
            ads, DIRECTION_MOBILE_ORIGINATED, 1, self._phone_setup_volte,
            self._phone_idle_volte, self._is_phone_in_call_volte, None, True)

        self.log.info("MO Slot0: %s, MO Slot1: %s", mo_result_0, mo_result_1)
        return ((mo_result_0 is True) and (mo_result_1 is True))


    @test_tracker_info(uuid="4fe74a11-6f13-4305-becd-ad77e13a3193")
    @TelephonyBaseTest.tel_test_wrap
    def test_msim_mt_to_ssim_volte(self):
        """ Test SSIM to MSIM MT VoLTE

        Make Sure PhoneA Slot0 is on VoLTE
        Make Sure PhoneB is able to make MO/MT call
        Call from PhoneB to PhoneA, call should succeed on VoLTE
        Make Sure PhoneA Slot1 is on VoLTE
        Call from PhoneB to PhoneA, call should succeed on VoLTE

        Returns:
            True if pass; False if fail.
        """
        ads = [self.android_devices[0], self.android_devices[1]]
        mt_result_0 = self._msim_call_sequence(
            ads, DIRECTION_MOBILE_TERMINATED, 0, self._phone_setup_volte,
            self._phone_idle_volte, self._is_phone_in_call_volte, None, True)

        mt_result_1 = self._msim_call_sequence(
            ads, DIRECTION_MOBILE_TERMINATED, 1, self._phone_setup_volte,
            self._phone_idle_volte, self._is_phone_in_call_volte, None, True)

        self.log.info("MT Slot0: %s, MT Slot1: %s", mt_result_0, mt_result_1)
        return ((mt_result_0 is True) and (mt_result_1 is True))


    @test_tracker_info(uuid="ffc7a414-8ec3-4bf4-a509-0bec59f2a3b9")
    @TelephonyBaseTest.tel_test_wrap
    def test_msim_mo_to_ssim_3g(self):
        """ Test MSIM to SSIM MO 3G

        Make Sure PhoneA Slot0 is on 3G
        Make Sure PhoneB is able to make MO/MT call
        Call from PhoneA to PhoneB, call should succeed on 3G
        Make Sure PhoneA Slot1 is on 3G
        Call from PhoneA to PhoneB, call should succeed on 3G

        Returns:
            True if pass; False if fail.
        """
        ads = [self.android_devices[0], self.android_devices[1]]
        mo_result_0 = self._msim_call_sequence(
            ads, DIRECTION_MOBILE_ORIGINATED, 0, self._phone_setup_3g,
            self._phone_idle_3g, self._is_phone_in_call_3g, None, True)

        mo_result_1 = self._msim_call_sequence(
            ads, DIRECTION_MOBILE_ORIGINATED, 1, self._phone_setup_3g,
            self._phone_idle_3g, self._is_phone_in_call_3g, None, True)

        self.log.info("MO Slot0: %s, MO Slot1: %s", mo_result_0, mo_result_1)
        return ((mo_result_0 is True) and (mo_result_1 is True))


    @test_tracker_info(uuid="21221072-01f2-4951-ad55-fe1f4fe1b044")
    @TelephonyBaseTest.tel_test_wrap
    def test_msim_mt_to_ssim_3g(self):
        """ Test SSIM to MSIM MT 3G

        Make Sure PhoneA Slot0 is on 3G
        Make Sure PhoneB is able to make MO/MT call
        Call from PhoneB to PhoneA, call should succeed on 3G
        Make Sure PhoneA Slot1 is on 3G
        Call from PhoneB to PhoneA, call should succeed on 3G

        Returns:
            True if pass; False if fail.
        """
        ads = [self.android_devices[0], self.android_devices[1]]
        mt_result_0 = self._msim_call_sequence(
            ads, DIRECTION_MOBILE_TERMINATED, 0, self._phone_setup_3g,
            self._phone_idle_3g, self._is_phone_in_call_3g, None, True)

        mt_result_1 = self._msim_call_sequence(
            ads, DIRECTION_MOBILE_TERMINATED, 1, self._phone_setup_3g,
            self._phone_idle_3g, self._is_phone_in_call_3g, None, True)

        self.log.info("MT Slot0: %s, MT Slot1: %s", mt_result_0, mt_result_1)
        return ((mt_result_0 is True) and (mt_result_1 is True))


    @test_tracker_info(uuid="65448385-42fe-429c-ad8e-6c75be93f83e")
    @TelephonyBaseTest.tel_test_wrap
    def test_msim_mo_to_ssim_2g(self):
        """ Test MSIM to SSIM MO 2G

        Make Sure PhoneA Slot0 is on 2G
        Make Sure PhoneB is able to make MO/MT call
        Call from PhoneA to PhoneB, call should succeed on 2G
        Make Sure PhoneA Slot1 is on 2G
        Call from PhoneA to PhoneB, call should succeed on 2G

        Returns:
            True if pass; False if fail.
        """
        ads = [self.android_devices[0], self.android_devices[1]]
        mo_result_0 = self._msim_call_sequence(
            ads, DIRECTION_MOBILE_ORIGINATED, 0, self._phone_setup_2g,
            self._phone_idle_2g, self._is_phone_in_call_2g, None, True)

        mo_result_1 = self._msim_call_sequence(
            ads, DIRECTION_MOBILE_ORIGINATED, 1, self._phone_setup_2g,
            self._phone_idle_2g, self._is_phone_in_call_2g, None, True)

        self.log.info("MO Slot0: %s, MO Slot1: %s", mo_result_0, mo_result_1)
        return ((mo_result_0 is True) and (mo_result_1 is True))


    @test_tracker_info(uuid="44d1ef0a-c981-4db1-958c-0af06eb0c503")
    @TelephonyBaseTest.tel_test_wrap
    def test_msim_mt_to_ssim_2g(self):
        """ Test SSIM to MSIM MT 2G

        Make Sure PhoneA Slot0 is on 2G
        Make Sure PhoneB is able to make MO/MT call
        Call from PhoneB to PhoneA, call should succeed on 2G
        Make Sure PhoneA Slot1 is on 2G
        Call from PhoneB to PhoneA, call should succeed on 2G

        Returns:
            True if pass; False if fail.
        """
        ads = [self.android_devices[0], self.android_devices[1]]
        mt_result_0 = self._msim_call_sequence(
            ads, DIRECTION_MOBILE_TERMINATED, 0, self._phone_setup_2g,
            self._phone_idle_2g, self._is_phone_in_call_2g, None, True)

        mt_result_1 = self._msim_call_sequence(
            ads, DIRECTION_MOBILE_TERMINATED, 1, self._phone_setup_2g,
            self._phone_idle_2g, self._is_phone_in_call_2g, None, True)

        self.log.info("MT Slot0: %s, MT Slot1: %s", mt_result_0, mt_result_1)
        return ((mt_result_0 is True) and (mt_result_1 is True))
