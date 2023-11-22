# Copyright 2015 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import os
import logging
import re

import common
from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib import global_config
from autotest_lib.client.common_lib.cros import dev_server
from autotest_lib.server import test
from autotest_lib.server.hosts import adb_host
from autotest_lib.server.hosts import host_info
from autotest_lib.site_utils import acts_lib
from server.cros import dnsname_mangler

CONFIG = global_config.global_config

CONFIG_FOLDER_LOCATION = global_config.global_config.get_config_value(
    'ACTS', 'acts_config_folder', default='')

TEST_CONFIG_FILE_FOLDER = os.path.join(CONFIG_FOLDER_LOCATION,
                                       'autotest_config')

TEST_CAMPAIGN_FILE_FOLDER = os.path.join(CONFIG_FOLDER_LOCATION,
                                         'autotest_campaign')
DEFAULT_TEST_RELATIVE_LOG_PATH = 'results/logs'


def get_global_config_value_regex(section, regex):
    """Get config values from global config based on regex of the key.

    @param section: Section of the config, e.g., CLIENT.
    @param regex: Regular expression of the key pattern.

    @return: A dictionary of all config values matching the regex. Value is
             assumed to be comma separated, and is converted to a list.
    """
    configs = CONFIG.get_config_value_regex(section, regex)
    result = {}
    for key, value in configs.items():
        match = re.match(regex, key)
        result[match.group(1)] = [v.strip() for v in value.split(',')
                                  if v.strip()]
    return result


class android_ACTS(test.test):
    """Run an Android CTS test case.

    Component relationship:
    Workstation ----(ssh)---> TestStation -----(adb)-----> Android DUT
    This code runs on Workstation.
    """
    version = 1

    BRANCH_ALIAS_PATTERN = 'acts_branch_alias_(.*)'
    aliases_map = get_global_config_value_regex('ACTS', BRANCH_ALIAS_PATTERN)

    def run_once(self,
                 testbed=None,
                 config_file=None,
                 testbed_name=None,
                 test_case=None,
                 test_file=None,
                 additional_configs=[],
                 additional_apks=[],
                 override_build_url=None,
                 override_build=None,
                 override_acts_zip=None,
                 override_internal_acts_dir=None,
                 override_python_bin='python',
                 acts_timeout=7200,
                 perma_path=None,
                 additional_cmd_line_params=None,
                 branch_mappings={},
                 valid_job_urls_only=False,
                 testtracker_project_id=None,
                 testtracker_extra_env=None,
                 testtracker_owner=None):
        """Runs an acts test case.

        @param testbed: The testbed to test on.
        @config_file: The main config file to use for running the test. This
                      should be relative to the autotest_config folder.
        @param test_case: The test case to run. Should be None when test_file
                          is given.
        @param test_file: The campaign file to run. Should be None when
                          test_case is given. This should be relative to the
                          autotest_campaign folder. If multiple are given,
                          multiple test cases will be run.
        @param additional_configs: Any additional config files to use.
                                   These should be relative to the
                                   autotest_config folder.
        @param additional_apks: An array of apk info dictionaries.
                                apk = Name of the apk (eg. sl4a.apk)
                                package = Name of the package (eg. test.tools)
                                artifact = Name of the artifact, if not given
                                           package is used.
        @param override_build_url: Deprecated, use override_build instead.
        @param override_build: The android build to fetch test artifacts from.
                               If not provided a default is selected from one
                               of the devices.
        @param override_acts_zip: If given a zip file on the drone is used
                                  rather than pulling a build.
        @param override_internal_acts_dir: The directory within the artifact
                                           where the acts framework folder
                                           lives.
        @param override_python_bin: Overrides the default python binary that
                                    is used.
        @param acts_timeout: How long to wait for acts to finish.
        @param valid_job_urls_only: Apps and resources will be downloaded and
                                    installed only on devices that have valid
                                    job urls.
        @param perma_path: If given a permantent path will be used rather than
                           a temp path.
        @para branch_mappings: A dictionary of branch names to branch names.
                               When pulling test resources, if the current
                               branch is found in the mapping it will use
                               the mapped branch instead.
        @param testtracker_project_id: ID to use for test tracker project.
        @param testtracker_extra_env: Extra environment info to publish
                                      with the results.
        """
        hostname = testbed.hostname
        if not testbed_name:
            if dnsname_mangler.is_ip_address(hostname):
                testbed_name = hostname
            else:
                testbed_name = hostname.split('.')[0]

        logging.info('Using testbed name %s', testbed_name)

        if not override_build:
            override_build = override_build_url

        valid_hosts = []
        if valid_job_urls_only:
            for v in testbed.get_adb_devices().values():
                try:
                    info = v.host_info_store.get()
                except host_info.StoreError:
                    pass
                else:
                    if v.job_repo_url_attribute in info.attributes:
                        valid_hosts.append(v)
        else:
            valid_hosts = list(testbed.get_adb_devices().values())

        if not valid_hosts:
            raise error.TestError('No valid hosts defined for this test, cannot'
                                  ' determine build to grab artifact from.')

        primary_host = valid_hosts[0]

        info = primary_host.host_info_store.get()
        job_repo_url = info.attributes.get(primary_host.job_repo_url_attribute,
                                           '')
        test_station = testbed.teststation
        if not perma_path:
            ts_tempfolder = test_station.get_tmp_dir()
        else:
            test_station.run('rm -fr "%s"' % perma_path)
            test_station.run('mkdir "%s"' % perma_path)
            ts_tempfolder = perma_path
        target_zip = os.path.join(ts_tempfolder, 'acts.zip')

        if override_build:
            build_pieces = override_build.split('/')
            job_build_branch = build_pieces[0]
            job_build_target = build_pieces[1]
            job_build_id = build_pieces[2]
        else:
            job_build_info = adb_host.ADBHost.get_build_info_from_build_url(
                    job_repo_url)
            job_build_branch = job_build_info['branch']
            job_build_target = job_build_info['target']
            job_build_id = job_build_info['build_id']

        if not override_build_url:
            if job_build_branch in branch_mappings:
                logging.info('Replacing branch %s -> %s',
                             job_build_branch,
                             branch_mappings[job_build_branch].strip())
                job_build_branch = branch_mappings[job_build_branch].strip()
                job_build_id = "LATEST"
            elif job_build_branch in self.aliases_map:
                logging.info('Replacing branch %s -> %s',
                             job_build_branch,
                             self.aliases_map[job_build_branch][0].strip())
                job_build_branch = self.aliases_map[job_build_branch][0].strip()
                job_build_id = "LATEST"

        build_name = '%s/%s/%s' % (job_build_branch,
                                   job_build_target,
                                   job_build_id)
        devserver = dev_server.AndroidBuildServer.resolve(build_name,
                                                          primary_host.hostname)
        build_name = devserver.translate(build_name)
        build_branch, build_target, build_id = build_name.split('/')

        logging.info('Using build info BRANCH:%s, TARGET:%s, BUILD_ID:%s',
                     build_branch, build_target, build_id)

        if override_acts_zip:
            package = acts_lib.create_acts_package_from_zip(test_station,
                                                            override_acts_zip,
                                                            target_zip)
        else:
            package = acts_lib.create_acts_package_from_artifact(test_station,
                                                                 build_branch,
                                                                 build_target,
                                                                 build_id,
                                                                 devserver,
                                                                 target_zip)

        test_env = package.create_environment(
                container_directory=ts_tempfolder,
                testbed_name=testbed_name,
                devices=valid_hosts,
                internal_acts_directory=override_internal_acts_dir)

        test_env.install_sl4a_apk()

        for apk in additional_apks:
            test_env.install_apk(apk)

        test_env.setup_enviroment(python_bin=override_python_bin)

        test_env.upload_config(config_file)

        if additional_configs:
            for additional_config in additional_configs:
                test_env.upload_config(additional_config)

        if test_file:
            test_env.upload_campaign(test_file)

        results = test_env.run_test(
                config_file,
                campaign=test_file,
                test_case=test_case,
                python_bin=override_python_bin,
                timeout=acts_timeout,
                additional_cmd_line_params=additional_cmd_line_params)

        results.log_output()
        results.report_to_autotest(self)
        results.save_test_info(self)
        results.rethrow_exception()
