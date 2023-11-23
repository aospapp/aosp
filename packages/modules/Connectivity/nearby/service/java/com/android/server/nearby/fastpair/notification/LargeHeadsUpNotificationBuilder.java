/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.nearby.fastpair.notification;

import static com.android.server.nearby.fastpair.Constant.TAG;

import android.annotation.LayoutRes;
import android.annotation.Nullable;
import android.app.Notification;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RemoteViews;

import com.android.nearby.halfsheet.R;

/** Wrapper class for creating larger heads up notifications. */
public class LargeHeadsUpNotificationBuilder extends FastPairNotificationBuilder {
    private final boolean mLargeIcon;
    private final RemoteViews mNotification;
    private final RemoteViews mNotificationCollapsed;

    @Nullable private Runnable mLargeIconAction;
    @Nullable private Runnable mProgressAction;

    public LargeHeadsUpNotificationBuilder(Context context, String channelId, boolean largeIcon) {
        super(context, channelId);

        this.mLargeIcon = largeIcon;
        this.mNotification = getRemoteViews(R.layout.fast_pair_heads_up_notification);
        this.mNotificationCollapsed = getRemoteViews(R.layout.fast_pair_heads_up_notification);

        if (mNotification != null) {
            mNotificationCollapsed.setViewVisibility(android.R.id.secondaryProgress, View.GONE);
        }
    }

    /**
     * Create a new RemoteViews object that will display the views contained
     * fast_pair_heads_up_notification layout.
     */
    @Nullable
    public RemoteViews getRemoteViews(@LayoutRes int layoutId) {
        return new RemoteViews(mPackageName, layoutId);
    }

    @Override
    public Notification.Builder setContentTitle(@Nullable CharSequence title) {
        if (mNotification != null) {
            mNotification.setTextViewText(android.R.id.title, title);
        }
        if (mNotificationCollapsed != null) {
            mNotificationCollapsed.setTextViewText(android.R.id.title, title);
            // Collapsed mode does not need additional lines.
            mNotificationCollapsed.setInt(android.R.id.title, "setMaxLines", 1);
        }
        return super.setContentTitle(title);
    }

    @Override
    public Notification.Builder setContentText(@Nullable CharSequence text) {
        if (mNotification != null) {
            mNotification.setTextViewText(android.R.id.text1, text);
        }
        if (mNotificationCollapsed != null) {
            mNotificationCollapsed.setTextViewText(android.R.id.text1, text);
            // Collapsed mode does not need additional lines.
            mNotificationCollapsed.setInt(android.R.id.text1, "setMaxLines", 1);
        }

        return super.setContentText(text);
    }

    @Override
    public Notification.Builder setSubText(@Nullable CharSequence subText) {
        if (mNotification != null) {
            mNotification.setTextViewText(android.R.id.text2, subText);
        }
        if (mNotificationCollapsed != null) {
            mNotificationCollapsed.setTextViewText(android.R.id.text2, subText);
        }
        return super.setSubText(subText);
    }

    @Override
    public Notification.Builder setLargeIcon(@Nullable Bitmap bitmap) {
        RemoteViews image =
                getRemoteViews(
                        useLargeIcon()
                                ? R.layout.fast_pair_heads_up_notification_large_image
                                : R.layout.fast_pair_heads_up_notification_small_image);
        if (image == null) {
            return super.setLargeIcon(bitmap);
        }
        image.setImageViewBitmap(android.R.id.icon, bitmap);

        if (mNotification != null) {
            mNotification.removeAllViews(android.R.id.icon1);
            mNotification.addView(android.R.id.icon1, image);
        }
        if (mNotificationCollapsed != null) {
            mNotificationCollapsed.removeAllViews(android.R.id.icon1);
            mNotificationCollapsed.addView(android.R.id.icon1, image);
            // In Android S, if super.setLargeIcon() is called, there will be an extra icon on
            // top-right.
            // But we still need to store this setting for the default UI when something wrong.
            mLargeIconAction = () -> super.setLargeIcon(bitmap);
            return this;
        }
        return super.setLargeIcon(bitmap);
    }

    @Override
    public Notification.Builder setProgress(int max, int progress, boolean indeterminate) {
        if (mNotification != null) {
            mNotification.setViewVisibility(android.R.id.secondaryProgress, View.VISIBLE);
            mNotification.setProgressBar(android.R.id.progress, max, progress, indeterminate);
        }
        if (mNotificationCollapsed != null) {
            mNotificationCollapsed.setViewVisibility(android.R.id.secondaryProgress, View.VISIBLE);
            mNotificationCollapsed.setProgressBar(android.R.id.progress, max, progress,
                    indeterminate);
            // In Android S, if super.setProgress() is called, there will be an extra progress bar.
            // But we still need to store this setting for the default UI when something wrong.
            mProgressAction = () -> super.setProgress(max, progress, indeterminate);
            return this;
        }
        return super.setProgress(max, progress, indeterminate);
    }

    @Override
    public Notification build() {
        if (mNotification != null) {
            boolean buildSuccess = false;
            try {
                // Attempt to apply the remote views. This verifies that all of the resources are
                // correctly available.
                // If it fails, fall back to a non-custom notification.
                mNotification.apply(mContext, new LinearLayout(mContext));
                if (mNotificationCollapsed != null) {
                    mNotificationCollapsed.apply(mContext, new LinearLayout(mContext));
                }
                buildSuccess = true;
            } catch (Resources.NotFoundException e) {
                Log.w(TAG, "Failed to build notification, not setting custom view.", e);
            }

            if (buildSuccess) {
                if (mNotificationCollapsed != null) {
                    setStyle(new Notification.DecoratedCustomViewStyle());
                    setCustomContentView(mNotificationCollapsed);
                    setCustomBigContentView(mNotification);
                } else {
                    setCustomHeadsUpContentView(mNotification);
                }
            } else {
                if (mLargeIconAction != null) {
                    mLargeIconAction.run();
                }
                if (mProgressAction != null) {
                    mProgressAction.run();
                }
            }
        }
        return super.build();
    }

    private boolean useLargeIcon() {
        return mLargeIcon;
    }
}
