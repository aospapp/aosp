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

import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import android.car.settings.CarSettings;
import android.graphics.Insets;
import android.graphics.Point;
import android.os.Handler;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.IWindowManager;
import android.view.InsetsSourceControl;
import android.view.InsetsState;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.TransactionPool;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class DisplaySystemBarsControllerTest extends SysuiTestCase {

    private DisplaySystemBarsController mController;

    private static final int DISPLAY_ID = 1;

    @Mock
    private IWindowManager mIWindowManager;
    @Mock
    private DisplayController mDisplayController;
    @Mock
    private DisplayLayout mDisplayLayout;
    @Mock
    private DisplayInsetsController mDisplayInsetsController;
    @Mock
    private Handler mHandler;
    @Mock
    private TransactionPool mTransactionPool;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mDisplayLayout.rotation()).thenReturn(0);
        when(mDisplayController.getDisplayLayout(anyInt())).thenReturn(mDisplayLayout);

        mController = new DisplaySystemBarsController(
                mContext,
                mIWindowManager,
                mDisplayController,
                mDisplayInsetsController,
                mHandler,
                mTransactionPool
        );
    }

    @Test
    public void onDisplayAdded_loadsBarControlPolicyFilters() {
        String text = "sample text";
        Settings.Global.putString(
                mContext.getContentResolver(),
                CarSettings.Global.SYSTEM_BAR_VISIBILITY_OVERRIDE,
                text
        );

        mController.onDisplayAdded(DISPLAY_ID);

        assertThat(BarControlPolicy.sSettingValue).isEqualTo(text);
    }

    @Test
    public void onControlsChanged_parentClassGetsImeControls() {
        mController.onDisplayAdded(DISPLAY_ID);
        assertThat(mController.mPerDisplaySparseArray.size()).isEqualTo(1);
        DisplaySystemBarsController.PerDisplay display = mController.mPerDisplaySparseArray.get(
                DISPLAY_ID);
        assertThat(display).isNotNull();

        InsetsSourceControl[] controls = new InsetsSourceControl[] {
            new InsetsSourceControl(ITYPE_STATUS_BAR, null, new Point(), Insets.NONE),
            new InsetsSourceControl(ITYPE_NAVIGATION_BAR, null, new Point(), Insets.NONE),
            new InsetsSourceControl(ITYPE_IME, null, new Point(), Insets.NONE)
        };
        display.insetsControlChanged(new InsetsState(), controls);

        assertThat(display.getImeSourceControl()).isNotNull();
    }
}
