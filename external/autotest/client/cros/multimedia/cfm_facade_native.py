# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Facade to access the CFM functionality."""

import time

from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import cfm_hangouts_api
from autotest_lib.client.common_lib.cros import cfm_meetings_api
from autotest_lib.client.common_lib.cros import enrollment
from autotest_lib.client.common_lib.cros import kiosk_utils


class TimeoutException(Exception):
    """Timeout Exception class."""
    pass


class CFMFacadeNative(object):
    """Facade to access the CFM functionality.

    The methods inside this class only accept Python native types.
    """
    _USER_ID = 'cfmtest@croste.tv'
    _PWD = 'test0000'
    _EXT_ID = 'ikfcpmgefdpheiiomgmhlmmkihchmdlj'
    _ENROLLMENT_DELAY = 15
    _DEFAULT_TIMEOUT = 30


    def __init__(self, resource):
        """Initializes a CFMFacadeNative.

        @param resource: A FacadeResource object.
        """
        self._resource = resource


    def enroll_device(self):
        """Enroll device into CFM."""
        self._resource.start_custom_chrome({"auto_login": False,
                                            "disable_gaia_services": False})
        enrollment.RemoraEnrollment(self._resource._browser, self._USER_ID,
                self._PWD)
        # Timeout to allow for the device to stablize and go back to the
        # login screen before proceeding.
        time.sleep(self._ENROLLMENT_DELAY)
        self.restart_chrome_for_cfm()
        self.check_hangout_extension_context()


    def restart_chrome_for_cfm(self):
        """Restart chrome with custom values for CFM."""
        custom_chrome_setup = {"clear_enterprise_policy": False,
                               "dont_override_profile": True,
                               "disable_gaia_services": False,
                               "disable_default_apps": False,
                               "auto_login": False}
        self._resource.start_custom_chrome(custom_chrome_setup)


    def check_hangout_extension_context(self):
        """Check to make sure hangout app launched.

        @raises error.TestFail if the URL checks fails.
        """
        ext_contexts = kiosk_utils.wait_for_kiosk_ext(
                self._resource._browser, self._EXT_ID)
        ext_urls = set([context.EvaluateJavaScript('location.href;')
                        for context in ext_contexts])
        if len(ext_urls) == 2:
            expected_urls = set(
                ['chrome-extension://' + self._EXT_ID + '/' + path
                 for path in ['hangoutswindow.html?windowid=0',
                              '_generated_background_page.html']])
        if len(ext_urls) == 3:
            expected_urls = set(
                ['chrome-extension://' + self._EXT_ID + '/' + path
                 for path in ['hangoutswindow.html?windowid=0',
                              'hangoutswindow.html?windowid=1',
                              '_generated_background_page.html']])
        if expected_urls != ext_urls:
            raise error.TestFail(
                    'Unexpected extension context urls, expected %s, got %s'
                    % (expected_urls, ext_urls))


    def skip_oobe_after_enrollment(self):
        """Skips oobe and goes to the app landing page after enrollment."""
        self.restart_chrome_for_cfm()
        self.wait_for_hangouts_telemetry_commands()
        self.wait_for_oobe_start_page()
        self.skip_oobe_screen()


    @property
    def _webview_context(self):
        """Get webview context object."""
        return kiosk_utils.get_webview_context(
                self._resource._browser, self._EXT_ID)


    @property
    def cfmApi(self):
        """Instantiate appropriate cfm api wrapper"""
        if self._webview_context.EvaluateJavaScript(
                "typeof window.hrRunDiagnosticsForTest == 'function'"):
            return cfm_hangouts_api.CfmHangoutsAPI(self._webview_context)
        return cfm_meetings_api.CfmMeetingsAPI(self._webview_context)


    #TODO: This is a legacy api. Deprecate this api and update existing hotrod
    #      tests to use the new wait_for_hangouts_telemetry_commands api.
    def wait_for_telemetry_commands(self):
        """Wait for telemetry commands."""
        self.wait_for_hangouts_telemetry_commands()


    def wait_for_hangouts_telemetry_commands(self):
        """Wait for Hangouts App telemetry commands."""
        self._webview_context.WaitForJavaScriptCondition(
                "typeof window.hrOobIsStartPageForTest == 'function'",
                timeout=self._DEFAULT_TIMEOUT)


    def wait_for_meetings_telemetry_commands(self):
        """Wait for Meet App telemetry commands """
        self._webview_context.WaitForJavaScriptCondition(
                'window.hasOwnProperty("hrTelemetryApi")',
                timeout=self._DEFAULT_TIMEOUT)


    def wait_for_meetings_in_call_page(self):
        """Waits for the in-call page to launch."""
        self.wait_for_meetings_telemetry_commands()
        self.cfmApi.wait_for_meetings_in_call_page()


    def wait_for_meetings_landing_page(self):
        """Waits for the landing page screen."""
        self.wait_for_meetings_telemetry_commands()
        self.cfmApi.wait_for_meetings_landing_page()


    # UI commands/functions
    def wait_for_oobe_start_page(self):
        """Wait for oobe start screen to launch."""
        self.cfmApi.wait_for_oobe_start_page()


    def skip_oobe_screen(self):
        """Skip Chromebox for Meetings oobe screen."""
        self.cfmApi.skip_oobe_screen()


    def is_oobe_start_page(self):
        """Check if device is on CFM oobe start screen.

        @return a boolean, based on oobe start page status.
        """
        return self.cfmApi.is_oobe_start_page()


    # Hangouts commands/functions
    def start_new_hangout_session(self, session_name):
        """Start a new hangout session.

        @param session_name: Name of the hangout session.
        """
        self.cfmApi.start_new_hangout_session(session_name)


    def end_hangout_session(self):
        """End current hangout session."""
        self.cfmApi.end_hangout_session()


    def is_in_hangout_session(self):
        """Check if device is in hangout session.

        @return a boolean, for hangout session state.
        """
        return self.cfmApi.is_in_hangout_session()


    def is_ready_to_start_hangout_session(self):
        """Check if device is ready to start a new hangout session.

        @return a boolean for hangout session ready state.
        """
        return self.cfmApi.is_ready_to_start_hangout_session()


    def join_meeting_session(self, session_name):
        """Joins a meeting.

        @param session_name: Name of the meeting session.
        """
        self.cfmApi.join_meeting_session(session_name)


    def start_meeting_session(self):
        """Start a meeting."""
        self.cfmApi.start_meeting_session()


    def end_meeting_session(self):
        """End current meeting session."""
        self.cfmApi.end_meeting_session()


    # Diagnostics commands/functions
    def is_diagnostic_run_in_progress(self):
        """Check if hotrod diagnostics is running.

        @return a boolean for diagnostic run state.
        """
        return self.cfmApi.is_diagnostic_run_in_progress()


    def wait_for_diagnostic_run_to_complete(self):
        """Wait for hotrod diagnostics to complete."""
        self.cfmApi.wait_for_diagnostic_run_to_complete()


    def run_diagnostics(self):
        """Run hotrod diagnostics."""
        self.cfmApi.run_diagnostics()


    def get_last_diagnostics_results(self):
        """Get latest hotrod diagnostics results.

        @return a dict with diagnostic test results.
        """
        return self.cfmApi.get_last_diagnostics_results()


    # Mic audio commands/functions
    def is_mic_muted(self):
        """Check if mic is muted.

        @return a boolean for mic mute state.
        """
        return self.cfmApi.is_mic_muted()


    def mute_mic(self):
        """Local mic mute from toolbar."""
        self.cfmApi.mute_mic()


    def unmute_mic(self):
        """Local mic unmute from toolbar."""
        self.cfmApi.unmute_mic()


    def remote_mute_mic(self):
        """Remote mic mute request from cPanel."""
        self.cfmApi.remote_mute_mic()


    def remote_unmute_mic(self):
        """Remote mic unmute request from cPanel."""
        self.cfmApi.remote_unmute_mic()


    def get_mic_devices(self):
        """Get all mic devices detected by hotrod.

        @return a list of mic devices.
        """
        return self.cfmApi.get_mic_devices()


    def get_preferred_mic(self):
        """Get mic preferred for hotrod.

        @return a str with preferred mic name.
        """
        return self.cfmApi.get_preferred_mic()


    def set_preferred_mic(self, mic):
        """Set preferred mic for hotrod.

        @param mic: String with mic name.
        """
        self.cfmApi.set_preferred_mic(mic)


    # Speaker commands/functions
    def get_speaker_devices(self):
        """Get all speaker devices detected by hotrod.

        @return a list of speaker devices.
        """
        return self.cfmApi.get_speaker_devices()


    def get_preferred_speaker(self):
        """Get speaker preferred for hotrod.

        @return a str with preferred speaker name.
        """
        return self.cfmApi.get_preferred_speaker()


    def set_preferred_speaker(self, speaker):
        """Set preferred speaker for hotrod.

        @param speaker: String with speaker name.
        """
        self.cfmApi.set_preferred_speaker(speaker)


    def set_speaker_volume(self, volume_level):
        """Set speaker volume.

        @param volume_level: String value ranging from 0-100 to set volume to.
        """
        self.cfmApi.set_speaker_volume(volume_level)


    def get_speaker_volume(self):
        """Get current speaker volume.

        @return a str value with speaker volume level 0-100.
        """
        return self.cfmApi.get_speaker_volume()


    def play_test_sound(self):
        """Play test sound."""
        self.cfmApi.play_test_sound()


    # Camera commands/functions
    def get_camera_devices(self):
        """Get all camera devices detected by hotrod.

        @return a list of camera devices.
        """
        return self.cfmApi.get_camera_devices()


    def get_preferred_camera(self):
        """Get camera preferred for hotrod.

        @return a str with preferred camera name.
        """
        return self.cfmApi.get_preferred_camera()


    def set_preferred_camera(self, camera):
        """Set preferred camera for hotrod.

        @param camera: String with camera name.
        """
        self.cfmApi.set_preferred_camera(camera)


    def is_camera_muted(self):
        """Check if camera is muted (turned off).

        @return a boolean for camera muted state.
        """
        return self.cfmApi.is_camera_muted()


    def mute_camera(self):
        """Turned camera off."""
        self.cfmApi.mute_camera()


    def unmute_camera(self):
        """Turned camera on."""
        self.cfmApi.unmute_camera()
