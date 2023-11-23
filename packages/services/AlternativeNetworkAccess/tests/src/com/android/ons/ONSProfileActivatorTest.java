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

package com.android.ons;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;

import android.content.Context;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Looper;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.euicc.EuiccManager;
import android.util.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

public class ONSProfileActivatorTest extends ONSBaseTest {
    private static final String TAG = ONSProfileActivatorTest.class.getName();

    @Mock
    Context mMockContext;
    @Mock
    SubscriptionManager mMockSubManager;
    @Mock
    EuiccManager mMockEuiccManager;
    @Mock
    TelephonyManager mMockTeleManager;
    @Mock
    ConnectivityManager mMockConnectivityManager;
    @Mock
    CarrierConfigManager mMockCarrierConfigManager;
    @Mock
    ONSProfileConfigurator mMockONSProfileConfigurator;
    @Mock
    ONSProfileDownloader mMockONSProfileDownloader;
    @Mock
    List<SubscriptionInfo> mMockactiveSubInfos;
    @Mock
    SubscriptionInfo mMockSubInfo;
    @Mock
    SubscriptionInfo mMockSubInfo2;
    @Mock
    List<SubscriptionInfo> mMocksubsInPSIMGroup;
    @Mock
    Resources mMockResources;

    @Before
    public void setUp() throws Exception {
        super.setUp("ONSTest");
        MockitoAnnotations.initMocks(this);
        Looper.prepare();
        doReturn(TelephonyManager.SIM_STATE_READY).when(mMockTeleManager).getSimState();
        doReturn(mMockResources).when(mMockContext).getResources();

        doReturn(mMockConnectivityManager).when(mMockContext).getSystemService(
                Context.CONNECTIVITY_SERVICE);
        NetworkRequest request = new NetworkRequest.Builder().addCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED).build();
        doNothing().when(mMockConnectivityManager).registerNetworkCallback(request,
                new ConnectivityManager.NetworkCallback());
    }

    /*@Test
    public void testSIMNotReady() {
        doReturn(TelephonyManager.SIM_STATE_NOT_READY).when(mMockTeleManager).getSimState();

        ONSProfileActivator onsProfileActivator = new ONSProfileActivator(mMockContext,
                mMockSubManager, mMockTeleManager, mMockCarrierConfigManager, mMockEuiccManager,
                mMockConnectivityManager, mMockONSProfileConfigurator, mMockONSProfileDownloader);

        assertEquals(ONSProfileActivator.Result.ERR_SIM_NOT_READY,
                onsProfileActivator.handleSimStateChange());
    }*/

    @Test
    public void testONSAutoProvisioningDisabled() {
        doReturn(TelephonyManager.SIM_STATE_READY).when(mMockTeleManager).getSimState();
        doReturn(false).when(mMockResources).getBoolean(R.bool.enable_ons_auto_provisioning);

        ONSProfileActivator onsProfileActivator = new ONSProfileActivator(mMockContext,
                mMockSubManager, mMockTeleManager, mMockCarrierConfigManager, mMockEuiccManager,
                mMockConnectivityManager, mMockONSProfileConfigurator, mMockONSProfileDownloader);

        assertEquals(ONSProfileActivator.Result.ERR_AUTO_PROVISIONING_DISABLED,
                onsProfileActivator.handleCarrierConfigChange());
    }

    @Test
    public void testESIMNotSupported() {
        doReturn(TelephonyManager.SIM_STATE_READY).when(mMockTeleManager).getSimState();
        doReturn(true).when(mMockResources).getBoolean(R.bool.enable_ons_auto_provisioning);
        doReturn(false).when(mMockEuiccManager).isEnabled();

        ONSProfileActivator onsProfileActivator = new ONSProfileActivator(mMockContext,
                mMockSubManager, mMockTeleManager, mMockCarrierConfigManager, mMockEuiccManager,
                mMockConnectivityManager, mMockONSProfileConfigurator, mMockONSProfileDownloader);

        assertEquals(ONSProfileActivator.Result.ERR_ESIM_NOT_SUPPORTED,
                onsProfileActivator.handleCarrierConfigChange());
    }

    @Test
    //@DisplayName("Single SIM Device with eSIM support")
    public void testMultiSIMNotSupported() {
        doReturn(TelephonyManager.SIM_STATE_READY).when(mMockTeleManager).getSimState();
        doReturn(true).when(mMockResources).getBoolean(R.bool.enable_ons_auto_provisioning);
        doReturn(true).when(mMockEuiccManager).isEnabled();
        doReturn(1).when(mMockTeleManager).getSupportedModemCount();
        doReturn(2).when(mMockTeleManager).getActiveModemCount();

        ONSProfileActivator onsProfileActivator = new ONSProfileActivator(mMockContext,
                mMockSubManager, mMockTeleManager, mMockCarrierConfigManager, mMockEuiccManager,
                mMockConnectivityManager, mMockONSProfileConfigurator, mMockONSProfileDownloader);

        assertEquals(ONSProfileActivator.Result.ERR_MULTISIM_NOT_SUPPORTED,
                onsProfileActivator.handleCarrierConfigChange());
    }

    @Test
    public void testDeviceSwitchToDualSIMModeFailed() {
        doReturn(TelephonyManager.SIM_STATE_READY).when(mMockTeleManager).getSimState();
        doReturn(true).when(mMockResources).getBoolean(R.bool.enable_ons_auto_provisioning);
        doReturn(true).when(mMockEuiccManager).isEnabled();
        doReturn(2).when(mMockTeleManager).getSupportedModemCount();
        doReturn(1).when(mMockTeleManager).getActiveModemCount();
        doReturn(true).when(mMockTeleManager).doesSwitchMultiSimConfigTriggerReboot();
        doReturn(mMockactiveSubInfos).when(mMockSubManager).getActiveSubscriptionInfoList();
        doReturn(1).when(mMockactiveSubInfos).size();
        doReturn(mMockSubInfo).when(mMockactiveSubInfos).get(0);
        doReturn(1).when(mMockSubInfo).getSubscriptionId();
        doReturn(false).when(mMockSubInfo).isOpportunistic();

        PersistableBundle persistableBundle = new PersistableBundle();
        persistableBundle.putBoolean(CarrierConfigManager
                .KEY_CARRIER_SUPPORTS_OPP_DATA_AUTO_PROVISIONING_BOOL, true);
        doReturn(persistableBundle).when(mMockCarrierConfigManager).getConfigForSubId(1);

        ONSProfileActivator onsProfileActivator = new ONSProfileActivator(mMockContext,
                mMockSubManager, mMockTeleManager, mMockCarrierConfigManager, mMockEuiccManager,
                mMockConnectivityManager, mMockONSProfileConfigurator, mMockONSProfileDownloader);

        assertEquals(ONSProfileActivator.Result.ERR_CANNOT_SWITCH_TO_DUAL_SIM_MODE,
                onsProfileActivator.handleCarrierConfigChange());
    }

    @Test
    public void testDeviceSwitchToDualSIMModeSuccess() {
        doReturn(TelephonyManager.SIM_STATE_READY).when(mMockTeleManager).getSimState();
        doReturn(true).when(mMockResources).getBoolean(R.bool.enable_ons_auto_provisioning);
        doReturn(true).when(mMockEuiccManager).isEnabled();
        doReturn(2).when(mMockTeleManager).getSupportedModemCount();
        doReturn(1).when(mMockTeleManager).getActiveModemCount();
        doReturn(mMockactiveSubInfos).when(mMockSubManager).getActiveSubscriptionInfoList();
        doReturn(1).when(mMockactiveSubInfos).size();
        doReturn(mMockSubInfo).when(mMockactiveSubInfos).get(0);
        doReturn(1).when(mMockSubInfo).getSubscriptionId();
        doReturn(false).when(mMockSubInfo).isOpportunistic();

        PersistableBundle persistableBundle = new PersistableBundle();
        persistableBundle.putBoolean(CarrierConfigManager
                .KEY_CARRIER_SUPPORTS_OPP_DATA_AUTO_PROVISIONING_BOOL, true);
        doReturn(persistableBundle).when(mMockCarrierConfigManager).getConfigForSubId(1);
        doReturn(false).when(mMockTeleManager).doesSwitchMultiSimConfigTriggerReboot();

        ONSProfileActivator onsProfileActivator = new ONSProfileActivator(mMockContext,
                mMockSubManager, mMockTeleManager, mMockCarrierConfigManager, mMockEuiccManager,
                mMockConnectivityManager, mMockONSProfileConfigurator, mMockONSProfileDownloader);

        assertEquals(ONSProfileActivator.Result.ERR_SWITCHING_TO_DUAL_SIM_MODE,
                onsProfileActivator.handleCarrierConfigChange());
    }

    //@DisplayName("Dual SIM device with no SIM inserted")
    public void testNoActiveSubscriptions() {
        doReturn(TelephonyManager.SIM_STATE_READY).when(mMockTeleManager).getSimState();
        doReturn(true).when(mMockResources).getBoolean(R.bool.enable_ons_auto_provisioning);
        doReturn(true).when(mMockEuiccManager).isEnabled();
        doReturn(2).when(mMockTeleManager).getSupportedModemCount();
        doReturn(2).when(mMockTeleManager).getActiveModemCount();
        doReturn(mMockactiveSubInfos).when(mMockSubManager).getActiveSubscriptionInfoList();
        doReturn(0).when(mMockactiveSubInfos).size();

        ONSProfileActivator onsProfileActivator = new ONSProfileActivator(mMockContext,
                mMockSubManager, mMockTeleManager, mMockCarrierConfigManager, mMockEuiccManager,
                mMockConnectivityManager, mMockONSProfileConfigurator, mMockONSProfileDownloader);

        assertEquals(ONSProfileActivator.Result.ERR_NO_SIM_INSERTED,
                onsProfileActivator.handleCarrierConfigChange());
    }

    @Test
    //@DisplayName("Dual SIM device and non CBRS carrier pSIM inserted")
    public void testNonCBRSCarrierPSIMInserted() {
        doReturn(TelephonyManager.SIM_STATE_READY).when(mMockTeleManager).getSimState();
        doReturn(true).when(mMockResources).getBoolean(R.bool.enable_ons_auto_provisioning);
        doReturn(true).when(mMockEuiccManager).isEnabled();
        doReturn(2).when(mMockTeleManager).getSupportedModemCount();
        doReturn(2).when(mMockTeleManager).getActiveModemCount();

        PersistableBundle persistableBundle = new PersistableBundle();
        persistableBundle.putBoolean(CarrierConfigManager
                .KEY_CARRIER_SUPPORTS_OPP_DATA_AUTO_PROVISIONING_BOOL, false);
        doReturn(persistableBundle).when(mMockCarrierConfigManager).getConfigForSubId(1);

        doReturn(mMockactiveSubInfos).when(mMockSubManager).getActiveSubscriptionInfoList();
        doReturn(1).when(mMockactiveSubInfos).size();
        doReturn(mMockSubInfo).when(mMockactiveSubInfos).get(0);
        doReturn(1).when(mMockSubInfo).getSubscriptionId();
        doReturn(false).when(mMockSubInfo).isOpportunistic();

        ONSProfileActivator onsProfileActivator = new ONSProfileActivator(mMockContext,
                mMockSubManager, mMockTeleManager, mMockCarrierConfigManager, mMockEuiccManager,
                mMockConnectivityManager, mMockONSProfileConfigurator, mMockONSProfileDownloader);

        assertEquals(ONSProfileActivator.Result.ERR_CARRIER_DOESNT_SUPPORT_CBRS,
                onsProfileActivator.handleCarrierConfigChange());
    }

    @Test
    //@DisplayName("Dual SIM device with Two PSIM active subscriptions")
    public void testTwoActivePSIMSubscriptions() {
        doReturn(TelephonyManager.SIM_STATE_READY).when(mMockTeleManager).getSimState();
        doReturn(true).when(mMockResources).getBoolean(R.bool.enable_ons_auto_provisioning);
        doReturn(true).when(mMockEuiccManager).isEnabled();
        doReturn(2).when(mMockTeleManager).getSupportedModemCount();
        doReturn(2).when(mMockTeleManager).getActiveModemCount();

        ArrayList<SubscriptionInfo> mActiveSubInfos = new ArrayList<>();
        mActiveSubInfos.add(mMockSubInfo);
        mActiveSubInfos.add(mMockSubInfo2);
        doReturn(mActiveSubInfos).when(mMockSubManager).getActiveSubscriptionInfoList();
        doReturn(false).when(mMockSubInfo).isEmbedded();
        doReturn(false).when(mMockSubInfo2).isEmbedded();

        ONSProfileActivator onsProfileActivator = new ONSProfileActivator(mMockContext,
                mMockSubManager, mMockTeleManager, mMockCarrierConfigManager, mMockEuiccManager,
                mMockConnectivityManager, mMockONSProfileConfigurator, mMockONSProfileDownloader);

        assertEquals(ONSProfileActivator.Result.ERR_DUAL_ACTIVE_SUBSCRIPTIONS,
                onsProfileActivator.handleCarrierConfigChange());
    }

    /*@Test
    //Cannot mock/spy class android.os.PersistableBundle
    public void testOneActivePSIMAndOneNonOpportunisticESIM() {
        doReturn(true).when(mMockONSUtil).isESIMSupported();
        doReturn(true).when(mMockONSUtil).isMultiSIMPhone();
        ArrayList<SubscriptionInfo> mActiveSubInfos = new ArrayList<>();
        mActiveSubInfos.add(mMockSubInfo1);
        mActiveSubInfos.add(mMockSubInfo2);
        doReturn(mActiveSubInfos).when(mMockSubManager).getActiveSubscriptionInfoList();
        doReturn(false).when(mMockSubInfo1).isEmbedded();
        doReturn(true).when(mMockSubInfo2).isEmbedded();
        //0 - using carrier-id=0 to make sure it doesn't map to any opportunistic carrier-id
        doReturn(0).when(mMockSubInfo2).getCarrierId();

        ONSProfileActivator onsProfileActivator = new ONSProfileActivator(mMockContext,
                mMockONSProfileConfigurator, mMockONSProfileDownloader);

        assertEquals(ONSProfileActivator.Result.ERR_DUAL_ACTIVE_SUBSCRIPTIONS,
                onsProfileActivator.handleSimStateChange());
    }*/

    /*@Test
    //Cannot mock/spy class android.os.PersistableBundle
    public void testOneActivePSIMAndOneOpportunisticESIM() {
        doReturn(true).when(mMockONSUtil).isESIMSupported();
        doReturn(true).when(mMockONSUtil).isMultiSIMPhone();
        ArrayList<SubscriptionInfo> mActiveSubInfos = new ArrayList<>();
        mActiveSubInfos.add(mMockSubInfo1);
        mActiveSubInfos.add(mMockSubInfo2);
        doReturn(mActiveSubInfos).when(mMockSubManager).getActiveSubscriptionInfoList();
        doReturn(false).when(mMockSubInfo1).isEmbedded();
        doReturn(true).when(mMockSubInfo2).isEmbedded();
        doReturn(1).when(mMockSubInfo2).getSubscriptionId();
        doReturn(mMockCarrierConfig).when(mMockCarrierConfigManager).getConfigForSubId(1);
        doReturn(new int[]{1}).when(mMockCarrierConfig).get(
                CarrierConfigManager.KEY_OPPORTUNISTIC_CARRIER_IDS_INT_ARRAY);
        //1 - using carrier-id=1 to match with opportunistic carrier-id
        doReturn(1).when(mMockSubInfo2).getCarrierId();

        ONSProfileActivator onsProfileActivator = new ONSProfileActivator(mMockContext,
                mMockONSProfileConfigurator, mMockONSProfileDownloader);

        assertEquals(ONSProfileActivator.Result.SUCCESS,
                onsProfileActivator.handleSimStateChange());
    }*/

    @Test
    //@DisplayName("Dual SIM device with only opportunistic eSIM active")
    public void testOnlyOpportunisticESIMActive() {
        doReturn(TelephonyManager.SIM_STATE_READY).when(mMockTeleManager).getSimState();
        doReturn(true).when(mMockResources).getBoolean(R.bool.enable_ons_auto_provisioning);
        doReturn(true).when(mMockEuiccManager).isEnabled();
        doReturn(2).when(mMockTeleManager).getSupportedModemCount();
        doReturn(2).when(mMockTeleManager).getActiveModemCount();
        doReturn(mMockactiveSubInfos).when(mMockSubManager).getActiveSubscriptionInfoList();
        doReturn(1).when(mMockactiveSubInfos).size();
        doReturn(mMockSubInfo).when(mMockactiveSubInfos).get(0);
        doReturn(true).when(mMockSubInfo).isOpportunistic();

        ONSProfileActivator onsProfileActivator = new ONSProfileActivator(mMockContext,
                mMockSubManager, mMockTeleManager, mMockCarrierConfigManager, mMockEuiccManager,
                mMockConnectivityManager, mMockONSProfileConfigurator, mMockONSProfileDownloader);

        assertEquals(ONSProfileActivator.Result.ERR_SINGLE_ACTIVE_OPPORTUNISTIC_SIM,
                onsProfileActivator.handleCarrierConfigChange());
    }

    @Test
    //@DisplayName("Dual SIM device, only CBRS carrier pSIM inserted and pSIM not Grouped")
    public void testCBRSpSIMAndNotGrouped() {
        doReturn(TelephonyManager.SIM_STATE_READY).when(mMockTeleManager).getSimState();
        doReturn(true).when(mMockResources).getBoolean(R.bool.enable_ons_auto_provisioning);
        doReturn(true).when(mMockEuiccManager).isEnabled();
        doReturn(2).when(mMockTeleManager).getSupportedModemCount();
        doReturn(2).when(mMockTeleManager).getActiveModemCount();

        PersistableBundle persistableBundle = new PersistableBundle();
        persistableBundle.putBoolean(CarrierConfigManager
                .KEY_CARRIER_SUPPORTS_OPP_DATA_AUTO_PROVISIONING_BOOL, true);
        doReturn(persistableBundle).when(mMockCarrierConfigManager).getConfigForSubId(1);

        doReturn(mMockactiveSubInfos).when(mMockSubManager).getActiveSubscriptionInfoList();
        doReturn(1).when(mMockactiveSubInfos).size();
        doReturn(mMockSubInfo).when(mMockactiveSubInfos).get(0);
        doReturn(false).when(mMockSubInfo).isOpportunistic();
        doReturn(1).when(mMockSubInfo).getSubscriptionId();
        doReturn(null).when(mMockSubInfo).getGroupUuid();

        ONSProfileActivator onsProfileActivator = new ONSProfileActivator(mMockContext,
                mMockSubManager, mMockTeleManager, mMockCarrierConfigManager, mMockEuiccManager,
                mMockConnectivityManager, mMockONSProfileConfigurator, mMockONSProfileDownloader);

        onsProfileActivator.mIsInternetConnAvailable = true;
        assertEquals(ONSProfileActivator.Result.SUCCESS,
                onsProfileActivator.handleCarrierConfigChange());
    }

    @Test
    public void testCalculateBackoffDelay() {
        final Object lock = new Object();

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                int delay = ONSProfileActivator.calculateBackoffDelay(1, 1) / 1000;
                assertEquals(true, (delay >= 1 && delay <= 2));

                Log.i(TAG, "calculateBackoffDelay(2, 1)");
                delay = ONSProfileActivator.calculateBackoffDelay(2, 1) / 1000;
                assertEquals(true, (delay >= 1 && delay < 4));

                delay = ONSProfileActivator.calculateBackoffDelay(3, 1) / 1000;
                assertEquals(true, (delay >= 1 && delay < 8));

                delay = ONSProfileActivator.calculateBackoffDelay(4, 1) / 1000;
                assertEquals(true, (delay >= 1 && delay < 16));

                delay = ONSProfileActivator.calculateBackoffDelay(1, 2) / 1000;
                assertEquals(true, (delay >= 1 && delay <= 4));

                delay = ONSProfileActivator.calculateBackoffDelay(1, 3) / 1000;
                assertEquals(true, (delay >= 1 && delay <= 6));

                delay = ONSProfileActivator.calculateBackoffDelay(2, 2) / 1000;
                assertEquals(true, (delay >= 2 && delay < 8));

                synchronized (lock) {
                    lock.notifyAll();
                }
            }
        };

        ONSProfileDownloaderTest.WorkerThread workerThread = new ONSProfileDownloaderTest
                .WorkerThread(runnable);
        workerThread.start();

        synchronized (lock) {
            try {
                lock.wait();
            } catch (Exception e) {
                Log.e(TAG, e.getLocalizedMessage());
            }
        }

        workerThread.exit();
    }

    /* Unable to mock final class ParcelUuid. These testcases should be enabled once the solution
    is found */
    /*@Test
    //@DisplayName("Dual SIM device, only CBRS carrier pSIM inserted and pSIM Grouped")
    public void testOneSubscriptionInCBRSpSIMGroup() {
        ParcelUuid mMockParcelUuid = crateMock(ParcelUuid.class);

        doReturn(true).when(mMockONSProfileConfigurator)
                .isESIMSupported();
        doReturn(true).when(mMockONSUtil).isMultiSIMPhone();
        doReturn(true).when(mMockONSProfileConfigurator).isOppDataAutoProvisioningSupported(
                mMockPrimaryCBRSSubInfo);
        doReturn(mMockactiveSubInfos).when(mMockSubManager).getActiveSubscriptionInfoList();
        doReturn(1).when(mMockactiveSubInfos).size();
        doReturn(mMockPrimaryCBRSSubInfo).when(mMockactiveSubInfos).get(0);
        doReturn(false).when(mMockPrimaryCBRSSubInfo).isOpportunistic();
        doReturn(true).when(mMockONSProfileConfigurator).isOppDataAutoProvisioningSupported(
        mMockPrimaryCBRSSubInfo);
        doReturn(mMockParcelUuid).when(mMockPrimaryCBRSSubInfo).getGroupUuid();
        doReturn(mMocksubsInPSIMGroup).when(mMockSubManager).getSubscriptionsInGroup(
        mMockParcelUuid);
        doReturn(1).when(mMocksubsInPSIMGroup).size();

        ONSProfileActivator onsProfileActivator =
                new ONSProfileActivator(mMockContext, mMockSubManager, mMockEuiCCManager,
                mMockTeleManager,
                        mMockONSProfileConfigurator, mMockONSProfileDownloader);

        assertEquals(ONSProfileActivator.Result.SUCCESS,
                onsProfileActivator.handleSimStateChange());
    }

    @Test
    //@DisplayName("Dual SIM device, only CBRS carrier pSIM inserted and pSIM Group has two
    subscription info.")
    public void testTwoSubscriptionsInCBRSpSIMGroup() {
        ParcelUuid mMockParcelUuid = crateMock(ParcelUuid.class);

        doReturn(true).when(mMockONSProfileConfigurator)
                .isESIMSupported();
        doReturn(true).when(mMockONSUtil).isMultiSIMPhone();
        doReturn(true).when(mMockONSProfileConfigurator).isOppDataAutoProvisioningSupported(
                mMockPrimaryCBRSSubInfo);
        doReturn(mMockactiveSubInfos).when(mMockSubManager).getActiveSubscriptionInfoList();
        doReturn(1).when(mMockactiveSubInfos).size();
        doReturn(mMockPrimaryCBRSSubInfo).when(mMockactiveSubInfos).get(0);
        doReturn(false).when(mMockPrimaryCBRSSubInfo).isOpportunistic();
        doReturn(true).when(mMockONSProfileConfigurator).isOppDataAutoProvisioningSupported(
        mMockPrimaryCBRSSubInfo);
        doReturn(mMockParcelUuid).when(mMockPrimaryCBRSSubInfo).getGroupUuid();
        doReturn(mMocksubsInPSIMGroup).when(mMockSubManager).getSubscriptionsInGroup(
        mMockParcelUuid);
        doReturn(2).when(mMocksubsInPSIMGroup).size();

        ONSProfileActivator onsProfileActivator =
                new ONSProfileActivator(mMockContext, mMockSubManager, mMockEuiCCManager,
                mMockTeleManager, mMockONSProfileConfigurator, mMockONSProfileDownloader);

        assertEquals(ONSProfileActivator.Result.SUCCESS,
                onsProfileActivator.handleSimStateChange());
    }

    @Test
    //@DisplayName("Dual SIM device, only CBRS carrier pSIM inserted and pSIM Group has more than
    two subscription info.")
    public void testMoreThanTwoSubscriptionsInCBRSpSIMGroup() {
        ParcelUuid mMockParcelUuid = crateMock(ParcelUuid.class);

        doReturn(true).when(mMockONSProfileConfigurator)
                .isESIMSupported();
        doReturn(true).when(mMockONSUtil).isMultiSIMPhone();
        doReturn(true).when(mMockONSProfileConfigurator).isOppDataAutoProvisioningSupported(
                mMockPrimaryCBRSSubInfo);
        doReturn(mMockactiveSubInfos).when(mMockSubManager).getActiveSubscriptionInfoList();
        doReturn(1).when(mMockactiveSubInfos).size();
        doReturn(mMockPrimaryCBRSSubInfo).when(mMockactiveSubInfos).get(0);
        doReturn(false).when(mMockPrimaryCBRSSubInfo).isOpportunistic();
        doReturn(true).when(mMockONSProfileConfigurator).isOppDataAutoProvisioningSupported(
        mMockPrimaryCBRSSubInfo);
        doReturn(mMockParcelUuid).when(mMockPrimaryCBRSSubInfo).getGroupUuid();
        doReturn(mMocksubsInPSIMGroup).when(mMockSubManager).getSubscriptionsInGroup(
        mMockParcelUuid);
        doReturn(3).when(mMocksubsInPSIMGroup).size();

        ONSProfileActivator onsProfileActivator =
                new ONSProfileActivator(mMockContext, mMockSubManager, mMockEuiCCManager,
                mMockTeleManager, mMockONSProfileConfigurator, mMockONSProfileDownloader);

        assertEquals(ONSProfileActivator.Result.SUCCESS,
                onsProfileActivator.handleSimStateChange());
    }

    @Test
    public void testRetryDownloadAfterRebootWithOppESIMAlreadyDownloaded() {
        doReturn(true).when(mMockONSProfileConfigurator).getRetryDownloadAfterReboot();
        doReturn(1).when(mMockONSProfileConfigurator).getRetryDownloadPSIMSubId();
        doReturn(mMockSubManager).when(mMockONSUtil).getSubscriptionManager();
        doReturn(mMocksubsInPSIMGroup).when(mMockSubManager).getActiveSubscriptionInfo();
        //TODO: mock ParcelUuid - pSIM group

        ONSProfileActivator onsProfileActivator = new ONSProfileActivator(mMockContext,
                mMockONSProfileConfigurator, mMockONSProfileDownloader);

        assertEquals(ONSProfileActivator.Result.ERR_INVALID_PSIM_SUBID,
                onsProfileActivator.retryDownloadAfterReboot());
    }
    */

    /*@Test
    public void testNoInternetDownloadRequest() {
        doReturn(TEST_SUB_ID).when(mMockSubInfo).getSubscriptionId();

        ONSProfileDownloader onsProfileDownloader = new ONSProfileDownloader(mMockContext,
                mMockCarrierConfigManager, mMockEUICCManager, mMockONSProfileConfig, null);

        onsProfileDownloader.mIsInternetConnAvailable = false;
        onsProfileDownloader.downloadOpportunisticESIM(mMockSubInfo);

        assertEquals(onsProfileDownloader.mRetryDownloadWhenNWConnected, true);
        verify(mMockEUICCManager, never()).downloadSubscription(null, true, null);
    }*/

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }
}