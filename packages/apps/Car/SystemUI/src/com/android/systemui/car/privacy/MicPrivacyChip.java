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

package com.android.systemui.car.privacy;

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.constraintlayout.motion.widget.MotionLayout;

import com.android.systemui.R;
import com.android.systemui.car.statusicon.AnimatedStatusIcon;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Car optimized Mic Privacy Chip View that is shown when microphone is being used.
 *
 * State flows:
 * Base state:
 * <ul>
 * <li>INVISIBLE - Start Mic Use ->> Mic Status?</li>
 * </ul>
 * Mic On:
 * <ul>
 * <li>Mic Status? - On ->> ACTIVE_INIT</li>
 * <li>ACTIVE_INIT - delay ->> ACTIVE/ACTIVE_SELECTED</li>
 * <li>ACTIVE/ACTIVE_SELECTED - Stop Mic Use ->> INACTIVE/INACTIVE_SELECTED</li>
 * <li>INACTIVE/INACTIVE_SELECTED - delay/close panel ->> INVISIBLE</li>
 * </ul>
 * Mic Off:
 * <ul>
 * <li>Mic Status? - Off ->> MICROPHONE_OFF</li>
 * <li>MICROPHONE_OFF - panel opened ->> MICROPHONE_OFF_SELECTED</li>
 * </ul>
 */
public class MicPrivacyChip extends MotionLayout implements AnimatedStatusIcon {
    private static final boolean DEBUG = Build.IS_DEBUGGABLE;
    private static final String TAG = "MicPrivacyChip";
    private static final String TYPES_TEXT_MICROPHONE = "microphone";

    private final int mDelayPillToCircle;
    private final int mDelayToNoMicUsage;

    private AnimationStates mCurrentTransitionState;
    private boolean mPanelOpen;
    private boolean mIsInflated;
    private boolean mIsMicrophoneEnabled;
    private ScheduledExecutorService mExecutor;

    public MicPrivacyChip(@NonNull Context context) {
        this(context, /* attrs= */ null);
    }

    public MicPrivacyChip(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, /* defStyleAttrs= */ 0);
    }

    public MicPrivacyChip(@NonNull Context context,
            @Nullable AttributeSet attrs, int defStyleAttrs) {
        super(context, attrs, defStyleAttrs);

        mDelayPillToCircle = getResources().getInteger(R.integer.privacy_chip_pill_to_circle_delay);
        mDelayToNoMicUsage = getResources().getInteger(R.integer.privacy_chip_no_mic_usage_delay);

        mExecutor = Executors.newSingleThreadScheduledExecutor();
        mIsInflated = false;

        // Microphone is enabled by default (invisible state).
        mIsMicrophoneEnabled = true;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mCurrentTransitionState = AnimationStates.INVISIBLE;
        mIsInflated = true;
    }

    @Override
    public void setOnClickListener(View.OnClickListener onClickListener) {
        // required for CTS tests.
        super.setOnClickListener(onClickListener);
        // required for rotary.
        requireViewById(R.id.focus_view).setOnClickListener(onClickListener);
    }

    /**
     * Sets whether microphone is enabled or disabled.
     * If enabled, animates to {@link AnimationStates#INVISIBLE}.
     * Otherwise, animates to {@link AnimationStates#MICROPHONE_OFF}.
     */
    @UiThread
    public void setMicrophoneEnabled(boolean isMicrophoneEnabled) {
        if (DEBUG) Log.d(TAG, "Microphone enabled: " + isMicrophoneEnabled);

        if (mIsMicrophoneEnabled == isMicrophoneEnabled) {
            if (isMicrophoneEnabled) {
                switch (mCurrentTransitionState) {
                    case INVISIBLE:
                    case ACTIVE:
                    case ACTIVE_SELECTED:
                    case INACTIVE:
                    case INACTIVE_SELECTED:
                    case ACTIVE_INIT:
                        return;
                }
            } else {
                if (mCurrentTransitionState == AnimationStates.MICROPHONE_OFF
                        || mCurrentTransitionState == AnimationStates.MICROPHONE_OFF_SELECTED) {
                    return;
                }
            }
        }

        mIsMicrophoneEnabled = isMicrophoneEnabled;

        if (!mIsInflated) {
            if (DEBUG) Log.d(TAG, "Layout not inflated");

            return;
        }

        if (mIsMicrophoneEnabled) {
            if (mPanelOpen) {
                setTransition(R.id.inactiveSelectedFromMicOffSelected);
            } else {
                setTransition(R.id.invisibleFromMicOff);
            }
        } else {
            if (mPanelOpen) {
                switch (mCurrentTransitionState) {
                    case INVISIBLE:
                        setTransition(R.id.micOffSelectedFromInvisible);
                        break;
                    case ACTIVE_INIT:
                        setTransition(R.id.micOffSelectedFromActiveInit);
                        break;
                    case ACTIVE:
                        setTransition(R.id.micOffSelectedFromActive);
                        break;
                    case ACTIVE_SELECTED:
                        setTransition(R.id.micOffSelectedFromActiveSelected);
                        break;
                    case INACTIVE:
                        setTransition(R.id.micOffSelectedFromInactive);
                        break;
                    case INACTIVE_SELECTED:
                        setTransition(R.id.micOffSelectedFromInactiveSelected);
                        break;
                    default:
                        return;
                }
            } else {
                switch (mCurrentTransitionState) {
                    case INVISIBLE:
                        setTransition(R.id.micOffFromInvisible);
                        break;
                    case ACTIVE_INIT:
                        setTransition(R.id.micOffFromActiveInit);
                        break;
                    case ACTIVE:
                        setTransition(R.id.micOffFromActive);
                        break;
                    case ACTIVE_SELECTED:
                        setTransition(R.id.micOffFromActiveSelected);
                        break;
                    case INACTIVE:
                        setTransition(R.id.micOffFromInactive);
                        break;
                    case INACTIVE_SELECTED:
                        setTransition(R.id.micOffFromInactiveSelected);
                        break;
                    default:
                        return;
                }
            }
        }

        mExecutor.shutdownNow();
        mExecutor = Executors.newSingleThreadScheduledExecutor();

        // TODO(182938429): Use Transition Listeners once ConstraintLayout 2.0.0 is being used.

        // When microphone is off, mic privacy chip is always visible.
        if (!mIsMicrophoneEnabled) setVisibility(View.VISIBLE);
        setContentDescription(!mIsMicrophoneEnabled);
        if (mIsMicrophoneEnabled) {
            if (mPanelOpen) {
                mCurrentTransitionState = AnimationStates.INACTIVE_SELECTED;
            } else {
                mCurrentTransitionState = AnimationStates.INVISIBLE;
            }
        } else {
            if (mPanelOpen) {
                mCurrentTransitionState = AnimationStates.MICROPHONE_OFF_SELECTED;
            } else {
                mCurrentTransitionState = AnimationStates.MICROPHONE_OFF;
            }
        }
        transitionToEnd();
        if (mIsMicrophoneEnabled && !mPanelOpen) setVisibility(View.GONE);
    }

    private void setContentDescription(boolean isMicOff) {
        String contentDescription;
        if (isMicOff) {
            contentDescription = getResources().getString(R.string.mic_privacy_chip_off_content);
        } else {
            contentDescription = getResources().getString(
                    R.string.ongoing_privacy_chip_content_multiple_apps, TYPES_TEXT_MICROPHONE);
        }

        setContentDescription(contentDescription);
    }

    /**
     * Starts reveal animation for Mic Privacy Chip.
     */
    @UiThread
    public void animateIn() {
        if (!mIsInflated) {
            if (DEBUG) Log.d(TAG, "Layout not inflated");

            return;
        }

        if (mCurrentTransitionState == null) {
            if (DEBUG) Log.d(TAG, "Current transition state is null or empty.");

            return;
        }

        switch (mCurrentTransitionState) {
            case INVISIBLE:
                setTransition(mIsMicrophoneEnabled ? R.id.activeInitFromInvisible
                        : R.id.micOffFromInvisible);
                break;
            case INACTIVE:
                setTransition(mIsMicrophoneEnabled ? R.id.activeInitFromInactive
                        : R.id.micOffFromInactive);
                break;
            case INACTIVE_SELECTED:
                setTransition(mIsMicrophoneEnabled ? R.id.activeInitFromInactiveSelected
                        : R.id.micOffFromInactiveSelected);
                break;
            case MICROPHONE_OFF:
                if (!mIsMicrophoneEnabled) {
                    if (DEBUG) {
                        Log.d(TAG, "No Transition.");
                    }
                    return;
                }

                setTransition(R.id.activeInitFromMicOff);
                break;
            case MICROPHONE_OFF_SELECTED:
                if (!mIsMicrophoneEnabled) {
                    if (DEBUG) {
                        Log.d(TAG, "No Transition.");
                    }
                    return;
                }

                setTransition(R.id.activeInitFromMicOffSelected);
                break;
            default:
                if (DEBUG) {
                    Log.d(TAG, "Early exit, mCurrentTransitionState= "
                            + mCurrentTransitionState);
                }

                return;
        }

        mExecutor.shutdownNow();
        mExecutor = Executors.newSingleThreadScheduledExecutor();

        // TODO(182938429): Use Transition Listeners once ConstraintLayout 2.0.0 is being used.
        setContentDescription(false);
        setVisibility(View.VISIBLE);
        if (mIsMicrophoneEnabled) {
            mCurrentTransitionState = AnimationStates.ACTIVE_INIT;
        } else {
            if (mPanelOpen) {
                mCurrentTransitionState = AnimationStates.MICROPHONE_OFF_SELECTED;
            } else {
                mCurrentTransitionState = AnimationStates.MICROPHONE_OFF;
            }
        }
        transitionToEnd();
        if (mIsMicrophoneEnabled) {
            mExecutor.schedule(MicPrivacyChip.this::animateToOrangeCircle, mDelayPillToCircle,
                    TimeUnit.MILLISECONDS);
        }
    }

    // TODO(182938429): Use Transition Listeners once ConstraintLayout 2.0.0 is being used.
    private void animateToOrangeCircle() {
        // Since this is launched using a {@link ScheduledExecutorService}, its UI based elements
        // need to execute on main executor.
        getContext().getMainExecutor().execute(() -> {
            if (mPanelOpen) {
                setTransition(R.id.activeSelectedFromActiveInit);
                mCurrentTransitionState = AnimationStates.ACTIVE_SELECTED;
            } else {
                setTransition(R.id.activeFromActiveInit);
                mCurrentTransitionState = AnimationStates.ACTIVE;
            }
            transitionToEnd();
        });
    }

    /**
     * Starts conceal animation for Mic Privacy Chip.
     */
    @UiThread
    public void animateOut() {
        if (!mIsInflated) {
            if (DEBUG) Log.d(TAG, "Layout not inflated");

            return;
        }

        if (mPanelOpen) {
            switch (mCurrentTransitionState) {
                case ACTIVE_INIT:
                    setTransition(R.id.inactiveSelectedFromActiveInit);
                    break;
                case ACTIVE:
                    setTransition(R.id.inactiveSelectedFromActive);
                    break;
                case ACTIVE_SELECTED:
                    setTransition(R.id.inactiveSelectedFromActiveSelected);
                    break;
                default:
                    if (DEBUG) {
                        Log.d(TAG, "Early exit, mCurrentTransitionState= "
                                + mCurrentTransitionState);
                    }

                    return;
            }
        } else {
            switch (mCurrentTransitionState) {
                case ACTIVE_INIT:
                    setTransition(R.id.inactiveFromActiveInit);
                    break;
                case ACTIVE:
                    setTransition(R.id.inactiveFromActive);
                    break;
                case ACTIVE_SELECTED:
                    setTransition(R.id.inactiveFromActiveSelected);
                    break;
                default:
                    if (DEBUG) {
                        Log.d(TAG, "Early exit, mCurrentTransitionState= "
                                + mCurrentTransitionState);
                    }

                    return;
            }
        }

        mExecutor.shutdownNow();
        mExecutor = Executors.newSingleThreadScheduledExecutor();

        // TODO(182938429): Use Transition Listeners once ConstraintLayout 2.0.0 is being used.
        mCurrentTransitionState = mPanelOpen
                ? AnimationStates.INACTIVE_SELECTED
                : AnimationStates.INACTIVE;
        transitionToEnd();
        mExecutor.schedule(MicPrivacyChip.this::reset, mDelayToNoMicUsage,
                TimeUnit.MILLISECONDS);
    }



    // TODO(182938429): Use Transition Listeners once ConstraintLayout 2.0.0 is being used.
    private void reset() {
        // Since this is launched using a {@link ScheduledExecutorService}, its UI based elements
        // need to execute on main executor.
        getContext().getMainExecutor().execute(() -> {
            if (mIsMicrophoneEnabled && !mPanelOpen) {
                setTransition(R.id.invisibleFromInactive);
                mCurrentTransitionState = AnimationStates.INVISIBLE;
            } else if (!mIsMicrophoneEnabled) {
                if (mPanelOpen) {
                    setTransition(R.id.inactiveSelectedFromMicOffSelected);
                    mCurrentTransitionState = AnimationStates.INACTIVE_SELECTED;
                } else {
                    setTransition(R.id.invisibleFromMicOff);
                    mCurrentTransitionState = AnimationStates.INVISIBLE;
                }
            }

            transitionToEnd();

            if (!mPanelOpen) {
                setVisibility(View.GONE);
            }
        });
    }

    @AnyThread
    @Override
    public void setIconHighlighted(boolean iconHighlighted) {
        // UI based elements need to execute on main executor.
        getContext().getMainExecutor().execute(() -> {
            if (mPanelOpen == iconHighlighted) {
                return;
            }

            mPanelOpen = iconHighlighted;

            if (mIsMicrophoneEnabled) {
                switch (mCurrentTransitionState) {
                    case ACTIVE:
                        if (mPanelOpen) {
                            setTransition(R.id.activeSelectedFromActive);
                            mCurrentTransitionState = AnimationStates.ACTIVE_SELECTED;
                            transitionToEnd();
                        }
                        return;
                    case ACTIVE_SELECTED:
                        if (!mPanelOpen) {
                            setTransition(R.id.activeFromActiveSelected);
                            mCurrentTransitionState = AnimationStates.ACTIVE;
                            transitionToEnd();
                        }
                        return;
                    case INACTIVE:
                        if (mPanelOpen) {
                            setTransition(R.id.inactiveSelectedFromInactive);
                            mCurrentTransitionState = AnimationStates.INACTIVE_SELECTED;
                            transitionToEnd();
                        }
                        return;
                    case INACTIVE_SELECTED:
                        if (!mPanelOpen) {
                            setTransition(R.id.invisibleFromInactiveSelected);
                            mCurrentTransitionState = AnimationStates.INVISIBLE;
                            transitionToEnd();
                            setVisibility(View.GONE);
                        }
                        return;
                }
            } else {
                switch (mCurrentTransitionState) {
                    case MICROPHONE_OFF:
                        if (mPanelOpen) {
                            setTransition(R.id.micOffSelectedFromMicOff);
                            mCurrentTransitionState = AnimationStates.MICROPHONE_OFF_SELECTED;
                            transitionToEnd();
                        }
                        return;
                    case MICROPHONE_OFF_SELECTED:
                        if (!mPanelOpen) {
                            setTransition(R.id.micOffFromMicOffSelected);
                            mCurrentTransitionState = AnimationStates.MICROPHONE_OFF;
                            transitionToEnd();
                        }
                        return;
                }
            }

            if (DEBUG) {
                Log.d(TAG, "Early exit, mCurrentTransitionState= "
                        + mCurrentTransitionState);
            }
        });
    }

    @Override
    public void setTransition(int transitionId) {
        if (DEBUG) {
            Log.d(TAG, "Transition set: " + getResources().getResourceEntryName(transitionId));
        }
        super.setTransition(transitionId);
    }

    private enum AnimationStates {
        INVISIBLE,
        ACTIVE_INIT,
        ACTIVE,
        ACTIVE_SELECTED,
        INACTIVE,
        INACTIVE_SELECTED,
        MICROPHONE_OFF,
        MICROPHONE_OFF_SELECTED,
    }
}
