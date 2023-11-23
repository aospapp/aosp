/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.devicepolicy.cts;

import static com.android.bedstead.metricsrecorder.truth.MetricQueryBuilderSubject.assertThat;
import static com.android.bedstead.remotedpc.RemoteDpc.DPC_COMPONENT_NAME;

import static com.google.common.truth.Truth.assertThat;

import android.app.admin.DevicePolicyManager;
import android.app.admin.RemoteDevicePolicyManager;
import android.net.http.X509TrustManagerExtensions;
import android.stats.devicepolicy.EventId;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.annotations.enterprise.CanSetPolicyTest;
import com.android.bedstead.harrier.annotations.enterprise.PositivePolicyTest;
import com.android.bedstead.harrier.policies.CaCertManagement;
import com.android.bedstead.metricsrecorder.EnterpriseMetricsRecorder;
import com.android.bedstead.nene.utils.Poll;
import com.android.compatibility.common.util.FakeKeys;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

// These tests rely on the line "android:networkSecurityConfig="@xml/network_security_config"" in
// the <application> element in the manifest.
// TODO(b/205261115): Use a testapp and query for it rather than relying on the Manifest content
@RunWith(BedsteadJUnit4.class)
public final class CaCertManagementTest {
    @ClassRule
    @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    public static final byte[] CA_CERT_1 = FakeKeys.FAKE_RSA_1.caCertificate;
    public static final byte[] CA_CERT_2 = FakeKeys.FAKE_DSA_1.caCertificate;

    @Test
    @CanSetPolicyTest(policy = CaCertManagement.class)
    public void getInstalledCaCerts_doesNotReturnNull() throws Exception {
        assertThat(sDeviceState.dpc().devicePolicyManager().getInstalledCaCerts(
                DPC_COMPONENT_NAME)).isNotNull();
    }

    @Test
    @PositivePolicyTest(policy = CaCertManagement.class)
    public void installCaCert_caCertIsInstalled() throws Exception {
        RemoteDevicePolicyManager remoteDpm = sDeviceState.dpc().devicePolicyManager();
        try {
            remoteDpm.uninstallAllUserCaCerts(DPC_COMPONENT_NAME);

            boolean result = remoteDpm.installCaCert(
                    DPC_COMPONENT_NAME, CA_CERT_1);

            assertThat(result).isTrue();
            assertCaCertInstalledForTheDpcAndLocally(CA_CERT_1);
        } finally {
            remoteDpm.uninstallAllUserCaCerts(DPC_COMPONENT_NAME);
        }
    }

    @Test
    @PositivePolicyTest(policy = CaCertManagement.class)
    public void installCaCert_logsEvent() throws Exception {
        RemoteDevicePolicyManager remoteDpm = sDeviceState.dpc().devicePolicyManager();
        try {
            remoteDpm.uninstallAllUserCaCerts(DPC_COMPONENT_NAME);

            try (EnterpriseMetricsRecorder metrics = EnterpriseMetricsRecorder.create()) {
                remoteDpm.installCaCert(DPC_COMPONENT_NAME, CA_CERT_1);

                assertThat(metrics.query()
                        .whereType().isEqualTo(EventId.INSTALL_CA_CERT_VALUE)
                        .whereAdminPackageName().isEqualTo(DPC_COMPONENT_NAME.getPackageName())
                        .whereBoolean().isFalse()
                ).wasLogged();
            }
        } finally {
            remoteDpm.uninstallAllUserCaCerts(DPC_COMPONENT_NAME);
        }
    }

    @Test
    @PositivePolicyTest(policy = CaCertManagement.class)
    public void uninstallCaCert_caCertIsNotInstalled() throws Exception {
        RemoteDevicePolicyManager remoteDpm = sDeviceState.dpc().devicePolicyManager();
        try {
            remoteDpm.uninstallAllUserCaCerts(DPC_COMPONENT_NAME);
            remoteDpm.installCaCert(DPC_COMPONENT_NAME, CA_CERT_1);

            remoteDpm.uninstallCaCert(DPC_COMPONENT_NAME, CA_CERT_1);

            assertCaCertNotInstalledForTheDpcOrLocally(CA_CERT_1);
        } finally {
            remoteDpm.uninstallAllUserCaCerts(DPC_COMPONENT_NAME);
        }
    }

    @Test
    @PositivePolicyTest(policy = CaCertManagement.class)
    public void uninstallCaCert_otherCaCertsAreNotUninstalled() throws Exception {
        RemoteDevicePolicyManager remoteDpm = sDeviceState.dpc().devicePolicyManager();
        try {
            remoteDpm.uninstallAllUserCaCerts(DPC_COMPONENT_NAME);
            remoteDpm.installCaCert(DPC_COMPONENT_NAME, CA_CERT_1);
            remoteDpm.installCaCert(DPC_COMPONENT_NAME, CA_CERT_2);

            remoteDpm.uninstallCaCert(DPC_COMPONENT_NAME, CA_CERT_1);

            assertCaCertInstalledForTheDpcAndLocally(CA_CERT_2);
        } finally {
            remoteDpm.uninstallAllUserCaCerts(DPC_COMPONENT_NAME);
        }
    }

    @Test
    @PositivePolicyTest(policy = CaCertManagement.class)
    public void uninstallCaCert_logsEvent() throws Exception {
        RemoteDevicePolicyManager remoteDpm = sDeviceState.dpc().devicePolicyManager();
        try {
            remoteDpm.uninstallAllUserCaCerts(DPC_COMPONENT_NAME);
            try (EnterpriseMetricsRecorder metrics = EnterpriseMetricsRecorder.create()) {
                remoteDpm.installCaCert(
                        DPC_COMPONENT_NAME, CA_CERT_1);

                remoteDpm.uninstallCaCert(
                        DPC_COMPONENT_NAME, CA_CERT_1);

                assertThat(metrics.query()
                        .whereType().isEqualTo(EventId.UNINSTALL_CA_CERTS_VALUE)
                        .whereAdminPackageName().isEqualTo(DPC_COMPONENT_NAME.getPackageName())
                        .whereBoolean().isFalse()
                ).wasLogged();
            }
        } finally {
            remoteDpm.uninstallAllUserCaCerts(DPC_COMPONENT_NAME);
        }
    }

    @Test
    @PositivePolicyTest(policy = CaCertManagement.class)
    public void uninstallAllUserCaCerts_uninstallsAllCaCerts()
            throws Exception {
        RemoteDevicePolicyManager remoteDpm = sDeviceState.dpc().devicePolicyManager();
        try {
            remoteDpm.uninstallAllUserCaCerts(DPC_COMPONENT_NAME);
            remoteDpm.installCaCert(DPC_COMPONENT_NAME, CA_CERT_1);
            remoteDpm.installCaCert(DPC_COMPONENT_NAME, CA_CERT_2);

            remoteDpm.uninstallAllUserCaCerts(DPC_COMPONENT_NAME);

            assertCaCertNotInstalledForTheDpcOrLocally(CA_CERT_1);
            assertCaCertNotInstalledForTheDpcOrLocally(CA_CERT_2);
        } finally {
            remoteDpm.uninstallAllUserCaCerts(DPC_COMPONENT_NAME);
        }
    }

    private void assertCaCertInstalledForTheDpcAndLocally(byte[] caBytes)
            throws GeneralSecurityException {
        assertCaCertInstalledAndTrusted(caBytes, /* installed= */ true);
    }

    private void assertCaCertNotInstalledForTheDpcOrLocally(byte[] caBytes)
            throws GeneralSecurityException {
        assertCaCertInstalledAndTrusted(caBytes, /* installed= */ false);
    }

    /**
     * Whether a given cert, or one a lot like it, has been installed system-wide and is available
     * to all apps.
     *
     * <p>A CA certificate is "installed" if it matches all of the following conditions:
     * <ul>
     *   <li>{@link DevicePolicyManager#hasCaCertInstalled} returns {@code true}.</li>
     *   <li>{@link DevicePolicyManager#getInstalledCaCerts} lists a matching certificate (not
     *       necessarily exactly the same) in its response.</li>
     *   <li>Any new instances of {@link TrustManager} should report the certificate among their
     *       accepted issuer list -- older instances may keep the set of issuers they were created
     *       with until explicitly refreshed.</li>
     *
     */
    private void assertCaCertInstalledAndTrusted(byte[] caBytes, boolean installed)
            throws GeneralSecurityException {
        RemoteDevicePolicyManager remoteDpm = sDeviceState.dpc().devicePolicyManager();
        Certificate caCert = readCertificate(caBytes);
        // All three responses should match - if an installed certificate isn't trusted or (worse)
        // a trusted certificate isn't even installed we should fail now, loudly.
        boolean isInstalled = remoteDpm.hasCaCertInstalled(DPC_COMPONENT_NAME, caCert.getEncoded());
        assertThat(isInstalled).isEqualTo(installed);

        assertThat(isCaCertListed(caCert)).isEqualTo(installed);

        assertCaCertTrustStatusChange(caCert, isInstalled);

        X509TrustManagerExtensions xtm = new X509TrustManagerExtensions(getFirstX509TrustManager());
        boolean userAddedCertificate = xtm.isUserAddedCertificate((X509Certificate) caCert);
        assertThat(userAddedCertificate).isEqualTo(installed);
    }

    /**
     * Convert an encoded certificate back into a {@link Certificate}.
     *
     * Instantiates a fresh CertificateFactory every time for repeatability.
     */
    private static Certificate readCertificate(byte[] certBuffer) throws CertificateException {
        final CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        return certFactory.generateCertificate(new ByteArrayInputStream(certBuffer));
    }

    private boolean isCaCertListed(Certificate caCert) throws CertificateException {
        boolean listed = false;
        RemoteDevicePolicyManager remoteDpm = sDeviceState.dpc().devicePolicyManager();
        for (byte[] certBuffer : remoteDpm.getInstalledCaCerts(DPC_COMPONENT_NAME)) {
            if (caCert.equals(readCertificate(certBuffer))) {
                listed = true;
            }
        }
        return listed;
    }

    private void assertCaCertTrustStatusChange(Certificate caCert, boolean newStatus)
            throws GeneralSecurityException {
        // Verify that the user added CA is reflected in the default X509TrustManager.
        X509TrustManager tm = getFirstX509TrustManager();

        Poll.forValue("Accepted issuers", () -> Arrays.asList(tm.getAcceptedIssuers()))
                .toMeet((acceptedIssuers) -> newStatus == acceptedIssuers.contains(caCert))
                .errorOnFail(
                        newStatus ? "Couldn't find certificate in trusted certificates list."
                        : "Found uninstalled certificate in trusted certificates list.")
                .await();
    }

    private static X509TrustManager getFirstX509TrustManager() throws GeneralSecurityException {
        final TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        // Use platform provided CA store.
        tmf.init((KeyStore) null);
        return getFirstX509TrustManager(tmf);
    }

    private static X509TrustManager getFirstX509TrustManager(TrustManagerFactory tmf) {
        for (TrustManager trustManager : tmf.getTrustManagers()) {
            if (trustManager instanceof X509TrustManager) {
                return (X509TrustManager) trustManager;
            }
        }
        throw new RuntimeException("Unable to find X509TrustManager");
    }
}
