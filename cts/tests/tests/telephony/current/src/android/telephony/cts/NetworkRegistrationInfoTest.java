/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.telephony.cts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

import android.telephony.AccessNetworkConstants;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.TelephonyManager;

import java.util.Arrays;

import org.junit.Test;

public class NetworkRegistrationInfoTest {

    @Test
    public void testDescribeContents() {
        NetworkRegistrationInfo networkRegistrationInfo = new NetworkRegistrationInfo.Builder()
                .build();
        assertEquals(0, networkRegistrationInfo.describeContents());
    }

    @Test
    public void testEquals() {
        NetworkRegistrationInfo nri1 = new NetworkRegistrationInfo.Builder()
                .setDomain(NetworkRegistrationInfo.DOMAIN_CS)
                .setAccessNetworkTechnology(TelephonyManager.NETWORK_TYPE_UMTS)
                .setEmergencyOnly(false)
                .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                .build();

        NetworkRegistrationInfo nri2 = new NetworkRegistrationInfo.Builder()
                .setDomain(NetworkRegistrationInfo.DOMAIN_CS)
                .setAccessNetworkTechnology(TelephonyManager.NETWORK_TYPE_UMTS)
                .setEmergencyOnly(false)
                .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                .build();

        NetworkRegistrationInfo nri3 = new NetworkRegistrationInfo.Builder()
                .setDomain(NetworkRegistrationInfo.DOMAIN_PS)
                .setAccessNetworkTechnology(TelephonyManager.NETWORK_TYPE_IWLAN)
                .setEmergencyOnly(false)
                .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_WLAN)
                .build();

        assertEquals(nri1.hashCode(), nri2.hashCode());
        assertEquals(nri1, nri2);

        assertNotSame(nri1.hashCode(), nri3.hashCode());
        assertNotSame(nri1, nri3);

        assertNotSame(nri2.hashCode(), nri3.hashCode());
        assertNotSame(nri2, nri3);
    }

    @Test
    public void testGetAccessNetworkTechnology() {
        NetworkRegistrationInfo nri = new NetworkRegistrationInfo.Builder()
                .setAccessNetworkTechnology(TelephonyManager.NETWORK_TYPE_EHRPD)
                .build();
        assertEquals(TelephonyManager.NETWORK_TYPE_EHRPD, nri.getAccessNetworkTechnology());
    }

    @Test
    public void testGetAvailableServices() {
        NetworkRegistrationInfo nri = new NetworkRegistrationInfo.Builder()
                .setAvailableServices(Arrays.asList(NetworkRegistrationInfo.SERVICE_TYPE_DATA,
                        NetworkRegistrationInfo.SERVICE_TYPE_VIDEO))
                .build();
        assertEquals(Arrays.asList(NetworkRegistrationInfo.SERVICE_TYPE_DATA,
                NetworkRegistrationInfo.SERVICE_TYPE_VIDEO), nri.getAvailableServices());
    }

    @Test
    public void testGetDomain() {
        NetworkRegistrationInfo nri = new NetworkRegistrationInfo.Builder()
                .setDomain(NetworkRegistrationInfo.DOMAIN_CS)
                .build();
        assertEquals(NetworkRegistrationInfo.DOMAIN_CS, nri.getDomain());
    }

    @Test
    public void testRegistrationState() {
        NetworkRegistrationInfo nri = new NetworkRegistrationInfo.Builder()
                .setRegistrationState(NetworkRegistrationInfo.REGISTRATION_STATE_HOME)
                .build();
        assertEquals(NetworkRegistrationInfo.REGISTRATION_STATE_HOME, nri.getRegistrationState());
    }

    @Test
    public void testGetTransportType() {
        NetworkRegistrationInfo nri = new NetworkRegistrationInfo.Builder()
                .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                .build();
        assertEquals(AccessNetworkConstants.TRANSPORT_TYPE_WWAN, nri.getTransportType());
    }
}
