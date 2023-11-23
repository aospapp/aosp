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

package com.android.nearby.halfsheet;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;

import static com.android.nearby.halfsheet.constants.Constant.ACTION_FAST_PAIR_HALF_SHEET_BAN_STATE_RESET;
import static com.android.nearby.halfsheet.constants.Constant.ACTION_FAST_PAIR_HALF_SHEET_CANCEL;
import static com.android.nearby.halfsheet.constants.Constant.ACTION_HALF_SHEET_FOREGROUND_STATE;
import static com.android.nearby.halfsheet.constants.Constant.DEVICE_PAIRING_FRAGMENT_TYPE;
import static com.android.nearby.halfsheet.constants.Constant.EXTRA_HALF_SHEET_FOREGROUND;
import static com.android.nearby.halfsheet.constants.Constant.EXTRA_HALF_SHEET_INFO;
import static com.android.nearby.halfsheet.constants.Constant.EXTRA_HALF_SHEET_IS_RETROACTIVE;
import static com.android.nearby.halfsheet.constants.Constant.EXTRA_HALF_SHEET_IS_SUBSEQUENT_PAIR;
import static com.android.nearby.halfsheet.constants.Constant.EXTRA_HALF_SHEET_TYPE;
import static com.android.nearby.halfsheet.constants.Constant.TAG;
import static com.android.nearby.halfsheet.constants.FastPairConstants.EXTRA_MODEL_ID;
import static com.android.nearby.halfsheet.constants.UserActionHandlerBase.EXTRA_MAC_ADDRESS;
import static com.android.nearby.halfsheet.fragment.DevicePairingFragment.APP_LAUNCH_FRAGMENT_TYPE;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.android.nearby.halfsheet.fragment.DevicePairingFragment;
import com.android.nearby.halfsheet.fragment.HalfSheetModuleFragment;
import com.android.nearby.halfsheet.utils.BroadcastUtils;

import com.google.protobuf.InvalidProtocolBufferException;

import java.util.Locale;

import service.proto.Cache;

/**
 * A class show Fast Pair related information in Half sheet format.
 */
public class HalfSheetActivity extends FragmentActivity {
    @Nullable
    private HalfSheetModuleFragment mHalfSheetModuleFragment;
    @Nullable
    private Cache.ScanFastPairStoreItem mScanFastPairStoreItem;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        byte[] infoArray = getIntent().getByteArrayExtra(EXTRA_HALF_SHEET_INFO);
        String fragmentType = getIntent().getStringExtra(EXTRA_HALF_SHEET_TYPE);
        if (infoArray == null || fragmentType == null) {
            Log.d(
                    "HalfSheetActivity",
                    "exit flag off or do not have enough half sheet information.");
            finish();
            return;
        }

        switch (fragmentType) {
            case DEVICE_PAIRING_FRAGMENT_TYPE:
                mHalfSheetModuleFragment = DevicePairingFragment.newInstance(getIntent(),
                        savedInstanceState);
                if (mHalfSheetModuleFragment == null) {
                    Log.d(TAG, "device pairing fragment has error.");
                    finish();
                    return;
                }
                break;
            case APP_LAUNCH_FRAGMENT_TYPE:
                // currentFragment = AppLaunchFragment.newInstance(getIntent());
                if (mHalfSheetModuleFragment == null) {
                    Log.v(TAG, "app launch fragment has error.");
                    finish();
                    return;
                }
                break;
            default:
                Log.w(TAG, "there is no valid type for half sheet");
                finish();
                return;
        }
        if (mHalfSheetModuleFragment != null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment_container, mHalfSheetModuleFragment)
                    .commit();
        }
        setContentView(R.layout.fast_pair_half_sheet);

        // If the user taps on the background, then close the activity.
        // Unless they tap on the card itself, then ignore the tap.
        findViewById(R.id.background).setOnClickListener(v -> onCancelClicked());
        findViewById(R.id.card)
                .setOnClickListener(
                        v -> Log.v(TAG, "card view is clicked noop"));
        try {
            mScanFastPairStoreItem =
                    Cache.ScanFastPairStoreItem.parseFrom(infoArray);
        } catch (InvalidProtocolBufferException e) {
            Log.w(
                    TAG, "error happens when pass info to half sheet");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        BroadcastUtils.sendBroadcast(
                this,
                new Intent(ACTION_HALF_SHEET_FOREGROUND_STATE)
                        .putExtra(EXTRA_HALF_SHEET_FOREGROUND, true));
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);
        if (mHalfSheetModuleFragment != null) {
            mHalfSheetModuleFragment.onSaveInstanceState(savedInstanceState);
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        sendHalfSheetCancelBroadcast();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        sendHalfSheetCancelBroadcast();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String fragmentType = getIntent().getStringExtra(EXTRA_HALF_SHEET_TYPE);
        if (fragmentType == null) {
            return;
        }
        if (fragmentType.equals(DEVICE_PAIRING_FRAGMENT_TYPE)
                && intent.getExtras() != null
                && intent.getByteArrayExtra(EXTRA_HALF_SHEET_INFO) != null) {
            try {
                Cache.ScanFastPairStoreItem testScanFastPairStoreItem =
                        Cache.ScanFastPairStoreItem.parseFrom(
                                intent.getByteArrayExtra(EXTRA_HALF_SHEET_INFO));
                if (mScanFastPairStoreItem != null
                        && !testScanFastPairStoreItem.getAddress().equals(
                        mScanFastPairStoreItem.getAddress())
                        && testScanFastPairStoreItem.getModelId().equals(
                        mScanFastPairStoreItem.getModelId())) {
                    Log.d(TAG, "possible factory reset happens");
                    halfSheetStateChange();
                }
            } catch (InvalidProtocolBufferException | NullPointerException e) {
                Log.w(TAG, "error happens when pass info to half sheet");
            }
        }
    }

    /** This function should be called when user click empty area and cancel button. */
    public void onCancelClicked() {
        Log.d(TAG, "Cancels the half sheet and paring.");
        sendHalfSheetCancelBroadcast();
        finish();
    }

    /** Changes the half sheet foreground state to false. */
    public void halfSheetStateChange() {
        BroadcastUtils.sendBroadcast(
                this,
                new Intent(ACTION_HALF_SHEET_FOREGROUND_STATE)
                        .putExtra(EXTRA_HALF_SHEET_FOREGROUND, false));
        finish();
    }


    /**
     * Changes the half sheet ban state to active.
     * Sometimes users leave half sheet to go to fast pair info page,
     * we do not want the behavior to be counted as dismiss.
     */
    public void sendBanStateResetBroadcast() {
        if (mScanFastPairStoreItem == null) {
            return;
        }
        BroadcastUtils.sendBroadcast(
                this,
                new Intent(ACTION_FAST_PAIR_HALF_SHEET_BAN_STATE_RESET)
                        .putExtra(EXTRA_MODEL_ID, mScanFastPairStoreItem.getModelId()
                                .toLowerCase(Locale.ROOT)));
    }

    private void sendHalfSheetCancelBroadcast() {
        BroadcastUtils.sendBroadcast(
                this,
                new Intent(ACTION_HALF_SHEET_FOREGROUND_STATE)
                        .putExtra(EXTRA_HALF_SHEET_FOREGROUND, false));
        if (mScanFastPairStoreItem == null) {
            return;
        }
        BroadcastUtils.sendBroadcast(
                this,
                new Intent(ACTION_FAST_PAIR_HALF_SHEET_CANCEL)
                        .putExtra(EXTRA_MODEL_ID,
                                mScanFastPairStoreItem.getModelId().toLowerCase(Locale.ROOT))
                        .putExtra(EXTRA_HALF_SHEET_TYPE,
                                getIntent().getStringExtra(EXTRA_HALF_SHEET_TYPE))
                        .putExtra(
                                EXTRA_HALF_SHEET_IS_SUBSEQUENT_PAIR,
                                getIntent().getBooleanExtra(EXTRA_HALF_SHEET_IS_SUBSEQUENT_PAIR,
                                        false))
                        .putExtra(
                                EXTRA_HALF_SHEET_IS_RETROACTIVE,
                                getIntent().getBooleanExtra(EXTRA_HALF_SHEET_IS_RETROACTIVE,
                                        false))
                        .putExtra(EXTRA_MAC_ADDRESS, mScanFastPairStoreItem.getAddress()),
                ACCESS_FINE_LOCATION);
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(title);
        TextView toolbarTitle = findViewById(R.id.toolbar_title);
        toolbarTitle.setText(title);
    }
}
