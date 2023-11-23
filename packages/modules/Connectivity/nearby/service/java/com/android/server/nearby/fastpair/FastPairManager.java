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

package com.android.server.nearby.fastpair;

import static com.android.nearby.halfsheet.constants.Constant.ACTION_FAST_PAIR;
import static com.android.nearby.halfsheet.constants.Constant.ACTION_FAST_PAIR_HALF_SHEET_BAN_STATE_RESET;
import static com.android.nearby.halfsheet.constants.Constant.ACTION_FAST_PAIR_HALF_SHEET_CANCEL;
import static com.android.nearby.halfsheet.constants.Constant.ACTION_HALF_SHEET_FOREGROUND_STATE;
import static com.android.nearby.halfsheet.constants.Constant.EXTRA_HALF_SHEET_FOREGROUND;
import static com.android.nearby.halfsheet.constants.FastPairConstants.EXTRA_MODEL_ID;
import static com.android.server.nearby.fastpair.Constant.TAG;

import static com.google.common.io.BaseEncoding.base16;

import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.app.KeyguardManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.nearby.FastPairDevice;
import android.nearby.NearbyDevice;
import android.nearby.NearbyManager;
import android.nearby.ScanCallback;
import android.nearby.ScanRequest;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.server.nearby.common.ble.decode.FastPairDecoder;
import com.android.server.nearby.common.bluetooth.BluetoothException;
import com.android.server.nearby.common.bluetooth.fastpair.FastPairConnection;
import com.android.server.nearby.common.bluetooth.fastpair.FastPairDualConnection;
import com.android.server.nearby.common.bluetooth.fastpair.PairingException;
import com.android.server.nearby.common.bluetooth.fastpair.Preferences;
import com.android.server.nearby.common.bluetooth.fastpair.ReflectionException;
import com.android.server.nearby.common.bluetooth.fastpair.SimpleBroadcastReceiver;
import com.android.server.nearby.common.eventloop.Annotations;
import com.android.server.nearby.common.eventloop.EventLoop;
import com.android.server.nearby.common.eventloop.NamedRunnable;
import com.android.server.nearby.common.locator.Locator;
import com.android.server.nearby.common.locator.LocatorContextWrapper;
import com.android.server.nearby.fastpair.cache.DiscoveryItem;
import com.android.server.nearby.fastpair.cache.FastPairCacheManager;
import com.android.server.nearby.fastpair.footprint.FootprintsDeviceManager;
import com.android.server.nearby.fastpair.halfsheet.FastPairHalfSheetManager;
import com.android.server.nearby.fastpair.pairinghandler.PairingProgressHandlerBase;
import com.android.server.nearby.util.ForegroundThread;
import com.android.server.nearby.util.Hex;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;

import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import service.proto.Cache;
import service.proto.Rpcs;

/**
 * FastPairManager is the class initiated in nearby service to handle Fast Pair related
 * work.
 */

public class FastPairManager {

    private static final String ACTION_PREFIX = UserActionHandler.PREFIX;
    private static final int WAIT_FOR_UNLOCK_MILLIS = 5000;

    /** A notification ID which should be dismissed */
    public static final String EXTRA_NOTIFICATION_ID = ACTION_PREFIX + "EXTRA_NOTIFICATION_ID";

    private static Executor sFastPairExecutor;

    private ContentObserver mFastPairScanChangeContentObserver = null;

    final LocatorContextWrapper mLocatorContextWrapper;
    final IntentFilter mIntentFilter;
    final Locator mLocator;
    private boolean mScanEnabled = false;
    private final FastPairCacheManager mFastPairCacheManager;

    private final BroadcastReceiver mScreenBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            switch (action) {
                case Intent.ACTION_SCREEN_ON:
                    Log.d(TAG, "onReceive: ACTION_SCREEN_ON");
                    invalidateScan();
                    break;
                case Intent.ACTION_BOOT_COMPLETED:
                    Log.d(TAG, "onReceive: ACTION_BOOT_COMPLETED.");
                    invalidateScan();
                    break;
                case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                    Log.d(TAG, "onReceive: ACTION_BOND_STATE_CHANGED");
                    processBluetoothConnectionEvent(intent);
                    break;
                case ACTION_HALF_SHEET_FOREGROUND_STATE:
                    boolean state = intent.getBooleanExtra(EXTRA_HALF_SHEET_FOREGROUND, false);
                    Log.d(TAG, "halfsheet report foreground state:  " + state);
                    Locator.get(mLocatorContextWrapper, FastPairHalfSheetManager.class)
                            .setHalfSheetForeground(state);
                    break;
                case ACTION_FAST_PAIR_HALF_SHEET_BAN_STATE_RESET:
                    Log.d(TAG, "onReceive: ACTION_FAST_PAIR_HALF_SHEET_BAN_STATE_RESET");
                    String deviceModelId = intent.getStringExtra(EXTRA_MODEL_ID);
                    if (deviceModelId == null) {
                        Log.d(TAG, "HalfSheetManager reset device ban state skipped, "
                                + "deviceModelId not found");
                        break;
                    }
                    Locator.get(mLocatorContextWrapper, FastPairHalfSheetManager.class)
                            .resetBanState(deviceModelId);
                    break;
                case ACTION_FAST_PAIR_HALF_SHEET_CANCEL:
                    Log.d(TAG, "onReceive: ACTION_FAST_PAIR_HALF_SHEET_CANCEL");
                    String modelId = intent.getStringExtra(EXTRA_MODEL_ID);
                    if (modelId == null) {
                        Log.d(TAG, "skip half sheet cancel action, model id not found");
                        break;
                    }
                    Locator.get(mLocatorContextWrapper, FastPairHalfSheetManager.class)
                            .dismiss(modelId);
                    break;
                case ACTION_FAST_PAIR:
                    Log.d(TAG, "onReceive: ACTION_FAST_PAIR");
                    String itemId = intent.getStringExtra(UserActionHandler.EXTRA_ITEM_ID);
                    String accountKeyString = intent
                            .getStringExtra(UserActionHandler.EXTRA_FAST_PAIR_SECRET);
                    if (itemId == null || accountKeyString == null) {
                        Log.d(TAG, "skip pair action, item id "
                                + "or fast pair account key not found");
                        break;
                    }
                    try {
                        FastPairController controller =
                                Locator.getFromContextWrapper(mLocatorContextWrapper,
                                        FastPairController.class);
                        if (mFastPairCacheManager != null) {
                            controller.pair(mFastPairCacheManager.getDiscoveryItem(itemId),
                                    base16().decode(accountKeyString), /* companionApp= */ null);
                        }
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "Cannot find FastPairController class", e);
                    }
            }
        }
    };

    public FastPairManager(LocatorContextWrapper contextWrapper) {
        mLocatorContextWrapper = contextWrapper;
        mIntentFilter = new IntentFilter();
        mLocator = mLocatorContextWrapper.getLocator();
        mLocator.bind(new FastPairModule());
        Rpcs.GetObservedDeviceResponse getObservedDeviceResponse =
                Rpcs.GetObservedDeviceResponse.newBuilder().build();
        mFastPairCacheManager =
                Locator.getFromContextWrapper(mLocatorContextWrapper, FastPairCacheManager.class);
    }

    final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onDiscovered(@NonNull NearbyDevice device) {
            Locator.get(mLocatorContextWrapper, FastPairAdvHandler.class).handleBroadcast(device);
        }

        @Override
        public void onUpdated(@NonNull NearbyDevice device) {
            FastPairDevice fastPairDevice = (FastPairDevice) device;
            byte[] modelArray = FastPairDecoder.getModelId(fastPairDevice.getData());
            Log.d(TAG, "update model id" + Hex.bytesToStringLowercase(modelArray));
        }

        @Override
        public void onLost(@NonNull NearbyDevice device) {
            FastPairDevice fastPairDevice = (FastPairDevice) device;
            byte[] modelArray = FastPairDecoder.getModelId(fastPairDevice.getData());
            Log.d(TAG, "lost model id" + Hex.bytesToStringLowercase(modelArray));
        }

        @Override
        public void onError(int errorCode) {
            Log.w(TAG, "[FastPairManager] Scan error is " + errorCode);
        }
    };

    /**
     * Function called when nearby service start.
     */
    public void initiate() {
        mIntentFilter.addAction(Intent.ACTION_SCREEN_ON);
        mIntentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        mIntentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        mIntentFilter.addAction(Intent.ACTION_BOOT_COMPLETED);
        mIntentFilter.addAction(ACTION_FAST_PAIR_HALF_SHEET_CANCEL);
        mIntentFilter.addAction(ACTION_FAST_PAIR_HALF_SHEET_BAN_STATE_RESET);
        mIntentFilter.addAction(ACTION_HALF_SHEET_FOREGROUND_STATE);
        mIntentFilter.addAction(ACTION_FAST_PAIR);

        mLocatorContextWrapper.getContext().registerReceiver(mScreenBroadcastReceiver,
                mIntentFilter, Context.RECEIVER_EXPORTED);
    }

    /**
     * Function to free up fast pair resource.
     */
    public void cleanUp() {
        mLocatorContextWrapper.getContext().unregisterReceiver(mScreenBroadcastReceiver);
        if (mFastPairScanChangeContentObserver != null) {
            mLocatorContextWrapper.getContentResolver().unregisterContentObserver(
                    mFastPairScanChangeContentObserver);
        }
    }

    /**
     * Starts fast pair process.
     */
    @Annotations.EventThread
    public static Future<Void> pair(
            ExecutorService executor,
            Context context,
            DiscoveryItem item,
            @Nullable byte[] accountKey,
            @Nullable String companionApp,
            FootprintsDeviceManager footprints,
            PairingProgressHandlerBase pairingProgressHandlerBase) {
        return executor.submit(
                () -> pairInternal(context, item, companionApp, accountKey, footprints,
                        pairingProgressHandlerBase), /* result= */ null);
    }

    /**
     * Starts fast pair
     */
    @WorkerThread
    public static void pairInternal(
            Context context,
            DiscoveryItem item,
            @Nullable String companionApp,
            @Nullable byte[] accountKey,
            FootprintsDeviceManager footprints,
            PairingProgressHandlerBase pairingProgressHandlerBase) {
        FastPairHalfSheetManager fastPairHalfSheetManager =
                Locator.get(context, FastPairHalfSheetManager.class);
        try {
            pairingProgressHandlerBase.onPairingStarted();
            if (pairingProgressHandlerBase.skipWaitingScreenUnlock()) {
                // Do nothing due to we are not showing the status notification in some pairing
                // types, e.g. the retroactive pairing.
            } else {
                // If the screen is locked when the user taps to pair, the screen will unlock. We
                // must wait for the unlock to complete before showing the status notification, or
                // it won't be heads-up.
                pairingProgressHandlerBase.onWaitForScreenUnlock();
                waitUntilScreenIsUnlocked(context);
                pairingProgressHandlerBase.onScreenUnlocked();
            }
            BluetoothAdapter bluetoothAdapter = getBluetoothAdapter(context);

            boolean isBluetoothEnabled = bluetoothAdapter != null && bluetoothAdapter.isEnabled();
            if (!isBluetoothEnabled) {
                if (bluetoothAdapter == null || !bluetoothAdapter.enable()) {
                    Log.d(TAG, "FastPair: Failed to enable bluetooth");
                    return;
                }
                Log.v(TAG, "FastPair: Enabling bluetooth for fast pair");

                Locator.get(context, EventLoop.class)
                        .postRunnable(
                                new NamedRunnable("enableBluetoothToast") {
                                    @Override
                                    public void run() {
                                        Log.d(TAG, "Enable bluetooth toast test");
                                    }
                                });
                // Set up call back to call this function again once bluetooth has been
                // enabled; this does not seem to be a problem as the device connects without a
                // problem, but in theory the timeout also includes turning on bluetooth now.
            }

            pairingProgressHandlerBase.onReadyToPair();

            String modelId = item.getTriggerId();
            Preferences.Builder prefsBuilder =
                    Preferences.builder()
                            .setEnableBrEdrHandover(false)
                            .setIgnoreDiscoveryError(true);
            pairingProgressHandlerBase.onSetupPreferencesBuilder(prefsBuilder);
            if (item.getFastPairInformation() != null) {
                prefsBuilder.setSkipConnectingProfiles(
                        item.getFastPairInformation().getDataOnlyConnection());
            }
            // When add watch and auto device needs to change the config
            prefsBuilder.setRejectMessageAccess(true);
            prefsBuilder.setRejectPhonebookAccess(true);
            prefsBuilder.setHandlePasskeyConfirmationByUi(false);

            FastPairConnection connection = new FastPairDualConnection(
                    context, item.getMacAddress(),
                    prefsBuilder.build(),
                    null);
            connection.setOnPairedCallback(
                    address -> {
                        Log.v(TAG, "connection on paired callback;");
                        // TODO(b/259150992) add fill Bluetooth metadata values logic
                        pairingProgressHandlerBase.onPairedCallbackCalled(
                                connection, accountKey, footprints, address);
                    });
            pairingProgressHandlerBase.onPairingSetupCompleted();

            FastPairConnection.SharedSecret sharedSecret;
            if ((accountKey != null || item.getAuthenticationPublicKeySecp256R1() != null)) {
                sharedSecret =
                        connection.pair(
                                accountKey != null ? accountKey
                                        : item.getAuthenticationPublicKeySecp256R1());
                if (accountKey == null) {
                    // Account key is null so it is initial pairing
                    if (sharedSecret != null) {
                        Locator.get(context, FastPairController.class).addDeviceToFootprint(
                                connection.getPublicAddress(), sharedSecret.getKey(), item);
                        cacheFastPairDevice(context, connection.getPublicAddress(),
                                sharedSecret.getKey(), item);
                    }
                }
            } else {
                // Fast Pair one
                connection.pair();
            }
            // TODO(b/213373051): Merge logic with pairingProgressHandlerBase or delete the
            // pairingProgressHandlerBase class.
            fastPairHalfSheetManager.showPairingSuccessHalfSheet(connection.getPublicAddress());
            pairingProgressHandlerBase.onPairingSuccess(connection.getPublicAddress());
        } catch (BluetoothException
                | InterruptedException
                | ReflectionException
                | TimeoutException
                | ExecutionException
                | PairingException
                | GeneralSecurityException e) {
            Log.e(TAG, "Failed to pair.", e);

            // TODO(b/213373051): Merge logic with pairingProgressHandlerBase or delete the
            // pairingProgressHandlerBase class.
            fastPairHalfSheetManager.showPairingFailed();
            pairingProgressHandlerBase.onPairingFailed(e);
        }
    }

    private static void cacheFastPairDevice(Context context, String publicAddress, byte[] key,
            DiscoveryItem item) {
        try {
            Locator.get(context, EventLoop.class).postAndWait(
                    new NamedRunnable("FastPairCacheDevice") {
                        @Override
                        public void run() {
                            Cache.StoredFastPairItem storedFastPairItem =
                                    Cache.StoredFastPairItem.newBuilder()
                                            .setMacAddress(publicAddress)
                                            .setAccountKey(ByteString.copyFrom(key))
                                            .setModelId(item.getTriggerId())
                                            .addAllFeatures(item.getFastPairInformation() == null
                                                    ? ImmutableList.of() :
                                                    item.getFastPairInformation().getFeaturesList())
                                            .setDiscoveryItem(item.getCopyOfStoredItem())
                                            .build();
                            Locator.get(context, FastPairCacheManager.class)
                                    .putStoredFastPairItem(storedFastPairItem);
                        }
                    }
            );
        } catch (InterruptedException e) {
            Log.e(TAG, "Fail to insert paired device into cache");
        }
    }

    /** Checks if the pairing is initial pairing with fast pair 2.0 design. */
    public static boolean isThroughFastPair2InitialPairing(
            DiscoveryItem item, @Nullable byte[] accountKey) {
        return accountKey == null && item.getAuthenticationPublicKeySecp256R1() != null;
    }

    private static void waitUntilScreenIsUnlocked(Context context)
            throws InterruptedException, ExecutionException, TimeoutException {
        KeyguardManager keyguardManager = context.getSystemService(KeyguardManager.class);

        // KeyguardManager's isScreenLocked() counterintuitively returns false when the lock screen
        // is showing if the user has set "swipe to unlock" (i.e. no required password, PIN, or
        // pattern) So we use this method instead, which returns true when on the lock screen
        // regardless.
        if (keyguardManager.isKeyguardLocked()) {
            Log.v(TAG, "FastPair: Screen is locked, waiting until unlocked "
                    + "to show status notifications.");
            try (SimpleBroadcastReceiver isUnlockedReceiver =
                         SimpleBroadcastReceiver.oneShotReceiver(
                                 context, FlagUtils.getPreferencesBuilder().build(),
                                 Intent.ACTION_USER_PRESENT)) {
                isUnlockedReceiver.await(WAIT_FOR_UNLOCK_MILLIS, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * Processed task in a background thread
     */
    @Annotations.EventThread
    public static void processBackgroundTask(Runnable runnable) {
        getExecutor().execute(runnable);
    }

    /**
     * This function should only be called on main thread since there is no lock
     */
    private static Executor getExecutor() {
        if (sFastPairExecutor != null) {
            return sFastPairExecutor;
        }
        sFastPairExecutor = Executors.newSingleThreadExecutor();
        return sFastPairExecutor;
    }

    /**
     * Null when the Nearby Service is not available.
     */
    @Nullable
    private NearbyManager getNearbyManager() {
        return (NearbyManager) mLocatorContextWrapper
                .getApplicationContext().getSystemService(Context.NEARBY_SERVICE);
    }

    /**
     * Starts or stops scanning according to mAllowScan value.
     */
    private void invalidateScan() {
        NearbyManager nearbyManager = getNearbyManager();
        if (nearbyManager == null) {
            Log.w(TAG, "invalidateScan: "
                    + "failed to start or stop scanning because NearbyManager is null.");
            return;
        }
        if (mScanEnabled) {
            Log.v(TAG, "invalidateScan: scan is enabled");
            nearbyManager.startScan(new ScanRequest.Builder()
                            .setScanType(ScanRequest.SCAN_TYPE_FAST_PAIR).build(),
                    ForegroundThread.getExecutor(),
                    mScanCallback);
        } else {
            Log.v(TAG, "invalidateScan: scan is disabled");
            nearbyManager.stopScan(mScanCallback);
        }
    }

    /**
     * When certain device is forgotten we need to remove the info from database because the info
     * is no longer useful.
     */
    private void processBluetoothConnectionEvent(Intent intent) {
        int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                BluetoothDevice.ERROR);
        if (bondState == BluetoothDevice.BOND_NONE) {
            BluetoothDevice device =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device != null) {
                Log.d("FastPairService", "Forget device detect");
                processBackgroundTask(new Runnable() {
                    @Override
                    public void run() {
                        mFastPairCacheManager.removeStoredFastPairItem(device.getAddress());
                    }
                });
            }

        }
    }

    /**
     * Helper function to get bluetooth adapter.
     */
    @Nullable
    public static BluetoothAdapter getBluetoothAdapter(Context context) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }
}
