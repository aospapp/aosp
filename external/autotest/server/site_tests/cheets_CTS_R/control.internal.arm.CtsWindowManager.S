# Copyright 2020 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

# This file has been automatically generated. Do not edit!

AUTHOR = 'ARC++ Team'
NAME = 'cheets_CTS_R.internal.arm.CtsWindowManager.S'
ATTRIBUTES = 'suite:arc-cts-r, suite:arc-cts'
DEPENDENCIES = 'arc'
JOB_RETRIES = 1
TEST_TYPE = 'server'
TIME = 'MEDIUM'
MAX_RESULT_SIZE_KB = 512000
PY_VERSION = 3
DOC = 'Run module CtsWindowManager.S of the Android Compatibility Test Suite (CTS) using arm ABI in the ARC++ container.'

def run_TS(machine):
    host_list = [hosts.create_host(machine)]
    job.run_test(
        'cheets_CTS_R',
        hosts=host_list,
        iterations=1,
        tag='internal.arm.CtsWindowManager.S',
        test_name='cheets_CTS_R.internal.arm.CtsWindowManager.S',
        run_template=['run', 'commandAndExit', 'cts', '--include-filter', 'CtsWindowManagerDeviceTestCases android.server.wm.SplashscreenTests', '--include-filter', 'CtsWindowManagerDeviceTestCases android.server.wm.SplitScreenTests', '--include-filter', 'CtsWindowManagerDeviceTestCases android.server.wm.StartActivityAsUserTests', '--include-filter', 'CtsWindowManagerDeviceTestCases android.server.wm.StartActivityTests', '--include-filter', 'CtsWindowManagerDeviceTestCases android.server.wm.SurfaceControlTest', '--include-filter', 'CtsWindowManagerDeviceTestCases android.server.wm.SurfaceControlViewHostTests', '--include-filter', 'CtsWindowManagerDeviceTestCases android.server.wm.SurfaceViewSurfaceValidatorTest', '--include-filter', 'CtsWindowManagerDeviceTestCases android.server.wm.SurfaceViewTest', '--logcat-on-failure'],
        retry_template=['run', 'commandAndExit', 'retry', '--retry', '{session_id}'],
        target_module='CtsWindowManagerDeviceTestCases',
        target_plan=None,
        bundle='arm',
        uri='LATEST',
        use_jdk9=True,
        timeout=3600)

parallel_simple(run_TS, machines)
