/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.car.qc;

import static android.car.Car.CarServiceLifecycleListener;

import static com.android.systemui.car.users.CarSystemUIUserUtil.getCurrentUserHandle;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.car.ui.utils.CarUxRestrictionsUtil;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.settings.UserTracker;

import java.net.URISyntaxException;

/**
 * Footer button layout which contains one or multiple views for quick control panels.
 *
 * Allows for an intent action to be specified via the
 * {@link R.styleable.QCFooterButtonView_intent} attribute and for enabled state to be set
 * according to driving mode via the {@link R.styleable.QCFooterButtonView_disableWhileDriving}
 * attribute.
 */
public class QCFooterButtonView extends ConstraintLayout {
    private static final String TAG = QCFooterButtonView.class.getSimpleName();

    private boolean mDisableWhileDriving;
    private final CarUxRestrictionsUtil.OnUxRestrictionsChangedListener mListener =
            carUxRestrictions -> setEnabled(!carUxRestrictions.isRequiresDistractionOptimization());
    @Nullable
    protected UserTracker mUserTracker;

    @Nullable
    protected BroadcastDispatcher mBroadcastDispatcher;

    @Nullable
    View.OnClickListener mOnClickListener;

    @Nullable
    CarServiceLifecycleListener mCarServiceLifecycleListener;

    public QCFooterButtonView(Context context) {
        this(context, null);
    }

    public QCFooterButtonView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public QCFooterButtonView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public QCFooterButtonView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        if (attrs == null) {
            return;
        }

        TypedArray typedArray = context.obtainStyledAttributes(attrs,
                R.styleable.QCFooterButtonView);
        String intentString = typedArray.getString(R.styleable.QCFooterButtonView_intent);
        if (intentString != null) {
            Intent intent;
            try {
                intent = Intent.parseUri(intentString, Intent.URI_INTENT_SCHEME);
            } catch (URISyntaxException e) {
                throw new RuntimeException("Failed to attach intent", e);
            }
            Intent finalIntent = intent;

            mOnClickListener = v -> {
                mContext.sendBroadcastAsUser(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
                        getCurrentUserHandle(mContext, mUserTracker));
                try {
                    ActivityOptions options = ActivityOptions.makeBasic();
                    options.setLaunchDisplayId(mContext.getDisplayId());
                    mContext.startActivityAsUser(finalIntent, options.toBundle(),
                            getCurrentUserHandle(mContext, mUserTracker));
                } catch (Exception e) {
                    Log.e(TAG, "Failed to launch intent", e);
                }
            };
            setOnClickListener(mOnClickListener);
        }

        mDisableWhileDriving = typedArray.getBoolean(
                R.styleable.QCFooterButtonView_disableWhileDriving, /* defValue= */ false);

        typedArray.recycle();
    }

    public void setUserTracker(UserTracker userTracker) {
        mUserTracker = userTracker;
    }

    public void setBroadcastDispatcher(BroadcastDispatcher broadcastDispatcher) {
        mBroadcastDispatcher = broadcastDispatcher;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mDisableWhileDriving) {
            CarUxRestrictionsUtil.getInstance(mContext).register(mListener);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mDisableWhileDriving) {
            CarUxRestrictionsUtil.getInstance(mContext).unregister(mListener);
        }
    }

    @Nullable
    @VisibleForTesting
    protected View.OnClickListener getOnClickListener() {
        return mOnClickListener;
    }

    @Nullable
    @VisibleForTesting
    protected CarServiceLifecycleListener getCarServiceLifecycleListener() {
        return mCarServiceLifecycleListener;
    }
}
