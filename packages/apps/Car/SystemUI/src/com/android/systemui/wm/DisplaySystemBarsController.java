/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.wm;

import static com.android.systemui.car.users.CarSystemUIUserUtil.isSecondaryMUMDSystemUI;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;
import android.view.IDisplayWindowInsetsController;
import android.view.IWindowManager;
import android.view.InsetsController;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.WindowInsets;
import android.view.WindowInsets.Type.InsetsType;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.VisibleForTesting;

import com.android.systemui.dagger.qualifiers.Main;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayInsetsController;

import java.util.Arrays;
import java.util.Objects;

/**
 * Controller that maps between displays and {@link IDisplayWindowInsetsController} in order to
 * give system bar control to SystemUI.
 * {@link R.bool#config_remoteInsetsControllerControlsSystemBars} determines whether this controller
 * takes control or not.
 */
public class DisplaySystemBarsController implements DisplayController.OnDisplaysChangedListener {

    private static final String TAG = "DisplaySystemBarsController";

    protected final Context mContext;
    protected final IWindowManager mWmService;
    protected final DisplayInsetsController mDisplayInsetsController;
    protected final Handler mHandler;
    @VisibleForTesting
    SparseArray<PerDisplay> mPerDisplaySparseArray;

    public DisplaySystemBarsController(
            Context context,
            IWindowManager wmService,
            DisplayController displayController,
            DisplayInsetsController displayInsetsController,
            @Main Handler mainHandler) {
        mContext = context;
        mWmService = wmService;
        mDisplayInsetsController = displayInsetsController;
        mHandler = mainHandler;
        if (!isSecondaryMUMDSystemUI()) {
            // This WM controller should only be initialized once for the primary SystemUI, as it
            // will affect insets on all displays.
            // TODO(b/262773276): support per-user remote inset controllers
            displayController.addDisplayWindowListener(this);
        }
    }

    @Override
    public void onDisplayAdded(int displayId) {
        PerDisplay pd = new PerDisplay(displayId);
        pd.register();
        // Lazy loading policy control filters instead of during boot.
        if (mPerDisplaySparseArray == null) {
            mPerDisplaySparseArray = new SparseArray<>();
            BarControlPolicy.reloadFromSetting(mContext);
            BarControlPolicy.registerContentObserver(mContext, mHandler, () -> {
                int size = mPerDisplaySparseArray.size();
                for (int i = 0; i < size; i++) {
                    mPerDisplaySparseArray.valueAt(i).updateDisplayWindowRequestedVisibleTypes();
                }
            });
        }
        mPerDisplaySparseArray.put(displayId, pd);
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        PerDisplay pd = mPerDisplaySparseArray.get(displayId);
        pd.unregister();
        mPerDisplaySparseArray.remove(displayId);
    }

    class PerDisplay implements DisplayInsetsController.OnInsetsChangedListener {

        int mDisplayId;
        InsetsController mInsetsController;
        @InsetsType int mRequestedVisibleTypes = WindowInsets.Type.defaultVisible();
        String mPackageName;

        PerDisplay(int displayId) {
            mDisplayId = displayId;
            InputMethodManager inputMethodManager =
                    mContext.getSystemService(InputMethodManager.class);
            mInsetsController = new InsetsController(
                    new DisplaySystemBarsInsetsControllerHost(mHandler, requestedVisibleTypes -> {
                        mRequestedVisibleTypes = requestedVisibleTypes;
                        updateDisplayWindowRequestedVisibleTypes();
                    }, inputMethodManager)
            );
        }

        public void register() {
            mDisplayInsetsController.addInsetsChangedListener(mDisplayId, this);
        }

        public void unregister() {
            mDisplayInsetsController.removeInsetsChangedListener(mDisplayId, this);
        }

        @Override
        public void insetsChanged(InsetsState insetsState) {
            mInsetsController.onStateChanged(insetsState);
            updateDisplayWindowRequestedVisibleTypes();
        }

        @Override
        public void hideInsets(@InsetsType int types, boolean fromIme,
                @Nullable ImeTracker.Token statsToken) {
            if ((types & WindowInsets.Type.ime()) == 0) {
                mInsetsController.hide(types, /* fromIme = */ false, statsToken);
            }
        }

        @Override
        public void showInsets(@InsetsType int types, boolean fromIme,
                @Nullable ImeTracker.Token statsToken) {
            if ((types & WindowInsets.Type.ime()) == 0) {
                mInsetsController.show(types, /* fromIme= */ false, statsToken);
            }
        }

        @Override
        public void insetsControlChanged(InsetsState insetsState,
                InsetsSourceControl[] activeControls) {
            InsetsSourceControl[] nonImeControls = null;
            // Need to filter out IME control to prevent control after leash is released
            if (activeControls != null) {
                nonImeControls = Arrays.stream(activeControls).filter(
                        c -> c.getType() != WindowInsets.Type.ime()).toArray(
                        InsetsSourceControl[]::new);
            }
            mInsetsController.onControlsChanged(nonImeControls);
        }

        @Override
        public void topFocusedWindowChanged(ComponentName component,
                @InsetsType int requestedVisibleTypes) {
            String packageName = component != null ? component.getPackageName() : null;
            if (Objects.equals(mPackageName, packageName)) {
                return;
            }
            mPackageName = packageName;
            updateDisplayWindowRequestedVisibleTypes();
        }

        protected void updateDisplayWindowRequestedVisibleTypes() {
            if (mPackageName == null) {
                return;
            }
            int[] barVisibilities = BarControlPolicy.getBarVisibilities(mPackageName);
            updateRequestedVisibleTypes(barVisibilities[0], /* visible= */ true);
            updateRequestedVisibleTypes(barVisibilities[1], /* visible= */ false);
            showInsets(barVisibilities[0], /* fromIme= */ false, /* statsToken= */ null);
            hideInsets(barVisibilities[1], /* fromIme= */ false, /* statsToken = */ null);
            try {
                mWmService.updateDisplayWindowRequestedVisibleTypes(mDisplayId,
                        mRequestedVisibleTypes);
            } catch (RemoteException e) {
                Slog.w(TAG, "Unable to update window manager service.");
            }
        }

        protected void updateRequestedVisibleTypes(@InsetsType int types, boolean visible) {
            mRequestedVisibleTypes = visible
                    ? (mRequestedVisibleTypes | types)
                    : (mRequestedVisibleTypes & ~types);
        }
    }
}
