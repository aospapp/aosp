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

package com.android.car.audio;

import static android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
import static android.media.AudioAttributes.USAGE_MEDIA;
import static android.media.AudioManager.AUDIOFOCUS_GAIN;
import static android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK;
import static android.media.AudioManager.AUDIOFOCUS_REQUEST_DELAYED;
import static android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED;
import static android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.media.CarAudioManager;
import android.media.AudioFocusInfo;
import android.os.Bundle;
import android.util.SparseArray;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;

@RunWith(MockitoJUnitRunner.class)
public final class CarZonesAudioFocusTest extends CarZonesAudioFocusTestBase {
    @Before
    public void setUp() {
        mCarAudioZones = generateAudioZones();
        when(mCarAudioService.getZoneIdForUid(MEDIA_CLIENT_UID_1)).thenReturn(PRIMARY_ZONE_ID);
    }

    @Test
    public void onAudioFocusRequest_forTwoDifferentZones_requestGranted() {
        CarZonesAudioFocus carZonesAudioFocus = getCarZonesAudioFocus();
        AudioFocusInfo audioFocusInfoClient1 = generateMediaRequestForPrimaryZone(
                /* isDelayedFocusEnabled= */ false);


        requestFocusAndAssertIfRequestNotGranted(carZonesAudioFocus, audioFocusInfoClient1);

        when(mCarAudioService.getZoneIdForUid(MEDIA_CLIENT_UID_2)).thenReturn(SECONDARY_ZONE_ID);
        AudioFocusInfo audioFocusInfoClient2 = new AudioFocusInfoBuilder().setUsage(USAGE_MEDIA)
                .setClientId(MEDIA_CLIENT_ID).setGainRequest(AUDIOFOCUS_GAIN)
                .setClientUid(MEDIA_CLIENT_UID_2).createAudioFocusInfo();

        requestFocusAndAssertIfRequestNotGranted(carZonesAudioFocus, audioFocusInfoClient2);

        verify(mMockAudioManager, never())
                .dispatchAudioFocusChange(eq(audioFocusInfoClient1), anyInt(), eq(mAudioPolicy));

        verify(mMockAudioManager, never())
                .dispatchAudioFocusChange(eq(audioFocusInfoClient2), anyInt(), eq(mAudioPolicy));
    }

    @Test
    public void onAudioFocusRequest_forTwoDifferentZones_abandonInOne_requestGranted() {
        CarZonesAudioFocus carZonesAudioFocus = getCarZonesAudioFocus();
        AudioFocusInfo audioFocusInfoClient1 = generateMediaRequestForPrimaryZone(
                /* isDelayedFocusEnabled= */ false);

        requestFocusAndAssertIfRequestNotGranted(carZonesAudioFocus, audioFocusInfoClient1);

        when(mCarAudioService.getZoneIdForUid(MEDIA_CLIENT_UID_2)).thenReturn(SECONDARY_ZONE_ID);
        AudioFocusInfo audioFocusInfoClient2 = new AudioFocusInfoBuilder().setUsage(USAGE_MEDIA)
                .setClientId(MEDIA_CLIENT_ID).setGainRequest(AUDIOFOCUS_GAIN)
                .setClientUid(MEDIA_CLIENT_UID_2).createAudioFocusInfo();

        requestFocusAndAssertIfRequestNotGranted(carZonesAudioFocus, audioFocusInfoClient2);

        abandonFocusAndAssertIfAbandonNotGranted(carZonesAudioFocus, audioFocusInfoClient2);
        verify(mMockAudioManager, never())
                .dispatchAudioFocusChange(eq(audioFocusInfoClient1), anyInt(), eq(mAudioPolicy));
    }

    @Test
    public void onAudioFocusRequest_withBundleFocusRequest_requestGranted() {
        CarZonesAudioFocus carZonesAudioFocus = getCarZonesAudioFocus();
        when(mCarAudioService.isAudioZoneIdValid(PRIMARY_ZONE_ID)).thenReturn(true);

        Bundle bundle = new Bundle();
        bundle.putInt(CarAudioManager.AUDIOFOCUS_EXTRA_REQUEST_ZONE_ID,
                PRIMARY_ZONE_ID);
        AudioFocusInfo audioFocusInfoClient = new AudioFocusInfoBuilder().setUsage(USAGE_MEDIA)
                .setClientId(MEDIA_CLIENT_ID).setGainRequest(AUDIOFOCUS_GAIN)
                .setClientUid(MEDIA_CLIENT_UID_1).setBundle(bundle).createAudioFocusInfo();

        requestFocusAndAssertIfRequestNotGranted(carZonesAudioFocus, audioFocusInfoClient);

        verify(mMockAudioManager, never())
                .dispatchAudioFocusChange(eq(audioFocusInfoClient), anyInt(), eq(mAudioPolicy));
    }

    @Test
    public void onAudioFocusRequest_repeatForSameZone_requestGranted() {
        CarZonesAudioFocus carZonesAudioFocus = getCarZonesAudioFocus();
        AudioFocusInfo audioFocusInfoMediaClient = generateMediaRequestForPrimaryZone(
                /* isDelayedFocusEnabled= */ false);

        requestFocusAndAssertIfRequestNotGranted(carZonesAudioFocus, audioFocusInfoMediaClient);

        when(mCarAudioService.getZoneIdForUid(NAVIGATION_CLIENT_UID_1))
                .thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo audioFocusInfoNavClient =
                new AudioFocusInfoBuilder().setUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setClientId(NAVIGATION_CLIENT_ID).setGainRequest(AUDIOFOCUS_GAIN)
                        .setClientUid(NAVIGATION_CLIENT_UID_1).createAudioFocusInfo();

        requestFocusAndAssertIfRequestNotGranted(carZonesAudioFocus, audioFocusInfoNavClient);

        verify(mMockAudioManager)
                .dispatchAudioFocusChange(eq(audioFocusInfoMediaClient),
                        anyInt(), eq(mAudioPolicy));

        verify(mMockAudioManager, never())
                .dispatchAudioFocusChange(eq(audioFocusInfoNavClient),
                        anyInt(), eq(mAudioPolicy));
    }

    @Test
    public void onAudioFocusRequest_forNavigationWhileOnCall_rejectNavOnCall_requestFailed() {
        CarZonesAudioFocus carZonesAudioFocus = getCarZonesAudioFocus();
        when(mCarAudioService.isAudioZoneIdValid(PRIMARY_ZONE_ID)).thenReturn(true);
        setUpRejectNavigationOnCallValue(true);
        carZonesAudioFocus.updateUserForZoneId(PRIMARY_ZONE_ID, TEST_USER_ID);

        when(mCarAudioService.getZoneIdForUid(CALL_CLIENT_UID_1)).thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo audioFocusInfoCallClient = generateCallRequestForPrimaryZone();

        requestFocusAndAssertIfRequestNotGranted(carZonesAudioFocus, audioFocusInfoCallClient);

        when(mCarAudioService.getZoneIdForUid(NAVIGATION_CLIENT_UID_1)).thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo audioFocusInfoNavClient =
                new AudioFocusInfoBuilder().setUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setGainRequest(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setClientId(NAVIGATION_CLIENT_ID)
                        .setClientUid(NAVIGATION_CLIENT_UID_1).createAudioFocusInfo();

        carZonesAudioFocus
                .onAudioFocusRequest(audioFocusInfoNavClient, AUDIOFOCUS_REQUEST_GRANTED);
        verify(mMockAudioManager).setFocusRequestResult(audioFocusInfoNavClient,
                AUDIOFOCUS_REQUEST_FAILED, mAudioPolicy);
    }

    @Test
    public void onAudioFocusRequest_forNavigationWhileOnCall_noRejectNavOnCall_requestSucceeds() {
        CarZonesAudioFocus carZonesAudioFocus = getCarZonesAudioFocus();
        when(mCarAudioService.isAudioZoneIdValid(PRIMARY_ZONE_ID)).thenReturn(true);
        setUpRejectNavigationOnCallValue(false);
        carZonesAudioFocus.updateUserForZoneId(PRIMARY_ZONE_ID, TEST_USER_ID);

        when(mCarAudioService.getZoneIdForUid(CALL_CLIENT_UID_1)).thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo audioFocusInfoCallClient = generateCallRequestForPrimaryZone();

        requestFocusAndAssertIfRequestNotGranted(carZonesAudioFocus, audioFocusInfoCallClient);

        when(mCarAudioService.getZoneIdForUid(NAVIGATION_CLIENT_UID_1)).thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo audioFocusInfoNavClient =
                new AudioFocusInfoBuilder().setUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setGainRequest(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setClientId(NAVIGATION_CLIENT_ID)
                        .setClientUid(NAVIGATION_CLIENT_UID_1).createAudioFocusInfo();


        carZonesAudioFocus
                .onAudioFocusRequest(audioFocusInfoNavClient, AUDIOFOCUS_REQUEST_GRANTED);
        verify(mMockAudioManager).setFocusRequestResult(audioFocusInfoNavClient,
                AUDIOFOCUS_REQUEST_GRANTED, mAudioPolicy);
    }

    @Test
    public void onAudioFocusRequest_forMediaWithDelayedFocus_requestSucceeds() {
        CarZonesAudioFocus carZonesAudioFocus = getCarZonesAudioFocus();
        when(mCarAudioService.isAudioZoneIdValid(PRIMARY_ZONE_ID)).thenReturn(true);

        when(mCarAudioService.getZoneIdForUid(CALL_CLIENT_UID_1)).thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo audioFocusMediaClient = generateMediaRequestForPrimaryZone(
                /* isDelayedFocusEnabled= */ true);

        carZonesAudioFocus
                .onAudioFocusRequest(audioFocusMediaClient, AUDIOFOCUS_REQUEST_GRANTED);

        verify(mMockAudioManager).setFocusRequestResult(audioFocusMediaClient,
                AUDIOFOCUS_REQUEST_GRANTED, mAudioPolicy);
    }

    @Test
    public void onAudioFocusRequest_forMediaWithDelayedFocusWhileOnCall_delayedSucceeds() {
        CarZonesAudioFocus carZonesAudioFocus = getCarZonesAudioFocus();
        when(mCarAudioService.isAudioZoneIdValid(PRIMARY_ZONE_ID)).thenReturn(true);

        when(mCarAudioService.getZoneIdForUid(CALL_CLIENT_UID_1)).thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo audioFocusInfoCallClient = generateCallRequestForPrimaryZone();

        requestFocusAndAssertIfRequestNotGranted(carZonesAudioFocus, audioFocusInfoCallClient);

        AudioFocusInfo audioFocusMediaClient = generateMediaRequestForPrimaryZone(
                /* isDelayedFocusEnabled= */ true);

        carZonesAudioFocus
                .onAudioFocusRequest(audioFocusMediaClient, AUDIOFOCUS_REQUEST_GRANTED);

        verify(mMockAudioManager).setFocusRequestResult(audioFocusMediaClient,
                AUDIOFOCUS_REQUEST_DELAYED, mAudioPolicy);
    }

    @Test
    public void onAudioFocusAbandon_forCallWhileOnMediaWithDelayedFocus_focusGained() {
        CarZonesAudioFocus carZonesAudioFocus = getCarZonesAudioFocus();
        when(mCarAudioService.isAudioZoneIdValid(PRIMARY_ZONE_ID)).thenReturn(true);

        when(mCarAudioService.getZoneIdForUid(CALL_CLIENT_UID_1)).thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo audioFocusInfoCallClient = generateCallRequestForPrimaryZone();

        carZonesAudioFocus
                .onAudioFocusRequest(audioFocusInfoCallClient, AUDIOFOCUS_REQUEST_GRANTED);

        AudioFocusInfo audioFocusMediaClient = generateMediaRequestForPrimaryZone(
                /* isDelayedFocusEnabled= */ true);

        carZonesAudioFocus
                .onAudioFocusRequest(audioFocusMediaClient, AUDIOFOCUS_REQUEST_GRANTED);
        when(mMockAudioManager.dispatchAudioFocusChange(audioFocusMediaClient,
                AUDIOFOCUS_GAIN, mAudioPolicy)).thenReturn(AUDIOFOCUS_REQUEST_GRANTED);
        carZonesAudioFocus.onAudioFocusAbandon(audioFocusInfoCallClient);

        verify(mMockAudioManager).dispatchAudioFocusChange(eq(audioFocusMediaClient),
                anyInt(), eq(mAudioPolicy));
    }

    @Test
    public void onAudioFocusAbandon_forMediaWithDelayedFocusWhileOnCall_abandonSucceeds() {
        CarZonesAudioFocus carZonesAudioFocus = getCarZonesAudioFocus();
        when(mCarAudioService.isAudioZoneIdValid(PRIMARY_ZONE_ID)).thenReturn(true);

        when(mCarAudioService.getZoneIdForUid(CALL_CLIENT_UID_1)).thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo audioFocusInfoCallClient = generateCallRequestForPrimaryZone();

        carZonesAudioFocus
                .onAudioFocusRequest(audioFocusInfoCallClient, AUDIOFOCUS_REQUEST_GRANTED);

        AudioFocusInfo audioFocusMediaClient = generateMediaRequestForPrimaryZone(
                /* isDelayedFocusEnabled= */ true);

        carZonesAudioFocus
                .onAudioFocusRequest(audioFocusMediaClient, AUDIOFOCUS_REQUEST_GRANTED);
        carZonesAudioFocus.onAudioFocusAbandon(audioFocusMediaClient);

        verify(mMockAudioManager, never()).dispatchAudioFocusChange(eq(audioFocusMediaClient),
                anyInt(), eq(mAudioPolicy));
    }

    @Test
    public void
            onAudioFocusAbandon_forCallAfterAbandonMediaWithDelayedFocus_delayedFocusNotGained() {
        CarZonesAudioFocus carZonesAudioFocus = getCarZonesAudioFocus();
        when(mCarAudioService.isAudioZoneIdValid(PRIMARY_ZONE_ID)).thenReturn(true);

        when(mCarAudioService.getZoneIdForUid(CALL_CLIENT_UID_1)).thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo audioFocusInfoCallClient = generateCallRequestForPrimaryZone();

        carZonesAudioFocus
                .onAudioFocusRequest(audioFocusInfoCallClient, AUDIOFOCUS_REQUEST_GRANTED);

        AudioFocusInfo audioFocusMediaClient = generateMediaRequestForPrimaryZone(
                /* isDelayedFocusEnabled= */ true);

        carZonesAudioFocus
                .onAudioFocusRequest(audioFocusMediaClient, AUDIOFOCUS_REQUEST_GRANTED);

        carZonesAudioFocus.onAudioFocusAbandon(audioFocusMediaClient);
        carZonesAudioFocus.onAudioFocusAbandon(audioFocusInfoCallClient);

        verify(mMockAudioManager, never()).dispatchAudioFocusChange(eq(audioFocusMediaClient),
                anyInt(), eq(mAudioPolicy));
    }

    @Test
    public void onAudioFocusRequest_multipleTimesForSameDelayedFocus_delayedFocusNotGainsFocus() {
        CarZonesAudioFocus carZonesAudioFocus = getCarZonesAudioFocus();
        when(mCarAudioService.isAudioZoneIdValid(PRIMARY_ZONE_ID)).thenReturn(true);

        when(mCarAudioService.getZoneIdForUid(CALL_CLIENT_UID_1)).thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo audioFocusInfoCallClient = generateCallRequestForPrimaryZone();

        carZonesAudioFocus
                .onAudioFocusRequest(audioFocusInfoCallClient, AUDIOFOCUS_REQUEST_GRANTED);

        AudioFocusInfo audioFocusMediaClient = generateMediaRequestForPrimaryZone(
                /* isDelayedFocusEnabled= */ true);

        carZonesAudioFocus
                .onAudioFocusRequest(audioFocusMediaClient, AUDIOFOCUS_REQUEST_GRANTED);

        verify(mMockAudioManager).setFocusRequestResult(audioFocusMediaClient,
                AUDIOFOCUS_REQUEST_DELAYED, mAudioPolicy);

        carZonesAudioFocus
                .onAudioFocusRequest(audioFocusMediaClient, AUDIOFOCUS_REQUEST_GRANTED);

        verify(mMockAudioManager, times(2)).setFocusRequestResult(audioFocusMediaClient,
                AUDIOFOCUS_REQUEST_DELAYED, mAudioPolicy);
    }

    @Test
    public void
            onAudioFocusRequest_multipleTimesForDifferentContextSameClient_delayedFocusFailed() {
        CarZonesAudioFocus carZonesAudioFocus = getCarZonesAudioFocus();
        when(mCarAudioService.isAudioZoneIdValid(PRIMARY_ZONE_ID)).thenReturn(true);

        when(mCarAudioService.getZoneIdForUid(CALL_CLIENT_UID_1)).thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo audioFocusInfoCallClient = generateCallRequestForPrimaryZone();

        carZonesAudioFocus
                .onAudioFocusRequest(audioFocusInfoCallClient, AUDIOFOCUS_REQUEST_GRANTED);

        AudioFocusInfo audioFocusMediaClient = generateMediaRequestForPrimaryZone(
                /* isDelayedFocusEnabled= */ true);

        carZonesAudioFocus
                .onAudioFocusRequest(audioFocusMediaClient, AUDIOFOCUS_REQUEST_GRANTED);

        AudioFocusInfo audioFocusInfoNavClient =
                new AudioFocusInfoBuilder().setUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setClientId(MEDIA_CLIENT_ID).setGainRequest(AUDIOFOCUS_GAIN)
                        .setClientUid(NAVIGATION_CLIENT_UID_1).createAudioFocusInfo();
        carZonesAudioFocus
                .onAudioFocusRequest(audioFocusInfoNavClient, AUDIOFOCUS_REQUEST_GRANTED);

        verify(mMockAudioManager).setFocusRequestResult(audioFocusInfoNavClient,
                AUDIOFOCUS_REQUEST_FAILED, mAudioPolicy);
    }

    @Test
    public void onAudioFocusRequest_notifiesFocusCallback() {
        CarZonesAudioFocus carZonesAudioFocus = getCarZonesAudioFocus();
        AudioFocusInfo audioFocusInfo = generateMediaRequestForPrimaryZone(
                /* isDelayedFocusEnabled= */ false);

        carZonesAudioFocus.onAudioFocusRequest(audioFocusInfo, AUDIOFOCUS_REQUEST_GRANTED);

        ArgumentCaptor<SparseArray<List<AudioFocusInfo>>> focusHoldersCaptor =
                ArgumentCaptor.forClass(SparseArray.class);
        verify(mMockCarFocusCallback).onFocusChange(eq(new int[]{PRIMARY_ZONE_ID}),
                focusHoldersCaptor.capture());
        assertThat(focusHoldersCaptor.getValue().get(PRIMARY_ZONE_ID))
                .containsExactly(audioFocusInfo);
    }

    @Test
    public void onAudioFocusAbandon_notifiesFocusCallback() {
        CarZonesAudioFocus carZonesAudioFocus = getCarZonesAudioFocus();
        AudioFocusInfo audioFocusInfo = generateMediaRequestForPrimaryZone(
                /* isDelayedFocusEnabled= */ false);

        carZonesAudioFocus.onAudioFocusAbandon(audioFocusInfo);

        ArgumentCaptor<SparseArray<List<AudioFocusInfo>>> focusHoldersCaptor =
                ArgumentCaptor.forClass(SparseArray.class);
        verify(mMockCarFocusCallback).onFocusChange(eq(new int[]{PRIMARY_ZONE_ID}),
                focusHoldersCaptor.capture());
        assertThat(focusHoldersCaptor.getValue().get(PRIMARY_ZONE_ID)).isEmpty();
    }
}
