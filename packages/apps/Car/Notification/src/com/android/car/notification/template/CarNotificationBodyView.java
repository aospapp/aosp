/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.car.notification.template;

import android.annotation.ColorInt;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.DateTimeView;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.VisibleForTesting;

import com.android.car.notification.NotificationUtils;
import com.android.car.notification.R;

/**
 * Common notification body that consists of a title line, a content text line, and an image icon on
 * the end.
 *
 * <p> For example, for a messaging notification, the title is the sender's name,
 * the content is the message, and the image icon is the sender's avatar.
 */
public class CarNotificationBodyView extends RelativeLayout {
    private static final int DEFAULT_MAX_LINES = 3;
    @ColorInt
    private final int mDefaultPrimaryTextColor;
    @ColorInt
    private final int mDefaultSecondaryTextColor;
    private final boolean mUseLauncherIcon;

    private boolean mIsHeadsUp;
    private boolean mShowBigIcon;
    private int mMaxLines;
    @Nullable
    private TextView mTitleView;
    @Nullable
    private TextView mContentView;
    @Nullable
    private ImageView mLargeIconView;
    @Nullable
    private TextView mCountView;
    @Nullable
    private DateTimeView mTimeView;
    @Nullable
    private ImageView mTitleIconView;

    public CarNotificationBodyView(Context context) {
        super(context);
    }

    public CarNotificationBodyView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    public CarNotificationBodyView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    public CarNotificationBodyView(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(attrs);
    }

    {
        mDefaultPrimaryTextColor =
                NotificationUtils.getAttrColor(getContext(), android.R.attr.textColorPrimary);
        mDefaultSecondaryTextColor =
                NotificationUtils.getAttrColor(getContext(), android.R.attr.textColorSecondary);
        mUseLauncherIcon = getResources().getBoolean(R.bool.config_useLauncherIcon);
    }

    private void init(AttributeSet attrs) {
        TypedArray attributes =
                getContext().obtainStyledAttributes(attrs, R.styleable.CarNotificationBodyView);
        mShowBigIcon = attributes.getBoolean(R.styleable.CarNotificationBodyView_showBigIcon,
                /* defValue= */ false);
        mMaxLines = attributes.getInteger(R.styleable.CarNotificationBodyView_maxLines,
                /* defValue= */ DEFAULT_MAX_LINES);
        mIsHeadsUp = attributes.getBoolean(R.styleable.CarNotificationHeaderView_isHeadsUp,
                /* defValue= */ false);
        inflate(getContext(), mIsHeadsUp ? R.layout.car_headsup_notification_body_view
                : R.layout.car_notification_body_view, /* root= */ this);
        attributes.recycle();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mTitleView = findViewById(R.id.notification_body_title);
        mTitleIconView = findViewById(R.id.notification_body_title_icon);
        mContentView = findViewById(R.id.notification_body_content);
        mLargeIconView = findViewById(R.id.notification_body_icon);
        mCountView = findViewById(R.id.message_count);
        mTimeView = findViewById(R.id.time);
        if (mTimeView != null) {
            mTimeView.setShowRelativeTime(true);
        }
    }

    /**
     * Binds the notification body.
     *
     * @param title     the primary text
     * @param content   the secondary text, if this is null then content view will be hidden
     * @param launcherIcon  the launcher icon drawable for notification's package.
     *        If this and largeIcon are null then large icon view will be hidden.
     * @param largeIcon the large icon, usually used for avatars.
     *        If this and launcherIcon are null then large icon view will be hidden.
     * @param countText text signifying the number of messages inside this notification
     * @param when      wall clock time in milliseconds for the notification
     */
    public void bind(CharSequence title, @Nullable CharSequence content,
            @Nullable Drawable launcherIcon, @Nullable Icon largeIcon, @Nullable Drawable titleIcon,
            @Nullable CharSequence countText, @Nullable Long when) {
        setVisibility(View.VISIBLE);

        if (mLargeIconView != null && largeIcon != null && !mUseLauncherIcon && mShowBigIcon) {
            largeIcon.loadDrawableAsync(getContext(), drawable -> {
                mLargeIconView.setVisibility(View.VISIBLE);
                mLargeIconView.setImageDrawable(drawable);
            }, Handler.createAsync(Looper.myLooper()));
        } else if (mLargeIconView != null && launcherIcon != null && mUseLauncherIcon) {
            mLargeIconView.setVisibility(View.VISIBLE);
            mLargeIconView.setImageDrawable(launcherIcon);
        } else if (mLargeIconView != null) {
            mLargeIconView.setVisibility(View.GONE);
        }

        if (mTitleView != null) {
            mTitleView.setVisibility(View.VISIBLE);
            mTitleView.setText(title);
        }

        if (mTitleIconView != null && titleIcon != null) {
            mTitleIconView.setVisibility(View.VISIBLE);
            mTitleIconView.setImageDrawable(titleIcon);
        }

        if (mContentView != null && !TextUtils.isEmpty(content)) {
            mContentView.setVisibility(View.VISIBLE);
            mContentView.setMaxLines(mMaxLines);
            mContentView.setText(content);
        } else if (mContentView != null) {
            mContentView.setVisibility(View.GONE);
        }

        if (mTimeView != null && when != null && !mIsHeadsUp) {
            mTimeView.setVisibility(View.VISIBLE);
            mTimeView.setTime(when);
        } else if (mTimeView != null) {
            mTimeView.setVisibility(View.GONE);
        }

        if (mCountView != null && countText != null) {
            mCountView.setVisibility(View.VISIBLE);
            mCountView.setText(countText);
        } else if (mCountView != null) {
            mCountView.setVisibility(View.GONE);
        }
    }

    /**
     * Sets the secondary text color.
     */
    public void setSecondaryTextColor(@ColorInt int color) {
        if (mContentView != null) {
            mContentView.setTextColor(color);
        }
    }

    /**
     * Sets max lines for the content view.
     */
    public void setContentMaxLines(int maxLines) {
        if (mContentView != null) {
            mContentView.setMaxLines(maxLines);
        }
    }

    /**
     * Sets the primary text color.
     */
    public void setPrimaryTextColor(@ColorInt int color) {
        if (mTitleView != null) {
            mTitleView.setTextColor(color);
        }
    }

    /**
     * Sets the text color for the count field.
     */
    public void setCountTextColor(@ColorInt int color) {
        if (mCountView != null) {
            mCountView.setTextColor(color);
        }
    }

    /**
     * Sets the {@link OnClickListener} for the count field.
     */
    public void setCountOnClickListener(@Nullable OnClickListener listener) {
        if (mCountView != null) {
            mCountView.setOnClickListener(listener);
        }
    }

    /**
     * Sets the text color for the time field.
     */
    public void setTimeTextColor(@ColorInt int color) {
        if (mTimeView != null) {
            mTimeView.setTextColor(color);
        }
    }

    /**
     * Resets the notification actions empty for recycling.
     */
    public void reset() {
        setVisibility(View.GONE);
        if (mTitleView != null) {
            mTitleView.setVisibility(View.GONE);
        }
        if (mTitleIconView != null) {
            mTitleIconView.setVisibility(View.GONE);
        }
        if (mContentView != null) {
            setContentMaxLines(mMaxLines);
            mContentView.setVisibility(View.GONE);
        }
        if (mLargeIconView != null) {
            mLargeIconView.setVisibility(View.GONE);
        }
        setPrimaryTextColor(mDefaultPrimaryTextColor);
        setSecondaryTextColor(mDefaultSecondaryTextColor);
        if (mTimeView != null) {
            mTimeView.setVisibility(View.GONE);
            mTimeView.setTime(0);
            setTimeTextColor(mDefaultPrimaryTextColor);
        }

        if (mCountView != null) {
            mCountView.setVisibility(View.GONE);
            mCountView.setText(null);
            mCountView.setTextColor(mDefaultPrimaryTextColor);
        }
    }

    @VisibleForTesting
    TextView getTitleView() {
        return mTitleView;
    }

    @VisibleForTesting
    TextView getContentView() {
        return mContentView;
    }

    @VisibleForTesting
    TextView getCountView() {
        return mCountView;
    }

    @VisibleForTesting
    DateTimeView getTimeView() {
        return mTimeView;
    }
}
