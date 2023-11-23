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

package com.android.server.nearby.fastpair.halfsheet;

import static com.android.nearby.halfsheet.constants.Constant.DEVICE_PAIRING_FRAGMENT_TYPE;
import static com.android.nearby.halfsheet.constants.Constant.EXTRA_BINDER;
import static com.android.nearby.halfsheet.constants.Constant.EXTRA_BUNDLE;
import static com.android.nearby.halfsheet.constants.Constant.EXTRA_HALF_SHEET_CONTENT;
import static com.android.nearby.halfsheet.constants.Constant.EXTRA_HALF_SHEET_INFO;
import static com.android.nearby.halfsheet.constants.Constant.EXTRA_HALF_SHEET_TYPE;
import static com.android.nearby.halfsheet.constants.Constant.FAST_PAIR_HALF_SHEET_HELP_URL;
import static com.android.nearby.halfsheet.constants.Constant.RESULT_FAIL;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.annotation.UiThread;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.nearby.FastPairDevice;
import android.nearby.FastPairStatusCallback;
import android.nearby.PairStatusMetadata;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;
import android.util.LruCache;
import android.widget.Toast;

import com.android.internal.annotations.VisibleForTesting;
import com.android.nearby.halfsheet.R;
import com.android.server.nearby.common.eventloop.Annotations;
import com.android.server.nearby.common.eventloop.EventLoop;
import com.android.server.nearby.common.eventloop.NamedRunnable;
import com.android.server.nearby.common.locator.Locator;
import com.android.server.nearby.common.locator.LocatorContextWrapper;
import com.android.server.nearby.fastpair.FastPairController;
import com.android.server.nearby.fastpair.PackageUtils;
import com.android.server.nearby.fastpair.blocklist.Blocklist;
import com.android.server.nearby.fastpair.cache.DiscoveryItem;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import service.proto.Cache;

/**
 * Fast Pair ux manager for half sheet.
 */
public class FastPairHalfSheetManager {
    private static final String ACTIVITY_INTENT_ACTION = "android.nearby.SHOW_HALFSHEET";
    private static final String HALF_SHEET_CLASS_NAME =
            "com.android.nearby.halfsheet.HalfSheetActivity";
    private static final String TAG = "FPHalfSheetManager";
    public static final String FINISHED_STATE = "FINISHED_STATE";
    @VisibleForTesting static final String DISMISS_HALFSHEET_RUNNABLE_NAME = "DismissHalfSheet";
    @VisibleForTesting static final String SHOW_TOAST_RUNNABLE_NAME = "SuccessPairingToast";

    // The timeout to ban half sheet after user trigger the ban logic odd number of time: 5 mins
    private static final int DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS = 300000;
    // Number of seconds half sheet will show after the advertisement is no longer seen.
    private static final int HALF_SHEET_TIME_OUT_SECONDS = 12;

    static final int HALFSHEET_ID_SEED = "new_fast_pair_half_sheet".hashCode();

    private String mHalfSheetApkPkgName;
    private boolean mIsHalfSheetForeground = false;
    private boolean mIsActivePairing = false;
    private Cache.ScanFastPairStoreItem mCurrentScanFastPairStoreItem = null;
    private final LocatorContextWrapper mLocatorContextWrapper;
    private final AtomicInteger mNotificationIds = new AtomicInteger(HALFSHEET_ID_SEED);
    private FastPairHalfSheetBlocklist mHalfSheetBlocklist;
    // Todo: Make "16" a flag, which can be updated from the server side.
    final LruCache<String, Integer> mModelIdMap = new LruCache<>(16);
    HalfSheetDismissState mHalfSheetDismissState = HalfSheetDismissState.ACTIVE;
    // Ban count map track the number of ban happens to certain model id
    // If the model id is baned by the odd number of time it is banned for 5 mins
    // if the model id is banned even number of time ban 24 hours.
    private final Map<Integer, Integer> mBanCountMap = new HashMap<>();

    FastPairUiServiceImpl mFastPairUiService;
    private NamedRunnable mDismissRunnable;

    /**
     * Half sheet state default is active. If user dismiss half sheet once controller will mark half
     * sheet as dismiss state. If user dismiss half sheet twice controller will mark half sheet as
     * ban state for certain period of time.
     */
    enum HalfSheetDismissState {
        ACTIVE,
        DISMISS,
        BAN
    }

    public FastPairHalfSheetManager(Context context) {
        this(new LocatorContextWrapper(context));
        mHalfSheetBlocklist = new FastPairHalfSheetBlocklist();
    }

    @VisibleForTesting
    public FastPairHalfSheetManager(LocatorContextWrapper locatorContextWrapper) {
        mLocatorContextWrapper = locatorContextWrapper;
        mFastPairUiService = new FastPairUiServiceImpl();
        mHalfSheetBlocklist = new FastPairHalfSheetBlocklist();
    }

    /**
     * Invokes half sheet in the other apk. This function can only be called in Nearby because other
     * app can't get the correct component name.
     */
    public void showHalfSheet(Cache.ScanFastPairStoreItem scanFastPairStoreItem) {
        String modelId = scanFastPairStoreItem.getModelId().toLowerCase(Locale.ROOT);
        if (modelId == null) {
            Log.d(TAG, "model id not found");
            return;
        }

        synchronized (mModelIdMap) {
            if (mModelIdMap.get(modelId) == null) {
                mModelIdMap.put(modelId, createNewHalfSheetId());
            }
        }
        int halfSheetId = mModelIdMap.get(modelId);

        if (!allowedToShowHalfSheet(halfSheetId)) {
            Log.d(TAG, "Not allow to show initial Half sheet");
            return;
        }

        // If currently half sheet UI is in the foreground,
        // DO NOT request start-activity to avoid unnecessary memory usage
        if (mIsHalfSheetForeground) {
            updateForegroundHalfSheet(scanFastPairStoreItem);
            return;
        } else {
            // If the half sheet is not in foreground but the system is still pairing
            // with the same device, mark as duplicate request and skip.
            if (mCurrentScanFastPairStoreItem != null && mIsActivePairing
                    && mCurrentScanFastPairStoreItem.getAddress().toLowerCase(Locale.ROOT)
                    .equals(scanFastPairStoreItem.getAddress().toLowerCase(Locale.ROOT))) {
                Log.d(TAG, "Same device is pairing.");
                return;
            }
        }

        try {
            if (mLocatorContextWrapper != null) {
                String packageName = getHalfSheetApkPkgName();
                if (packageName == null) {
                    Log.e(TAG, "package name is null");
                    return;
                }
                mFastPairUiService.setFastPairController(
                        mLocatorContextWrapper.getLocator().get(FastPairController.class));
                Bundle bundle = new Bundle();
                bundle.putBinder(EXTRA_BINDER, mFastPairUiService);
                mLocatorContextWrapper
                        .startActivityAsUser(new Intent(ACTIVITY_INTENT_ACTION)
                                        .putExtra(EXTRA_HALF_SHEET_INFO,
                                                scanFastPairStoreItem.toByteArray())
                                        .putExtra(EXTRA_HALF_SHEET_TYPE,
                                                DEVICE_PAIRING_FRAGMENT_TYPE)
                                        .putExtra(EXTRA_BUNDLE, bundle)
                                        .setComponent(new ComponentName(packageName,
                                                HALF_SHEET_CLASS_NAME)),
                                UserHandle.CURRENT);
                mHalfSheetBlocklist.updateState(halfSheetId, Blocklist.BlocklistState.ACTIVE);
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Can't resolve package that contains half sheet");
        }
        Log.d(TAG, "show initial half sheet.");
        mCurrentScanFastPairStoreItem = scanFastPairStoreItem;
        mIsHalfSheetForeground = true;
        enableAutoDismiss(scanFastPairStoreItem.getAddress(), HALF_SHEET_TIME_OUT_SECONDS);
    }

    /**
     * Auto dismiss half sheet after timeout
     */
    @VisibleForTesting
    void enableAutoDismiss(String address, long timeoutDuration) {
        if (mDismissRunnable == null
                || !mDismissRunnable.name.equals(DISMISS_HALFSHEET_RUNNABLE_NAME)) {
            mDismissRunnable =
                new NamedRunnable(DISMISS_HALFSHEET_RUNNABLE_NAME) {
                    @Override
                    public void run() {
                        Log.d(TAG, "Dismiss the half sheet after "
                                + timeoutDuration + " seconds");
                        // BMW car kit will advertise even after pairing start,
                        // to avoid the half sheet be dismissed during active pairing,
                        // If the half sheet is in the pairing state, disable the auto dismiss.
                        // See b/182396106
                        if (mIsActivePairing) {
                            return;
                        }
                        mIsHalfSheetForeground = false;
                        FastPairStatusCallback pairStatusCallback =
                                mFastPairUiService.getPairStatusCallback();
                        if (pairStatusCallback != null) {
                            pairStatusCallback.onPairUpdate(new FastPairDevice.Builder()
                                            .setBluetoothAddress(address).build(),
                                    new PairStatusMetadata(PairStatusMetadata.Status.DISMISS));
                        } else {
                            Log.w(TAG, "pairStatusCallback is null,"
                                    + " failed to enable auto dismiss ");
                        }
                    }
                };
        }
        if (Locator.get(mLocatorContextWrapper, EventLoop.class).isPosted(mDismissRunnable)) {
            disableDismissRunnable();
        }
        Locator.get(mLocatorContextWrapper, EventLoop.class)
            .postRunnableDelayed(mDismissRunnable, SECONDS.toMillis(timeoutDuration));
    }

    private void updateForegroundHalfSheet(Cache.ScanFastPairStoreItem scanFastPairStoreItem) {
        if (mCurrentScanFastPairStoreItem == null) {
            return;
        }
        if (mCurrentScanFastPairStoreItem.getAddress().toLowerCase(Locale.ROOT)
                .equals(scanFastPairStoreItem.getAddress().toLowerCase(Locale.ROOT))) {
            // If current address is the same, reset the timeout.
            Log.d(TAG, "same Address device, reset the auto dismiss timeout");
            enableAutoDismiss(scanFastPairStoreItem.getAddress(), HALF_SHEET_TIME_OUT_SECONDS);
        } else {
            // If current address is different, not reset timeout
            // wait for half sheet auto dismiss or manually dismiss to start new pair.
            if (mCurrentScanFastPairStoreItem.getModelId().toLowerCase(Locale.ROOT)
                    .equals(scanFastPairStoreItem.getModelId().toLowerCase(Locale.ROOT))) {
                Log.d(TAG, "same model id device is also nearby");
            }
            Log.d(TAG, "showInitialHalfsheet: address changed, from "
                    +  mCurrentScanFastPairStoreItem.getAddress()
                    + " to " + scanFastPairStoreItem.getAddress());
        }
    }

    /**
     * Show passkey confirmation info on half sheet
     */
    public void showPasskeyConfirmation(BluetoothDevice device, int passkey) {
    }

    /**
     * This function will handle pairing steps for half sheet.
     */
    public void showPairingHalfSheet(DiscoveryItem item) {
        Log.d(TAG, "show pairing half sheet");
    }

    /**
     * Shows pairing success info.
     * If the half sheet is not shown, show toast to remind user.
     */
    public void showPairingSuccessHalfSheet(String address) {
        resetPairingStateDisableAutoDismiss();
        if (mIsHalfSheetForeground) {
            FastPairStatusCallback pairStatusCallback = mFastPairUiService.getPairStatusCallback();
            if (pairStatusCallback == null) {
                Log.w(TAG, "FastPairHalfSheetManager failed to show success half sheet because "
                        + "the pairStatusCallback is null");
                return;
            }
            Log.d(TAG, "showPairingSuccess: pairStatusCallback not NULL");
            pairStatusCallback.onPairUpdate(
                    new FastPairDevice.Builder().setBluetoothAddress(address).build(),
                    new PairStatusMetadata(PairStatusMetadata.Status.SUCCESS));
        } else {
            Locator.get(mLocatorContextWrapper, EventLoop.class)
                    .postRunnable(
                            new NamedRunnable(SHOW_TOAST_RUNNABLE_NAME) {
                                @Override
                                public void run() {
                                    try {
                                        Toast.makeText(mLocatorContextWrapper,
                                                mLocatorContextWrapper
                                                        .getPackageManager()
                                                        .getResourcesForApplication(
                                                                getHalfSheetApkPkgName())
                                                        .getString(R.string.fast_pair_device_ready),
                                                Toast.LENGTH_LONG).show();
                                    } catch (PackageManager.NameNotFoundException e) {
                                        Log.d(TAG, "showPairingSuccess fail:"
                                                + " package name cannot be found ");
                                        e.printStackTrace();
                                    }
                                }
                            });
        }
    }

    /**
     * Shows pairing fail half sheet.
     * If the half sheet is not shown, create a new half sheet to help user go to Setting
     * to manually pair with the device.
     */
    public void showPairingFailed() {
        resetPairingStateDisableAutoDismiss();
        if (mCurrentScanFastPairStoreItem == null) {
            return;
        }
        if (mIsHalfSheetForeground) {
            FastPairStatusCallback pairStatusCallback = mFastPairUiService.getPairStatusCallback();
            if (pairStatusCallback != null) {
                Log.v(TAG, "showPairingFailed: pairStatusCallback not NULL");
                pairStatusCallback.onPairUpdate(
                        new FastPairDevice.Builder()
                                .setBluetoothAddress(mCurrentScanFastPairStoreItem.getAddress())
                                .build(),
                        new PairStatusMetadata(PairStatusMetadata.Status.FAIL));
            } else {
                Log.w(TAG, "FastPairHalfSheetManager failed to show fail half sheet because "
                        + "the pairStatusCallback is null");
            }
        } else {
            String packageName = getHalfSheetApkPkgName();
            if (packageName == null) {
                Log.e(TAG, "package name is null");
                return;
            }
            Bundle bundle = new Bundle();
            bundle.putBinder(EXTRA_BINDER, mFastPairUiService);
            mLocatorContextWrapper
                    .startActivityAsUser(new Intent(ACTIVITY_INTENT_ACTION)
                                    .putExtra(EXTRA_HALF_SHEET_INFO,
                                            mCurrentScanFastPairStoreItem.toByteArray())
                                    .putExtra(EXTRA_HALF_SHEET_TYPE,
                                            DEVICE_PAIRING_FRAGMENT_TYPE)
                                    .putExtra(EXTRA_HALF_SHEET_CONTENT, RESULT_FAIL)
                                    .putExtra(EXTRA_BUNDLE, bundle)
                                    .setComponent(new ComponentName(packageName,
                                            HALF_SHEET_CLASS_NAME)),
                            UserHandle.CURRENT);
            Log.d(TAG, "Starts a new half sheet to showPairingFailed");
            String modelId = mCurrentScanFastPairStoreItem.getModelId().toLowerCase(Locale.ROOT);
            if (modelId == null || mModelIdMap.get(modelId) == null) {
                Log.d(TAG, "info not enough");
                return;
            }
            int halfSheetId = mModelIdMap.get(modelId);
            mHalfSheetBlocklist.updateState(halfSheetId, Blocklist.BlocklistState.ACTIVE);
        }
    }

    /**
     * Removes dismiss half sheet runnable. When half sheet shows, there is timer for half sheet to
     * dismiss. But when user is pairing, half sheet should not dismiss.
     * So this function disable the runnable.
     */
    public void disableDismissRunnable() {
        if (mDismissRunnable == null) {
            return;
        }
        Log.d(TAG, "remove dismiss runnable");
        Locator.get(mLocatorContextWrapper, EventLoop.class).removeRunnable(mDismissRunnable);
    }

    /**
     * When user first click back button or click the empty space in half sheet the half sheet will
     * be banned for certain short period of time for that device model id. When user click cancel
     * or dismiss half sheet for the second time the half sheet related item should be added to
     * blocklist so the half sheet will not show again to interrupt user.
     *
     * @param modelId half sheet display item modelId.
     */
    @Annotations.EventThread
    public void dismiss(String modelId) {
        Log.d(TAG, "HalfSheetManager report dismiss device modelId: " + modelId);
        mIsHalfSheetForeground = false;
        Integer halfSheetId = mModelIdMap.get(modelId);
        if (mDismissRunnable != null
                && Locator.get(mLocatorContextWrapper, EventLoop.class)
                          .isPosted(mDismissRunnable)) {
            disableDismissRunnable();
        }
        if (halfSheetId != null) {
            Log.d(TAG, "id: " + halfSheetId + " half sheet is dismissed");
            boolean isDontShowAgain =
                    !mHalfSheetBlocklist.updateState(halfSheetId,
                            Blocklist.BlocklistState.DISMISSED);
            if (isDontShowAgain) {
                if (!mBanCountMap.containsKey(halfSheetId)) {
                    mBanCountMap.put(halfSheetId, 0);
                }
                int dismissCountTrack = mBanCountMap.get(halfSheetId) + 1;
                mBanCountMap.put(halfSheetId, dismissCountTrack);
                if (dismissCountTrack % 2 == 1) {
                    Log.d(TAG, "id: " + halfSheetId + " half sheet is short time banned");
                    mHalfSheetBlocklist.forceUpdateState(halfSheetId,
                            Blocklist.BlocklistState.DO_NOT_SHOW_AGAIN);
                } else {
                    Log.d(TAG, "id: " + halfSheetId +  " half sheet is long time banned");
                    mHalfSheetBlocklist.updateState(halfSheetId,
                            Blocklist.BlocklistState.DO_NOT_SHOW_AGAIN_LONG);
                }
            }
        }
    }

    /**
     * Changes the half sheet ban state to active.
     */
    @UiThread
    public void resetBanState(String modelId) {
        Log.d(TAG, "HalfSheetManager reset device ban state modelId: " + modelId);
        Integer halfSheetId = mModelIdMap.get(modelId);
        if (halfSheetId == null) {
            Log.d(TAG, "halfSheetId not found.");
            return;
        }
        mHalfSheetBlocklist.resetBlockState(halfSheetId);
    }

    // Invokes this method to reset some states when showing the pairing result.
    private void resetPairingStateDisableAutoDismiss() {
        mIsActivePairing = false;
        if (mDismissRunnable != null && Locator.get(mLocatorContextWrapper, EventLoop.class)
                .isPosted(mDismissRunnable)) {
            disableDismissRunnable();
        }
    }

    /**
     * When the device pairing finished should remove the suppression for the model id
     * so the user canntry twice if the user want to.
     */
    public void reportDonePairing(int halfSheetId) {
        mHalfSheetBlocklist.removeBlocklist(halfSheetId);
    }

    @VisibleForTesting
    public FastPairHalfSheetBlocklist getHalfSheetBlocklist() {
        return mHalfSheetBlocklist;
    }

    /**
     * Destroys the bluetooth pairing controller.
     */
    public void destroyBluetoothPairController() {
    }

    /**
     * Notifies manager the pairing has finished.
     */
    public void notifyPairingProcessDone(boolean success, String address, DiscoveryItem item) {
        mCurrentScanFastPairStoreItem = null;
        mIsHalfSheetForeground = false;
    }

    private boolean allowedToShowHalfSheet(int halfSheetId) {
        // Half Sheet will not show when the screen is locked so disable half sheet
        KeyguardManager keyguardManager =
                mLocatorContextWrapper.getSystemService(KeyguardManager.class);
        if (keyguardManager != null && keyguardManager.isKeyguardLocked()) {
            Log.d(TAG, "device is locked");
            return false;
        }

        // Check whether the blocklist state has expired
        if (mHalfSheetBlocklist.isStateExpired(halfSheetId)) {
            mHalfSheetBlocklist.removeBlocklist(halfSheetId);
            mBanCountMap.remove(halfSheetId);
        }

        // Half Sheet will not show when the model id is banned
        if (mHalfSheetBlocklist.isBlocklisted(
                halfSheetId, DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS)) {
            Log.d(TAG, "id: " + halfSheetId + " is blocked");
            return false;
        }
        return  !isHelpPageForeground();
    }

    /**
    * Checks if the user already open the info page, return true to suppress half sheet.
    * ActivityManager#getRunningTasks to get the most recent task and check the baseIntent's
    * url to see if we should suppress half sheet.
    */
    private boolean isHelpPageForeground() {
        ActivityManager activityManager =
                mLocatorContextWrapper.getSystemService(ActivityManager.class);
        if (activityManager == null) {
            Log.d(TAG, "ActivityManager is null");
            return false;
        }
        try {
            List<ActivityManager.RunningTaskInfo> taskInfos = activityManager.getRunningTasks(1);
            if (taskInfos.isEmpty()) {
                Log.d(TAG, "Empty running tasks");
                return false;
            }
            String url = taskInfos.get(0).baseIntent.getDataString();
            Log.d(TAG, "Info page url:" + url);
            if (FAST_PAIR_HALF_SHEET_HELP_URL.equals(url)) {
                return true;
            }
        } catch (SecurityException e) {
            Log.d(TAG, "Unable to get running tasks");
        }
        return false;
    }

    /** Report actively pairing when the Fast Pair starts. */
    public void reportActivelyPairing() {
        mIsActivePairing = true;
    }


    private Integer createNewHalfSheetId() {
        return mNotificationIds.getAndIncrement();
    }

    /** Gets the half sheet status whether it is foreground or dismissed */
    public boolean getHalfSheetForeground() {
        return mIsHalfSheetForeground;
    }

    /** Sets whether the half sheet is at the foreground or not. */
    public void setHalfSheetForeground(boolean state) {
        mIsHalfSheetForeground = state;
    }

    /** Returns whether the fast pair is actively pairing . */
    @VisibleForTesting
    public boolean isActivePairing() {
        return mIsActivePairing;
    }

    /** Sets fast pair to be active pairing or not, used for testing. */
    @VisibleForTesting
    public void setIsActivePairing(boolean isActivePairing) {
        mIsActivePairing = isActivePairing;
    }

    /**
     * Gets the package name of HalfSheet.apk
     * getHalfSheetApkPkgName may invoke PackageManager multiple times and it does not have
     * race condition check. Since there is no lock for mHalfSheetApkPkgName.
     */
    private String getHalfSheetApkPkgName() {
        if (mHalfSheetApkPkgName != null) {
            return mHalfSheetApkPkgName;
        }
        mHalfSheetApkPkgName = PackageUtils.getHalfSheetApkPkgName(mLocatorContextWrapper);
        Log.v(TAG, "Found halfsheet APK at: " + mHalfSheetApkPkgName);
        return mHalfSheetApkPkgName;
    }
}
