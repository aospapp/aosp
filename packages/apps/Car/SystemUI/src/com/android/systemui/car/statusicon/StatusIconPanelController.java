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

import static android.content.Intent.ACTION_USER_FOREGROUND;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;
import static android.widget.ListPopupWindow.WRAP_CONTENT;

import android.annotation.ColorInt;
import android.annotation.DimenRes;
import android.annotation.LayoutRes;
import android.car.drivingstate.CarUxRestrictions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserHandle;
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
import androidx.annotation.VisibleForTesting;

import com.android.car.ui.FocusParkingView;
import com.android.car.ui.utils.CarUxRestrictionsUtil;
import com.android.car.ui.utils.ViewUtils;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.car.qc.SystemUIQCView;
import com.android.systemui.statusbar.policy.ConfigurationController;

import java.util.ArrayList;

/**
 * A controller for a panel view associated with a status icon.
 */
public class StatusIconPanelController {
    private static final int DEFAULT_POPUP_WINDOW_ANCHOR_GRAVITY = Gravity.TOP | Gravity.START;
    private static final IntentFilter INTENT_FILTER_USER_CHANGED = new IntentFilter(
            ACTION_USER_FOREGROUND);

    private final Context mContext;
    private final String mIdentifier;
    private final String mIconTag;
    private final @ColorInt int mIconHighlightedColor;
    private final @ColorInt int mIconNotHighlightedColor;
    private final int mYOffsetPixel;
    private final boolean mIsDisabledWhileDriving;
    private final ArrayList<SystemUIQCView> mQCViews = new ArrayList<>();

    private PopupWindow mPanel;
    private ViewGroup mPanelContent;
    private OnQcViewsFoundListener mOnQcViewsFoundListener;
    private View mAnchorView;
    private ImageView mStatusIconView;
    private CarUxRestrictionsUtil mCarUxRestrictionsUtil;
    private float mDimValue = -1.0f;

    private final BroadcastReceiver mUserChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reset();
        }
    };

    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
                @Override
                public void onLayoutDirectionChanged(boolean isLayoutRtl) {
                    reset();
                }
            };

    private final CarUxRestrictionsUtil.OnUxRestrictionsChangedListener
            mUxRestrictionsChangedListener =
            new CarUxRestrictionsUtil.OnUxRestrictionsChangedListener() {
                @Override
                public void onRestrictionsChanged(@NonNull CarUxRestrictions carUxRestrictions) {
                    if (mIsDisabledWhileDriving
                            && carUxRestrictions.isRequiresDistractionOptimization()
                            && mPanel != null) {
                        mPanel.dismiss();
                    }
                }
            };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            boolean isIntentFromSelf =
                    intent.getIdentifier() != null && intent.getIdentifier().equals(mIdentifier);

            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action) && !isIntentFromSelf
                    && mPanel != null && mPanel.isShowing()) {
                mPanel.dismiss();
            }
        }
    };

    private final ViewTreeObserver.OnGlobalFocusChangeListener mFocusChangeListener =
            (oldFocus, newFocus) -> {
                if (mPanel != null && oldFocus != null && newFocus instanceof FocusParkingView) {
                    // When nudging out of the panel, RotaryService will focus on the
                    // FocusParkingView to clear the focus highlight. When this occurs, dismiss the
                    // panel.
                    mPanel.dismiss();
                }
            };

    public StatusIconPanelController(
            Context context,
            CarServiceProvider carServiceProvider,
            BroadcastDispatcher broadcastDispatcher,
            ConfigurationController configurationController) {
        this(context, carServiceProvider, broadcastDispatcher, configurationController,
                /* isDisabledWhileDriving= */ false);
    }

    public StatusIconPanelController(
            Context context,
            CarServiceProvider carServiceProvider,
            BroadcastDispatcher broadcastDispatcher,
            ConfigurationController configurationController,
            boolean isDisabledWhileDriving) {
        mContext = context;
        mIdentifier = Integer.toString(System.identityHashCode(this));

        mIconTag = mContext.getResources().getString(R.string.qc_icon_tag);
        mIconHighlightedColor = mContext.getColor(R.color.status_icon_highlighted_color);
        mIconNotHighlightedColor = mContext.getColor(R.color.status_icon_not_highlighted_color);

        int panelMarginTop = mContext.getResources().getDimensionPixelSize(
                R.dimen.car_status_icon_panel_margin_top);
        int topSystemBarHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.car_top_system_bar_height);
        // Cancel out the superfluous inset automatically applied to the panel.
        mYOffsetPixel = panelMarginTop - topSystemBarHeight;

        broadcastDispatcher.registerReceiver(mBroadcastReceiver,
                new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), /* executor= */ null,
                UserHandle.ALL);
        configurationController.addCallback(mConfigurationListener);

        context.registerReceiverForAllUsers(mUserChangeReceiver, INTENT_FILTER_USER_CHANGED,
                /* broadcastPermission= */ null, /* scheduler= */ null);

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

    /**
     * @return list of {@link SystemUIQCView} in this controller
     */
    public ArrayList<SystemUIQCView> getQCViews() {
        return mQCViews;
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
     * Variant of {@link #attachPanel(View, int, int, int, int, int)} with
     * xOffset={@code 0}, yOffset={@link #mYOffsetPixel} &
     * gravity={@link #DEFAULT_POPUP_WINDOW_ANCHOR_GRAVITY}.
     */
    public void attachPanel(View view, @LayoutRes int layoutRes, @DimenRes int widthRes) {
        attachPanel(view, layoutRes, widthRes, DEFAULT_POPUP_WINDOW_ANCHOR_GRAVITY);
    }

    /**
     * Attaches a panel to a root view that toggles the panel visibility when clicked.
     *
     * Variant of {@link #attachPanel(View, int, int, int, int, int)} with
     * xOffset={@code 0} & yOffset={@link #mYOffsetPixel}.
     */
    public void attachPanel(View view, @LayoutRes int layoutRes, @DimenRes int widthRes,
            int gravity) {
        attachPanel(view, layoutRes, widthRes, /* xOffset= */ 0, mYOffsetPixel,
                gravity);
    }

    /**
     * Attaches a panel to a root view that toggles the panel visibility when clicked.
     *
     * Variant of {@link #attachPanel(View, int, int, int, int, int)} with
     * gravity={@link #DEFAULT_POPUP_WINDOW_ANCHOR_GRAVITY}.
     */
    public void attachPanel(View view, @LayoutRes int layoutRes, @DimenRes int widthRes,
            int xOffset, int yOffset) {
        attachPanel(view, layoutRes, widthRes, xOffset, yOffset,
                DEFAULT_POPUP_WINDOW_ANCHOR_GRAVITY);
    }

    /**
     * Attaches a panel to a root view that toggles the panel visibility when clicked.
     */
    public void attachPanel(View view, @LayoutRes int layoutRes, @DimenRes int widthRes,
            int xOffset, int yOffset, int gravity) {
        if (mAnchorView == null) {
            mAnchorView = view;
        }

        mAnchorView.setOnClickListener(v -> {
            if (mIsDisabledWhileDriving && mCarUxRestrictionsUtil.getCurrentRestrictions()
                    .isRequiresDistractionOptimization()) {
                dismissAllSystemDialogs();
                Toast.makeText(mContext, R.string.car_ui_restricted_while_driving,
                        Toast.LENGTH_LONG).show();
                return;
            }

            if (mPanel == null) {
                mPanel = createPanel(layoutRes, widthRes);
            }

            if (mPanel.isShowing()) {
                mPanel.dismiss();
                return;
            }

            // Dismiss all currently open system dialogs before opening this panel.
            dismissAllSystemDialogs();

            mQCViews.forEach(qcView -> qcView.listen(true));

            // Clear the focus highlight in this window since a dialog window is about to show.
            // TODO(b/201700195): remove this workaround once the window focus issue is fixed.
            if (view.isFocused()) {
                ViewUtils.hideFocus(view.getRootView());
            }
            registerFocusListener(true);

            // TODO(b/202563671): remove yOffsetPixel when the PopupWindow API is updated.
            mPanel.showAsDropDown(mAnchorView, xOffset, yOffset, gravity);
            mAnchorView.setSelected(true);
            highlightStatusIcon(true);
            setAnimatedStatusIconHighlightedStatus(true);

            dimBehind(mPanel);
        });
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

    private PopupWindow createPanel(@LayoutRes int layoutRes, @DimenRes int widthRes) {
        int panelWidth = mContext.getResources().getDimensionPixelSize(widthRes);

        mPanelContent = (ViewGroup) LayoutInflater.from(mContext).inflate(layoutRes, /* root= */
                null);
        mPanelContent.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        findQcViews(mPanelContent);
        if (mOnQcViewsFoundListener != null) {
            mOnQcViewsFoundListener.qcViewsFound(mQCViews);
        }
        PopupWindow panel = new PopupWindow(mPanelContent, panelWidth, WRAP_CONTENT);
        panel.setBackgroundDrawable(
                mContext.getResources().getDrawable(R.drawable.status_icon_panel_bg,
                        mContext.getTheme()));
        panel.setWindowLayoutType(TYPE_SYSTEM_DIALOG);
        panel.setFocusable(true);
        panel.setOutsideTouchable(false);
        panel.setOnDismissListener(() -> {
            setAnimatedStatusIconHighlightedStatus(false);
            mAnchorView.setSelected(false);
            highlightStatusIcon(false);
            registerFocusListener(false);
            mQCViews.forEach(qcView -> qcView.listen(false));
        });
        addFocusParkingView();

        return panel;
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
        mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
    }

    /**
     * Add a FocusParkingView to the panel content to prevent rotary controller rotation wrapping
     * around in the panel - this only should be called once per panel.
     */
    private void addFocusParkingView() {
        if (mPanelContent != null) {
            FocusParkingView fpv = new FocusParkingView(mContext);
            mPanelContent.addView(fpv);
        }
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
        mOnQcViewsFoundListener = null;
        mQCViews.forEach(v -> v.destroy());
        mQCViews.clear();
    }

    private void findQcViews(ViewGroup rootView) {
        for (int i = 0; i < rootView.getChildCount(); i++) {
            View v = rootView.getChildAt(i);
            if (v instanceof SystemUIQCView) {
                mQCViews.add((SystemUIQCView) v);
            } else if (v instanceof ViewGroup) {
                this.findQcViews((ViewGroup) v);
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
}
