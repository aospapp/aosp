# Copyright 2015 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import copy
import json
import logging
import os
import time

from autotest_lib.client.common_lib.cros import arc
from autotest_lib.client.common_lib.cros.arc import is_android_container_alive

from autotest_lib.client.bin import test
from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import chrome
from autotest_lib.client.common_lib.cros import enrollment
from autotest_lib.client.common_lib.cros import policy
from autotest_lib.client.cros import cryptohome
from autotest_lib.client.cros import httpd
from autotest_lib.client.cros.input_playback import keyboard
from autotest_lib.client.cros.enterprise import enterprise_fake_dmserver
from py_utils import TimeoutException

from telemetry.core import exceptions

CROSQA_FLAGS = [
    '--gaia-url=https://gaiastaging.corp.google.com',
    '--lso-url=https://gaiastaging.corp.google.com',
    '--google-apis-url=https://www-googleapis-test.sandbox.google.com',
    '--oauth2-client-id=236834563817.apps.googleusercontent.com',
    '--oauth2-client-secret=RsKv5AwFKSzNgE0yjnurkPVI',
    ('--cloud-print-url='
     'https://cloudprint-nightly-ps.sandbox.google.com/cloudprint'),
    '--ignore-urlfetcher-cert-requests']
CROSALPHA_FLAGS = [
    ('--cloud-print-url='
     'https://cloudprint-nightly-ps.sandbox.google.com/cloudprint'),
    '--ignore-urlfetcher-cert-requests']
TESTDMS_FLAGS = [
    '--ignore-urlfetcher-cert-requests',
    '--disable-policy-key-verification']
FLAGS_DICT = {
    'prod': [],
    'crosman-qa': CROSQA_FLAGS,
    'crosman-alpha': CROSALPHA_FLAGS,
    'dm-test': TESTDMS_FLAGS,
    'dm-fake': TESTDMS_FLAGS
}
DMS_URL_DICT = {
    'prod': 'http://m.google.com/devicemanagement/data/api',
    'crosman-qa':
        'https://crosman-qa.sandbox.google.com/devicemanagement/data/api',
    'crosman-alpha':
        'https://crosman-alpha.sandbox.google.com/devicemanagement/data/api',
    'dm-test': 'http://chromium-dm-test.appspot.com/d/%s',
    'dm-fake': 'http://127.0.0.1:%d/'
}
DMSERVER = '--device-management-url=%s'
# Username and password for the fake dm server can be anything, since
# they are not used to authenticate against GAIA.
USERNAME = 'fake-user@managedchrome.com'
PASSWORD = 'fakepassword'
GAIA_ID = 'fake-gaia-id'

# Convert from chrome://policy name to what fake dms expects.
DEVICE_POLICY_DICT = {
    'DeviceAutoUpdateDisabled': 'update_disabled',
    'DeviceEphemeralUsersEnabled': 'ephemeral_users_enabled',
    'DeviceOpenNetworkConfiguration': 'open_network_configuration',
    'DeviceRollbackToTargetVersion': 'rollback_to_target_version',
    'DeviceTargetVersionPrefix': 'target_version_prefix',
    'SystemTimezone': 'timezone',
    'ReportUploadFrequency': 'device_status_frequency',
    'DeviceLocalAccounts': 'account',
    'DeviceLocalAccountAutoLoginId': 'auto_login_id'
}

# Default settings for managed user policies
DEFAULT_POLICY = {
    'AllowDinosaurEasterEgg': False,
    'ArcBackupRestoreServiceEnabled': 0,
    'ArcEnabled': False,
    'ArcGoogleLocationServicesEnabled': 0,
    'CaptivePortalAuthenticationIgnoresProxy': False,
    'CastReceiverEnabled': False,
    'ChromeOsMultiProfileUserBehavior': 'primary-only',
    'EasyUnlockAllowed': False,
    'InstantTetheringAllowed': False,
    'NTLMShareAuthenticationEnabled': False,
    'NTPContentSuggestionsEnabled': False,
    'NetBiosShareDiscoveryEnabled': False,
    'QuickUnlockModeWhitelist': [],
    'SmartLockSigninAllowed': False,
    'SmsMessagesAllowed': False
}


class EnterprisePolicyTest(arc.ArcTest, test.test):
    """Base class for Enterprise Policy Tests."""

    WEB_PORT = 8080
    WEB_HOST = 'http://localhost:%d' % WEB_PORT
    CHROME_POLICY_PAGE = 'chrome://policy'
    CHROME_VERSION_PAGE = 'chrome://version'


    def initialize(self, **kwargs):
        """
        Initialize test parameters.

        Consume the check_client_result parameter if this test was started
        from a server test.

        """
        self._initialize_enterprise_policy_test(**kwargs)


    def _initialize_enterprise_policy_test(
            self, case='', env='dm-fake', dms_name=None,
            username=USERNAME, password=PASSWORD, gaia_id=GAIA_ID,
            set_auto_logout=None, **kwargs):
        """
        Initialize test parameters and fake DM Server.

        This function exists so that ARC++ tests (which inherit from the
        ArcTest class) can also initialize a policy setup.

        @param case: String name of the test case to run.
        @param env: String environment of DMS and Gaia servers.
        @param username: String user name login credential.
        @param password: String password login credential.
        @param gaia_id: String gaia_id login credential.
        @param dms_name: String name of test DM Server.
        @param kwargs: Not used.

        """
        self.case = case
        self.env = env
        self.username = username
        self.password = password
        self.gaia_id = gaia_id
        self.set_auto_logout = set_auto_logout
        self.dms_name = dms_name
        self.dms_is_fake = (env == 'dm-fake')
        self.arc_enabled = False
        self.version = None
        self._enforce_variable_restrictions()

        # Install protobufs and add import path.
        policy.install_protobufs(self.autodir, self.job)

        # Initialize later variables to prevent error after an early failure.
        self._web_server = None
        self.cr = None

        # Start AutoTest DM Server if using local fake server.
        if self.dms_is_fake:
            self.fake_dm_server = enterprise_fake_dmserver.FakeDMServer()
            self.fake_dm_server.start(self.tmpdir, self.debugdir)

        # Get enterprise directory of shared resources.
        client_dir = os.path.dirname(os.path.dirname(self.bindir))
        self.enterprise_dir = os.path.join(client_dir, 'cros/enterprise')

        if self.set_auto_logout is not None:
            self._auto_logout = self.set_auto_logout

        # Log the test context parameters.
        logging.info('Test Context Parameters:')
        logging.info('  Case: %r', self.case)
        logging.info('  Environment: %r', self.env)
        logging.info('  Username: %r', self.username)
        logging.info('  Password: %r', self.password)
        logging.info('  Test DMS Name: %r', self.dms_name)


    def check_page_readiness(self, tab, command):
        """
        Checks to see if page has fully loaded.

        @param tab: chrome tab loading the page.
        @param command: JS command to be checked in the tab.

        @returns True if loaded and False if not.

        """
        try:
            tab.EvaluateJavaScript(command)
            return True
        except exceptions.EvaluateException:
            return False


    def cleanup(self):
        """Close out anything used by this test."""
        # Clean up AutoTest DM Server if using local fake server.
        if self.dms_is_fake:
            self.fake_dm_server.stop()

        # Stop web server if it was started.
        if self._web_server:
            self._web_server.stop()

        # Close Chrome instance if opened.
        if self.cr and self._auto_logout:
            self.cr.close()

        # Cleanup the ARC test if needed.
        if self.arc_enabled:
            super(EnterprisePolicyTest, self).cleanup()


    def start_webserver(self):
        """Set up HTTP Server to serve pages from enterprise directory."""
        self._web_server = httpd.HTTPListener(
                self.WEB_PORT, docroot=self.enterprise_dir)
        self._web_server.run()


    def _enforce_variable_restrictions(self):
        """Validate class-level test context parameters.

        @raises error.TestError if context parameter has an invalid value,
                or a combination of parameters have incompatible values.
        """
        # Verify |env| is a valid environment.
        if self.env not in FLAGS_DICT:
            raise error.TestError('Environment is invalid: %s' % self.env)

        # Verify test |dms_name| is given iff |env| is 'dm-test'.
        if self.env == 'dm-test' and not self.dms_name:
            raise error.TestError('dms_name must be given when using '
                                  'env=dm-test.')
        if self.env != 'dm-test' and self.dms_name:
            raise error.TestError('dms_name must not be given when not using '
                                  'env=dm-test.')


    def setup_case(self,
                   user_policies={},
                   suggested_user_policies={},
                   device_policies={},
                   extension_policies={},
                   skip_policy_value_verification=False,
                   kiosk_mode=False,
                   enroll=False,
                   auto_login=True,
                   auto_logout=True,
                   init_network_controller=False,
                   arc_mode=False,
                   setup_arc=True,
                   use_clouddpc_test=None,
                   disable_default_apps=True,
                   extension_paths=[],
                   extra_chrome_flags=[]):
        """Set up DMS, log in, and verify policy values.

        If the AutoTest fake DM Server is used, make a JSON policy blob
        and upload it to the fake DM server.

        Launch Chrome and sign in to Chrome OS. Examine the user's
        cryptohome vault, to confirm user is signed in successfully.

        @param user_policies: dict of mandatory user policies in
                name -> value format.
        @param suggested_user_policies: optional dict of suggested policies
                in name -> value format.
        @param device_policies: dict of device policies in
                name -> value format.
        @param extension_policies: dict of extension policies.
        @param skip_policy_value_verification: True if setup_case should not
                verify that the correct policy value shows on policy page.
        @param enroll: True for enrollment instead of login.
        @param auto_login: Sign in to chromeos.
        @param auto_logout: Sign out of chromeos when test is complete.
        @param init_network_controller: whether to init network controller.
        @param arc_mode: whether to enable arc_mode on chrome.chrome().
        @param setup_arc: whether to run setup_arc in arc.Arctest.
        @param use_clouddpc_test: whether to run the cloud dpc test.
        @param extension_paths: list of extensions to install.
        @param extra_chrome_flags: list of flags to add to Chrome.

        @raises error.TestError if cryptohome vault is not mounted for user.
        @raises error.TestFail if |policy_name| and |policy_value| are not
                shown on the Policies page.
        """

        # Need a real account, for now. Note: Even though the account is 'real'
        # you can still use a fake DM server.
        if arc_mode and self.username == USERNAME:
            self.username = 'tester50@managedchrome.com'
            self.password = 'Test0000'

        self._auto_logout = auto_logout
        self._kiosk_mode = kiosk_mode

        if self.dms_is_fake:
            self.fake_dm_server.setup_policy(self._make_json_blob(
                user_policies, suggested_user_policies, device_policies,
                extension_policies))

        self._create_chrome(enroll=enroll,
                            auto_login=auto_login,
                            init_network_controller=init_network_controller,
                            extension_paths=extension_paths,
                            arc_mode=arc_mode,
                            disable_default_apps=disable_default_apps,
                            extra_chrome_flags=extra_chrome_flags)

        # Skip policy check upon request or if we enroll but don't log in.
        skip_policy_value_verification = (
                skip_policy_value_verification or not auto_login)

        if not skip_policy_value_verification:
            self.verify_policy_stats(user_policies, suggested_user_policies,
                                     device_policies)
            self.verify_extension_stats(extension_policies)

        if arc_mode:
            self.start_arc(use_clouddpc_test, setup_arc)

    def start_arc(self, use_clouddpc_test, setup_arc):
        '''
        Starts ARC when creating the chrome object. Specifically will create
        the ADB shell container for testing use.

        We are NOT going to use the arc class initialize, it overwrites the
        creation of chrome.chrome() in a way which cannot support the DM sever.

        Instead we check for the android container, and run arc_setup if
        needed. Note: To use the cloud dpc test, you MUST also run setup_arc

        @param setup_arc: whether to run setup_arc in arc.Arctest.
        @param use_clouddpc_test: bool, run_clouddpc_test() or not.
        '''
        _APP_FILENAME = 'autotest-deps-cloudpctest-0.4.apk'
        _DEP_PACKAGE = 'CloudDPCTest-apks'
        _PKG_NAME = 'com.google.android.apps.work.clouddpc.e2etests'

        # By default on boot the container is alive, and will not close until
        # a user with ARC disabled logs in. This wait accounts for that.
        time.sleep(3)

        if use_clouddpc_test and not setup_arc:
            raise error.TestFail('For cloud DPC setup_arc cannot be disabled')

        if is_android_container_alive():
            logging.info('Android Container is alive!')
        else:
            logging.error('Android Container is not alive!')

        # Install the clouddpc test.
        if use_clouddpc_test:
            self.arc_setup(dep_packages=_DEP_PACKAGE,
                           apks=[_APP_FILENAME],
                           full_pkg_names=[_PKG_NAME])
            self.run_clouddpc_test()
        else:
            if setup_arc:
                self.arc_setup()

        self.arc_enabled = True

    def run_clouddpc_test(self):
        """
        Run clouddpc end-to-end test and fail this test if it fails.

        Assumes start_arc() was run with use_clouddpc_test.

        Determines the policy values to pass to the test from those set in
        Chrome OS.

        @raises error.TestFail if the test does not pass.

        """
        policy_blob = self._get_clouddpc_policies()
        policy_blob_str = json.dumps(policy_blob, separators=(',',':'))
        cmd = ('am instrument -w -e policy "%s" '
               'com.google.android.apps.work.clouddpc.e2etests/'
               '.ArcInstrumentationTestRunner') % policy_blob_str

        # Run the command as a shell script so that its length is not capped.
        temp_shell_script_path = '/sdcard/tmp.sh'
        arc.write_android_file(temp_shell_script_path, cmd)

        logging.info('Running clouddpc test with policy: %s', policy_blob_str)
        results = arc.adb_shell('sh ' + temp_shell_script_path).strip()
        arc.remove_android_file(temp_shell_script_path)
        if results.find('FAILURES!!!') >= 0:
            logging.info('CloudDPC E2E Results:\n%s', results)
            err_msg = results.splitlines()[-1]
            raise error.TestFail('CloudDPC E2E failure: %s' % err_msg)

        logging.debug(results)
        logging.info('CloudDPC E2E test passed!')

    def _get_clouddpc_policies(self):
        """
        Return the relevant CloudDPC policies and their values for e2e testing.

        The CloudDPC end-to-end test takes in a string of the policies which
        are set.  These policies don't have the same names in Chrome OS, or
        they don't map 1:1 with Chrome OS's policies.

        Figuring out the values from chrome://policy (rather than creating a
        dict of the policies under test) will prevent the test from failing on
        accounts which happen to have unrelated policies set.

        Constructs a json object of the CloudDPC policy names and values.
        Finds the values which map 1:1 to Chrome OS and handles the exceptions.

        @returns a dict where the keys are CloudDPC policy names and the values
                 are as shown on chrome://policy.

        """

        # CloudDPC policy -> value
        policy_map = {}

        # The policies which are a 1:1 rename between Chrome OS and CloudDPC.
        chromeos_to_clouddpc = {
            'AudioCaptureAllowed': 'unmuteMicrophoneDisabled',
            'DefaultGeolocationSetting': 'shareLocationDisabled',
            'DeviceBlockDevmode': 'debuggingFeaturesDisabled',
            'DisableScreenshots': 'screenCaptureDisabled',
            'ExternalStorageDisabled': 'usbFileTransferDisabled',
            'VideoCaptureAllowed': 'cameraDisabled',
        }

        # Only check caCerts if the value is allowed to be passed to Android.
        caCerts_passed = self._get_policy_value_from_new_tab(
            'ArcCertificatesSyncMode')
        if caCerts_passed:
            chromeos_to_clouddpc['OpenNetworkConfiguration'] = 'caCerts'

        # The policies which take some special handling to convert.
        exception_policies = ['URLBlacklist', 'URLWhitelist', 'ArcPolicy']

        values = self._get_policy_values_from_new_tab(
            chromeos_to_clouddpc.keys() + exception_policies)

        # Map the 1:1 policies: Android policy name to ChromeOS policy value.
        for chromeos_policy in chromeos_to_clouddpc:
            clouddpc_policy = chromeos_to_clouddpc[chromeos_policy]
            value = values[chromeos_policy]
            if value is not None:
                policy_map[clouddpc_policy] = value

        # ArcPolicy value contains some stand-alone CloudDPC policies.
        arc_policy_value = values['ArcPolicy']

        if arc_policy_value:
            for key in ['applications', 'accountTypesWithManagementDisabled']:
                if key in arc_policy_value:
                    policy_map[key] = arc_policy_value[key]

        return policy_map

    def _make_json_blob(self, user_policies={}, suggested_user_policies={},
                        device_policies={}, extension_policies={}):
        """Create JSON policy blob from mandatory and suggested policies.

        For the status of a policy to be shown as "Not set" on the
        chrome://policy page, the policy dictionary must contain no NVP for
        for that policy. Remove policy NVPs if value is None.

        @param user_policies: mandatory user policies -> values.
        @param suggested user_policies: suggested user policies -> values.
        @param device_policies: mandatory device policies -> values.
        @param extension_policies: extension policies.

        @returns: JSON policy blob to send to the fake DM server.
        """

        user_p = copy.deepcopy(user_policies)
        s_user_p = copy.deepcopy(suggested_user_policies)
        device_p = copy.deepcopy(device_policies)
        extension_p = copy.deepcopy(extension_policies)

        # Replace all device policies with their FakeDMS-friendly names.
        fixed_device_p = {}
        for policy in device_p:
            if policy not in DEVICE_POLICY_DICT:
                raise error.TestError('Cannot convert %s!' % policy)
            fixed_device_p[DEVICE_POLICY_DICT[policy]] = device_p[policy]

        # Remove "Not set" policies and json-ify dicts because the
        # FakeDMServer expects "policy": "{value}" not "policy": {value}
        # and "policy": "[{value}]" not "policy": [{value}].
        for policies_dict in [user_p, s_user_p, fixed_device_p]:
            policies_to_pop = []
            for policy in policies_dict:
                value = policies_dict[policy]
                if value is None:
                    policies_to_pop.append(policy)
                elif isinstance(value, dict):
                    policies_dict[policy] = encode_json_string(value)
                elif isinstance(value, list) and not (
                    policies_dict in [fixed_device_p]):
                    if value and isinstance(value[0], dict):
                        policies_dict[policy] = encode_json_string(value)
            for policy in policies_to_pop:
                policies_dict.pop(policy)

        management_dict = {
            'managed_users': ['*'],
            'policy_user': self.username,
            'current_key_index': 0,
            'invalidation_source': 16,
            'invalidation_name': 'test_policy'
        }

        if user_p or s_user_p:
            user_modes_dict = {}
            if user_p:
                user_modes_dict['mandatory'] = user_p
            if suggested_user_policies:
                user_modes_dict['recommended'] = s_user_p
            management_dict['google/chromeos/user'] = user_modes_dict

        if fixed_device_p:
            management_dict['google/chromeos/device'] = fixed_device_p

        if extension_p:
            management_dict['google/chrome/extension'] = extension_p
        logging.info('Created policy blob: %s', management_dict)
        return encode_json_string(management_dict)


    def _get_extension_policy_table(self, policy_tab, ext_id):
        """
        Find the policy table that matches the given extension ID.

        The user and device policies are in table[0]. Extension policies are
        in their own tables.

        @param policy_tab: Tab displaying the policy page.
        @param ext_id: Extension ID.

        @returns: Index of the table in the DOM.
        @raises error.TestError: if the table for the extension ID does
            not exist.

        """
        table_index = policy_tab.EvaluateJavaScript("""
        var table_id = -1;
        var section = document.getElementsByClassName('policy-table');
        for (var i = 0; i < section.length; i++) {
            var temp_name = section[i]
                .getElementsByClassName('id')[0].innerText;
            if (temp_name === "%s")
                { var table_id = i;
                  break ;
                }
           };
        table_id;
            """ % ext_id)
        if table_index == -1:
            raise error.TestError(
                    'Policy table for extension %s does not exist. '
                    'Make sure the extension is installed.' % ext_id)

        return table_index


    def reload_policies(self):
        """Force a policy fetch."""
        policy_tab = self.navigate_to_url(self.CHROME_POLICY_PAGE)
        reload_button = "document.querySelector('button#reload-policies')"
        policy_tab.ExecuteJavaScript("%s.click()" % reload_button)
        policy_tab.WaitForJavaScriptCondition("!%s.disabled" % reload_button,
                                              timeout=1)
        policy_tab.Close()


    def verify_extension_stats(self, extension_policies, sensitive_fields=[]):
        """
        Verify the extension policies match what is on chrome://policy.

        @param extension_policies: the dictionary of extension IDs mapping
            to download_url and secure_hash.
        @param sensitive_fields: list of fields that should have their value
            censored.
        @raises error.TestError: if the shown values do not match what we are
            expecting.

        """
        policy_tab = self.navigate_to_url(self.CHROME_POLICY_PAGE)
        for id in extension_policies.keys():
            table = self._get_extension_policy_table(policy_tab, id)
            download_url = extension_policies[id]['download_url']
            policy_file = os.path.join(self.enterprise_dir,
                                       download_url.split('/')[-1])

            with open(policy_file) as f:
                policies = json.load(f)

            for policy_name, settings in policies.items():
                expected_value = settings['Value']
                value_shown = self._get_policy_stats_shown(
                        policy_tab, policy_name, table)['value']

                if policy_name in sensitive_fields:
                    expected_value = '********'

                self._compare_values(policy_name, expected_value, value_shown)

        policy_tab.Close()

    def _get_policy_stats_shown(self, policy_tab, policy_name,
                                table_index=0):
        """Get the info shown for |policy_name| from the |policy_tab| page.

        Return a dict of stats for the policy given by |policy_name|, from
        from the chrome://policy page given by |policy_tab|.

        CAVEAT: the policy page does not display proper JSON. For example, lists
        are generally shown without the [ ] and cannot be distinguished from
        strings.  This function decodes what it can and returns the string it
        found when in doubt.

        @param policy_tab: Tab displaying the Policies page.
        @param policy_name: The name of the policy.
        @param table_index: Index of table in DOM to check.

        @returns: A dict of stats, including JSON decode 'value' (see caveat).
                  Also included are 'name', 'status', 'level', 'scope',
                  and 'source'.
        """
        stats = {'name': policy_name}
        row_values = policy_tab.EvaluateJavaScript('''
        var rowValues = {};
        var section = document.getElementsByClassName('policy-table')[%s];
        table = section.getElementsByClassName('main')[0];
        var pol_rows = table.getElementsByClassName('policy-data');
        for (i = 0; i < pol_rows.length; i++) {
            if (window.getComputedStyle(pol_rows[i]).display === "none")
                { break ;}
            var pol_name = pol_rows[i]
                .getElementsByClassName('policy row')[0]
                .getElementsByClassName('name')[0].innerText;
            if (pol_name === '%s'){
                var pol_data = pol_rows[i]
                    .getElementsByClassName('policy row')[0];
                var value_data = pol_rows[i]
                    .getElementsByClassName('value row')[0];
                rowValues["value"] = value_data
                    .getElementsByClassName('value')[0].innerText;
                var column_titles = ["name", "source",
                                     "scope", "level", "messages"];
                column_titles.forEach(function(entry) {
                    var entry_div = pol_data.getElementsByClassName(entry)[0];
                    rowValues[entry] = entry_div.innerText});
           };
        };
        rowValues;
        ''' % (table_index, policy_name))

        entries = ["name", "value", "source", "scope", "level", "messages"]

        # New Policy Parser returns empty, rather than 'Not Set.'. This is
        # a fix to make it compatible with the rest of the parsing code rather
        # than a larger re-write.
        if not row_values:
            for entry in entries:
                row_values[entry] = ''
            row_values['messages'] = 'Not set.'

        logging.debug('Policy %s row: %s', policy_name, row_values)
        key_diff = set(entries) - set(row_values.keys())
        if key_diff:
            raise error.TestError(
                'Could not get policy info for %s. '
                'Missing columns: %s.' % (policy_name, key_diff))

        for v in entries:
            stats[v] = row_values[v].encode('ascii', 'ignore')

        if stats['messages'] == 'Not set.':
            for v in entries:
                stats[v] = None
        else:
            stats['value'] = decode_json_string(stats['value'])

        return stats

    def _get_policy_value_from_new_tab(self, policy_name):
        """Get the policy value for |policy_name| from the Policies page.

        Information comes from the policy page.  A single new tab is opened
        and then closed to check this info, so device must be logged in.

        @param policy_name: string of policy name.

        @returns: decoded value of the policy as shown on chrome://policy.
        """
        values = self._get_policy_stats_from_new_tab([policy_name])
        return values[policy_name]['value']

    def _get_chrome_version_from_browser(self):
        """Get the Chrome Version from the ://version page.

        @returns: Version as shown on ://version page.
        """
        tab = self.navigate_to_url(self.CHROME_VERSION_PAGE)
        table_name = 'inner'
        version_box = 'version'
        version_row = 0
        return tab.EvaluateJavaScript(
            "document.getElementById('{}').rows[{}]\
            .getElementsByClassName('{}')[0].innerText"
            .format(table_name, version_row, version_box))

    def _get_policy_values_from_new_tab(self, policy_names):
        """Get the policy values of the given policies.

        Information comes from the policy page.  A single new tab is opened
        and then closed to check this info, so device must be logged in.

        @param policy_names: list of strings of policy names.

        @returns: dict of policy name mapped to decoded values of the policy as
                  shown on chrome://policy.
        """
        values = {}
        tab = self.navigate_to_url(self.CHROME_POLICY_PAGE)
        for policy_name in policy_names:
            values[policy_name] = (
                self._get_policy_stats_shown(tab, policy_name)['value'])
        tab.Close()

        return values


    def _get_policy_stats_from_new_tab(self, policy_names):
        """Get policy info about the given policy names.

        Information comes from the policy page.  A single new tab is opened
        and then closed to check this info, so device must be logged in.

        @param policy_name: list of policy names (strings).

        @returns: dict of policy names mapped to dicts containing policy info.
                  Values are decoded JSON.
        """
        stats = {}
        tab = self.navigate_to_url(self.CHROME_POLICY_PAGE)
        for policy_name in policy_names:
            stats[policy_name] = self._get_policy_stats_shown(tab, policy_name)
        tab.Close()

        return stats


    def _compare_values(self, policy_name, input_value, value_shown):
        """Pass if an expected value and the chrome://policy version match.

        Handles some of the inconsistencies in the chrome://policy JSON format.

        @param policy_name: The policy name to be verified.
        @param input_value: The setting given for the policy.
        @param value_shown: The setting of the policy from the DUT.

        @raises: error.TestError if policy values do not match.

        """
        expected_value = copy.deepcopy(input_value)

        # If we expect a list and don't have a list, modify the value_shown.
        if isinstance(expected_value, list):
            if isinstance(value_shown, str):
                if '{' in value_shown:  # List of dicts.
                    value_shown = decode_json_string('[%s]' % value_shown)
                elif ',' in value_shown:  # List of strs.
                    value_shown = value_shown.split(',')
                else:  # List with one str.
                    value_shown = [value_shown]
            elif not isinstance(value_shown, list):  # List with one element.
                value_shown = [value_shown]

        # Special case for user and device network configurations.
        # Passphrases are hidden on the policy page, so the passphrase
        # field needs to be converted to asterisks to be compared.
        SANITIZED_PASSWORD = '*' * 8
        if policy_name.endswith('OpenNetworkConfiguration'):
            for network in expected_value.get('NetworkConfigurations', []):
                wifi = network.get('WiFi', {})
                if 'Passphrase' in wifi:
                    wifi['Passphrase'] = SANITIZED_PASSWORD
                if 'EAP' in wifi and 'Password' in wifi['EAP']:
                    wifi['EAP']['Password'] = SANITIZED_PASSWORD
            for cert in expected_value.get('Certificates', []):
                if 'PKCS12' in cert:
                    cert['PKCS12'] = SANITIZED_PASSWORD

        # Some managed policies have a default value when they are not set.
        # Replace these unset policies with their default value.
        elif policy_name in DEFAULT_POLICY and expected_value is None:
            expected_value = DEFAULT_POLICY[policy_name]

        if expected_value != value_shown:
            raise error.TestError('chrome://policy shows the incorrect value '
                                  'for %s!  Expected %s, got %s.' % (
                                      policy_name, expected_value,
                                      value_shown))


    def verify_policy_value(self, policy_name, expected_value):
        """
        Verify that the a single policy correctly shows in chrome://policy.

        @param policy_name: the policy we are checking.
        @param expected_value: the expected value for policy_name.

        @raises error.TestError if value does not match expected.

        """
        value_shown = self._get_policy_value_from_new_tab(policy_name)
        self._compare_values(policy_name, expected_value, value_shown)


    def verify_policy_stats(self, user_policies={}, suggested_user_policies={},
                            device_policies={}):
        """Verify that the correct policy values show in chrome://policy.

        @param policy_dict: the policies we are checking.

        @raises error.TestError if value does not match expected.
        """
        def _compare_stat(stat, desired, name, stats):
            """ Raise error if a stat doesn't match."""
            err_str = 'Incorrect '+stat+' for '+name+': expected %s, got %s!'
            shown = stats[name][stat]
            # If policy is not set, there are no stats to match.
            if stats[name]['messages'] is None:
                if not shown == None:
                    raise error.TestError(err_str % (None, shown))
                else:
                    return
            if not desired == shown:
                raise error.TestError(err_str % (desired, shown))

        keys = (user_policies.keys() + suggested_user_policies.keys() +
                device_policies.keys())

        # If no policies were modified from default, return.
        if len(keys) == 0:
            return

        stats = self._get_policy_stats_from_new_tab(keys)

        for policy in user_policies:
            self._compare_values(policy, user_policies[policy],
                                 stats[policy]['value'])
            _compare_stat('level', 'Mandatory', policy, stats)
            _compare_stat('scope', 'Current user', policy, stats)
        for policy in suggested_user_policies:
            self._compare_values(policy, suggested_user_policies[policy],
                                 stats[policy]['value'])
            _compare_stat('level', 'Recommended', policy, stats)
            _compare_stat('scope', 'Current user', policy, stats)
        for policy in device_policies:
            self._compare_values(policy, device_policies[policy],
                                 stats[policy]['value'])
            _compare_stat('level', 'Mandatory', policy, stats)
            _compare_stat('scope', 'Device', policy, stats)


    def _initialize_chrome_extra_flags(self):
        """
        Initialize flags used to create Chrome instance.

        @returns: list of extra Chrome flags.

        """
        # Construct DM Server URL flags if not using production server.
        env_flag_list = []
        if self.env != 'prod':
            if self.dms_is_fake:
                # Use URL provided by the fake AutoTest DM server.
                dmserver_str = (DMSERVER % self.fake_dm_server.server_url)
            else:
                # Use URL defined in the DMS URL dictionary.
                dmserver_str = (DMSERVER % (DMS_URL_DICT[self.env]))
                if self.env == 'dm-test':
                    dmserver_str = (dmserver_str % self.dms_name)

            # Merge with other flags needed by non-prod enviornment.
            env_flag_list = ([dmserver_str] + FLAGS_DICT[self.env])

        return env_flag_list


    def _create_chrome(self,
                       enroll=False,
                       auto_login=True,
                       arc_mode=False,
                       init_network_controller=False,
                       disable_default_apps=True,
                       extension_paths=[],
                       extra_chrome_flags=[]):
        """
        Create a Chrome object. Enroll and/or sign in.

        Function results in self.cr set as the Chrome object.

        @param enroll: enroll the device.
        @param auto_login: sign in to chromeos.
        @param arc_mode: enable arc mode.
        @param extension_paths: list of extensions to install.
        @param init_network_controller: whether to init network controller.
        @param extra_chrome_flags: list of flags to add.
        """
        extra_flags = self._initialize_chrome_extra_flags() + extra_chrome_flags

        logging.info('Chrome Browser Arguments:')
        logging.info('  extra_browser_args: %s', extra_flags)
        logging.info('  username: %s', self.username)
        logging.info('  password: %s', self.password)
        logging.info('  gaia_login: %s', not self.dms_is_fake)

        if enroll:
            self.cr = chrome.Chrome(
                    auto_login=False,
                    extra_browser_args=extra_flags,
                    extension_paths=extension_paths,
                    expect_policy_fetch=True)
            if self.dms_is_fake:
                if self._kiosk_mode:
                    # This try is needed for kiosk; without it the test fails
                    # in telemtry code in _WaitForEnterpriseWebview. Webview
                    # never loads since it's kiosk.
                    # TODO(rzakarian): Try to modify telemetry code to not
                    # wait for Webview when in kiosk mode.
                    # http://crbug.com/934876.
                    try:
                        enrollment.EnterpriseFakeEnrollment(
                            self.cr.browser, self.username, self.password,
                            self.gaia_id, auto_login=auto_login)
                    except TimeoutException:
                        pass
                else:
                    enrollment.EnterpriseFakeEnrollment(
                        self.cr.browser, self.username, self.password,
                        self.gaia_id, auto_login=auto_login)
            else:
                enrollment.EnterpriseEnrollment(
                        self.cr.browser, self.username, self.password,
                        auto_login=auto_login)

        elif auto_login:
            if arc_mode:
                self.cr = chrome.Chrome(extension_paths=extension_paths,
                                        username=self.username,
                                        password=self.password,
                                        arc_mode=arc_mode,
                                        disable_gaia_services=False,
                                        disable_arc_opt_in=False,
                                        enterprise_arc_test=True,
                                        extra_browser_args=extra_flags)

            else:
                self.cr = chrome.Chrome(
                        extra_browser_args=extra_flags,
                        username=self.username,
                        password=self.password,
                        gaia_login=not self.dms_is_fake,
                        disable_gaia_services=self.dms_is_fake,
                        autotest_ext=True,
                        init_network_controller=init_network_controller,
                        expect_policy_fetch=True,
                        extension_paths=extension_paths,
                        disable_default_apps=disable_default_apps)
        else:
            self.cr = chrome.Chrome(
                    auto_login=False,
                    extra_browser_args=extra_flags,
                    disable_gaia_services=self.dms_is_fake,
                    autotest_ext=True,
                    expect_policy_fetch=True)

        # Used by arc.py to determine the state of the chrome obj
        self.initialized = True
        if auto_login:
            if not cryptohome.is_vault_mounted(user=self.username,
                                               allow_fail=True):
                raise error.TestError('Expected to find a mounted vault for %s.'
                                      % self.username)


    def navigate_to_url(self, url, tab=None):
        """Navigate tab to the specified |url|. Create new tab if none given.

        @param url: URL of web page to load.
        @param tab: browser tab to load (if any).
        @returns: browser tab loaded with web page.
        @raises: telemetry TimeoutException if document ready state times out.
        """
        logging.info('Navigating to URL: %r', url)
        if not tab:
            tab = self.cr.browser.tabs.New()
            tab.Activate()
        tab.Navigate(url, timeout=8)
        tab.WaitForDocumentReadyStateToBeComplete()
        return tab


    def get_elements_from_page(self, tab, cmd):
        """Get collection of page elements that match the |cmd| filter.

        @param tab: tab containing the page to be scraped.
        @param cmd: JavaScript command to evaluate on the page.
        @returns object containing elements on page that match the cmd.
        @raises: TestFail if matching elements are not found on the page.
        """
        try:
            elements = tab.EvaluateJavaScript(cmd)
        except Exception as err:
            raise error.TestFail('Unable to find matching elements on '
                                 'the test page: %s\n %r' %(tab.url, err))
        return elements

    def log_out_via_keyboard(self):
        """
        Logs out of the device using the keyboard shortcut

        """
        _keyboard = keyboard.Keyboard()
        _keyboard.press_key('ctrl+shift+q')
        _keyboard.press_key('ctrl+shift+q')
        _keyboard.close()

def encode_json_string(object_value):
    """Convert given value to JSON format string.

    @param object_value: object to be converted.

    @returns: string in JSON format.
    """
    return json.dumps(object_value)


def decode_json_string(json_string):
    """Convert given JSON format string to an object.

    If no object is found, return json_string instead.  This is to allow
    us to "decode" items off the policy page that aren't real JSON.

    @param json_string: the JSON string to be decoded.

    @returns: Python object represented by json_string or json_string.
    """
    def _decode_list(json_list):
        result = []
        for value in json_list:
            if isinstance(value, unicode):
                value = value.encode('ascii')
            if isinstance(value, list):
                value = _decode_list(value)
            if isinstance(value, dict):
                value = _decode_dict(value)
            result.append(value)
        return result

    def _decode_dict(json_dict):
        result = {}
        for key, value in json_dict.iteritems():
            if isinstance(key, unicode):
                key = key.encode('ascii')
            if isinstance(value, unicode):
                value = value.encode('ascii')
            elif isinstance(value, list):
                value = _decode_list(value)
            result[key] = value
        return result

    try:
        # Decode JSON turning all unicode strings into ascii.
        # object_hook will get called on all dicts, so also handle lists.
        result = json.loads(json_string, encoding='ascii',
                            object_hook=_decode_dict)
        if isinstance(result, list):
            result = _decode_list(result)
        return result
    except ValueError as e:
        # Input not valid, e.g. '1, 2, "c"' instead of '[1, 2, "c"]'.
        logging.warning('Could not unload: %s (%s)', json_string, e)
        return json_string
