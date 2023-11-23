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

package android.telecom.cts;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.cts.carmodetestapp.ICtsCarModeInCallServiceControl;
import android.telecom.cts.carmodetestappselfmanaged.CtsCarModeInCallServiceControlSelfManaged;
import android.util.Log;

import com.android.compatibility.common.util.ShellIdentityUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class PhoneAccountRegistrarTest extends BaseTelecomTestWithMockServices {

    private static final String TAG = "PhoneAccountRegistrarTest";
    private static final long TIMEOUT = 3000L;
    public static final long SEED = 52L; // random seed chosen
    public static final int MAX_PHONE_ACCOUNT_REGISTRATIONS = 10; // mirrors constant in...
    // PhoneAccountRegistrar called MAX_PHONE_ACCOUNT_REGISTRATIONS

    @Override
    public void setUp() throws Exception {
        // Sets up this package as default dialer in super.
        super.setUp();
        NewOutgoingCallBroadcastReceiver.reset();
        if (!mShouldTestTelecom) return;
        setupConnectionService(null, 0);
        // cleanup any accounts registered to the test package before starting tests
        cleanupPhoneAccounts();
    }

    @Override
    public void tearDown() throws Exception {
        // cleanup any accounts registered to the test package after testing to avoid crashing other
        // tests.
        cleanupPhoneAccounts();
        super.tearDown();
    }

    /**
     * Test scenario where a single package can register MAX_PHONE_ACCOUNT_REGISTRATIONS via
     * {@link android.telecom.TelecomManager#registerPhoneAccount(PhoneAccount)}  without an
     * exception being thrown.
     */
    public void testRegisterMaxPhoneAccountsWithoutException() {
        if (!mShouldTestTelecom) return;

        // ensure the test starts without any phone accounts registered to the test package
        cleanupPhoneAccounts();

        //  determine the number of phone accounts that can be registered before hitting limit
        int numberOfAccountsThatCanBeRegistered = MAX_PHONE_ACCOUNT_REGISTRATIONS
                - getNumberOfPhoneAccountsRegisteredToTestPackage();

        // create the remaining number of phone accounts via helper function
        // in order to reach the upper bound MAX_PHONE_ACCOUNT_REGISTRATIONS
        ArrayList<PhoneAccount> accounts = TestUtils.generateRandomPhoneAccounts(SEED,
                numberOfAccountsThatCanBeRegistered, TestUtils.PACKAGE, TestUtils.COMPONENT);
        try {
            // register all accounts created
            accounts.stream().forEach(a -> mTelecomManager.registerPhoneAccount(a));
            // assert the maximum accounts that can be registered were registered successfully
            assertEquals(MAX_PHONE_ACCOUNT_REGISTRATIONS,
                    getNumberOfPhoneAccountsRegisteredToTestPackage());
        } finally {
            // cleanup accounts registered
            accounts.stream().forEach(
                    d -> mTelecomManager.unregisterPhoneAccount(d.getAccountHandle()));
        }
    }

    /**
     * Tests a scenario where a single package exceeds MAX_PHONE_ACCOUNT_REGISTRATIONS and
     * an {@link IllegalArgumentException}  is thrown. Will fail if no exception is thrown.
     */
    public void testExceptionThrownDueUserExceededMaxPhoneAccountRegistrations()
            throws IllegalArgumentException {
        if (!mShouldTestTelecom) return;

        // ensure the test starts without any phone accounts registered to the test package
        cleanupPhoneAccounts();

        // Create MAX_PHONE_ACCOUNT_REGISTRATIONS + 1 via helper function
        ArrayList<PhoneAccount> accounts = TestUtils.generateRandomPhoneAccounts(SEED,
                MAX_PHONE_ACCOUNT_REGISTRATIONS + 1, TestUtils.PACKAGE,
                TestUtils.COMPONENT);

        try {
            // Try to register more phone accounts than allowed by the upper bound limit
            // MAX_PHONE_ACCOUNT_REGISTRATIONS
            accounts.stream().forEach(a -> mTelecomManager.registerPhoneAccount(a));
            // A successful test should never reach this line of execution.
            // However, if it does, fail the test by throwing a fail(...)
            fail("Test failed. The test did not throw an IllegalArgumentException when "
                    + "registering phone accounts over the upper bound: "
                    + "MAX_PHONE_ACCOUNT_REGISTRATIONS");
        } catch (IllegalArgumentException e) {
            // Assert the IllegalArgumentException was thrown
            assertNotNull(e.toString());
        } finally {
            // Cleanup accounts registered
            accounts.stream().forEach(d -> mTelecomManager.unregisterPhoneAccount(
                    d.getAccountHandle()));
        }
    }

    /**
     * Test scenario where two distinct packages register MAX_PHONE_ACCOUNT_REGISTRATIONS via
     * {@link
     * android.telecom.TelecomManager#registerPhoneAccount(PhoneAccount)} without an exception being
     * thrown.
     * This ensures that PhoneAccountRegistrar is handling {@link PhoneAccount} registrations
     * to distinct packages correctly.
     */
    public void testTwoPackagesRegisterMax() throws Exception {
        if (!mShouldTestTelecom) return;

        // ensure the test starts without any phone accounts registered to the test package
        cleanupPhoneAccounts();

        //  determine the number of phone accounts that can be registered to package 1
        int numberOfAccountsThatCanBeRegisteredToPackage1 = MAX_PHONE_ACCOUNT_REGISTRATIONS
                - getNumberOfPhoneAccountsRegisteredToTestPackage();

        // Create MAX phone accounts for package 1
        ArrayList<PhoneAccount> accountsPackage1 = TestUtils.generateRandomPhoneAccounts(SEED,
                numberOfAccountsThatCanBeRegisteredToPackage1, TestUtils.PACKAGE,
                TestUtils.COMPONENT);

        // Constants for creating a second package to register phone accounts
        final String carPkgSelfManaged =
                CtsCarModeInCallServiceControlSelfManaged.class
                        .getPackage().getName();
        final ComponentName carComponentSelfManaged = ComponentName.createRelative(
                carPkgSelfManaged, CtsCarModeInCallServiceControlSelfManaged.class.getName());
        final String carModeControl =
                "android.telecom.cts.carmodetestapp.ACTION_CAR_MODE_CONTROL";

        // Set up binding for second package. This is needed in order to bypass a SecurityException
        // thrown by a second test package registering phone accounts.
        TestServiceConnection control = setUpControl(carModeControl,
                carComponentSelfManaged);

        ICtsCarModeInCallServiceControl carModeIncallServiceControlSelfManaged =
                ICtsCarModeInCallServiceControl.Stub
                        .asInterface(control.getService());

        carModeIncallServiceControlSelfManaged.reset(); //... done setting up binding

        // Create MAX phone accounts for package 2
        ArrayList<PhoneAccount> accountsPackage2 = TestUtils.generateRandomPhoneAccounts(SEED,
                MAX_PHONE_ACCOUNT_REGISTRATIONS, carPkgSelfManaged,
                TestUtils.SELF_MANAGED_COMPONENT);

        try {

            // register all accounts for package 1
            accountsPackage1.stream().forEach(a -> mTelecomManager.registerPhoneAccount(a));


            // register all phone accounts for package 2
            for (int i = 0; i < MAX_PHONE_ACCOUNT_REGISTRATIONS; i++) {
                carModeIncallServiceControlSelfManaged.registerPhoneAccount(
                        accountsPackage2.get(i));
            }

        } finally {
            // cleanup all phone accounts registered. Note, unregisterPhoneAccount will not
            // cause a runtime error if no phone account is found when trying to unregister.

            accountsPackage1.stream().forEach(d -> mTelecomManager.unregisterPhoneAccount(
                    d.getAccountHandle()));

            for (int i = 0; i < MAX_PHONE_ACCOUNT_REGISTRATIONS; i++) {
                carModeIncallServiceControlSelfManaged.unregisterPhoneAccount(
                        accountsPackage2.get(i).getAccountHandle());
            }
        }
        // unbind from second package
        mContext.unbindService(control);
    }

    // -- The following are helper methods for this testing class. --

    private TestServiceConnection setUpControl(String action, ComponentName componentName) {
        Intent bindIntent = new Intent(action);
        bindIntent.setComponent(componentName);

        TestServiceConnection
                serviceConnection = new TestServiceConnection();
        mContext.bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        if (!serviceConnection.waitBind()) {
            fail("fail bind to service");
        }
        return serviceConnection;
    }

    private class TestServiceConnection implements ServiceConnection {
        private IBinder mService;
        private CountDownLatch mLatch = new CountDownLatch(1);
        private boolean mIsConnected;

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            Log.i(TAG, "Service Connected: " + componentName);
            mService = service;
            mIsConnected = true;
            mLatch.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mService = null;
        }

        public IBinder getService() {
            return mService;
        }

        public boolean waitBind() {
            try {
                mLatch.await(TIMEOUT, TimeUnit.MILLISECONDS);
                return mIsConnected;
            } catch (InterruptedException e) {
                return false;
            }
        }
    }

    /**
     * Helper that cleans up any phone accounts registered to this testing package.  Requires
     * the permission READ_PRIVILEGED_PHONE_STATE in order to invoke the
     * getPhoneAccountsForPackage() method.
     */
    private void cleanupPhoneAccounts() {
        if (mTelecomManager != null) {
            // Get all handles registered to the testing package
            List<PhoneAccountHandle> handles = ShellIdentityUtils.invokeMethodWithShellPermissions(
                    mTelecomManager, (tm) -> tm.getPhoneAccountsForPackage(),
                    "android.permission.READ_PRIVILEGED_PHONE_STATE");

            // cleanup any extra phone accounts registered to the testing package
            if (handles.size() > 0 && mTelecomManager != null) {
                handles.stream().forEach(
                        d -> mTelecomManager.unregisterPhoneAccount(d));
            }
        }
    }

    /**
     * Helper that gets the number of phone accounts registered to the testing package. Requires
     * the permission READ_PRIVILEGED_PHONE_STATE in order to invoke the
     * getPhoneAccountsForPackage() method.
     * @return number of phone accounts registered to the testing package.
     */
    private int getNumberOfPhoneAccountsRegisteredToTestPackage() {
        if (mTelecomManager != null) {
            return ShellIdentityUtils.invokeMethodWithShellPermissions(
                    mTelecomManager, (tm) -> tm.getPhoneAccountsForPackage(),
                    "android.permission.READ_PRIVILEGED_PHONE_STATE").size();
        }
        return 0;
    }
}

