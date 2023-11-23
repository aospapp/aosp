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

import static android.media.AudioAttributes.USAGE_MEDIA;

import static com.android.car.audio.CarAudioContext.AudioContext;
import static com.android.car.audio.ContentObserverFactory.ContentChangeCallback;
import static com.android.car.audio.FocusInteraction.AUDIO_FOCUS_NAVIGATION_REJECTED_DURING_CALL_URI;
import static com.android.car.audio.FocusInteraction.INTERACTION_CONCURRENT;
import static com.android.car.audio.FocusInteraction.INTERACTION_EXCLUSIVE;
import static com.android.car.audio.FocusInteraction.INTERACTION_REJECT;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.util.SparseArray;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class FocusInteractionTest {
    private static final int UNDEFINED_CONTEXT_VALUE = -10;
    private static final int TEST_USER_ID = 100;

    private static final CarAudioContext TEST_CAR_AUDIO_CONTEXT =
            new CarAudioContext(CarAudioContext.getAllContextsInfo(),
                    /* useCoreAudioRouting= */ false);

    private static final @AudioContext int TEST_MEDIA_CONTEXT =
            TEST_CAR_AUDIO_CONTEXT.getContextForAudioAttribute(
                    CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA));

    @Mock
    private CarAudioSettings mMockCarAudioSettings;
    @Mock
    private ContentResolver mMockContentResolver;
    @Mock
    private ContentObserverFactory mMockContentObserverFactory;
    @Mock
    private Handler mMockHandler;
    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();

    private ContentObserver mContentObserver;

    private final List<FocusEntry> mLosers = new ArrayList<>();

    private FocusInteraction mFocusInteraction;

    @Before
    public void setUp() {
        when(mMockCarAudioSettings.getContentResolverForUser(TEST_USER_ID))
                .thenReturn(mMockContentResolver);
        doAnswer(invocation -> {
            ContentChangeCallback wrapper = (ContentChangeCallback) invocation.getArguments()[0];
            mContentObserver = new ContentObserver(mMockHandler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    if (AUDIO_FOCUS_NAVIGATION_REJECTED_DURING_CALL_URI.equals(uri)) {
                        wrapper.onChange();
                    }
                }
            };
            return mContentObserver;
        }).when(mMockContentObserverFactory).createObserver(any());
        mFocusInteraction =
                new FocusInteraction(mMockCarAudioSettings, mMockContentObserverFactory,
                        TEST_CAR_AUDIO_CONTEXT);
    }

    @Test
    public void constructor_withNullSettings_fails() {
        NullPointerException thrown =
                assertThrows(NullPointerException.class,
                        () -> new FocusInteraction(null, mMockContentObserverFactory,
                                TEST_CAR_AUDIO_CONTEXT));

        assertWithMessage("Constructor with Null Settings Exception")
                .that(thrown).hasMessageThat().contains("Settings");
    }

    @Test
    public void constructor_withNullObserverFactory_fails() {
        NullPointerException thrown =
                assertThrows(NullPointerException.class,
                        () -> new FocusInteraction(mMockCarAudioSettings, null,
                                TEST_CAR_AUDIO_CONTEXT));

        assertWithMessage("Constructor with Null Observer Factory Exception")
                .that(thrown).hasMessageThat().contains("Content Observer Factory");
    }

    @Test
    public void getInteractionMatrix_returnsNByNMatrix() {
        // One extra for CarAudioContext.getInvalidContext()
        CarAudioContext carAudioContext =
                new CarAudioContext(CarAudioContext.getAllContextsInfo(),
                        /* useCoreAudioRouting= */ false);
        int n = carAudioContext.getAllContextsIds().size() + 1;

        SparseArray<SparseArray<Integer>> interactionMatrix =
                mFocusInteraction.getInteractionMatrix();

        assertThat(interactionMatrix.size()).isEqualTo(n);
        for (int i = 0; i < n; i++) {
            assertWithMessage("Length of row %s in interaction matrix", i)
                    .that(interactionMatrix.get(i).size()).isEqualTo(n);
        }
    }

    @Test
    public void getInteractionMatrix_hasValidInteractionValues() {
        List<Integer> supportedInteractions = Arrays.asList(INTERACTION_REJECT,
                INTERACTION_EXCLUSIVE, INTERACTION_CONCURRENT);

        SparseArray<SparseArray<Integer>> interactionMatrix =
                mFocusInteraction.getInteractionMatrix();
        for (int i = 0; i < interactionMatrix.size(); i++) {
            for (int j = 0; j < interactionMatrix.get(i).size(); j++) {
                assertWithMessage("Row %s column %s has unexpected value %s", i, j,
                        interactionMatrix.get(i).get(j)).that(
                        interactionMatrix.get(i).get(j)).isIn(supportedInteractions);
            }
        }
    }

    @Test
    public void evaluateResult_forRejectPair_returnsFailed() {
        FocusEntry focusEntry = newMockFocusEntryWithContext(CarAudioContext.getInvalidContext());

        int result = mFocusInteraction.evaluateRequest(CarAudioContext.getInvalidContext(),
                focusEntry, /* allowDucking= */ false, /* allowsDelayedFocus= */ false, mLosers);

        assertThat(result).isEqualTo(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
    }

    @Test
    public void evaluateResult_forCallAndNavigation_withNavigationNotRejected_returnsConcurrent() {
        when(mMockCarAudioSettings.isRejectNavigationOnCallEnabledInSettings(TEST_USER_ID))
                .thenReturn(false);

        mFocusInteraction.setUserIdForSettings(TEST_USER_ID);
        FocusEntry focusEntry = newMockFocusEntryWithContext(CarAudioContext.CALL);

        int result = mFocusInteraction.evaluateRequest(CarAudioContext.NAVIGATION, focusEntry,
                /* allowDucking= */ false, /* allowsDelayedFocus= */ false, mLosers);

        assertThat(result).isEqualTo(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    }

    @Test
    public void evaluateResult_forCallAndNavigation_withNavigationRejected_returnsConcurrent() {
        when(mMockCarAudioSettings.isRejectNavigationOnCallEnabledInSettings(TEST_USER_ID))
                .thenReturn(true);
        mFocusInteraction.setUserIdForSettings(TEST_USER_ID);
        FocusEntry focusEntry = newMockFocusEntryWithContext(CarAudioContext.CALL);

        int result = mFocusInteraction.evaluateRequest(CarAudioContext.NAVIGATION, focusEntry,
                /* allowDucking= */ false, /* allowsDelayedFocus= */ false, mLosers);

        assertThat(result).isEqualTo(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
    }

    @Test
    public void evaluateResult_forRejectPair_doesNotAddToLosers() {
        FocusEntry focusEntry = newMockFocusEntryWithContext(CarAudioContext.getInvalidContext());

        mFocusInteraction
                .evaluateRequest(CarAudioContext.getInvalidContext(), focusEntry,
                        /* allowDucking= */ false, /* allowsDelayedFocus= */ false,
                        mLosers);

        assertThat(mLosers).isEmpty();
    }

    @Test
    public void evaluateRequest_forExclusivePair_returnsGranted() {
        FocusEntry focusEntry = newMockFocusEntryWithContext(TEST_MEDIA_CONTEXT);

        int result = mFocusInteraction.evaluateRequest(TEST_MEDIA_CONTEXT, focusEntry,
                /* allowDucking= */ false, /* allowsDelayedFocus= */ false, mLosers);

        assertThat(result).isEqualTo(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    }

    @Test
    public void evaluateRequest_forExclusivePair_addsEntryToLosers() {
        FocusEntry focusEntry = newMockFocusEntryWithContext(TEST_MEDIA_CONTEXT);

        mFocusInteraction
                .evaluateRequest(TEST_MEDIA_CONTEXT, focusEntry, /* allowDucking= */ false,
                        /* allowsDelayedFocus= */ false, mLosers);

        assertThat(mLosers).containsExactly(focusEntry);
    }

    @Test
    public void evaluateResult_forConcurrentPair_returnsGranted() {
        FocusEntry focusEntry = newMockFocusEntryWithContext(CarAudioContext.NAVIGATION);

        int result = mFocusInteraction.evaluateRequest(TEST_MEDIA_CONTEXT, focusEntry,
                /* allowDucking= */ false, /* allowsDelayedFocus= */ false, mLosers);

        assertThat(result).isEqualTo(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    }

    @Test
    public void evaluateResult_forConcurrentPair_andNoDucking_addsToLosers() {
        FocusEntry focusEntry =
                newMockFocusEntryWithDuckingBehavior(false, false);

        mFocusInteraction.evaluateRequest(TEST_MEDIA_CONTEXT, focusEntry, /* allowDucking= */ false,
                /* allowsDelayedFocus= */ false, mLosers);

        assertThat(mLosers).containsExactly(focusEntry);
    }

    @Test
    public void evaluateResult_forConcurrentPair_andWantsPauseInsteadOfDucking_addsToLosers() {
        FocusEntry focusEntry =
                newMockFocusEntryWithDuckingBehavior(true, false);

        mFocusInteraction.evaluateRequest(TEST_MEDIA_CONTEXT, focusEntry, /* allowDucking= */ true,
                /* allowsDelayedFocus= */ false, mLosers);

        assertThat(mLosers).containsExactly(focusEntry);
    }

    @Test
    public void evaluateResult_forConcurrentPair_andReceivesDuckEvents_addsToLosers() {
        FocusEntry focusEntry =
                newMockFocusEntryWithDuckingBehavior(false, true);

        mFocusInteraction.evaluateRequest(TEST_MEDIA_CONTEXT, focusEntry, /* allowDucking= */ true,
                /* allowsDelayedFocus= */ false, mLosers);

        assertThat(mLosers).containsExactly(focusEntry);
    }

    @Test
    public void evaluateResult_forConcurrentPair_andDucking_doesAddsToLosers() {
        FocusEntry focusEntry =
                newMockFocusEntryWithDuckingBehavior(false, true);

        mFocusInteraction.evaluateRequest(TEST_MEDIA_CONTEXT, focusEntry, /* allowDucking= */ true,
                /* allowsDelayedFocus= */ false, mLosers);

        assertThat(mLosers).containsExactly(focusEntry);
    }

    @Test
    public void evaluateResult_forUndefinedContext_throws() {
        FocusEntry focusEntry = newMockFocusEntryWithContext(CarAudioContext.NAVIGATION);

        assertThrows(NullPointerException.class,
                () -> mFocusInteraction.evaluateRequest(UNDEFINED_CONTEXT_VALUE, focusEntry,
                        /* allowDucking= */ false, /* allowsDelayedFocus= */ false,
                        mLosers));
    }

    @Test
    public void evaluateResult_forUndefinedFocusHolderContext_throws() {
        FocusEntry focusEntry = newMockFocusEntryWithContext(UNDEFINED_CONTEXT_VALUE);

        assertThrows(NullPointerException.class,
                () -> mFocusInteraction.evaluateRequest(TEST_MEDIA_CONTEXT, focusEntry,
                        /* allowDucking= */ false, /* allowsDelayedFocus= */ false,
                        mLosers));
    }

    @Test
    public void evaluateRequest_forExclusivePair_withDelayedFocus_returnsGranted() {
        FocusEntry focusEntry = newMockFocusEntryWithContext(TEST_MEDIA_CONTEXT);

        int result = mFocusInteraction.evaluateRequest(TEST_MEDIA_CONTEXT, focusEntry,
                /* allowDucking= */ false, /* allowsDelayedFocus= */ true, mLosers);

        assertThat(result).isEqualTo(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    }

    @Test
    public void evaluateRequest_forRejectPair_withDelayedFocus_returnsDelayed() {
        FocusEntry focusEntry = newMockFocusEntryWithContext(CarAudioContext.CALL);

        int result = mFocusInteraction.evaluateRequest(TEST_MEDIA_CONTEXT, focusEntry,
                /* allowDucking= */ false, /* allowsDelayedFocus= */ true, mLosers);

        assertThat(result).isEqualTo(AudioManager.AUDIOFOCUS_REQUEST_DELAYED);
    }

    @Test
    public void evaluateRequest_forRejectPair_withoutDelayedFocus_returnsReject() {
        FocusEntry focusEntry = newMockFocusEntryWithContext(CarAudioContext.CALL);

        int result = mFocusInteraction.evaluateRequest(TEST_MEDIA_CONTEXT, focusEntry,
                /* allowDucking= */ false, /* allowsDelayedFocus= */ false, mLosers);

        assertThat(result).isEqualTo(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
    }

    @Test
    public void isRejectNavigationOnCallEnabled_isRejected() {
        when(mMockCarAudioSettings.isRejectNavigationOnCallEnabledInSettings(TEST_USER_ID))
                .thenReturn(true, false);

        mFocusInteraction.setUserIdForSettings(TEST_USER_ID);

        assertWithMessage("Initial Reject Navigation on Call Status")
                .that(mFocusInteraction.isRejectNavigationOnCallEnabled()).isTrue();
    }

    @Test
    public void onChange_forContentObserver_rejectedOnCallDisabled() {
        when(mMockCarAudioSettings.isRejectNavigationOnCallEnabledInSettings(TEST_USER_ID))
                .thenReturn(true, false);

        mFocusInteraction.setUserIdForSettings(TEST_USER_ID);

        mContentObserver.onChange(true, AUDIO_FOCUS_NAVIGATION_REJECTED_DURING_CALL_URI);

        assertWithMessage("Reject Navigation on Call Status after Update")
                .that(mFocusInteraction.isRejectNavigationOnCallEnabled()).isFalse();
    }

    private FocusEntry newMockFocusEntryWithContext(@AudioContext int audioContext) {
        FocusEntry focusEntry = mock(FocusEntry.class);
        when(focusEntry.getAudioContext()).thenReturn(audioContext);
        return focusEntry;
    }

    private FocusEntry newMockFocusEntryWithDuckingBehavior(boolean pauseInsteadOfDucking,
            boolean receivesDuckingEvents) {
        FocusEntry focusEntry = newMockFocusEntryWithContext(CarAudioContext.NAVIGATION);
        when(focusEntry.wantsPauseInsteadOfDucking()).thenReturn(pauseInsteadOfDucking);
        when(focusEntry.receivesDuckEvents()).thenReturn(receivesDuckingEvents);
        return focusEntry;
    }
}
