/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.telecom.tests;

import static android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.VolumeShaper;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.telecom.AsyncRingtonePlayer;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallState;
import com.android.server.telecom.InCallController;
import com.android.server.telecom.InCallTonePlayer;
import com.android.server.telecom.Ringer;
import com.android.server.telecom.RingtoneFactory;
import com.android.server.telecom.SystemSettingsUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.concurrent.CompletableFuture;

@RunWith(JUnit4.class)
public class RingerTest extends TelecomTestCase {
    private static final Uri FAKE_RINGTONE_URI = Uri.parse("content://media/fake/audio/1729");
    // Returned when the a URI-based VibrationEffect is attempted, to avoid depending on actual
    // device configuration for ringtone URIs. The actual Uri can be verified via the
    // VibrationEffectProxy mock invocation.
    private static final VibrationEffect URI_VIBRATION_EFFECT =
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);

    @Mock InCallTonePlayer.Factory mockPlayerFactory;
    @Mock SystemSettingsUtil mockSystemSettingsUtil;
    @Mock RingtoneFactory mockRingtoneFactory;
    @Mock Vibrator mockVibrator;
    @Mock InCallController mockInCallController;
    @Mock NotificationManager mockNotificationManager;
    @Mock Ringer.AccessibilityManagerAdapter mockAccessibilityManagerAdapter;

    @Spy Ringer.VibrationEffectProxy spyVibrationEffectProxy;

    @Mock InCallTonePlayer mockTonePlayer;
    @Mock Call mockCall1;
    @Mock Call mockCall2;

    private static final PhoneAccountHandle PA_HANDLE =
            new PhoneAccountHandle(new ComponentName("pa_pkg", "pa_cls"),
                    "pa_id");

    boolean mIsHapticPlaybackSupported = true;  // Note: initializeRinger() after changes.
    AsyncRingtonePlayer asyncRingtonePlayer = new AsyncRingtonePlayer();
    Ringer mRingerUnderTest;
    AudioManager mockAudioManager;
    CompletableFuture<Void> mRingCompletionFuture = new CompletableFuture<>();

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mContext = mComponentContextFixture.getTestDouble().getApplicationContext();
        doReturn(URI_VIBRATION_EFFECT).when(spyVibrationEffectProxy).get(any(), any());
        when(mockPlayerFactory.createPlayer(anyInt())).thenReturn(mockTonePlayer);
        mockAudioManager = mContext.getSystemService(AudioManager.class);
        when(mockAudioManager.getRingerMode()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        when(mockSystemSettingsUtil.isHapticPlaybackSupported(any(Context.class)))
                .thenAnswer((invocation) -> mIsHapticPlaybackSupported);
        mockNotificationManager =mContext.getSystemService(NotificationManager.class);
        when(mockTonePlayer.startTone()).thenReturn(true);
        when(mockNotificationManager.matchesCallFilter(any(Bundle.class))).thenReturn(true);
        when(mockRingtoneFactory.hasHapticChannels(any(Ringtone.class))).thenReturn(false);
        when(mockCall1.getState()).thenReturn(CallState.RINGING);
        when(mockCall2.getState()).thenReturn(CallState.RINGING);
        when(mockCall1.getAssociatedUser()).thenReturn(PA_HANDLE.getUserHandle());
        when(mockCall2.getAssociatedUser()).thenReturn(PA_HANDLE.getUserHandle());

        createRingerUnderTest();
    }

    /**
     * (Re-)Creates the Ringer for the test. This needs to be called if changing final properties,
     * like mIsHapticPlaybackSupported.
     */
    private void createRingerUnderTest() {
        mRingerUnderTest = new Ringer(mockPlayerFactory, mContext, mockSystemSettingsUtil,
                asyncRingtonePlayer, mockRingtoneFactory, mockVibrator, spyVibrationEffectProxy,
                mockInCallController, mockNotificationManager, mockAccessibilityManagerAdapter);
        // This future is used to wait for AsyncRingtonePlayer to finish its part.
        mRingerUnderTest.setBlockOnRingingFuture(mRingCompletionFuture);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @SmallTest
    @Test
    public void testNoActionInTheaterMode() throws Exception {
        // Start call waiting to make sure that it doesn't stop when we start ringing
        mRingerUnderTest.startCallWaiting(mockCall1);
        when(mockSystemSettingsUtil.isTheaterModeOn(any(Context.class))).thenReturn(true);
        assertFalse(startRingingAndWaitForAsync(mockCall2, false));
        verifyZeroInteractions(mockRingtoneFactory);
        verify(mockTonePlayer, never()).stopTone();
        verify(mockVibrator, never())
                .vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testNoActionWithExternalRinger() throws Exception {
        Bundle externalRingerExtra = new Bundle();
        externalRingerExtra.putBoolean(TelecomManager.EXTRA_CALL_HAS_IN_BAND_RINGTONE, true);
        when(mockCall1.getIntentExtras()).thenReturn(externalRingerExtra);
        when(mockCall2.getIntentExtras()).thenReturn(externalRingerExtra);
        // Start call waiting to make sure that it doesn't stop when we start ringing
        mRingerUnderTest.startCallWaiting(mockCall1);
        assertFalse(startRingingAndWaitForAsync(mockCall2, false));

        verifyZeroInteractions(mockRingtoneFactory);
        verify(mockTonePlayer, never()).stopTone();
        verify(mockVibrator, never())
                .vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testNoActionWhenDialerRings() throws Exception {
        // Start call waiting to make sure that it doesn't stop when we start ringing
        mRingerUnderTest.startCallWaiting(mockCall1);
        when(mockInCallController.doesConnectedDialerSupportRinging(
                any(UserHandle.class))).thenReturn(true);
        ensureRingerIsNotAudible();
        assertFalse(startRingingAndWaitForAsync(mockCall2, false));

        verifyZeroInteractions(mockRingtoneFactory);
        verify(mockTonePlayer, never()).stopTone();
        verify(mockVibrator, never())
                .vibrate(any(VibrationEffect.class), any(AudioAttributes.class));
    }

    @SmallTest
    @Test
    public void testAudioFocusStillAcquiredWhenDialerRings() throws Exception {

        // Start call waiting to make sure that it doesn't stop when we start ringing
        mRingerUnderTest.startCallWaiting(mockCall1);
        when(mockInCallController.doesConnectedDialerSupportRinging(
                any(UserHandle.class))).thenReturn(true);
        ensureRingerIsAudible();
        assertTrue(startRingingAndWaitForAsync(mockCall2, false));
        verifyZeroInteractions(mockRingtoneFactory);
        verify(mockTonePlayer, never()).stopTone();
        verify(mockVibrator, never())
                .vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testNoActionWhenCallIsSelfManaged() throws Exception {
        // Start call waiting to make sure that it doesn't stop when we start ringing
        mRingerUnderTest.startCallWaiting(mockCall1);
        when(mockCall2.isSelfManaged()).thenReturn(true);
        // We do want to acquire audio focus when self-managed
        assertTrue(startRingingAndWaitForAsync(mockCall2, true));

        verifyZeroInteractions(mockRingtoneFactory);
        verify(mockTonePlayer, never()).stopTone();
        verify(mockVibrator, never())
                .vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testCallWaitingButNoRingForSpecificContacts() throws Exception {
        when(mockNotificationManager.matchesCallFilter(any(Bundle.class))).thenReturn(false);
        // Start call waiting to make sure that it does stop when we start ringing
        mRingerUnderTest.startCallWaiting(mockCall1);
        verify(mockTonePlayer).startTone();

        assertFalse(startRingingAndWaitForAsync(mockCall2, false));

        verifyZeroInteractions(mockRingtoneFactory);
        verify(mockTonePlayer).stopTone();
        verify(mockVibrator, never())
                .vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testNoVibrateDueToAudioCoupledHaptics() throws Exception {
        Ringtone mockRingtone = ensureRingtoneMocked();

        mRingerUnderTest.startCallWaiting(mockCall1);
        ensureRingerIsAudible();
        enableVibrationWhenRinging();
        // Pretend we're using audio coupled haptics.
        setIsUsingHaptics(mockRingtone, true);
        assertTrue(startRingingAndWaitForAsync(mockCall1, false));
        verify(mockRingtoneFactory, times(1))
            .getRingtone(any(Call.class), nullable(VolumeShaper.Configuration.class), anyBoolean());
        verifyNoMoreInteractions(mockRingtoneFactory);
        verify(mockTonePlayer).stopTone();
        verify(mockRingtone).play();
        verify(mockVibrator, never()).vibrate(any(VibrationEffect.class),
                any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testVibrateButNoRingForNullRingtone() throws Exception {
        when(mockRingtoneFactory.getRingtone(
                 any(Call.class), nullable(VolumeShaper.Configuration.class), anyBoolean()))
            .thenReturn(null);

        mRingerUnderTest.startCallWaiting(mockCall1);
        when(mockAudioManager.getRingerMode()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        enableVibrationWhenRinging();
        // The ringtone isn't known to be null until the async portion after the call completes,
        // so startRinging still returns true here as there should nominally be a ringtone.
        // Notably, vibration still happens in this scenario.
        assertTrue(startRingingAndWaitForAsync(mockCall2, false));
        verify(mockTonePlayer).stopTone();

        // Just the one call to mockRingtoneFactory, which returned null.
        verify(mockRingtoneFactory).getRingtone(
                any(Call.class), nullable(VolumeShaper.Configuration.class), anyBoolean());
        verifyNoMoreInteractions(mockRingtoneFactory);

        // Play default vibration when future completes with no audio coupled haptics
        verify(mockVibrator).vibrate(eq(mRingerUnderTest.mDefaultVibrationEffect),
                any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testVibrateButNoRingForSilentRingtone() throws Exception {
        Ringtone mockRingtone = ensureRingtoneMocked();

        mRingerUnderTest.startCallWaiting(mockCall1);
        when(mockRingtoneFactory.getRingtone(any(Call.class), eq(null), anyBoolean()))
            .thenReturn(mockRingtone);
        when(mockAudioManager.getRingerMode()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);
        when(mockAudioManager.getStreamVolume(AudioManager.STREAM_RING)).thenReturn(0);
        enableVibrationWhenRinging();
        assertFalse(startRingingAndWaitForAsync(mockCall2, false));
        verify(mockTonePlayer).stopTone();
        // Try to play a silent haptics ringtone
        verify(mockRingtoneFactory, times(1)).getHapticOnlyRingtone();
        verifyNoMoreInteractions(mockRingtoneFactory);
        verify(mockRingtone).play();

        // Play default vibration when future completes with no audio coupled haptics
        verify(mockVibrator).vibrate(eq(mRingerUnderTest.mDefaultVibrationEffect),
                any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testVibrateButNoRingForSilentRingtoneWithoutAudioHapticSupport() throws Exception {
        mIsHapticPlaybackSupported = false;
        createRingerUnderTest();  // Needed after changing haptic playback support.
        mRingerUnderTest.startCallWaiting(mockCall1);
        when(mockAudioManager.getRingerMode()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);
        when(mockAudioManager.getStreamVolume(AudioManager.STREAM_RING)).thenReturn(0);
        enableVibrationWhenRinging();
        assertFalse(startRingingAndWaitForAsync(mockCall2, false));
        verify(mockTonePlayer).stopTone();
        verifyZeroInteractions(mockRingtoneFactory);

        // Play default vibration when future completes with no audio coupled haptics
        verify(mockVibrator).vibrate(eq(mRingerUnderTest.mDefaultVibrationEffect),
                any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testAudioCoupledHapticsForSilentRingtone() throws Exception {
        Ringtone mockRingtone = ensureRingtoneMocked();

        mRingerUnderTest.startCallWaiting(mockCall1);
        when(mockAudioManager.getRingerMode()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);
        when(mockAudioManager.getStreamVolume(AudioManager.STREAM_RING)).thenReturn(0);
        setIsUsingHaptics(mockRingtone, true);
        enableVibrationWhenRinging();
        assertFalse(startRingingAndWaitForAsync(mockCall2, false));

        verify(mockRingtoneFactory, times(1)).getHapticOnlyRingtone();
        verifyNoMoreInteractions(mockRingtoneFactory);
        verify(mockTonePlayer).stopTone();
        // Try to play a silent haptics ringtone
        verify(mockRingtone).play();
        // Skip vibration for audio coupled haptics
        verify(mockVibrator, never()).vibrate(any(VibrationEffect.class),
                any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testCustomVibrationForRingtone() throws Exception {
        mRingerUnderTest.startCallWaiting(mockCall1);
        Ringtone mockRingtone = ensureRingtoneMocked();
        when(mockAudioManager.getRingerMode()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        when(mockRingtone.getUri()).thenReturn(FAKE_RINGTONE_URI);
        enableVibrationWhenRinging();
        assertTrue(startRingingAndWaitForAsync(mockCall2, false));
        verify(mockTonePlayer).stopTone();
        verify(mockRingtoneFactory, times(1))
            .getRingtone(any(Call.class), isNull(), anyBoolean());
        verifyNoMoreInteractions(mockRingtoneFactory);
        verify(mockRingtone).play();
        verify(spyVibrationEffectProxy).get(eq(FAKE_RINGTONE_URI), any(Context.class));
        verify(mockVibrator).vibrate(eq(URI_VIBRATION_EFFECT), any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testRingAndNoVibrate() throws Exception {
        Ringtone mockRingtone = ensureRingtoneMocked();

        mRingerUnderTest.startCallWaiting(mockCall1);
        ensureRingerIsAudible();
        enableVibrationOnlyWhenNotRinging();
        assertTrue(startRingingAndWaitForAsync(mockCall2, false));
        verify(mockRingtoneFactory, times(1))
            .getRingtone(any(Call.class), nullable(VolumeShaper.Configuration.class), anyBoolean());
        verifyNoMoreInteractions(mockRingtoneFactory);
        verify(mockTonePlayer).stopTone();
        verify(mockRingtone).play();
        verify(mockVibrator, never())
                .vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testRingWithRampingRinger() throws Exception {
        Ringtone mockRingtone = ensureRingtoneMocked();

        mRingerUnderTest.startCallWaiting(mockCall1);
        ensureRingerIsAudible();
        enableRampingRinger();
        enableVibrationWhenRinging();
        assertTrue(startRingingAndWaitForAsync(mockCall2, false));
        verify(mockRingtoneFactory, times(1))
            .getRingtone(any(Call.class), nullable(VolumeShaper.Configuration.class), anyBoolean());
        verifyNoMoreInteractions(mockRingtoneFactory);
        verify(mockTonePlayer).stopTone();
        verify(mockRingtone).play();
    }

    @SmallTest
    @Test
    public void testSilentRingWithHfpStillAcquiresFocus() throws Exception {
        mRingerUnderTest.startCallWaiting(mockCall1);
        when(mockAudioManager.getRingerMode()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        when(mockAudioManager.getStreamVolume(AudioManager.STREAM_RING)).thenReturn(0);
        enableVibrationOnlyWhenNotRinging();
        assertTrue(startRingingAndWaitForAsync(mockCall2, true));
        verify(mockTonePlayer).stopTone();
        // Ringer not audible, so never tries to create a ringtone.
        verifyZeroInteractions(mockRingtoneFactory);
        verify(mockVibrator, never())
                .vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testRingAndVibrateForAllowedCallInDndMode() throws Exception {
        mRingerUnderTest.startCallWaiting(mockCall1);
        Ringtone mockRingtone = ensureRingtoneMocked();
        when(mockNotificationManager.getZenMode()).thenReturn(ZEN_MODE_IMPORTANT_INTERRUPTIONS);
        when(mockAudioManager.getRingerMode()).thenReturn(AudioManager.RINGER_MODE_SILENT);
        when(mockAudioManager.getStreamVolume(AudioManager.STREAM_RING)).thenReturn(100);
        enableVibrationWhenRinging();
        assertTrue(startRingingAndWaitForAsync(mockCall2, true));
        verify(mockRingtoneFactory, times(1))
            .getRingtone(any(Call.class), isNull(), anyBoolean());
        verifyNoMoreInteractions(mockRingtoneFactory);
        verify(mockTonePlayer).stopTone();
        verify(mockRingtone).play();
    }

    /**
     * test shouldRingForContact will suppress the incoming call if matchesCallFilter returns
     * false (meaning DND is ON and the caller cannot bypass the settings)
     */
    @Test
    public void testShouldRingForContact_CallSuppressed() {
        // WHEN
        when(mockCall1.wasDndCheckComputedForCall()).thenReturn(false);
        when(mockCall1.getHandle()).thenReturn(Uri.parse(""));
        when(mContext.getSystemService(NotificationManager.class)).thenReturn(
                mockNotificationManager);
        // suppress the call
        when(mockNotificationManager.matchesCallFilter(any(Bundle.class))).thenReturn(false);

        // run the method under test
        assertFalse(mRingerUnderTest.shouldRingForContact(mockCall1));

        // THEN
        // verify we never set the call object and matchesCallFilter is called
        verify(mockCall1, never()).setCallIsSuppressedByDoNotDisturb(true);
        verify(mockNotificationManager, times(1))
                .matchesCallFilter(any(Bundle.class));
    }

    /**
     * test shouldRingForContact will alert the user of an incoming call if matchesCallFilter
     * returns true (meaning DND is NOT suppressing the caller)
     */
    @Test
    public void testShouldRingForContact_CallShouldRing() {
        // WHEN
        when(mockCall1.wasDndCheckComputedForCall()).thenReturn(false);
        when(mockCall1.getHandle()).thenReturn(Uri.parse(""));
        // alert the user of the call
        when(mockNotificationManager.matchesCallFilter(any(Bundle.class))).thenReturn(true);

        // run the method under test
        assertTrue(mRingerUnderTest.shouldRingForContact(mockCall1));

        // THEN
        // verify we never set the call object and matchesCallFilter is called
        verify(mockCall1, never()).setCallIsSuppressedByDoNotDisturb(false);
        verify(mockNotificationManager, times(1))
                .matchesCallFilter(any(Bundle.class));
    }

    /**
     * ensure Telecom does not re-query the NotificationManager if the call object already has
     * the result.
     */
    @Test
    public void testShouldRingForContact_matchesCallFilterIsAlreadyComputed() {
        // WHEN
        when(mockCall1.wasDndCheckComputedForCall()).thenReturn(true);
        when(mockCall1.isCallSuppressedByDoNotDisturb()).thenReturn(true);

        // THEN
        assertFalse(mRingerUnderTest.shouldRingForContact(mockCall1));
        verify(mockCall1, never()).setCallIsSuppressedByDoNotDisturb(false);
        verify(mockNotificationManager, never()).matchesCallFilter(any(Bundle.class));
    }

    @Test
    public void testNoFlashNotificationWhenCallSuppressed() throws Exception {
        ensureRingtoneMocked();
        // Start call waiting to make sure that it doesn't stop when we start ringing
        mRingerUnderTest.startCallWaiting(mockCall1);
        when(mockCall2.wasDndCheckComputedForCall()).thenReturn(false);
        when(mockCall2.getHandle()).thenReturn(Uri.parse(""));
        when(mockNotificationManager.matchesCallFilter(any(Bundle.class))).thenReturn(false);

        assertFalse(mRingerUnderTest.shouldRingForContact(mockCall2));
        assertFalse(startRingingAndWaitForAsync(mockCall2, false));
        verify(mockAccessibilityManagerAdapter, never())
                .startFlashNotificationSequence(any(Context.class), anyInt());
    }

    @Test
    public void testStartFlashNotificationWhenRingStarts() throws Exception {
        ensureRingtoneMocked();
        // Start call waiting to make sure that it doesn't stop when we start ringing
        mRingerUnderTest.startCallWaiting(mockCall1);
        when(mockCall2.wasDndCheckComputedForCall()).thenReturn(false);
        when(mockCall2.getHandle()).thenReturn(Uri.parse(""));
        when(mockNotificationManager.matchesCallFilter(any(Bundle.class))).thenReturn(true);

        assertTrue(mRingerUnderTest.shouldRingForContact(mockCall2));
        assertTrue(startRingingAndWaitForAsync(mockCall2, false));
        verify(mockAccessibilityManagerAdapter, atLeastOnce())
                .startFlashNotificationSequence(any(Context.class), anyInt());
    }

    @Test
    public void testStopFlashNotificationWhenRingStops() throws Exception {
        Ringtone mockRingtone = mock(Ringtone.class);
        when(mockRingtoneFactory.getRingtone(
                any(Call.class), nullable(VolumeShaper.Configuration.class), anyBoolean()))
                .thenAnswer(x -> {
                    // Be slow to create ringtone.
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return mockRingtone;
                });
        // Start call waiting to make sure that it doesn't stop when we start ringing
        enableVibrationWhenRinging();
        mRingerUnderTest.startCallWaiting(mockCall1);
        when(mockCall2.wasDndCheckComputedForCall()).thenReturn(false);
        when(mockCall2.getHandle()).thenReturn(Uri.parse(""));
        when(mockNotificationManager.matchesCallFilter(any(Bundle.class))).thenReturn(true);

        assertTrue(mRingerUnderTest.shouldRingForContact(mockCall2));
        assertTrue(mRingerUnderTest.startRinging(mockCall2, false));
        mRingerUnderTest.stopRinging();
        verify(mockAccessibilityManagerAdapter, atLeastOnce())
                .stopFlashNotificationSequence(any(Context.class));
        mRingCompletionFuture.get();  // Don't leak async work.
        verify(mockVibrator, never())  // cancelled before it started.
                .vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testNoRingingForQuietProfile() throws Exception {
        UserManager um = mContext.getSystemService(UserManager.class);
        when(um.isManagedProfile(PA_HANDLE.getUserHandle().getIdentifier())).thenReturn(true);
        when(um.isQuietModeEnabled(PA_HANDLE.getUserHandle())).thenReturn(true);
        // We don't want to acquire audio focus when self-managed
        assertFalse(startRingingAndWaitForAsync(mockCall2, true));

        verify(mockTonePlayer, never()).stopTone();
        verifyZeroInteractions(mockRingtoneFactory);
        verify(mockVibrator, never())
                .vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
    }

    /**
     * Call startRinging and wait for its effects to have played out, to allow reliable assertions
     * after it. The effects are generally "start playing ringtone" and "start vibration" - not
     * waiting for anything open-ended.
     */
    private boolean startRingingAndWaitForAsync(Call mockCall2, boolean isHfpDeviceAttached)
            throws Exception {
        boolean result = mRingerUnderTest.startRinging(mockCall2, isHfpDeviceAttached);
        mRingCompletionFuture.get();
        return result;
    }

    private void ensureRingerIsAudible() {
        when(mockAudioManager.getRingerMode()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        when(mockAudioManager.getStreamVolume(AudioManager.STREAM_RING)).thenReturn(100);
    }

    private void ensureRingerIsNotAudible() {
        when(mockAudioManager.getRingerMode()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        when(mockAudioManager.getStreamVolume(AudioManager.STREAM_RING)).thenReturn(0);
    }

    private void enableVibrationWhenRinging() {
        when(mockVibrator.hasVibrator()).thenReturn(true);
        when(mockSystemSettingsUtil.isRingVibrationEnabled(any(Context.class))).thenReturn(true);
    }

    private void enableVibrationOnlyWhenNotRinging() {
        when(mockVibrator.hasVibrator()).thenReturn(true);
        when(mockSystemSettingsUtil.isRingVibrationEnabled(any(Context.class))).thenReturn(false);
    }

    private void enableRampingRinger() {
        when(mockSystemSettingsUtil.isRampingRingerEnabled(any(Context.class))).thenReturn(true);
    }

    private void setIsUsingHaptics(Ringtone mockRingtone, boolean useHaptics) {
        // Note: using haptics can also depend on mIsHapticPlaybackSupported. If changing
        // that, the ringerUnderTest needs to be re-created.
        when(mockSystemSettingsUtil.isAudioCoupledVibrationForRampingRingerEnabled())
            .thenReturn(useHaptics);
        when(mockRingtone.hasHapticChannels()).thenReturn(useHaptics);
    }

    private Ringtone ensureRingtoneMocked() {
        Ringtone mockRingtone = mock(Ringtone.class);
        when(mockRingtoneFactory.getRingtone(
                any(Call.class), nullable(VolumeShaper.Configuration.class), anyBoolean()))
                .thenReturn(mockRingtone);
        when(mockRingtoneFactory.getHapticOnlyRingtone()).thenReturn(mockRingtone);
        return mockRingtone;
    }
}
