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

package com.android.systemui.car.keyguard;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowManager;

import androidx.annotation.MainThread;
import androidx.annotation.VisibleForTesting;

import com.android.car.ui.FocusParkingView;
import com.android.internal.widget.LockPatternView;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardViewController;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.R;
import com.android.systemui.car.systembar.CarSystemBarController;
import com.android.systemui.car.window.OverlayViewController;
import com.android.systemui.car.window.OverlayViewGlobalStateController;
import com.android.systemui.car.window.SystemUIOverlayWindowController;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.phone.BiometricUnlockController;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.KeyguardBouncer;
import com.android.systemui.statusbar.phone.KeyguardBouncer.Factory;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationPanelViewController;
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.toast.SystemUIToast;
import com.android.systemui.toast.ToastFactory;
import com.android.systemui.util.concurrency.DelayableExecutor;

import javax.inject.Inject;

import dagger.Lazy;

/**
 * Automotive implementation of the {@link KeyguardViewController}. It controls the Keyguard View
 * that is mounted to the SystemUIOverlayWindow.
 */
@SysUISingleton
public class CarKeyguardViewController extends OverlayViewController implements
        KeyguardViewController {
    private static final String TAG = "CarKeyguardViewController";
    private static final boolean DEBUG = true;
    private static final float TOAST_PARAMS_HORIZONTAL_WEIGHT = 1.0f;
    private static final float TOAST_PARAMS_VERTICAL_WEIGHT = 1.0f;

    private final Context mContext;
    private final DelayableExecutor mMainExecutor;
    private final WindowManager mWindowManager;
    private final ToastFactory mToastFactory;
    private final FocusParkingView mFocusParkingView;
    private final KeyguardStateController mKeyguardStateController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final Lazy<BiometricUnlockController> mBiometricUnlockControllerLazy;
    private final ViewMediatorCallback mViewMediatorCallback;
    private final CarSystemBarController mCarSystemBarController;
    private final Factory mKeyguardBouncerFactory;
    // Needed to instantiate mBouncer.
    private final KeyguardBouncer.BouncerExpansionCallback mExpansionCallback =
            new KeyguardBouncer.BouncerExpansionCallback() {
                @Override
                public void onFullyShown() {
                    LockPatternView patternView = getLayout().findViewById(R.id.lockPatternView);
                    if (patternView != null) {
                        patternView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                            @Override
                            public void onFocusChange(View v, boolean hasFocus) {
                                if (hasFocus) {
                                    makeOverlayToast(R.string.lockpattern_does_not_support_rotary);
                                }
                            }
                        });
                    }
                }

                @Override
                public void onStartingToHide() {
                }

                @Override
                public void onStartingToShow() {
                }

                @Override
                public void onFullyHidden() {
                }
            };

    private KeyguardBouncer mBouncer;
    private OnKeyguardCancelClickedListener mKeyguardCancelClickedListener;
    private boolean mShowing;
    private boolean mIsOccluded;
    private boolean mIsSleeping;
    private int mToastShowDurationMillisecond;

    @Inject
    public CarKeyguardViewController(
            Context context,
            @Main DelayableExecutor mainExecutor,
            WindowManager windowManager,
            ToastFactory toastFactory,
            SystemUIOverlayWindowController systemUIOverlayWindowController,
            OverlayViewGlobalStateController overlayViewGlobalStateController,
            KeyguardStateController keyguardStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            Lazy<BiometricUnlockController> biometricUnlockControllerLazy,
            ViewMediatorCallback viewMediatorCallback,
            CarSystemBarController carSystemBarController,
            KeyguardBouncer.Factory keyguardBouncerFactory) {

        super(R.id.keyguard_stub, overlayViewGlobalStateController);

        mContext = context;
        mMainExecutor = mainExecutor;
        mWindowManager = windowManager;
        mToastFactory = toastFactory;
        mFocusParkingView = systemUIOverlayWindowController.getBaseLayout().findViewById(
                R.id.focus_parking_view);
        mKeyguardStateController = keyguardStateController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mBiometricUnlockControllerLazy = biometricUnlockControllerLazy;
        mViewMediatorCallback = viewMediatorCallback;
        mCarSystemBarController = carSystemBarController;
        mKeyguardBouncerFactory = keyguardBouncerFactory;

        mToastShowDurationMillisecond = mContext.getResources().getInteger(
                R.integer.car_keyguard_toast_show_duration_millisecond);
    }

    @Override
    protected int getFocusAreaViewId() {
        return R.id.keyguard_container;
    }

    @Override
    protected boolean shouldShowNavigationBarInsets() {
        return true;
    }

    @Override
    public void onFinishInflate() {
        mBouncer = mKeyguardBouncerFactory
                .create(getLayout().findViewById(R.id.keyguard_container), mExpansionCallback);
        mBiometricUnlockControllerLazy.get().setKeyguardViewController(this);
    }

    @Override
    @MainThread
    public void notifyKeyguardAuthenticated(boolean strongAuth) {
        if (mBouncer != null) {
            mBouncer.notifyKeyguardAuthenticated(strongAuth);
        }
    }

    @Override
    @MainThread
    public void showBouncer(boolean scrimmed) {
        if (mShowing && !mBouncer.isShowing()) {
            mBouncer.show(/* resetSecuritySelection= */ false);
        }
    }

    @Override
    @MainThread
    public void show(Bundle options) {
        if (mShowing) return;

        mShowing = true;
        mKeyguardStateController.notifyKeyguardState(mShowing, /* occluded= */ false);
        mCarSystemBarController.showAllKeyguardButtons(/* isSetUp= */ true);
        start();
        reset(/* hideBouncerWhenShowing= */ false);
        notifyKeyguardUpdateMonitor();
    }

    @Override
    @MainThread
    public void hide(long startTime, long fadeoutDuration) {
        if (!mShowing || mIsSleeping) return;

        mViewMediatorCallback.readyForKeyguardDone();
        mShowing = false;
        mKeyguardStateController.notifyKeyguardState(mShowing, /* occluded= */ false);
        mBouncer.hide(/* destroyView= */ true);
        mCarSystemBarController.showAllNavigationButtons(/* isSetUp= */ true);
        stop();
        mKeyguardStateController.notifyKeyguardDoneFading();
        mMainExecutor.execute(mViewMediatorCallback::keyguardGone);
        notifyKeyguardUpdateMonitor();
    }

    @Override
    public void reset(boolean hideBouncerWhenShowing) {
        if (mIsSleeping) return;

        mMainExecutor.execute(() -> {
            if (mShowing) {
                if (mBouncer != null) {
                    if (!mBouncer.isSecure()) {
                        dismissAndCollapse();
                    }
                    resetBouncer();
                }
                mKeyguardUpdateMonitor.sendKeyguardReset();
                notifyKeyguardUpdateMonitor();
            } else {
                // This is necessary in order to address an inconsistency between the keyguard
                // service and the keyguard views.
                // TODO: Investigate the source of the inconsistency.
                show(/* options= */ null);
            }
        });
    }

    @Override
    public void resetAlternateAuth(boolean forceUpdateScrim) {
        // no-op
    }

    @Override
    @MainThread
    public void onFinishedGoingToSleep() {
        if (mBouncer != null) {
            mBouncer.onScreenTurnedOff();
        }
    }

    @Override
    @MainThread
    public void setOccluded(boolean occluded, boolean animate) {
        mIsOccluded = occluded;
        getOverlayViewGlobalStateController().setOccluded(occluded);
        if (occluded) {
            mCarSystemBarController.showAllOcclusionButtons(/* isSetup= */ true);
        } else {
            if (mShowing && mBouncer.isSecure()) {
                mCarSystemBarController.showAllKeyguardButtons(/* isSetup= */ true);
            } else {
                mCarSystemBarController.showAllNavigationButtons(/* isSetUp= */ true);
            }
        }
    }

    @Override
    @MainThread
    public void onCancelClicked() {
        if (mBouncer == null) return;

        getOverlayViewGlobalStateController().setWindowNeedsInput(/* needsInput= */ false);

        mBouncer.hide(/* destroyView= */ true);
        mKeyguardCancelClickedListener.onCancelClicked();
    }

    @Override
    public boolean isShowing() {
        return mShowing;
    }

    @Override
    @MainThread
    public void dismissAndCollapse() {
        // If dismissing and collapsing Keyguard is requested (e.g. by a Keyguard-dismissing
        // Activity) while Keyguard is occluded, unocclude Keyguard so the user can authenticate to
        // dismiss Keyguard.
        if (mIsOccluded) {
            setOccluded(/* occluded= */ false, /* animate= */ false);
        }
        if (mBouncer != null && !mBouncer.isSecure()) {
            hide(/* startTime= */ 0, /* fadeoutDuration= */ 0);
        }
    }

    @Override
    @MainThread
    public void startPreHideAnimation(Runnable finishRunnable) {
        if (mBouncer == null) return;

        mBouncer.startPreHideAnimation(finishRunnable);
    }

    @Override
    public void setNeedsInput(boolean needsInput) {
        getOverlayViewGlobalStateController().setWindowNeedsInput(needsInput);
    }

    @Override
    public void onStartedGoingToSleep() {
        mIsSleeping = true;
    }

    @Override
    public void onStartedWakingUp() {
        mIsSleeping = false;
        reset(/* hideBouncerWhenShowing= */ false);
    }

    /**
     * Add listener for keyguard cancel clicked.
     */
    public void registerOnKeyguardCancelClickedListener(
            OnKeyguardCancelClickedListener keyguardCancelClickedListener) {
        mKeyguardCancelClickedListener = keyguardCancelClickedListener;
    }

    /**
     * Remove listener for keyguard cancel clicked.
     */
    public void unregisterOnKeyguardCancelClickedListener(
            OnKeyguardCancelClickedListener keyguardCancelClickedListener) {
        mKeyguardCancelClickedListener = null;
    }

    @Override
    public ViewRootImpl getViewRootImpl() {
        return ((View) getLayout().getParent()).getViewRootImpl();
    }

    @Override
    @MainThread
    public boolean isBouncerShowing() {
        return mBouncer != null && mBouncer.isShowing();
    }

    @Override
    @MainThread
    public boolean bouncerIsOrWillBeShowing() {
        return mBouncer != null && (mBouncer.isShowing() || mBouncer.inTransit());
    }

    @Override
    public void keyguardGoingAway() {
        // no-op
    }

    @Override
    public void setKeyguardGoingAwayState(boolean isKeyguardGoingAway) {
        // no-op
    }

    @Override
    public boolean shouldDisableWindowAnimationsForUnlock() {
        // TODO(b/205189147): revert the following change after the proper fix is landed.
        // Disables the KeyGuard animation to resolve TaskView misalignment issue after display-on.
        return true;
    }

    @Override
    public boolean isGoingToNotificationShade() {
        return false;
    }

    @Override
    public boolean isUnlockWithWallpaper() {
        return false;
    }

    @Override
    public boolean shouldSubtleWindowAnimationsForUnlock() {
        return false;
    }

    @Override
    public void blockPanelExpansionFromCurrentTouch() {
        // no-op
    }

    @Override
    public void registerCentralSurfaces(
            CentralSurfaces centralSurfaces,
            NotificationPanelViewController notificationPanelViewController,
            PanelExpansionStateManager panelExpansionStateManager,
            BiometricUnlockController biometricUnlockController,
            View notificationContainer,
            KeyguardBypassController bypassController) {
        // no-op
    }

    /**
     * Hides Keyguard so that the transitioning Bouncer can be hidden until it is prepared. To be
     * called by {@link com.android.systemui.car.userswitcher.FullscreenUserSwitcherViewMediator}
     * when a new user is selected.
     */
    public void hideKeyguardToPrepareBouncer() {
        getLayout().setVisibility(View.INVISIBLE);
    }

    @VisibleForTesting
    void setKeyguardBouncer(KeyguardBouncer keyguardBouncer) {
        mBouncer = keyguardBouncer;
    }

    private void revealKeyguardIfBouncerPrepared() {
        int reattemptDelayMillis = 50;
        Runnable revealKeyguard = () -> {
            if (mBouncer == null) {
                if (DEBUG) {
                    Log.d(TAG, "revealKeyguardIfBouncerPrepared: revealKeyguard request is ignored "
                            + "since the Bouncer has not been initialized yet.");
                }
                return;
            }
            if (!mBouncer.inTransit() || !mBouncer.isSecure()) {
                if (mShowing) {
                    // Only set the layout as visible if the keyguard should be showing
                    showInternal();
                }
            } else {
                if (DEBUG) {
                    Log.d(TAG, "revealKeyguardIfBouncerPrepared: Bouncer is not prepared "
                            + "yet so reattempting after " + reattemptDelayMillis + "ms.");
                }
                mMainExecutor.executeDelayed(this::revealKeyguardIfBouncerPrepared,
                        reattemptDelayMillis);
            }
        };
        mMainExecutor.execute(revealKeyguard);
    }

    private void notifyKeyguardUpdateMonitor() {
        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(mShowing);
        if (mBouncer != null) {
            mKeyguardUpdateMonitor.sendKeyguardBouncerChanged(
                    bouncerIsOrWillBeShowing(), isBouncerShowing());
        }
    }

    private void resetBouncer() {
        mMainExecutor.execute(() -> {
            hideInternal();
            mBouncer.hide(/* destroyView= */ false);
            mBouncer.show(/* resetSecuritySelection= */ true);
            revealKeyguardIfBouncerPrepared();
        });
    }

    private void makeOverlayToast(int stringId) {
        Resources res = mContext.getResources();

        SystemUIToast systemUIToast = mToastFactory.createToast(mContext,
                res.getString(stringId), mContext.getPackageName(), UserHandle.myUserId(),
                res.getConfiguration().orientation);

        if (systemUIToast == null) {
            return;
        }

        View toastView = systemUIToast.getView();

        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.width = WindowManager.LayoutParams.WRAP_CONTENT;
        params.format = PixelFormat.TRANSLUCENT;
        params.type = WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL;
        params.y = systemUIToast.getYOffset();

        int absGravity = Gravity.getAbsoluteGravity(systemUIToast.getGravity(),
                res.getConfiguration().getLayoutDirection());
        params.gravity = absGravity;

        if ((absGravity & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.FILL_HORIZONTAL) {
            params.horizontalWeight = TOAST_PARAMS_HORIZONTAL_WEIGHT;
        }
        if ((absGravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.FILL_VERTICAL) {
            params.verticalWeight = TOAST_PARAMS_VERTICAL_WEIGHT;
        }

        // Make FocusParkingView temporarily unfocusable so it does not steal the focus.
        // If FocusParkingView is focusable, it first steals focus and then returns it to Pattern
        // Lock, which causes the Toast to appear repeatedly.
        mFocusParkingView.setFocusable(false);
        mWindowManager.addView(toastView, params);

        Animator inAnimator = systemUIToast.getInAnimation();
        if (inAnimator != null) {
            inAnimator.start();
        }

        mMainExecutor.executeDelayed(new Runnable() {
            @Override
            public void run() {
                Animator outAnimator = systemUIToast.getOutAnimation();
                if (outAnimator != null) {
                    outAnimator.start();
                    outAnimator.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animator) {
                            mWindowManager.removeViewImmediate(toastView);
                            mFocusParkingView.setFocusable(true);
                        }
                    });
                } else {
                    mFocusParkingView.setFocusable(true);
                }
            }
        }, mToastShowDurationMillisecond);
    }

    /**
     * Defines a callback for keyguard cancel button clicked listeners.
     */
    public interface OnKeyguardCancelClickedListener {
        /**
         * Called when keyguard cancel button is clicked.
         */
        void onCancelClicked();
    }
}
