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

package com.android.car.media;

import static android.car.CarOccupantZoneManager.INVALID_USER_ID;
import static android.car.CarOccupantZoneManager.OccupantZoneInfo.INVALID_ZONE_ID;
import static android.car.media.CarMediaManager.MEDIA_SOURCE_MODE_BROWSE;
import static android.car.media.CarMediaManager.MEDIA_SOURCE_MODE_PLAYBACK;
import static android.car.user.CarUserManager.USER_LIFECYCLE_EVENT_TYPE_INVISIBLE;
import static android.car.user.CarUserManager.USER_LIFECYCLE_EVENT_TYPE_VISIBLE;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.internal.util.Preconditions.checkState;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.usage.UsageStatsManager;
import android.car.Car;
import android.car.media.ICarMediaSourceListener;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.test.mocks.AndroidMockitoHelper;
import android.car.user.CarUserManager.UserLifecycleEvent;
import android.car.user.CarUserManager.UserLifecycleListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.media.session.MediaController;
import android.media.session.MediaController.TransportControls;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.KeyEvent;

import com.android.car.CarInputService;
import com.android.car.CarInputService.KeyEventListener;
import com.android.car.CarLocalServices;
import com.android.car.CarLog;
import com.android.car.CarMediaService;
import com.android.car.CarOccupantZoneService;
import com.android.car.R;
import com.android.car.power.CarPowerManagementService;
import com.android.car.user.CarUserService;
import com.android.car.user.UserHandleHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;

public final class CarMediaServiceTest extends AbstractExtendedMockitoTestCase {

    private static final String MEDIA_PACKAGE = "test.package";
    private static final String MEDIA_PACKAGE2 = "test.package2";
    private static final String MEDIA_CLASS = "test_class";
    private static final String MEDIA_CLASS2 = "test_class2";

    private static final int TEST_USER_ID = 100;
    private static final int ANOTHER_TEST_USER_ID = 333;
    private static final int DISPLAY_TYPE_IGNORED = 314;
    private static final int SEAT_NUMBER = 23;
    private static final int OCCUPANT_ZONE_ID = 41;
    private static final KeyEvent MEDIA_KEY_EVENT =
            new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY);

    private static final ComponentName MEDIA_COMPONENT =
            new ComponentName(MEDIA_PACKAGE, MEDIA_CLASS);
    private static final ComponentName MEDIA_COMPONENT2 =
            new ComponentName(MEDIA_PACKAGE2, MEDIA_CLASS2);

    @Mock private Context mContext;
    @Mock private Resources mResources;
    @Mock private CarInputService mMockInputService;
    @Mock private CarOccupantZoneService mMockOccupantZoneService;
    @Mock private CarUserService mUserService;
    @Mock private UserManager mUserManager;
    @Mock private PackageManager mPackageManager;
    @Mock private MediaSessionManager mMediaSessionManager;
    @Mock private CarPowerManagementService mMockCarPowerManagementService;
    @Mock private UserHandleHelper mUserHandleHelper;
    @Mock private SharedPreferences mMockSharedPreferences;
    @Mock private SharedPreferences.Editor mMockSharedPreferencesEditor;
    @Mock private UsageStatsManager mMockUsageStatsManager;

    private CarMediaService mCarMediaService;

    private KeyEventListener mKeyEventListener;
    private UserLifecycleListener mUserLifecycleListener;

    public CarMediaServiceTest() {
        super(CarLog.TAG_MEDIA);
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder builder) {
        builder.spyStatic(ActivityManager.class).spyStatic(Binder.class);
    }

    @Before
    public void setUp() {
        when(mContext.checkCallingOrSelfPermission(anyString()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mContext.createContextAsUser(any(), anyInt())).thenReturn(mContext);
        when(mContext.getSharedPreferences(anyString(), anyInt()))
                .thenReturn(mMockSharedPreferences);
        when(mMockSharedPreferences.getString(anyString(), anyString())).thenReturn(
                MEDIA_COMPONENT.flattenToString() + "," + MEDIA_COMPONENT2.flattenToString());
        when(mMockSharedPreferences.edit()).thenReturn(mMockSharedPreferencesEditor);
        when(mMockSharedPreferencesEditor.putInt(anyString(), anyInt()))
                .thenReturn(mMockSharedPreferencesEditor);
        when(mMockSharedPreferencesEditor.putLong(anyString(), anyLong()))
                .thenReturn(mMockSharedPreferencesEditor);
        when(mMockSharedPreferencesEditor.putString(anyString(), anyString()))
                .thenReturn(mMockSharedPreferencesEditor);
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(SEAT_NUMBER))
                .thenReturn(OCCUPANT_ZONE_ID);
        when(mMockOccupantZoneService.getUserForOccupant(OCCUPANT_ZONE_ID))
                .thenReturn(TEST_USER_ID);

        doReturn(mResources).when(mContext).getResources();
        doReturn(mUserManager).when(mContext).getSystemService(UserManager.class);
        AndroidMockitoHelper.mockAmGetCurrentUser(TEST_USER_ID);
        mockGetCallingUserHandle(TEST_USER_ID);
        when(mUserHandleHelper.isEphemeralUser(UserHandle.of(TEST_USER_ID))).thenReturn(false);
        doReturn(mMediaSessionManager).when(mContext).getSystemService(MediaSessionManager.class);
        doReturn(mMockUsageStatsManager).when(mContext).getSystemService(UsageStatsManager.class);
        mCarMediaService = new CarMediaService(mContext, mMockOccupantZoneService, mUserService,
                mUserHandleHelper);
        CarLocalServices.removeServiceForTest(CarPowerManagementService.class);
        CarLocalServices.addService(CarPowerManagementService.class,
                mMockCarPowerManagementService);
        CarLocalServices.removeServiceForTest(CarInputService.class);
        CarLocalServices.addService(CarInputService.class, mMockInputService);
        mockUserLifecycleEvents();
    }

    @After
    public void tearDown() {
        CarLocalServices.removeServiceForTest(CarPowerManagementService.class);
        CarLocalServices.removeServiceForTest(CarInputService.class);
    }

    @Test
    public void testSetMediaSource_ModePlaybackIndependent() {
        mCarMediaService.setIndependentPlaybackConfig(true);
        initMediaService();

        mCarMediaService.setMediaSource(MEDIA_COMPONENT, MEDIA_SOURCE_MODE_PLAYBACK);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT);
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_BROWSE))
                .isNotEqualTo(MEDIA_COMPONENT);
    }

    @Test
    public void testSetMediaSource_ModeBrowseIndependent() {
        mCarMediaService.setIndependentPlaybackConfig(true);
        initMediaService();

        mCarMediaService.setMediaSource(MEDIA_COMPONENT, MEDIA_SOURCE_MODE_BROWSE);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_BROWSE))
                .isEqualTo(MEDIA_COMPONENT);
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isNotEqualTo(MEDIA_COMPONENT);
    }

    @Test
    public void testSetMediaSource_ModePlaybackAndBrowseIndependent() {
        mCarMediaService.setIndependentPlaybackConfig(true);
        initMediaService();

        mCarMediaService.setMediaSource(MEDIA_COMPONENT, MEDIA_SOURCE_MODE_BROWSE);
        mCarMediaService.setMediaSource(MEDIA_COMPONENT2, MEDIA_SOURCE_MODE_PLAYBACK);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_BROWSE))
                .isEqualTo(MEDIA_COMPONENT);
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT2);
    }

    @Test
    public void testSetMediaSource_Dependent() {
        mCarMediaService.setIndependentPlaybackConfig(false);
        initMediaService();

        mCarMediaService.setMediaSource(MEDIA_COMPONENT, MEDIA_SOURCE_MODE_PLAYBACK);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_BROWSE))
                .isEqualTo(MEDIA_COMPONENT);
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT);

        mCarMediaService.setMediaSource(MEDIA_COMPONENT2, MEDIA_SOURCE_MODE_BROWSE);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_BROWSE))
                .isEqualTo(MEDIA_COMPONENT2);
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT2);
    }

    @Test
    public void testSetMediaSource_settingPlaybackSourcePausesAndStopsPreviousMedia() {
        MediaController mockController = mock(MediaController.class);
        TransportControls mockTransportControls = mock(TransportControls.class);
        when(mockController.getTransportControls()).thenReturn(mockTransportControls);
        when(mockController.getPackageName()).thenReturn(MEDIA_PACKAGE);
        when(mockController.getPlaybackState()).thenReturn(
                createPlaybackState(PlaybackState.STATE_PLAYING, PlaybackState.ACTION_PAUSE));
        when(mMediaSessionManager.getActiveSessionsForUser(any(), eq(UserHandle.of(TEST_USER_ID))))
                .thenReturn(List.of(mockController));
        initMediaService();

        // Set the playback media source to MEDIA_COMPONENT, and then to MEDIA_COMPONENT2
        mCarMediaService.setMediaSource(MEDIA_COMPONENT, MEDIA_SOURCE_MODE_PLAYBACK);
        mCarMediaService.setMediaSource(MEDIA_COMPONENT2, MEDIA_SOURCE_MODE_PLAYBACK);

        verify(mockController).unregisterCallback(any());
        verify(mockTransportControls).pause();
        verify(mockTransportControls).stop();
    }

    @Test
    public void testSetMediaSource_multiUsers() {
        // Set a media source for one user.
        mockGetCallingUserHandle(TEST_USER_ID);
        initMediaService();
        mCarMediaService.setMediaSource(MEDIA_COMPONENT, MEDIA_SOURCE_MODE_PLAYBACK);
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT);

        // Set a different media source for another user.
        mockGetCallingUserHandle(ANOTHER_TEST_USER_ID);
        sendUserLifecycleEvent(new UserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_VISIBLE,
                ANOTHER_TEST_USER_ID));
        mCarMediaService.setMediaSource(MEDIA_COMPONENT2, MEDIA_SOURCE_MODE_PLAYBACK);
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT2);

        // Setting a media source for a user should not affect other users.
        mockGetCallingUserHandle(TEST_USER_ID);
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT);
    }

    @Test
    public void testDefaultMediaSource_currentUserInitialized() {
        mockGetCallingUserHandle(TEST_USER_ID);
        initMediaService(MEDIA_CLASS);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_BROWSE))
                .isEqualTo(MEDIA_COMPONENT);
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT);
    }

    @Test
    public void testDefaultMediaSource_backgroundUserUninitialized() {
        mockGetCallingUserHandle(TEST_USER_ID);
        initMediaService(MEDIA_CLASS);

        mockGetCallingUserHandle(ANOTHER_TEST_USER_ID);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_BROWSE)).isNull();
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK)).isNull();
    }

    @Test
    public void testDefaultMediaSource_backgroundUserInitialized() {
        mockGetCallingUserHandle(TEST_USER_ID);
        initMediaService(MEDIA_CLASS);

        mockGetCallingUserHandle(ANOTHER_TEST_USER_ID);
        sendUserLifecycleEvent(new UserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_VISIBLE,
                ANOTHER_TEST_USER_ID));

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_BROWSE))
                .isEqualTo(MEDIA_COMPONENT);
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT);
    }

    @Test
    public void testDefaultMediaSource_userDataRemoved() {
        mockGetCallingUserHandle(TEST_USER_ID);
        initMediaService(MEDIA_CLASS);

        // Set a different media source for another user.
        mockGetCallingUserHandle(ANOTHER_TEST_USER_ID);
        sendUserLifecycleEvent(new UserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_VISIBLE,
                ANOTHER_TEST_USER_ID));
        mCarMediaService.setMediaSource(MEDIA_COMPONENT2, MEDIA_SOURCE_MODE_PLAYBACK);
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT2);

        // Background user becomes invisible.
        sendUserLifecycleEvent(new UserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_INVISIBLE,
                ANOTHER_TEST_USER_ID));

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_BROWSE)).isNull();
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK)).isNull();
    }

    @Test
    public void testMediaSourceListener_Independent() throws Exception {
        mCarMediaService.setIndependentPlaybackConfig(true);
        initMediaService();
        ICarMediaSourceListener listenerPlayback = mockMediaSourceListener();
        ICarMediaSourceListener listenerBrowse = mockMediaSourceListener();

        mCarMediaService.registerMediaSourceListener(listenerPlayback, MEDIA_SOURCE_MODE_PLAYBACK);
        mCarMediaService.registerMediaSourceListener(listenerBrowse, MEDIA_SOURCE_MODE_BROWSE);
        mCarMediaService.setMediaSource(MEDIA_COMPONENT, MEDIA_SOURCE_MODE_PLAYBACK);

        verify(listenerPlayback).onMediaSourceChanged(MEDIA_COMPONENT);
        verify(listenerBrowse, never()).onMediaSourceChanged(any());
    }

    @Test
    public void testMediaSourceListener_IndependentBrowse() throws Exception {
        mCarMediaService.setIndependentPlaybackConfig(true);
        initMediaService();
        ICarMediaSourceListener listenerPlayback = mockMediaSourceListener();
        ICarMediaSourceListener listenerBrowse = mockMediaSourceListener();

        mCarMediaService.registerMediaSourceListener(listenerPlayback, MEDIA_SOURCE_MODE_PLAYBACK);
        mCarMediaService.registerMediaSourceListener(listenerBrowse, MEDIA_SOURCE_MODE_BROWSE);
        mCarMediaService.setMediaSource(MEDIA_COMPONENT, MEDIA_SOURCE_MODE_BROWSE);

        verify(listenerBrowse).onMediaSourceChanged(MEDIA_COMPONENT);
        verify(listenerPlayback, never()).onMediaSourceChanged(any());
    }

    @Test
    public void testMediaSourceListener_Dependent() throws Exception {
        mCarMediaService.setIndependentPlaybackConfig(false);
        initMediaService();
        ICarMediaSourceListener listenerPlayback = mockMediaSourceListener();
        ICarMediaSourceListener listenerBrowse = mockMediaSourceListener();

        mCarMediaService.registerMediaSourceListener(listenerPlayback, MEDIA_SOURCE_MODE_PLAYBACK);
        mCarMediaService.registerMediaSourceListener(listenerBrowse, MEDIA_SOURCE_MODE_BROWSE);
        mCarMediaService.setMediaSource(MEDIA_COMPONENT, MEDIA_SOURCE_MODE_PLAYBACK);

        verify(listenerPlayback).onMediaSourceChanged(MEDIA_COMPONENT);
        verify(listenerBrowse).onMediaSourceChanged(MEDIA_COMPONENT);

        mCarMediaService.setMediaSource(MEDIA_COMPONENT, MEDIA_SOURCE_MODE_BROWSE);

        verify(listenerPlayback).onMediaSourceChanged(MEDIA_COMPONENT);
        verify(listenerBrowse).onMediaSourceChanged(MEDIA_COMPONENT);
    }

    @Test
    public void testMediaSourceListener_Unregister() throws Exception {
        initMediaService();
        ICarMediaSourceListener listener = mockMediaSourceListener();

        mCarMediaService.registerMediaSourceListener(listener, MEDIA_SOURCE_MODE_PLAYBACK);
        mCarMediaService.unregisterMediaSourceListener(listener, MEDIA_SOURCE_MODE_PLAYBACK);
        mCarMediaService.setMediaSource(MEDIA_COMPONENT, MEDIA_SOURCE_MODE_PLAYBACK);

        verify(listener, never()).onMediaSourceChanged(MEDIA_COMPONENT);
    }

    @Test
    public void testMediaSourceListener_multiUsers() throws Exception {
        mockGetCallingUserHandle(TEST_USER_ID);
        initMediaService();
        ICarMediaSourceListener user1Listener = mockMediaSourceListener();
        mCarMediaService.registerMediaSourceListener(user1Listener, MEDIA_SOURCE_MODE_PLAYBACK);

        mockGetCallingUserHandle(ANOTHER_TEST_USER_ID);
        sendUserLifecycleEvent(new UserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_VISIBLE,
                ANOTHER_TEST_USER_ID));
        ICarMediaSourceListener user2Listener = mockMediaSourceListener();
        mCarMediaService.registerMediaSourceListener(user2Listener, MEDIA_SOURCE_MODE_PLAYBACK);

        // Set a media source for user2. Only the user2 callback is invoked.
        mCarMediaService.setMediaSource(MEDIA_COMPONENT2, MEDIA_SOURCE_MODE_PLAYBACK);
        verify(user1Listener, never()).onMediaSourceChanged(any());
        verify(user2Listener).onMediaSourceChanged(MEDIA_COMPONENT2);

        // Set a media source for user1. Only the user1 callback is invoked.
        mockGetCallingUserHandle(TEST_USER_ID);
        mCarMediaService.setMediaSource(MEDIA_COMPONENT, MEDIA_SOURCE_MODE_PLAYBACK);
        verify(user1Listener).onMediaSourceChanged(MEDIA_COMPONENT);
        verify(user2Listener).onMediaSourceChanged(MEDIA_COMPONENT2);
    }

    @Test
    public void testGetLastMediaSources() {
        initMediaService();

        assertThat(mCarMediaService.getLastMediaSources(MEDIA_SOURCE_MODE_PLAYBACK))
                .containsExactly(MEDIA_COMPONENT, MEDIA_COMPONENT2);
    }

    @Test
    public void testIsIndependentPlaybackConfig_true() {
        mCarMediaService.setIndependentPlaybackConfig(true);

        assertThat(mCarMediaService.isIndependentPlaybackConfig()).isTrue();
    }

    @Test
    public void testIsIndependentPlaybackConfig_false() {
        mCarMediaService.setIndependentPlaybackConfig(false);

        assertThat(mCarMediaService.isIndependentPlaybackConfig()).isFalse();
    }

    @Test
    public void testIsIndependentPlaybackConfig_multiUsers() {
        // Set a value for one user.
        mockGetCallingUserHandle(TEST_USER_ID);
        mCarMediaService.setIndependentPlaybackConfig(false);
        assertThat(mCarMediaService.isIndependentPlaybackConfig()).isFalse();

        // Set a different value for another user.
        mockGetCallingUserHandle(ANOTHER_TEST_USER_ID);
        mCarMediaService.setIndependentPlaybackConfig(true);
        assertThat(mCarMediaService.isIndependentPlaybackConfig()).isTrue();

        // Setting a value for a user should not affect other users.
        mockGetCallingUserHandle(TEST_USER_ID);
        assertThat(mCarMediaService.isIndependentPlaybackConfig()).isFalse();
    }

    @Test
    public void testDefaultMediaSource() {
        initMediaService(MEDIA_CLASS);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT);
    }

    @Test
    public void testUnresolvedMediaPackage() {
        initializeMockPackageManager();

        assertThat(mCarMediaService.isMediaService(MEDIA_COMPONENT, TEST_USER_ID)).isFalse();
    }

    /**
     * Tests that PlaybackState changing to PlaybackState#isActive
     * will result the media source changing
     */
    @Test
    public void testActiveSessionListener_StateActiveChangesSource() {
        mockPlaybackStateChange(
                createPlaybackState(PlaybackState.STATE_BUFFERING, /* actions= */ 0));

        initMediaService(MEDIA_CLASS, MEDIA_CLASS2);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT2);
        verify(mContext, times(2)).startForegroundService(any());
    }

    // Tests that PlaybackState changing to STATE_PLAYING will result the media source changing
    @Test
    public void testActiveSessionListener_StatePlayingChangesSource() {
        mockPlaybackStateChange(createPlaybackState(PlaybackState.STATE_PLAYING, /* actions= */ 0));

        initMediaService(MEDIA_CLASS, MEDIA_CLASS2);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT2);
        verify(mContext, times(2)).startForegroundService(any());
    }

    @Test
    public void testActiveSessionListener_StatePlayingNonMediaAppDoesntChangesSource() {
        mockPlaybackStateChange(createPlaybackState(PlaybackState.STATE_PLAYING, /* actions= */ 0));

        // setup media source info only for MEDIA Component
        // second one will stay null
        initMediaService(MEDIA_CLASS);

        // New Media source should be null
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK)).isNull();
        // service start should happen on init but not on media source change
        verify(mContext).startForegroundService(any());
    }

    @Test
    public void testActiveSessionListener_IndependentBrowseUnchanged() {
        mCarMediaService.setIndependentPlaybackConfig(true);
        mockPlaybackStateChange(createPlaybackState(PlaybackState.STATE_PLAYING, /* actions= */ 0));

        initMediaService(MEDIA_CLASS, MEDIA_CLASS2);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT2);
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_BROWSE))
                .isEqualTo(MEDIA_COMPONENT);
        verify(mContext, times(2)).startForegroundService(any());
    }

    @Test
    public void testActiveSessionListener_DependentBrowseChanged() {
        mCarMediaService.setIndependentPlaybackConfig(false);
        mockPlaybackStateChange(createPlaybackState(PlaybackState.STATE_PLAYING, /* actions= */ 0));

        initMediaService(MEDIA_CLASS, MEDIA_CLASS2);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT2);
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_BROWSE))
                .isEqualTo(MEDIA_COMPONENT2);
        verify(mContext, times(2)).startForegroundService(any());
    }

    @Test
    public void testActiveSessionListener_StatePaused() {
        mockPlaybackStateChange(createPlaybackState(PlaybackState.STATE_PAUSED, /* actions= */ 0));

        initMediaService(MEDIA_CLASS, MEDIA_CLASS2);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT);
        verify(mContext).startForegroundService(any());
    }

    @Test
    public void testMediaKeyEventListenerOnKeyEvent_ignoresInvalidOccupantZone() {
        when(mMockOccupantZoneService.getOccupantZoneIdForSeat(SEAT_NUMBER))
                .thenReturn(INVALID_ZONE_ID);
        mCarMediaService.init();
        mockInputServiceKeyEvents();

        sendKeyEvent(MEDIA_KEY_EVENT, DISPLAY_TYPE_IGNORED, SEAT_NUMBER);

        verifyZeroInteractions(mMediaSessionManager);
    }

    @Test
    public void testMediaKeyEventListenerOnKeyEvent_ignoresInvalidUser() {
        when(mMockOccupantZoneService.getUserForOccupant(OCCUPANT_ZONE_ID))
                .thenReturn(INVALID_USER_ID);
        mCarMediaService.init();
        mockInputServiceKeyEvents();

        sendKeyEvent(MEDIA_KEY_EVENT, DISPLAY_TYPE_IGNORED, SEAT_NUMBER);

        verifyZeroInteractions(mMediaSessionManager);
    }

    @Test
    public void testMediaKeyEventListenerOnKeyEvent_stopsAfterFirstDelivery() {
        MediaController mockController1 = createMockMediaController(/* dispatchResult== */ false);
        MediaController mockController2 = createMockMediaController(/* dispatchResult== */ true);
        MediaController mockController3 = createMockMediaController(/* dispatchResult== */ true);
        when(mMediaSessionManager.getActiveSessionsForUser(any(), eq(UserHandle.of(TEST_USER_ID))))
                .thenReturn(List.of(mockController1, mockController2, mockController3));
        mCarMediaService.init();
        mockInputServiceKeyEvents();

        sendKeyEvent(MEDIA_KEY_EVENT, DISPLAY_TYPE_IGNORED, SEAT_NUMBER);

        verify(mockController1).dispatchMediaButtonEvent(MEDIA_KEY_EVENT);
        verify(mockController2).dispatchMediaButtonEvent(MEDIA_KEY_EVENT);
        verify(mockController3, never()).dispatchMediaButtonEvent(any());
    }

    private void initMediaService(String... classesToResolve) {
        initializeMockPackageManager(classesToResolve);
        mockUserUnlocked(true);

        mCarMediaService.init();
    }

    private void mockUserUnlocked(boolean unlocked) {
        when(mUserManager.isUserUnlocked(any())).thenReturn(unlocked);
    }

    /** Sets {@code mKeyEventListener} to the key event listener that is being registered. */
    private void mockInputServiceKeyEvents() {
        ArgumentCaptor<KeyEventListener> keyEventListenerCaptor =
                ArgumentCaptor.forClass(KeyEventListener.class);
        verify(mMockInputService).registerKeyEventListener(keyEventListenerCaptor.capture(),
                notNull());
        mKeyEventListener = keyEventListenerCaptor.getValue();
    }

    /** Sends the given {@code keyEvent} to the listener stored in {@code mKeyEventListener}. */
    private void sendKeyEvent(KeyEvent keyEvent, int displayType, int seat) {
        checkState(mKeyEventListener != null,
                "KeyEventListener has not been registered to CarInputService.");
        mKeyEventListener.onKeyEvent(keyEvent, displayType, seat);
    }

    /** Sets {@code mUserLifecycleListener} to the listener that is being registered. */
    private void mockUserLifecycleEvents() {
        ArgumentCaptor<UserLifecycleListener> userLifecycleListenerCaptor =
                ArgumentCaptor.forClass(UserLifecycleListener.class);
        verify(mUserService).addUserLifecycleListener(notNull(),
                userLifecycleListenerCaptor.capture());
        mUserLifecycleListener = userLifecycleListenerCaptor.getValue();
    }

    /** Sends the given {@code event} to the listener stored in {@code mUserLifecycleListener}. */
    private void sendUserLifecycleEvent(UserLifecycleEvent event) {
        checkState(mUserLifecycleListener != null,
                "UserLifeCycleListener has not been registered to CarUserService.");
        mUserLifecycleListener.onEvent(event);
    }

    private MediaController createMockMediaController(boolean dispatchResult) {
        MediaController mockController = mock(MediaController.class);
        when(mockController.dispatchMediaButtonEvent(any())).thenReturn(dispatchResult);

        return mockController;
    }

    private ICarMediaSourceListener mockMediaSourceListener() {
        ICarMediaSourceListener listener = mock(ICarMediaSourceListener.class);
        when(listener.asBinder()).thenReturn(mock(IBinder.class));
        return listener;
    }

    // This method invokes a playback state changed callback on a mock MediaController
    private void mockPlaybackStateChange(PlaybackState newState) {
        List<MediaController> controllers = new ArrayList<>();
        MediaController mockController = mock(MediaController.class);
        when(mockController.getPackageName()).thenReturn(MEDIA_PACKAGE2);
        MediaSession.Token mockSessionToken = mock(MediaSession.Token.class);
        when(mockController.getSessionToken()).thenReturn(mockSessionToken);
        when(mockSessionToken.getUid()).thenReturn(TEST_USER_ID * UserHandle.PER_USER_RANGE);
        Bundle sessionExtras = new Bundle();
        sessionExtras.putString(Car.CAR_EXTRA_BROWSE_SERVICE_FOR_SESSION, MEDIA_CLASS2);
        when(mockController.getExtras()).thenReturn(sessionExtras);

        doAnswer(invocation -> {
            MediaController.Callback callback = invocation.getArgument(0);
            callback.onPlaybackStateChanged(newState);
            return null;
        }).when(mockController).registerCallback(notNull());
        controllers.add(mockController);

        doAnswer(invocation -> {
            MediaSessionManager.OnActiveSessionsChangedListener callback =
                    invocation.getArgument(3);
            callback.onActiveSessionsChanged(controllers);
            return null;
        }).when(mMediaSessionManager).addOnActiveSessionsChangedListener(any(), any(), any(),
                any(MediaSessionManager.OnActiveSessionsChangedListener.class));
    }

    // This method sets up PackageManager queries to return mocked media components if specified
    private void initializeMockPackageManager(String... classesToResolve) {
        when(mContext.getString(R.string.config_defaultMediaSource))
                .thenReturn(MEDIA_COMPONENT.flattenToShortString());
        List<ResolveInfo> packageList = new ArrayList();
        for (String className : classesToResolve) {
            ResolveInfo info = new ResolveInfo();
            ServiceInfo serviceInfo = new ServiceInfo();
            serviceInfo.name = className;
            info.serviceInfo = serviceInfo;
            packageList.add(info);
        }
        when(mPackageManager.queryIntentServicesAsUser(any(), anyInt(), any()))
                .thenReturn(packageList);
    }

    private PlaybackState createPlaybackState(
            @PlaybackState.State int state, @PlaybackState.Actions long actions) {
        return new PlaybackState.Builder().setState(state, 0, 0).setActions(actions).build();
    }
}
