# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.
import common
import os
import unittest

import tradefed_test


def _load_data(filename):
    """Loads the test data of the given file name."""
    with open(os.path.join(os.path.dirname(os.path.realpath(__file__)),
                           'tradefed_test_unittest_data', filename), 'r') as f:
        return f.read()


class TradefedTestTest(unittest.TestCase):
    """Unittest for tradefed_test."""

    def test_parse_tradefed_result(self):
        """Test for parse_tradefed_result."""

        waivers = set([
            'android.app.cts.SystemFeaturesTest#testUsbAccessory',
            'android.widget.cts.GridViewTest#testSetNumColumns',
        ])

        # b/35605415 and b/36520623
        # http://pantheon/storage/browser/chromeos-autotest-results/108103986-chromeos-test/
        # CTS: Tradefed may split a module to multiple chunks.
        # Besides, the module name may not end with "TestCases".
        waived, _ = tradefed_test.parse_tradefed_result(
            _load_data('CtsHostsideNetworkTests.txt'),
            waivers=waivers)
        self.assertEquals(0, len(waived))

        # b/35530394
        # http://pantheon/storage/browser/chromeos-autotest-results/108291418-chromeos-test/
        # Crashed, but the automatic retry by tradefed executed the rest.
        waived, _ = tradefed_test.parse_tradefed_result(
            _load_data('CtsMediaTestCases.txt'),
            waivers=waivers)
        self.assertEquals(0, len(waived))

        # b/35530394
        # http://pantheon/storage/browser/chromeos-autotest-results/106540705-chromeos-test/
        # Crashed in the middle, and the device didn't came back.
        waived, _ = tradefed_test.parse_tradefed_result(
            _load_data('CtsSecurityTestCases.txt'),
            waivers=waivers)
        self.assertEquals(0, len(waived))

        # b/36629187
        # http://pantheon/storage/browser/chromeos-autotest-results/108855595-chromeos-test/
        # Crashed in the middle. Tradefed decided not to continue.
        waived, _ = tradefed_test.parse_tradefed_result(
            _load_data('CtsViewTestCases.txt'),
            waivers=waivers)
        self.assertEquals(0, len(waived))

        # b/36375690
        # http://pantheon/storage/browser/chromeos-autotest-results/109040174-chromeos-test/
        # Mixture of real failures and waivers.
        waived, _ = tradefed_test.parse_tradefed_result(
            _load_data('CtsAppTestCases.txt'),
            waivers=waivers)
        self.assertEquals(1, len(waived))
        # ... and the retry of the above failing iteration.
        waived, _ = tradefed_test.parse_tradefed_result(
            _load_data('CtsAppTestCases-retry.txt'),
            waivers=waivers)
        self.assertEquals(1, len(waived))

        # http://pantheon/storage/browser/chromeos-autotest-results/116875512-chromeos-test/
        # When a test case crashed during teardown, tradefed prints the "fail"
        # message twice. Tolerate it and still return an (inconsistent) count.
        waived, _ = tradefed_test.parse_tradefed_result(
            _load_data('CtsWidgetTestCases.txt'),
            waivers=waivers)
        self.assertEquals(1, len(waived))

        # http://pantheon/storage/browser/chromeos-autotest-results/117914707-chromeos-test/
        # When a test case unrecoverably crashed during teardown, tradefed
        # prints the "fail" and failure summary message twice. Tolerate it.
        waived, _ = tradefed_test.parse_tradefed_result(
            _load_data('CtsPrintTestCases.txt'),
            waivers=waivers)
        self.assertEquals(0, len(waived))

        gts_waivers = set([
            ('com.google.android.placement.gts.CoreGmsAppsTest#' +
                'testCoreGmsAppsPreloaded'),
            ('com.google.android.placement.gts.CoreGmsAppsTest#' +
                'testGoogleDuoPreloaded'),
            'com.google.android.placement.gts.UiPlacementTest#testPlayStore'
        ])

        # crbug.com/748116
        # http://pantheon/storage/browser/chromeos-autotest-results/130080763-chromeos-test/
        # 3 ABIS: x86, x86_64, and armeabi-v7a
        waived, _ = tradefed_test.parse_tradefed_result(
            _load_data('GtsPlacementTestCases.txt'),
            waivers=gts_waivers)
        self.assertEquals(9, len(waived))

        # b/64095702
        # http://pantheon/storage/browser/chromeos-autotest-results/130211812-chromeos-test/
        # The result of the last chunk not reported by tradefed.
        # The actual dEQP log is too big, hence the test data here is trimmed.
        waived, _ = tradefed_test.parse_tradefed_result(
            _load_data('CtsDeqpTestCases-trimmed.txt'),
            waivers=waivers)
        self.assertEquals(0, len(waived))

        # b/80160772
        # http://pantheon/storage/browser/chromeos-autotest-results/201962931-kkanchi/
        # The newer tradefed requires different parsing to count waivers.
        waived, _ = tradefed_test.parse_tradefed_result(
            _load_data('CtsAppTestCases_P_simplified.txt'),
            waivers=waivers)
        self.assertEquals(1, len(waived))

        # b/66899135, tradefed may reported inaccuratly with `list results`.
        # Check if summary section shows that the result is inacurrate.
        _, accurate = tradefed_test.parse_tradefed_result(
            _load_data('CtsAppTestCases_P_simplified.txt'),
            waivers=waivers)
        self.assertTrue(accurate)

        _, accurate = tradefed_test.parse_tradefed_result(
            _load_data('CtsDeqpTestCases-trimmed-inaccurate.txt'),
            waivers=waivers)
        self.assertFalse(accurate)

if __name__ == '__main__':
    unittest.main()
