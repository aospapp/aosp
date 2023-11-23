/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.cts.statsdatom.usb;

import static android.service.ComplianceWarning.COMPLIANCE_WARNING_OTHER;
import static android.service.ComplianceWarning.COMPLIANCE_WARNING_BC_1_2;
import static android.service.ComplianceWarning.COMPLIANCE_WARNING_DEBUG_ACCESSORY;
import static android.service.ComplianceWarning.COMPLIANCE_WARNING_MISSING_RP;
import static android.service.ComplianceWarning.COMPLIANCE_WARNING_UNSPECIFIED;

import static com.google.common.truth.Truth.assertThat;

import android.cts.statsdatom.lib.AtomTestUtils;
import android.cts.statsdatom.lib.ConfigUtils;
import android.cts.statsdatom.lib.DeviceUtils;
import android.cts.statsdatom.lib.ReportUtils;
import android.service.ComplianceWarning;
import android.service.ServiceProtoEnums;


import com.android.os.AtomsProto;
import com.android.os.StatsLog;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.log.LogUtil;
import com.android.tradefed.testtype.DeviceTestCase;
import com.android.tradefed.testtype.IBuildReceiver;
import com.android.tradefed.util.RunUtil;

import java.util.Arrays;
import java.util.List;

public class UsbStatsTests extends DeviceTestCase implements IBuildReceiver {
    private IBuildInfo mCtsBuild;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        assertThat(mCtsBuild).isNotNull();
        ConfigUtils.removeConfig(getDevice());
        ReportUtils.clearReports(getDevice());
        DeviceUtils.installStatsdTestApp(getDevice(), mCtsBuild);
        resetSimulatedUsbPorts();
        RunUtil.getDefault().sleep(AtomTestUtils.WAIT_TIME_LONG);
    }

    @Override
    protected void tearDown() throws Exception {
        ConfigUtils.removeConfig(getDevice());
        ReportUtils.clearReports(getDevice());
        DeviceUtils.uninstallStatsdTestApp(getDevice());
        super.tearDown();
    }

    @Override
    public void setBuild(IBuildInfo buildInfo) {
        mCtsBuild = buildInfo;
    }

    private void createSimulatedUsbPort() throws Exception {
        getDevice().executeShellCommand(
                "dumpsys usb add-port UsbStatsTests dual --compliance-warnings");
    }

    private void resetSimulatedUsbPorts() throws Exception {
        getDevice().executeShellCommand(
                "dumpsys usb reset");
    }

    private void simulateComplianceWarnings(ComplianceWarning[] warnings) throws Exception {
        StringBuilder warningsString = new StringBuilder();

        warningsString.append("\"");
        for (ComplianceWarning warning : warnings) {
            warningsString.append(warning.getNumber() + ", ");
        }
        warningsString.append("\"");
        getDevice().executeShellCommand(
                "dumpsys usb set-compliance-reasons UsbStatsTests " + warningsString.toString());
    }

    /**
     * Tests that each compliance warning gets logged
     */
    public void testUsbComplianceWarnings() throws Exception {
        ComplianceWarning[] warnings = new ComplianceWarning[] {
                COMPLIANCE_WARNING_DEBUG_ACCESSORY,
                COMPLIANCE_WARNING_BC_1_2,
                COMPLIANCE_WARNING_MISSING_RP,
                COMPLIANCE_WARNING_OTHER
        };

        ConfigUtils.uploadConfigForPushedAtom(getDevice(), DeviceUtils.STATSD_ATOM_TEST_PKG,
                AtomsProto.Atom.USB_COMPLIANCE_WARNINGS_REPORTED_FIELD_NUMBER);

        createSimulatedUsbPort();

        // Trigger Compliance Warnings
        simulateComplianceWarnings(warnings);
        RunUtil.getDefault().sleep(AtomTestUtils.WAIT_TIME_SHORT);

        List<StatsLog.EventMetricData> eventMetrics =
                ReportUtils.getEventMetricDataList(getDevice());
        assertThat(eventMetrics.size()).isEqualTo(1);

        // Retrieve atom and compliance warning list
        AtomsProto.Atom atom = eventMetrics.get(0).getAtom();
        List<ComplianceWarning> pushedComplianceWarnings =
                atom.getUsbComplianceWarningsReported().getComplianceWarningsList();
        assertThat(pushedComplianceWarnings).isNotEmpty();
        assertThat(pushedComplianceWarnings).containsExactly(
                COMPLIANCE_WARNING_DEBUG_ACCESSORY,
                COMPLIANCE_WARNING_BC_1_2,
                COMPLIANCE_WARNING_MISSING_RP,
                COMPLIANCE_WARNING_OTHER
        );

        resetSimulatedUsbPorts();
    }
}
