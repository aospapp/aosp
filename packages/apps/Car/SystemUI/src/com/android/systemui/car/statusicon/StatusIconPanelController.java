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

import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;
import static android.widget.ListPopupWindow.WRAP_CONTENT;

import android.annotation.ColorInt;
import android.annotation.DimenRes;
import android.annotation.LayoutRes;
import android.app.PendingIntent;
import android.car.app.CarActivityManager;
import android.car.drivingstate.CarUxRestrictions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.car.qc.QCItem;
import com.android.car.qc.view.QCView;
import com.android.car.ui.FocusParkingView;
import com.android.car.ui.utils.CarUxRestrictionsUtil;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.car.qc.QCFooterButton;
import com.android.systemui.car.qc.QCFooterButtonView;
import com.android.systemui.car.qc.QCHeaderReadOnlyIconsContainer;
import com.android.systemui.car.qc.SystemUIQCView;
import com.android.systemui.car.qc.SystemUIQCViewController;
import com.android.systemui.car.statusicon.ui.QCPanelReadOnlyIconsController;
import com.android.systemui.car.users.CarSystemUIUserUtil;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.ConfigurationController;

import java.util.ArrayList;

import javax.inject.Provider;

/**
 * A controller for a panel view associated with a status icon.
 */
public class StatusIconPanelController {
    private static final int DEFAULT_POPUP_WINDOW_ANCHOR_GRAVITY = Gravity.TOP | Gravity.START;

    private final Context mContext;
    private final UserTracker mUserTracker;
    private final CarServiceProvider mCarServiceProvider;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final ConfigurationController mConfigurationController;
    private final Provider<SystemUIQCViewController> mQCViewControllerProvider;
    @Nullable
    private final QCPanelReadOnlyIconsController mQCPanelReadOnlyIconsController;
    private final String mIdentifier;
    private final String mIconTag;
    private final @ColorInt int mIconHighlightedColor;
    private final @ColorInt int mIconNotHighlightedColor;
    private final int mYOffsetPixel;
    private final boolean mIsDisabledWhileDriving;
    private final ArrayList<SystemUIQCViewController> mQCViewControllers = new ArrayList<>();

    private PopupWindow mPanel;
    private @LayoutRes int mPanelLayoutRes;
    private @DimenRes int mPanelWidthRes;
    private ViewGroup mPanelContent;
    private OnQcViewsFoundListener mOnQcViewsFoundListener;
    private View mAnchorView;
    private ImageView mStatusIconView;
    private CarUxRestrictionsUtil mCarUxRestrictionsUtil;
    private CarActivityManager mCarActivityManager;
    private float mDimValue = -1.0f;
    private View.OnClickListener mOnClickListener;
    private boolean mIsPanelDestroyed;

    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
                @Override
                public void onLayoutDirectionChanged(boolean isLayoutRtl) {
                    recreatePanel();
                }
            };

    private final CarUxRestrictionsUtil.OnUxRestrictionsChangedListener
            mUxRestrictionsChangedListener =
            new CarUxRestrictionsUtil.OnUxRestrictionsChangedListener() {
                @Override
                public void onRestrictionsChanged(@NonNull CarUxRestrictions carUxRestrictions) {
                    if (mIsDisabledWhileDriving
                            && carUxRestrictions.isRequiresDistractionOptimization()
                            && isPanelShowing()) {
                        mPanel.dismiss();
                    }
                }
            };

    private final CarServiceProvider.CarServiceOnConnectedListener mCarServiceOnConnectedListener =
            car -> {
                mCarActivityManager = car.getCarManager(CarActivityManager.class);
            };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            boolean isIntentFromSelf =
                    intent.getIdentifier() != null && intent.getIdentifier().equals(mIdentifier);

            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action) && !isIntentFromSelf
                    && isPanelShowing()) {
                mPanel.dismiss();
            }
        }
    };

    private final UserTracker.Callback mUserTrackerCallback = new UserTracker.Callback() {
        @Override
        public void onUserChanged(int newUser, Context userContext) {
            mBroadcastDispatcher.unregisterReceiver(mBroadcastReceiver);
            mBroadcastDispatcher.registerReceiver(mBroadcastReceiver,
                    new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), /* executor= */ null,
                    mUserTracker.getUserHandle());
        }
    };

    private final ViewTreeObserver.OnGlobalFocusChangeListener mFocusChangeListener =
            (oldFocus, newFocus) -> {
                if (isPanelShowing() && oldFocus != null && newFocus instanceof FocusParkingView) {
                    // When nudging out of the panel, RotaryService will focus on the
                    // FocusParkingView to clear the focus highlight. When this occurs, dismiss the
                    // panel.
                    mPanel.dismiss();
                }
            };

    private final QCView.QCActionListener mQCActionListener = (item, action) -> {
        if (!isPanelShowing()) {
            return;
        }
        if (action instanceof PendingIntent) {
            if (((PendingIntent) action).isActivity()) {
                mPanel.dismiss();
            }
        } else if (action instanceof QCItem.ActionHandler) {
            if (((QCItem.ActionHandler) action).isActivity()) {
                mPanel.dismiss();
            }
        }
    };

    public StatusIconPanelController(
            Context context,
            UserTracker userTracker,
            CarServiceProvider carServiceProvider,
            BroadcastDispatcher broadcastDispatcher,
            ConfigurationController configurationController,
            Provider<SystemUIQCViewController> qcViewControllerProvider) {
        this(context, userTracker, carServiceProvider, broadcastDispatcher, configurationController,
                qcViewControllerProvider, /* isDisabledWhileDriving= */ false);
    }

    public StatusIconPanelController(
            Context context,
            UserTracker userTracker,
            CarServiceProvider carServiceProvider,
            BroadcastDispatcher broadcastDispatcher,
            ConfigurationController configurationController,
            Provider<SystemUIQCViewController> qcViewControllerProvider,
            boolean isDisabledWhileDriving) {
        this(context, userTracker, carServiceProvider, broadcastDispatcher, configurationController,
                qcViewControllerProvider, isDisabledWhileDriving,
                /* qcPanelReadOnlyIconsController= */ null);
    }

    public StatusIconPanelController(
            Context context,
            UserTracker userTracker,
            CarServiceProvider carServiceProvider,
            BroadcastDispatcher broadcastDispatcher,
            ConfigurationController configurationController,
            Provider<SystemUIQCViewController> qcViewControllerProvider,
            boolean isDisabledWhileDriving,
            QCPanelReadOnlyIconsController qcPanelReadOnlyIconsController) {
        mContext = context;
        mUserTracker = userTracker;
        mCarServiceProvider = carServiceProvider;
        mBroadcastDispatcher = broadcastDispatcher;
        mConfigurationController = configurationController;
        mQCViewControllerProvider = qcViewControllerProvider;
        mQCPanelReadOnlyIconsController = qcPanelReadOnlyIconsController;
        mIdentifier = Integer.toString(System.identityHashCode(this));

        mIconTag = mContext.getResources().getString(R.string.qc_icon_tag);
        mIconHighlightedColor = mContext.getColor(R.color.status_icon_highlighted_color);
        mIconNotHighlightedColor = mContext.getColor(R.color.status_icon_not_highlighted_color);

        int panelMarginTop = mContext.getResources().getDimensionPixelSize(
                R.dimen.car_status_icon_panel_margin_top);
        int topSystemBarHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.car_top_system_bar_height);
        // TODO(b/202563671): remove mYOffsetPixel when the PopupWindow API is updated.
        mYOffsetPixel = panelMarginTop - topSystemBarHeight;

        mBroadcastDispatcher.registerReceiver(mBroadcastReceiver,
                new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), /* executor= */ null,
                mUserTracker.getUserHandle());
        mUserTracker.addCallback(mUserTrackerCallback, mContext.getMainExecutor());
        mConfigurationController.addCallback(mConfigurationListener);

        mIsDisabledWhileDriving = isDisabledWhileDriving;
        if (mIsDisabledWhileDriving) {
            mCarUxRestrictionsUtil = CarUxRestrictionsUtil.getInstance(mContext);
            mCarUxRestrictionsUtil.register(mUxRestrictionsChangedListener);
        }
    }

    /**
     * @return default Y offset in pixels that cancels out the superfluous inset automatically
     *         applied to the panel
     */
    public int getDefaultYOffset() {
        return mYOffsetPixel;
    }

    public void setOnQcViewsFoundListener(OnQcViewsFoundListener onQcViewsFoundListener) {
        mOnQcViewsFoundListener = onQcViewsFoundListener;
    }

    /**
     * A listener that can be used to attach controllers quick control panels using
     * {@link SystemUIQCView#getLocalQCProvider()}
     */
    public interface OnQcViewsFoundListener {
        /**
         * This method is call up when {@link SystemUIQCView}s are found
         */
        void qcViewsFound(ArrayList<SystemUIQCView> qcViews);
    }

    /**
     * Attaches a panel to a root view that toggles the panel visibility when clicked.
     *
     * Variant of {@link #attachPanel(View, int, int, int, int, int, boolean)} with
     * xOffset={@code 0}, yOffset={@link #mYOffsetPixel} &
     * gravity={@link #DEFAULT_POPUP_WINDOW_ANCHOR_GRAVITY} &
     * showAsDropDown={@code true}.
     */
    public void attachPanel(View view, @LayoutRes int layoutRes, @DimenRes int widthRes) {
        attachPanel(view, layoutRes, widthRes, DEFAULT_POPUP_WINDOW_ANCHOR_GRAVITY);
    }

    /**
     * Attaches a panel to a root view that toggles the panel visibility when clicked.
     *
     * Variant of {@link #attachPanel(View, int, int, int, int, int, boolean)} with
     * xOffset={@code 0} & yOffset={@link #mYOffsetPixel} &
     * showAsDropDown={@code true}.
     */
    public void attachPanel(View view, @LayoutRes int layoutRes, @DimenRes int widthRes,
            int gravity) {
        attachPanel(view, layoutRes, widthRes, /* xOffset= */ 0, mYOffsetPixel,
                gravity);
    }

    /**
     * Attaches a panel to a root view that toggles the panel visibility when clicked.
     *
     * Variant of {@link #attachPanel(View, int, int, int, int, int, boolean)} with
     * gravity={@link #DEFAULT_POPUP_WINDOW_ANCHOR_GRAVITY} &
     * showAsDropDown={@code true}.
     */
    public void attachPanel(View view, @LayoutRes int layoutRes, @DimenRes int widthRes,
            int xOffset, int yOffset) {
        attachPanel(view, layoutRes, widthRes, xOffset, yOffset,
                DEFAULT_POPUP_WINDOW_ANCHOR_GRAVITY);
    }

    /**
     * Attaches a panel to a root view that toggles the panel visibility when clicked.
     *
     * Variant of {@link #attachPanel(View, int, int, int, int, int, boolean)} with
     * showAsDropDown={@code true}.
     */
    public void attachPanel(View view, @LayoutRes int layoutRes, @DimenRes int widthRes,
            int xOffset, int yOffset, int gravity) {
        attachPanel(view, layoutRes, widthRes, xOffset, yOffset, gravity,
                /* showAsDropDown= */ true);
    }

    /**
     * Attaches a panel to a root view that toggles the panel visibility when clicked.
     */
    public void attachPanel(View view, @LayoutRes int layoutRes, @DimenRes int widthRes,
            int xOffset, int yOffset, int gravity, boolean showAsDropDown) {
        if (mIsPanelDestroyed) {
            throw new IllegalStateException("Attempting to attach destroyed panel");
        }

        mCarServiceProvider.addListener(mCarServiceOnConnectedListener);

        if (mAnchorView == null) {
            mAnchorView = view;
        }
        mPanelLayoutRes = layoutRes;
        mPanelWidthRes = widthRes;
        // Pre-create panel to improve perceived UI performance
        createPanel();

        mOnClickListener = v -> {
            if (mIsDisabledWhileDriving && mCarUxRestrictionsUtil.getCurrentRestrictions()
                    .isRequiresDistractionOptimization()) {
                dismissAllSystemDialogs();
                Toast.makeText(mContext, R.string.car_ui_restricted_while_driving,
                        Toast.LENGTH_LONG).show();
                return;
            }

            if (mPanel == null && !createPanel()) {
                return;
            }

            if (mPanel.isShowing()) {
                mPanel.dismiss();
                return;
            }

            // Dismiss all currently open system dialogs before opening this panel.
            dismissAllSystemDialogs();

            mQCViewControllers.forEach(controller -> controller.listen(true));

            registerFocusListener(true);

            if (CarSystemUIUserUtil.isMUMDSystemUI()
                    && mPanelLayoutRes == R.layout.qc_profile_switcher) {
                // TODO(b/269490856): consider removal of UserPicker carve-outs
                if (mCarActivityManager != null) {
                    mCarActivityManager.startUserPickerOnDisplay(mContext.getDisplayId());
                }
            } else {
                if (showAsDropDown) {
                    // TODO(b/202563671): remove yOffsetPixel when the PopupWindow API is updated.
                    mPanel.showAsDropDown(mAnchorView, xOffset, yOffset, gravity);
                } else {
                    int verticalGravity = gravity & Gravity.VERTICAL_GRAVITY_MASK;
                    int animationStyle = verticalGravity == Gravity.BOTTOM
                            ? com.android.internal.R.style.Animation_DropDownUp
                            : com.android.internal.R.style.Animation_DropDownDown;
                    mPanel.setAnimationStyle(animationStyle);
                    mPanel.showAtLocation(mAnchorView, gravity, xOffset, yOffset);
                }
                mAnchorView.setSelected(true);
                highlightStatusIcon(true);
                setAnimatedStatusIconHighlightedStatus(true);
                dimBehind(mPanel);
            }
        };

        mAnchorView.setOnClickListener(mOnClickListener);
    }

    /**
     * Cleanup listeners and reset panel. This controller instance should not be used after this
     * method is called.
     */
    public void destroyPanel() {
        reset();
        if (mCarUxRestrictionsUtil != null) {
            mCarUxRestrictionsUtil.unregister(mUxRestrictionsChangedListener);
        }
        mCarServiceProvider.removeListener(mCarServiceOnConnectedListener);
        mConfigurationController.removeCallback(mConfigurationListener);
        mUserTracker.removeCallback(mUserTrackerCallback);
        mBroadcastDispatcher.unregisterReceiver(mBroadcastReceiver);
        mPanelLayoutRes = 0;
        mIsPanelDestroyed = true;
    }

    @VisibleForTesting
    protected PopupWindow getPanel() {
        return mPanel;
    }

    @VisibleForTesting
    protected BroadcastReceiver getBroadcastReceiver() {
        return mBroadcastReceiver;
    }

    @VisibleForTesting
    protected String getIdentifier() {
        return mIdentifier;
    }

    @VisibleForTesting
    @ColorInt
    protected int getIconHighlightedColor() {
        return mIconHighlightedColor;
    }

    @VisibleForTesting
    @ColorInt
    protected int getIconNotHighlightedColor() {
        return mIconNotHighlightedColor;
    }

    @VisibleForTesting
    protected View.OnClickListener getOnClickListener() {
        return mOnClickListener;
    }

    @VisibleForTesting
    protected ConfigurationController.ConfigurationListener getConfigurationListener() {
        return mConfigurationListener;
    }

    @VisibleForTesting
    protected UserTracker.Callback getUserTrackerCallback() {
        return mUserTrackerCallback;
    }

    @VisibleForTesting
    protected ViewTreeObserver.OnGlobalFocusChangeListener getFocusChangeListener() {
        return mFocusChangeListener;
    }

    @VisibleForTesting
    protected QCView.QCActionListener getQCActionListener() {
        return mQCActionListener;
    }

    /**
     * Create the PopupWindow panel and assign to {@link mPanel}.
     * @return true if the panel was created, false otherwise
     */
    boolean createPanel() {
        if (mPanelWidthRes == 0 || mPanelLayoutRes == 0) {
            return false;
        }

        int panelWidth = mContext.getResources().getDimensionPixelSize(mPanelWidthRes);

        mPanelContent = (ViewGroup) LayoutInflater.from(mContext).inflate(mPanelLayoutRes,
                /* root= */ null);
        mPanelContent.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        findQcHeaderViews(mPanelContent);
        findQcViews(mPanelContent);
        findQcFooterViews(mPanelContent);
        mPanel = new PopupWindow(mPanelContent, panelWidth, WRAP_CONTENT);
        mPanel.setBackgroundDrawable(
                mContext.getResources().getDrawable(R.drawable.status_icon_panel_bg,
                        mContext.getTheme()));
        mPanel.setWindowLayoutType(TYPE_SYSTEM_DIALOG);
        mPanel.setFocusable(true);
        mPanel.setOutsideTouchable(false);
        mPanel.setOnDismissListener(() -> {
            setAnimatedStatusIconHighlightedStatus(false);
            mAnchorView.setSelected(false);
            highlightStatusIcon(false);
            registerFocusListener(false);
            mQCViewControllers.forEach(controller -> controller.listen(false));
        });

        return true;
    }

    private void dimBehind(PopupWindow popupWindow) {
        View container = popupWindow.getContentView().getRootView();
        WindowManager wm = mContext.getSystemService(WindowManager.class);

        if (wm == null) return;

        if (mDimValue < 0) {
            mDimValue = mContext.getResources().getFloat(R.dimen.car_status_icon_panel_dim);
        }

        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) container.getLayoutParams();
        lp.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        lp.dimAmount = mDimValue;
        wm.updateViewLayout(container, lp);
    }

    private void dismissAllSystemDialogs() {
        Intent intent = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        intent.setIdentifier(mIdentifier);
        mContext.sendBroadcastAsUser(intent, mUserTracker.getUserHandle());
    }

    private void registerFocusListener(boolean register) {
        if (mPanelContent == null) {
            return;
        }
        if (register) {
            mPanelContent.getViewTreeObserver().addOnGlobalFocusChangeListener(
                    mFocusChangeListener);
        } else {
            mPanelContent.getViewTreeObserver().removeOnGlobalFocusChangeListener(
                    mFocusChangeListener);
        }
    }

    private void reset() {
        if (mPanel == null) return;

        mPanel.dismiss();
        mPanel = null;
        mPanelContent = null;
        mQCViewControllers.forEach(SystemUIQCViewController::destroy);
        mQCViewControllers.clear();
    }

    private void recreatePanel() {
        reset();
        createPanel();
    }

    private void findQcHeaderViews(ViewGroup rootView) {
        for (int i = 0; i < rootView.getChildCount(); i++) {
            View v = rootView.getChildAt(i);
            if (v instanceof QCHeaderReadOnlyIconsContainer) {
                if (mQCPanelReadOnlyIconsController != null) {
                    mQCPanelReadOnlyIconsController.addIconViews(
                            (QCHeaderReadOnlyIconsContainer) v, /* shouldAttachPanel= */ false);
                }
            } else if (v instanceof ViewGroup) {
                this.findQcHeaderViews((ViewGroup) v);
            }
        }
    }

    private void findQcViews(ViewGroup rootView) {
        for (int i = 0; i < rootView.getChildCount(); i++) {
            View v = rootView.getChildAt(i);
            if (v instanceof SystemUIQCView) {
                SystemUIQCView qcv = (SystemUIQCView) v;
                SystemUIQCViewController controller = mQCViewControllerProvider.get();
                controller.attachView(qcv);
                mQCViewControllers.add(controller);
                qcv.setActionListener(mQCActionListener);
            } else if (v instanceof ViewGroup) {
                this.findQcViews((ViewGroup) v);
            }
        }
    }

    private void findQcFooterViews(ViewGroup rootView) {
        for (int i = 0; i < rootView.getChildCount(); i++) {
            View v = rootView.getChildAt(i);
            if (v instanceof QCFooterButton) {
                ((QCFooterButton) v).setUserTracker(mUserTracker);
            } else if (v instanceof QCFooterButtonView) {
                ((QCFooterButtonView) v).setUserTracker(mUserTracker);
                ((QCFooterButtonView) v).setBroadcastDispatcher(mBroadcastDispatcher);
            } else if (v instanceof ViewGroup) {
                this.findQcFooterViews((ViewGroup) v);
            }
        }
    }

    private void setAnimatedStatusIconHighlightedStatus(boolean isHighlighted) {
        if (mAnchorView instanceof AnimatedStatusIcon) {
            ((AnimatedStatusIcon) mAnchorView).setIconHighlighted(isHighlighted);
        }
    }

    private void highlightStatusIcon(boolean isHighlighted) {
        if (mStatusIconView == null) {
            mStatusIconView = mAnchorView.findViewWithTag(mIconTag);
        }

        if (mStatusIconView != null) {
            mStatusIconView.setColorFilter(
                    isHighlighted ? mIconHighlightedColor : mIconNotHighlightedColor);
        }
    }

    private boolean isPanelShowing() {
        return mPanel != null && mPanel.isShowing();
    }
}
