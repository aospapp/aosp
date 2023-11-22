/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.telephony.TelephonyManager.SET_OPPORTUNISTIC_SUB_SUCCESS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.annotation.Nullable;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.ParcelUuid;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionPlan;
import android.telephony.TelephonyManager;

import androidx.test.InstrumentationRegistry;

import com.android.compatibility.common.util.ShellIdentityUtils;
import com.android.compatibility.common.util.SystemUtil;
import com.android.internal.util.ArrayUtils;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.time.Period;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class SubscriptionManagerTest {
    private SubscriptionManager mSm;

    private int mSubId;
    private String mPackageName;

    /**
     * Callback used in testRegisterNetworkCallback that allows caller to block on
     * {@code onAvailable}.
     */
    private static class TestNetworkCallback extends ConnectivityManager.NetworkCallback {
        private final CountDownLatch mAvailableLatch = new CountDownLatch(1);

        public void waitForAvailable() throws InterruptedException {
            assertTrue("Cellular network did not come up after 5 seconds",
                    mAvailableLatch.await(5, TimeUnit.SECONDS));
        }

        @Override
        public void onAvailable(Network network) {
            mAvailableLatch.countDown();
        }
    }

    @BeforeClass
    public static void setUpClass() throws Exception {
        if (!isSupported()) return;

        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand("svc wifi disable");

        final TestNetworkCallback callback = new TestNetworkCallback();
        final ConnectivityManager cm = InstrumentationRegistry.getContext()
                .getSystemService(ConnectivityManager.class);
        cm.registerNetworkCallback(new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR)
                .addCapability(NET_CAPABILITY_INTERNET)
                .build(), callback);
        try {
            // Wait to get callback for availability of internet
            callback.waitForAvailable();
        } catch (InterruptedException e) {
            fail("NetworkCallback wait was interrupted.");
        } finally {
            cm.unregisterNetworkCallback(callback);
        }
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        if (!isSupported()) return;

        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand("svc wifi enable");
    }

    @Before
    public void setUp() throws Exception {
        if (!isSupported()) return;

        mSm = InstrumentationRegistry.getContext().getSystemService(SubscriptionManager.class);
        mSubId = SubscriptionManager.getDefaultDataSubscriptionId();
        mPackageName = InstrumentationRegistry.getContext().getPackageName();
    }

    /**
     * Sanity check that the device has a cellular network and a valid default data subId
     * when {@link PackageManager#FEATURE_TELEPHONY} support.
     */
    @Test
    public void testSanity() throws Exception {
        if (!isSupported()) return;

        final boolean hasCellular = findCellularNetwork() != null;
        if (!hasCellular) {
            fail("Device claims to support " + PackageManager.FEATURE_TELEPHONY
                    + " but has no active cellular network, which is required for validation");
        }

        if (mSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            fail("Device must have a valid default data subId for validation");
        }
    }

    @Test
    public void testGetActiveSubscriptionInfoCount() throws Exception {
        if (!isSupported()) return;
        assertTrue(mSm.getActiveSubscriptionInfoCount() <=
                mSm.getActiveSubscriptionInfoCountMax());
    }

    @Test
    public void testIsActiveSubscriptionId() throws Exception {
        if (!isSupported()) return;
        assertTrue(mSm.isActiveSubscriptionId(mSubId));
    }

    @Test
    public void testGetSubscriptionIds() throws Exception {
        if (!isSupported()) return;
        int slotId = SubscriptionManager.getSlotIndex(mSubId);
        int[] subIds = mSm.getSubscriptionIds(slotId);
        assertNotNull(subIds);
        assertTrue(ArrayUtils.contains(subIds, mSubId));
    }

    @Test
    public void testIsUsableSubscriptionId() throws Exception {
        if (!isSupported()) return;
        assertTrue(SubscriptionManager.isUsableSubscriptionId(mSubId));
    }

    @Test
    public void testActiveSubscriptions() throws Exception {
        if (!isSupported()) return;

        List<SubscriptionInfo> subList = mSm.getActiveSubscriptionInfoList();
        // Assert when there is no sim card present or detected
        assertNotNull("Active subscriber required", subList);
        assertFalse("Active subscriber required", subList.isEmpty());
        for (int i = 0; i < subList.size(); i++) {
            assertTrue(subList.get(i).getSubscriptionId() >= 0);
            assertTrue(subList.get(i).getSimSlotIndex() >= 0);
            if (i >= 1) {
                assertTrue(subList.get(i - 1).getSimSlotIndex()
                        <= subList.get(i).getSimSlotIndex());
                assertTrue(subList.get(i - 1).getSimSlotIndex() < subList.get(i).getSimSlotIndex()
                        || subList.get(i - 1).getSubscriptionId()
                        < subList.get(i).getSubscriptionId());
            }
        }
    }

    @Test
    public void testSubscriptionPlans() throws Exception {
        if (!isSupported()) return;

        // Make ourselves the owner
        setSubPlanOwner(mSubId, mPackageName);

        // Push empty list and we get empty back
        mSm.setSubscriptionPlans(mSubId, Arrays.asList());
        assertEquals(Arrays.asList(), mSm.getSubscriptionPlans(mSubId));

        // Push simple plan and get it back
        final SubscriptionPlan plan = buildValidSubscriptionPlan();
        mSm.setSubscriptionPlans(mSubId, Arrays.asList(plan));
        assertEquals(Arrays.asList(plan), mSm.getSubscriptionPlans(mSubId));

        // Now revoke our access
        setSubPlanOwner(mSubId, null);
        try {
            mSm.setSubscriptionPlans(mSubId, Arrays.asList());
            fail();
        } catch (SecurityException expected) {
        }
        try {
            mSm.getSubscriptionPlans(mSubId);
            fail();
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testSubscriptionPlansOverrideCongested() throws Exception {
        if (!isSupported()) return;

        final ConnectivityManager cm = InstrumentationRegistry.getContext()
                .getSystemService(ConnectivityManager.class);
        final Network net = findCellularNetwork();
        assertNotNull("Active cellular network required", net);

        // Make ourselves the owner
        setSubPlanOwner(mSubId, mPackageName);

        // Missing plans means no overrides
        mSm.setSubscriptionPlans(mSubId, Arrays.asList());
        try {
            mSm.setSubscriptionOverrideCongested(mSubId, true, 0);
            fail();
        } catch (SecurityException | IllegalStateException expected) {
        }

        // Defining plans means we get to override
        mSm.setSubscriptionPlans(mSubId, Arrays.asList(buildValidSubscriptionPlan()));

        // Cellular is uncongested by default
        assertTrue(cm.getNetworkCapabilities(net).hasCapability(NET_CAPABILITY_NOT_CONGESTED));

        // Override should make it go congested
        {
            final CountDownLatch latch = waitForNetworkCapabilities(net, caps -> {
                return !caps.hasCapability(NET_CAPABILITY_NOT_CONGESTED);
            });
            mSm.setSubscriptionOverrideCongested(mSubId, true, 0);
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        }

        // Clearing override should make it go uncongested
        {
            final CountDownLatch latch = waitForNetworkCapabilities(net, caps -> {
                return caps.hasCapability(NET_CAPABILITY_NOT_CONGESTED);
            });
            mSm.setSubscriptionOverrideCongested(mSubId, false, 0);
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        }

        // Now revoke our access
        setSubPlanOwner(mSubId, null);
        try {
            mSm.setSubscriptionOverrideCongested(mSubId, true, 0);
            fail();
        } catch (SecurityException | IllegalStateException expected) {
        }
    }

    @Test
    public void testSubscriptionPlansOverrideUnmetered() throws Exception {
        if (!isSupported()) return;

        final ConnectivityManager cm = InstrumentationRegistry.getContext()
                .getSystemService(ConnectivityManager.class);
        final Network net = findCellularNetwork();
        assertNotNull("Active cellular network required", net);

        // Make ourselves the owner and define some plans
        setSubPlanOwner(mSubId, mPackageName);
        mSm.setSubscriptionPlans(mSubId, Arrays.asList(buildValidSubscriptionPlan()));

        // Cellular is metered by default
        assertFalse(cm.getNetworkCapabilities(net).hasCapability(NET_CAPABILITY_NOT_METERED));

        // Override should make it go unmetered
        {
            final CountDownLatch latch = waitForNetworkCapabilities(net, caps -> {
                return caps.hasCapability(NET_CAPABILITY_NOT_METERED);
            });
            mSm.setSubscriptionOverrideUnmetered(mSubId, true, 0);
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        }

        // Clearing override should make it go metered
        {
            final CountDownLatch latch = waitForNetworkCapabilities(net, caps -> {
                return !caps.hasCapability(NET_CAPABILITY_NOT_METERED);
            });
            mSm.setSubscriptionOverrideUnmetered(mSubId, false, 0);
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testSubscriptionPlansInvalid() throws Exception {
        if (!isSupported()) return;

        // Make ourselves the owner
        setSubPlanOwner(mSubId, mPackageName);

        // Empty plans can't override
        assertOverrideFails();

        // Nonrecurring plan in the past can't override
        assertOverrideFails(SubscriptionPlan.Builder
                .createNonrecurring(ZonedDateTime.now().minusDays(14),
                        ZonedDateTime.now().minusDays(7))
                .setTitle("CTS")
                .setDataLimit(1_000_000_000, SubscriptionPlan.LIMIT_BEHAVIOR_DISABLED)
                .build());

        // Plan with undefined limit can't override
        assertOverrideFails(SubscriptionPlan.Builder
                .createRecurring(ZonedDateTime.parse("2007-03-14T00:00:00.000Z"),
                        Period.ofMonths(1))
                .setTitle("CTS")
                .build());

        // We can override when there is an active plan somewhere
        final SubscriptionPlan older = SubscriptionPlan.Builder
                .createNonrecurring(ZonedDateTime.now().minusDays(14),
                        ZonedDateTime.now().minusDays(7))
                .setTitle("CTS")
                .setDataLimit(1_000_000_000, SubscriptionPlan.LIMIT_BEHAVIOR_DISABLED)
                .build();
        final SubscriptionPlan newer = SubscriptionPlan.Builder
                .createNonrecurring(ZonedDateTime.now().minusDays(7),
                        ZonedDateTime.now().plusDays(7))
                .setTitle("CTS")
                .setDataLimit(1_000_000_000, SubscriptionPlan.LIMIT_BEHAVIOR_DISABLED)
                .build();
        assertOverrideSuccess(older, newer);
    }

    @Test
    public void testSubscriptionGrouping() throws Exception {
        if (!isSupported()) return;

        // Set subscription group with current sub Id. This should fail
        // because we don't have MODIFY_PHONE_STATE or carrier privilege permission.
        List<Integer> subGroup = new ArrayList();
        subGroup.add(mSubId);
        try {
            mSm.createSubscriptionGroup(subGroup);
            fail();
        } catch (SecurityException expected) {
        }

        // Getting subscriptions in group should return null as setSubscriptionGroup
        // should fail.
        SubscriptionInfo info = mSm.getActiveSubscriptionInfo(mSubId);
        assertNull(info.getGroupUuid());

        // Remove from subscription group with current sub Id. This should fail
        // because we don't have MODIFY_PHONE_STATE or carrier privilege permission.
        try {
            mSm.addSubscriptionsIntoGroup(subGroup, null);
            fail();
        } catch (NullPointerException expected) {
        }

        // Add into subscription group that doesn't exist. This should fail
        // with IllegalArgumentException.
        try {
            ParcelUuid groupUuid = new ParcelUuid(UUID.randomUUID());
            mSm.addSubscriptionsIntoGroup(subGroup, groupUuid);
            fail();
        } catch (IllegalArgumentException expected) {
        }

        // Remove from subscription group with current sub Id. This should fail
        // because we don't have MODIFY_PHONE_STATE or carrier privilege permission.
        try {
            mSm.removeSubscriptionsFromGroup(subGroup, null);
            fail();
        } catch (NullPointerException expected) {
        }
    }

    @Test
    public void testSubscriptionGroupingWithPermission() throws Exception {
        if (!isSupported()) return;

        // Set subscription group with current sub Id.
        List<Integer> subGroup = new ArrayList();
        subGroup.add(mSubId);
        ParcelUuid uuid = ShellIdentityUtils.invokeMethodWithShellPermissions(mSm,
                (sm) -> sm.createSubscriptionGroup(subGroup));

        // Getting subscriptions in group.
        List<SubscriptionInfo> infoList = mSm.getSubscriptionsInGroup(uuid);
        assertNotNull(infoList);
        assertEquals(1, infoList.size());
        assertEquals(uuid, infoList.get(0).getGroupUuid());

        List<SubscriptionInfo> availableInfoList = mSm.getAvailableSubscriptionInfoList();
        if (availableInfoList.size() > 1) {
            List<Integer> availableSubGroup = availableInfoList.stream()
                    .map(info -> info.getSubscriptionId())
                    .filter(subId -> subId != mSubId)
                    .collect(Collectors.toList());

            ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(mSm,
                    (sm) -> sm.addSubscriptionsIntoGroup(availableSubGroup, uuid));

            infoList = mSm.getSubscriptionsInGroup(uuid);
            assertNotNull(infoList);
            assertEquals(availableInfoList.size(), infoList.size());

            ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(mSm,
                    (sm) -> sm.removeSubscriptionsFromGroup(availableSubGroup, uuid));
        }

        // Remove from subscription group with current sub Id.
        ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(mSm,
                (sm) -> sm.removeSubscriptionsFromGroup(subGroup, uuid));

        infoList = mSm.getSubscriptionsInGroup(uuid);
        assertNotNull(infoList);
        assertTrue(infoList.isEmpty());
    }

    @Test
    public void testSettingOpportunisticSubscription() throws Exception {
        if (!isSupported()) return;

        // Set subscription to be opportunistic. This should fail
        // because we don't have MODIFY_PHONE_STATE or carrier privilege permission.
        try {
            mSm.setOpportunistic(true, mSubId);
            fail();
        } catch (SecurityException expected) {
        }

        // Shouldn't crash.
        SubscriptionInfo info = mSm.getActiveSubscriptionInfo(mSubId);
        info.isOpportunistic();
    }

    @Test
    public void testMccMncString() {
        if (!isSupported()) return;

        SubscriptionInfo info = mSm.getActiveSubscriptionInfo(mSubId);
        String mcc = info.getMccString();
        String mnc = info.getMncString();
        assertTrue(mcc == null || mcc.length() <= 3);
        assertTrue(mnc == null || mnc.length() <= 3);
    }

    @Test
    public void testSubscriptionInfoCarrierId() {
        if (!isSupported()) return;

        SubscriptionInfo info = mSm.getActiveSubscriptionInfo(mSubId);
        int carrierId = info.getCarrierId();
        assertTrue(carrierId >= TelephonyManager.UNKNOWN_CARRIER_ID);
    }

    @Test
    public void testGetOpportunisticSubscriptions() throws Exception {
        if (!isSupported()) return;

        List<SubscriptionInfo> infoList = mSm.getOpportunisticSubscriptions();

        for (SubscriptionInfo info : infoList) {
            assertTrue(info.isOpportunistic());
        }
    }

    @Test
    public void testGetEnabledSubscriptionId() {
        if (!isSupported()) return;
        int slotId = SubscriptionManager.getSlotIndex(mSubId);
        if (!SubscriptionManager.isValidSlotIndex(slotId)) {
            fail("Invalid slot id " + slotId + " for subscription id " + mSubId);
        }
        int enabledSubId = executeWithShellPermissionAndDefault(-1, mSm,
                (sm) -> sm.getEnabledSubscriptionId(slotId));
        assertEquals(mSubId, enabledSubId);
    }

    @Test
    public void testSetAndCheckSubscriptionEnabled() {
        if (!isSupported()) return;
        boolean enabled = executeWithShellPermissionAndDefault(false, mSm,
                (sm) -> sm.isSubscriptionEnabled(mSubId));
        if (isDSDS()) {
            // Change it to a different value
            changeAndVerifySubscriptionEnabledValue(mSubId, !enabled);
            // Reset it back to original
            changeAndVerifySubscriptionEnabledValue(mSubId, enabled);
        } else {
            boolean changeSuccessfully = executeWithShellPermissionAndDefault(false, mSm,
                    (sm) -> sm.setSubscriptionEnabled(mSubId, !enabled));
            assertFalse(changeSuccessfully);
        }
    }

    @Test
    public void testSetPreferredDataSubscriptionId() {
        if (!isSupported()) return;
        int preferredSubId = executeWithShellPermissionAndDefault(-1, mSm,
                (sm) -> sm.getPreferredDataSubscriptionId());

        final LinkedBlockingQueue<Integer> resultQueue = new LinkedBlockingQueue<>(1);
        Executor executor = new Executor() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };

        Consumer<Integer> consumer = new Consumer<Integer>() {
            @Override
            public void accept(Integer res) {
                if (res == null) {
                    resultQueue.offer(-1);
                } else {
                    resultQueue.offer(res);
                }
            }
        };

        List<SubscriptionInfo> subscriptionInfos = mSm.getActiveSubscriptionInfoList();
        boolean changes = false;

        for (SubscriptionInfo subInfo : subscriptionInfos) {
            int subId = subInfo.getSubscriptionId();
            if (subId != preferredSubId) {
                int newPreferredSubId = subId;
                // Change to a new value.
                ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(mSm,
                        (sm) -> sm.setPreferredDataSubscriptionId(newPreferredSubId, false,
                                executor, consumer));
                int res = -1;
                try {
                    res = resultQueue.poll(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    fail("Cannot get the modem result in time");
                }
                assertEquals(SET_OPPORTUNISTIC_SUB_SUCCESS, res);
                int newGetValue = executeWithShellPermissionAndDefault(-1, mSm,
                        (sm) -> sm.getPreferredDataSubscriptionId());
                assertEquals(newPreferredSubId, newGetValue);
                changes = true;
                break;
            }
        }

        // Reset back, or set the duplicate.
        if (SubscriptionManager.isValidSubscriptionId(preferredSubId)) {
            ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(mSm,
                    (sm) -> sm.setPreferredDataSubscriptionId(preferredSubId, false,
                            executor, consumer));
            int res = -1;
            try {
                res = resultQueue.poll(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail("Cannot get the modem result in time");
            }
            // Duplicate setting ends up with nothing.
            if (!changes) {
                assertEquals(-1, res);
            } else {
                assertEquals(SET_OPPORTUNISTIC_SUB_SUCCESS, res);
                int resetGetValue = executeWithShellPermissionAndDefault(-1, mSm,
                        (sm) -> sm.getPreferredDataSubscriptionId());
                assertEquals(resetGetValue, preferredSubId);
            }
        }
    }

    private void changeAndVerifySubscriptionEnabledValue(int subId, boolean targetValue) {
        boolean changeSuccessfully = executeWithShellPermissionAndDefault(false, mSm,
                (sm) -> sm.setSubscriptionEnabled(subId, targetValue));
        if (!changeSuccessfully) {
            fail("Cannot change subscription " + subId
                    + " from " + !targetValue + " to " + targetValue);
        }
        boolean res = executeWithShellPermissionAndDefault(targetValue, mSm,
                (sm) -> sm.isSubscriptionEnabled(subId));
        assertEquals(targetValue, res);
    }

    private <T, U> T executeWithShellPermissionAndDefault(T defaultValue, U targetObject,
            ShellIdentityUtils.ShellPermissionMethodHelper<T, U> helper) {
        try {
            return ShellIdentityUtils.invokeMethodWithShellPermissions(targetObject, helper);
        } catch (Exception e) {
            // do nothing, return default
        }
        return defaultValue;
    }

    private void assertOverrideSuccess(SubscriptionPlan... plans) {
        mSm.setSubscriptionPlans(mSubId, Arrays.asList(plans));
        mSm.setSubscriptionOverrideCongested(mSubId, false, 0);
    }

    private void assertOverrideFails(SubscriptionPlan... plans) {
        mSm.setSubscriptionPlans(mSubId, Arrays.asList(plans));
        try {
            mSm.setSubscriptionOverrideCongested(mSubId, false, 0);
            fail();
        } catch (SecurityException | IllegalStateException expected) {
        }
    }

    public static CountDownLatch waitForNetworkCapabilities(Network network,
            Predicate<NetworkCapabilities> predicate) {
        final CountDownLatch latch = new CountDownLatch(1);
        final ConnectivityManager cm = InstrumentationRegistry.getContext()
                .getSystemService(ConnectivityManager.class);
        cm.registerNetworkCallback(new NetworkRequest.Builder().build(),
                new NetworkCallback() {
                    @Override
                    public void onCapabilitiesChanged(Network net, NetworkCapabilities caps) {
                        if (net.equals(network) && predicate.test(caps)) {
                            latch.countDown();
                            cm.unregisterNetworkCallback(this);
                        }
                    }
                });
        return latch;
    }

    private static SubscriptionPlan buildValidSubscriptionPlan() {
        return SubscriptionPlan.Builder
                .createRecurring(ZonedDateTime.parse("2007-03-14T00:00:00.000Z"),
                        Period.ofMonths(1))
                .setTitle("CTS")
                .setDataLimit(1_000_000_000, SubscriptionPlan.LIMIT_BEHAVIOR_DISABLED)
                .setDataUsage(500_000_000, System.currentTimeMillis())
                .build();
    }

    private static @Nullable Network findCellularNetwork() {
        final ConnectivityManager cm = InstrumentationRegistry.getContext()
                .getSystemService(ConnectivityManager.class);
        for (Network net : cm.getAllNetworks()) {
            final NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps != null && caps.hasTransport(TRANSPORT_CELLULAR)
                    && caps.hasCapability(NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NET_CAPABILITY_NOT_RESTRICTED)) {
                return net;
            }
        }
        return null;
    }

    private static boolean isSupported() {
        return InstrumentationRegistry.getContext().getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
    }

    private static boolean isDSDS() {
        TelephonyManager tm = InstrumentationRegistry.getContext()
                .getSystemService(TelephonyManager.class);
        return tm != null && tm.getPhoneCount() > 1;
    }

    private static void setSubPlanOwner(int subId, String packageName) throws Exception {
        SystemUtil.runShellCommand(InstrumentationRegistry.getInstrumentation(),
                "cmd netpolicy set sub-plan-owner " + subId + " " + packageName);
    }
}
