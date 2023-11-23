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

package com.android.systemui.car.statusicon;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserHandle;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.widget.ImageView;

import com.android.car.qc.QCItem;
import com.android.car.ui.FocusParkingView;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.car.CarSystemUiTest;
import com.android.systemui.car.qc.SystemUIQCViewController;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.ConfigurationController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@CarSystemUiTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class StatusIconPanelControllerTest extends SysuiTestCase {
    private StatusIconPanelController mStatusIconPanelController;
    private ImageView mAnchorView;
    private String mIconTag;
    private UserHandle mUserHandle;

    @Mock
    private UserTracker mUserTracker;
    @Mock
    private CarServiceProvider mCarServiceProvider;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private ConfigurationController mConfigurationController;
    @Mock
    private SystemUIQCViewController mSystemUIQCViewController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mContext = spy(mContext);
        mIconTag = mContext.getResources().getString(R.string.qc_icon_tag);
        mUserHandle = UserHandle.of(1000);
        when(mUserTracker.getUserHandle()).thenReturn(mUserHandle);

        mStatusIconPanelController = new StatusIconPanelController(mContext, mUserTracker,
                mCarServiceProvider, mBroadcastDispatcher, mConfigurationController,
                () -> mSystemUIQCViewController);
        spyOn(mStatusIconPanelController);
        mAnchorView = spy(new ImageView(mContext));
        mAnchorView.setTag(mIconTag);
        mAnchorView.setImageDrawable(mContext.getDrawable(R.drawable.ic_bluetooth_status_off));
        mAnchorView.setColorFilter(mStatusIconPanelController.getIconHighlightedColor());
        reset(mAnchorView);
        mStatusIconPanelController.attachPanel(mAnchorView, R.layout.qc_display_panel,
                R.dimen.car_status_icon_panel_default_width);
    }

    @After
    public void tearDown() {
        mStatusIconPanelController.destroyPanel();
    }

    @Test
    public void onPanelAnchorViewClicked_panelShowing() {
        clickAnchorView();
        waitForIdleSync();

        assertThat(mStatusIconPanelController.getPanel().isShowing()).isTrue();
    }

    @Test
    public void onPanelAnchorViewClicked_statusIconHighlighted() {
        clickAnchorView();
        waitForIdleSync();

        verify(mAnchorView).setColorFilter(mStatusIconPanelController.getIconHighlightedColor());
    }

    @Test
    public void onPanelAnchorViewClicked_panelShowing_panelDismissed() {
        clickAnchorView();

        clickAnchorView();
        waitForIdleSync();

        assertThat(mStatusIconPanelController.getPanel().isShowing()).isFalse();
    }

    @Test
    public void onPanelAnchorViewClicked_panelShowing_statusIconNotHighlighted() {
        clickAnchorView();

        clickAnchorView();
        waitForIdleSync();

        verify(mAnchorView).setColorFilter(mStatusIconPanelController.getIconNotHighlightedColor());
    }

    @Test
    public void onPanelAnchorViewClicked_sendsIntentToDismissSystemDialogsWithIdentifier() {
        ArgumentCaptor<Intent> argumentCaptor = ArgumentCaptor.forClass(Intent.class);

        clickAnchorView();
        waitForIdleSync();

        verify(mContext).sendBroadcastAsUser(argumentCaptor.capture(), eq(mUserHandle));
        assertThat(argumentCaptor.getValue().getAction()).isEqualTo(
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        assertThat(argumentCaptor.getValue().getIdentifier()).isEqualTo(
                mStatusIconPanelController.getIdentifier());
    }

    @Test
    public void onDismissSystemDialogReceived_fromSelf_panelOpen_doesNotDismissPanel() {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        intent.setIdentifier(mStatusIconPanelController.getIdentifier());
        clickAnchorView();
        waitForIdleSync();

        mStatusIconPanelController.getBroadcastReceiver().onReceive(mContext, intent);

        assertThat(mStatusIconPanelController.getPanel().isShowing()).isTrue();
    }

    @Test
    public void onDismissSystemDialogReceived_fromSelf_panelOpen_statusIconHighlighted() {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        intent.setIdentifier(mStatusIconPanelController.getIdentifier());
        clickAnchorView();
        waitForIdleSync();

        mStatusIconPanelController.getBroadcastReceiver().onReceive(mContext, intent);

        verify(mAnchorView).setColorFilter(mStatusIconPanelController.getIconHighlightedColor());
    }

    @Test
    public void onDismissSystemDialogReceived_notFromSelf_panelOpen_dismissesPanel() {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        clickAnchorView();
        waitForIdleSync();

        mStatusIconPanelController.getBroadcastReceiver().onReceive(mContext, intent);

        assertThat(mStatusIconPanelController.getPanel().isShowing()).isFalse();
    }

    @Test
    public void onDismissSystemDialogReceived_notFromSelf_panelOpen_statusIconNotHighlighted() {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        clickAnchorView();
        waitForIdleSync();

        mStatusIconPanelController.getBroadcastReceiver().onReceive(mContext, intent);

        verify(mAnchorView).setColorFilter(mStatusIconPanelController.getIconNotHighlightedColor());
    }

    @Test
    public void onDestroy_unregistersListeners() {
        mStatusIconPanelController.destroyPanel();

        verify(mConfigurationController).removeCallback(any());
        verify(mBroadcastDispatcher).unregisterReceiver(any());
    }

    @Test
    public void onDestroy_reAttach_throwsException() {
        mStatusIconPanelController.destroyPanel();

        assertThrows(IllegalStateException.class, () -> mStatusIconPanelController.attachPanel(
                mAnchorView, R.layout.qc_display_panel,
                R.dimen.car_status_icon_panel_default_width));
    }

    @Test
    public void onLayoutDirectionChanged_recreatePanel() {
        mStatusIconPanelController.getConfigurationListener()
                .onLayoutDirectionChanged(/* isLayoutRtl= */ true);

        assertThat(mStatusIconPanelController.getPanel()).isNotNull();
    }

    @Test
    public void onUserChanged_unregisterRegisterReceiver() {
        int newUser = 999;
        Context userContext = mock(Context.class);
        reset(mBroadcastDispatcher);

        mStatusIconPanelController.getUserTrackerCallback()
                .onUserChanged(newUser, userContext);

        verify(mBroadcastDispatcher).unregisterReceiver(
                eq(mStatusIconPanelController.getBroadcastReceiver()));
        verify(mBroadcastDispatcher).registerReceiver(
                eq(mStatusIconPanelController.getBroadcastReceiver()),
                any(IntentFilter.class), eq(null), eq(mUserHandle));
    }

    @Test
    public void onGlobalFocusChanged_panelShowing_panelDismissed() {
        FocusParkingView newFocusView = mock(FocusParkingView.class);
        clickAnchorView();
        waitForIdleSync();

        mStatusIconPanelController.getFocusChangeListener()
                .onGlobalFocusChanged(mAnchorView, newFocusView);

        assertThat(mStatusIconPanelController.getPanel().isShowing()).isFalse();
    }

    @Test
    public void onQCAction_pendingIntentAction_panelDismissed() {
        QCItem qcItem = mock(QCItem.class);
        PendingIntent action = mock(PendingIntent.class);
        when(action.isActivity()).thenReturn(true);
        clickAnchorView();
        waitForIdleSync();

        mStatusIconPanelController.getQCActionListener().onQCAction(qcItem, action);

        assertThat(mStatusIconPanelController.getPanel().isShowing()).isFalse();
    }

    @Test
    public void onQCAction_actionHandler_panelDismissed() {
        QCItem qcItem = mock(QCItem.class);
        QCItem.ActionHandler action = mock(QCItem.ActionHandler.class);
        when(action.isActivity()).thenReturn(true);
        clickAnchorView();
        waitForIdleSync();

        mStatusIconPanelController.getQCActionListener().onQCAction(qcItem, action);

        assertThat(mStatusIconPanelController.getPanel().isShowing()).isFalse();
    }

    private void clickAnchorView() {
        mStatusIconPanelController.getOnClickListener().onClick(mAnchorView);
    }
}
