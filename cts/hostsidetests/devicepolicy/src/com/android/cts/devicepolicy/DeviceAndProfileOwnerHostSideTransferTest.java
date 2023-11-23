package com.android.cts.devicepolicy;

import static com.android.cts.devicepolicy.metrics.DevicePolicyEventLogVerifier.assertMetricsLogged;

import com.android.cts.devicepolicy.metrics.DevicePolicyEventWrapper;
import com.android.tradefed.device.DeviceNotAvailableException;

import android.stats.devicepolicy.EventId;

public abstract class DeviceAndProfileOwnerHostSideTransferTest extends BaseDevicePolicyTest {

    protected static final String TRANSFER_OWNER_OUTGOING_PKG =
            "com.android.cts.transferowneroutgoing";
    protected static final String TRANSFER_OWNER_OUTGOING_APK = "CtsTransferOwnerOutgoingApp.apk";
    protected static final String TRANSFER_OWNER_OUTGOING_TEST_RECEIVER =
            TRANSFER_OWNER_OUTGOING_PKG
                    + "/com.android.cts.transferowner"
                    + ".DeviceAndProfileOwnerTransferOutgoingTest$BasicAdminReceiver";
    protected static final String TRANSFER_OWNER_INCOMING_PKG =
            "com.android.cts.transferownerincoming";
    protected static final String TRANSFER_OWNER_INCOMING_APK = "CtsTransferOwnerIncomingApp.apk";
    protected static final String INVALID_TARGET_APK = "CtsIntentReceiverApp.apk";
    protected static final String TRANSFER_PROFILE_OWNER_OUTGOING_TEST =
        "com.android.cts.transferowner.TransferProfileOwnerOutgoingTest";
    protected static final String TRANSFER_PROFILE_OWNER_INCOMING_TEST =
        "com.android.cts.transferowner.TransferProfileOwnerIncomingTest";

    protected int mUserId;
    protected String mOutgoingTestClassName;
    protected String mIncomingTestClassName;

    public void testTransferOwnership() throws Exception {
        if (!mHasFeature) {
            return;
        }

        final boolean hasManagedProfile = (mUserId != mPrimaryUserId);
        final String expectedManagementType = hasManagedProfile ? "profile-owner" : "device-owner";
        assertMetricsLogged(getDevice(), () -> {
            runDeviceTestsAsUser(TRANSFER_OWNER_OUTGOING_PKG, mOutgoingTestClassName,
                    "testTransferOwnership", mUserId);
        }, new DevicePolicyEventWrapper.Builder(EventId.TRANSFER_OWNERSHIP_VALUE)
                .setAdminPackageName(TRANSFER_OWNER_OUTGOING_PKG)
                .setStrings(TRANSFER_OWNER_INCOMING_PKG, expectedManagementType)
                .build());
    }

    public void testTransferSameAdmin() throws Exception {
        if (!mHasFeature) {
            return;
        }
        runDeviceTestsAsUser(TRANSFER_OWNER_OUTGOING_PKG,
                mOutgoingTestClassName,
                "testTransferSameAdmin", mUserId);
    }

    public void testTransferInvalidTarget() throws Exception {
        if (!mHasFeature) {
            return;
        }
        installAppAsUser(INVALID_TARGET_APK, mUserId);
        runDeviceTestsAsUser(TRANSFER_OWNER_OUTGOING_PKG,
                mOutgoingTestClassName,
                "testTransferInvalidTarget", mUserId);
    }

    public void testTransferPolicies() throws Exception {
        if (!mHasFeature) {
            return;
        }
        runDeviceTestsAsUser(TRANSFER_OWNER_OUTGOING_PKG,
                mOutgoingTestClassName,
                "testTransferWithPoliciesOutgoing", mUserId);
        runDeviceTestsAsUser(TRANSFER_OWNER_INCOMING_PKG,
                mIncomingTestClassName,
                "testTransferPoliciesAreRetainedAfterTransfer", mUserId);
    }

    public void testTransferOwnershipChangedBroadcast() throws Exception {
        if (!mHasFeature) {
            return;
        }
        runDeviceTestsAsUser(TRANSFER_OWNER_OUTGOING_PKG,
                mOutgoingTestClassName,
                "testTransferOwnershipChangedBroadcast", mUserId);
    }

    public void testTransferCompleteCallback() throws Exception {
        if (!mHasFeature) {
            return;
        }
        runDeviceTestsAsUser(TRANSFER_OWNER_OUTGOING_PKG,
                mOutgoingTestClassName,
                "testTransferOwnership", mUserId);

        waitForBroadcastIdle();

        runDeviceTestsAsUser(TRANSFER_OWNER_INCOMING_PKG,
                mIncomingTestClassName,
                "testTransferCompleteCallbackIsCalled", mUserId);
    }

    protected void setupTestParameters(int userId, String outgoingTestClassName,
            String incomingTestClassName) {
        mUserId = userId;
        mOutgoingTestClassName = outgoingTestClassName;
        mIncomingTestClassName = incomingTestClassName;
    }

    public void testTransferOwnershipNoMetadata() throws Exception {
        if (!mHasFeature) {
            return;
        }
        runDeviceTestsAsUser(TRANSFER_OWNER_OUTGOING_PKG,
                mOutgoingTestClassName,
                "testTransferOwnershipNoMetadata", mUserId);
    }

    public void testIsTransferBundlePersisted() throws DeviceNotAvailableException {
        if (!mHasFeature) {
            return;
        }
        runDeviceTestsAsUser(TRANSFER_OWNER_OUTGOING_PKG,
                mOutgoingTestClassName,
                "testTransferOwnershipBundleSaved", mUserId);
        runDeviceTestsAsUser(TRANSFER_OWNER_INCOMING_PKG,
                mIncomingTestClassName,
                "testTransferOwnershipBundleLoaded", mUserId);
    }

    public void testGetTransferOwnershipBundleOnlyCalledFromAdmin()
            throws DeviceNotAvailableException {
        if (!mHasFeature) {
            return;
        }
        runDeviceTestsAsUser(TRANSFER_OWNER_OUTGOING_PKG,
                mOutgoingTestClassName,
                "testGetTransferOwnershipBundleOnlyCalledFromAdmin", mUserId);
    }

    public void testBundleEmptyAfterTransferWithNullBundle() throws DeviceNotAvailableException {
        if (!mHasFeature) {
            return;
        }
        runDeviceTestsAsUser(TRANSFER_OWNER_OUTGOING_PKG,
                mOutgoingTestClassName,
                "testTransferOwnershipNullBundle", mUserId);
        runDeviceTestsAsUser(TRANSFER_OWNER_INCOMING_PKG,
                mIncomingTestClassName,
                "testTransferOwnershipEmptyBundleLoaded", mUserId);
    }

    public void testIsBundleNullNoTransfer() throws DeviceNotAvailableException {
        if (!mHasFeature) {
            return;
        }
        runDeviceTestsAsUser(TRANSFER_OWNER_OUTGOING_PKG,
                mOutgoingTestClassName,
                "testIsBundleNullNoTransfer", mUserId);
    }

    protected int setupManagedProfileOnDeviceOwner(String apkName, String adminReceiverClassName)
            throws Exception {
        // Temporary disable the DISALLOW_ADD_MANAGED_PROFILE, so that we can create profile
        // using adb command.
        clearDisallowAddManagedProfileRestriction();
        try {
            return setupManagedProfile(apkName, adminReceiverClassName);
        } finally {
            // Adding back DISALLOW_ADD_MANAGED_PROFILE.
            addDisallowAddManagedProfileRestriction();
        }
    }

    protected int setupManagedProfile(String apkName, String adminReceiverClassName)
            throws Exception {
        final int userId = createManagedProfile(mPrimaryUserId);
        installAppAsUser(apkName, userId);
        if (!setProfileOwner(adminReceiverClassName, userId, false)) {
            removeAdmin(TRANSFER_OWNER_OUTGOING_TEST_RECEIVER, userId);
            getDevice().uninstallPackage(TRANSFER_OWNER_OUTGOING_PKG);
            fail("Failed to set device owner");
            return -1;
        }
        startUser(userId);
        return userId;
    }

    /**
     * Clear {@link android.os.UserManager#DISALLOW_ADD_MANAGED_PROFILE}.
     */
    private void clearDisallowAddManagedProfileRestriction() throws Exception {
        runDeviceTestsAsUser(
                TRANSFER_OWNER_OUTGOING_PKG,
                mOutgoingTestClassName,
                "testClearDisallowAddManagedProfileRestriction",
                mPrimaryUserId);
    }

    /**
     * Add {@link android.os.UserManager#DISALLOW_ADD_MANAGED_PROFILE}.
     */
    private void addDisallowAddManagedProfileRestriction() throws Exception {
        runDeviceTestsAsUser(
                TRANSFER_OWNER_OUTGOING_PKG,
                mOutgoingTestClassName,
                "testAddDisallowAddManagedProfileRestriction",
                mPrimaryUserId);
    }

    public void testTargetDeviceAdminServiceBound() throws Exception {
        if (!mHasFeature) {
            return;
        }
        runDeviceTestsAsUser(TRANSFER_OWNER_OUTGOING_PKG,
            mOutgoingTestClassName,
            "testTransferOwnership", mUserId);
        runDeviceTestsAsUser(TRANSFER_OWNER_INCOMING_PKG,
            mIncomingTestClassName,
            "testAdminServiceIsBound", mUserId);
    }

    protected void setSameAffiliationId(int profileUserId, String testClassName)
        throws Exception {
        runDeviceTestsAsUser(TRANSFER_OWNER_OUTGOING_PKG,
            testClassName,
            "testSetAffiliationId1", mPrimaryUserId);
        runDeviceTestsAsUser(TRANSFER_OWNER_OUTGOING_PKG,
            testClassName,
            "testSetAffiliationId1", profileUserId);
    }

    protected void assertAffiliationIdsAreIntact(int profileUserId,
        String testClassName) throws Exception {
        runDeviceTestsAsUser(TRANSFER_OWNER_INCOMING_PKG,
            testClassName,
            "testIsAffiliationId1", mPrimaryUserId);
        runDeviceTestsAsUser(TRANSFER_OWNER_INCOMING_PKG,
            testClassName,
            "testIsAffiliationId1", profileUserId);
    }

    /* TODO: Add tests for:
    * 1. passwordOwner
    *
    * */
}
