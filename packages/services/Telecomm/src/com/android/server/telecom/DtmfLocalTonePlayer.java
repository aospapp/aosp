/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.provider.Settings;

// TODO: Needed for move to system service: import com.android.internal.R;
import com.google.common.collect.ImmutableMap;

import java.util.Map;

/**
 * Plays DTMF tones locally for the caller to hear. In order to reduce (1) the amount of times we
 * check the "play local tones" setting and (2) the length of time we keep the tone generator, this
 * class employs a concept of a call "session" that starts and stops when the foreground call
 * changes.
 */
class DtmfLocalTonePlayer extends CallsManagerListenerBase {
    private static final Map<Character, Integer> TONE_MAP =
            ImmutableMap.<Character, Integer>builder()
                    .put('1', ToneGenerator.TONE_DTMF_1)
                    .put('2', ToneGenerator.TONE_DTMF_2)
                    .put('3', ToneGenerator.TONE_DTMF_3)
                    .put('4', ToneGenerator.TONE_DTMF_4)
                    .put('5', ToneGenerator.TONE_DTMF_5)
                    .put('6', ToneGenerator.TONE_DTMF_6)
                    .put('7', ToneGenerator.TONE_DTMF_7)
                    .put('8', ToneGenerator.TONE_DTMF_8)
                    .put('9', ToneGenerator.TONE_DTMF_9)
                    .put('0', ToneGenerator.TONE_DTMF_0)
                    .put('#', ToneGenerator.TONE_DTMF_P)
                    .put('*', ToneGenerator.TONE_DTMF_S)
                    .build();

    /** Generator used to actually play the tone. */
    private ToneGenerator mToneGenerator;

    /** The current call associated with an existing dtmf session. */
    private Call mCall;

    /** The context. */
    private final Context mContext;

    public DtmfLocalTonePlayer(Context context) {
        mContext = context;
    }

    /** {@inheritDoc} */
    @Override
    public void onForegroundCallChanged(Call oldForegroundCall, Call newForegroundCall) {
        endDtmfSession(oldForegroundCall);
        startDtmfSession(newForegroundCall);
    }

    /**
     * Starts playing the dtmf tone specified by c.
     *
     * @param call The associated call.
     * @param c The digit to play.
     */
    void playTone(Call call, char c) {
        // Do nothing if it is not the right call.
        if (mCall != call) {
            return;
        }

        if (mToneGenerator == null) {
            Log.d(this, "playTone: mToneGenerator == null, %c.", c);
        } else {
            Log.d(this, "starting local tone: %c.", c);
            if (TONE_MAP.containsKey(c)) {
                mToneGenerator.startTone(TONE_MAP.get(c), -1 /* toneDuration */);
            }
        }
    }

    /**
     * Stops any currently playing dtmf tone.
     *
     * @param call The associated call.
     */
    void stopTone(Call call) {
        // Do nothing if it's not the right call.
        if (mCall != call) {
            return;
        }

        if (mToneGenerator == null) {
            Log.d(this, "stopTone: mToneGenerator == null.");
        } else {
            Log.d(this, "stopping local tone.");
            mToneGenerator.stopTone();
        }
    }

    /**
     * Runs initialization requires to play local tones during a call.
     *
     * @param call The call associated with this dtmf session.
     */
    private void startDtmfSession(Call call) {
        if (call == null) {
            return;
        }
        final Context context = call.getContext();
        final boolean areLocalTonesEnabled;
        if (context.getResources().getBoolean(R.bool.allow_local_dtmf_tones)) {
            areLocalTonesEnabled = Settings.System.getInt(
                    context.getContentResolver(), Settings.System.DTMF_TONE_WHEN_DIALING, 1) == 1;
        } else {
            areLocalTonesEnabled = false;
        }

        mCall = call;

        if (areLocalTonesEnabled) {
            if (mToneGenerator == null) {
                try {
                    mToneGenerator = new ToneGenerator(AudioManager.STREAM_DTMF, 80);
                } catch (RuntimeException e) {
                    Log.e(this, e, "Error creating local tone generator.");
                    mToneGenerator = null;
                }
            }
        }
    }

    /**
     * Releases resources needed for playing local dtmf tones.
     *
     * @param call The call associated with the session to end.
     */
    private void endDtmfSession(Call call) {
        if (call != null && mCall == call) {
            // Do a stopTone() in case the sessions ends before we are told to stop the tone.
            stopTone(call);

            mCall = null;

            if (mToneGenerator != null) {
                mToneGenerator.release();
                mToneGenerator = null;
            }
        }
    }
}
