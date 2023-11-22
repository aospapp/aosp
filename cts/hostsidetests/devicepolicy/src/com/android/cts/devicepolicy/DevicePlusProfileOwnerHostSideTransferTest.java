package com.android.cts.devicepolicy;

import android.util.Log;

/**
 * Tests the DPC transfer functionality for COMP mode - managed profile on top of a device owner.
 * Testing is done by having two dummy DPCs, CtsTransferOwnerOutgoingApp and
 * CtsTransferOwnerIncomingApp. The former is the current DPC and the latter will be the new DPC
 * after transfer. In order to run the tests from the correct process, first we setup some
 * policies in the client side in CtsTransferOwnerOutgoingApp and then we verify the policies are
 * still there in CtsTransferOwnerIncomingApp. Note that these tests are run on the profile owner
 * user.
 */
public class DevicePlusProfileOwnerHostSideTransferTest
    extends DeviceAndProfileOwnerHostSideTransferTest {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // We need managed users to be supported in order to create a profile of the user owner.
        mHasFeature &= hasDeviceFeature("android.software.managed_users");
        if (mHasFeature) {
            installAppAsUser(TRANSFER_OWNER_OUTGOING_APK, mPrimaryUserId);
            // First set up the device owner.
            if (setDeviceOwner(TRANSFER_OWNER_OUTGOING_TEST_RECEIVER, mPrimaryUserId,
                false)) {
                mOutgoingTestClassName = TRANSFER_PROFILE_OWNER_OUTGOING_TEST;
                mIncomingTestClassName = TRANSFER_PROFILE_OWNER_INCOMING_TEST;

                // And then set up the managed profile on top of it.
                final int profileUserId = setupManagedProfileOnDeviceOwner(
                    TRANSFER_OWNER_OUTGOING_APK, TRANSFER_OWNER_OUTGOING_TEST_RECEIVER);
                setSameAffiliationId(profileUserId, TRANSFER_PROFILE_OWNER_OUTGOING_TEST);

                installAppAsUser(TRANSFER_OWNER_INCOMING_APK, mPrimaryUserId);
                installAppAsUser(TRANSFER_OWNER_INCOMING_APK, profileUserId);
                mUserId = profileUserId;
            } else {
                removeAdmin(TRANSFER_OWNER_OUTGOING_TEST_RECEIVER, mUserId);
                getDevice().uninstallPackage(TRANSFER_OWNER_OUTGOING_PKG);
                fail("Failed to set device owner");
            }
        }
    }
}
