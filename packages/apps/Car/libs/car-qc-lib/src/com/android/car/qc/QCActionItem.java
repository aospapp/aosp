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

package com.android.car.qc;

import android.app.PendingIntent;
import android.graphics.drawable.Icon;
import android.os.Parcel;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Quick Control Action that are includes as either start or end actions in {@link QCRow}
 */
public class QCActionItem extends QCItem {
    private final boolean mIsChecked;
    private final boolean mIsEnabled;
    private final boolean mIsAvailable;
    private Icon mIcon;
    private PendingIntent mAction;

    public QCActionItem(@NonNull @QCItemType String type, boolean isChecked, boolean isEnabled,
            boolean isAvailable, @Nullable Icon icon, @Nullable PendingIntent action) {
        super(type);
        mIsEnabled = isEnabled;
        mIsChecked = isChecked;
        mIsAvailable = isAvailable;
        mIcon = icon;
        mAction = action;
    }

    public QCActionItem(@NonNull Parcel in) {
        super(in);
        mIsChecked = in.readBoolean();
        mIsEnabled = in.readBoolean();
        mIsAvailable = in.readBoolean();
        boolean hasIcon = in.readBoolean();
        if (hasIcon) {
            mIcon = Icon.CREATOR.createFromParcel(in);
        }
        boolean hasAction = in.readBoolean();
        if (hasAction) {
            mAction = PendingIntent.CREATOR.createFromParcel(in);
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeBoolean(mIsChecked);
        dest.writeBoolean(mIsEnabled);
        dest.writeBoolean(mIsAvailable);
        boolean includeIcon = getType().equals(QC_TYPE_ACTION_TOGGLE) && mIcon != null;
        dest.writeBoolean(includeIcon);
        if (includeIcon) {
            mIcon.writeToParcel(dest, flags);
        }
        boolean hasAction = mAction != null;
        dest.writeBoolean(hasAction);
        if (hasAction) {
            mAction.writeToParcel(dest, flags);
        }
    }

    @Override
    public PendingIntent getPrimaryAction() {
        return mAction;
    }

    public boolean isChecked() {
        return mIsChecked;
    }

    public boolean isEnabled() {
        return mIsEnabled;
    }

    public boolean isAvailable() {
        return mIsAvailable;
    }

    @Nullable
    public Icon getIcon() {
        return mIcon;
    }

    public static Creator<QCActionItem> CREATOR = new Creator<QCActionItem>() {
        @Override
        public QCActionItem createFromParcel(Parcel source) {
            return new QCActionItem(source);
        }

        @Override
        public QCActionItem[] newArray(int size) {
            return new QCActionItem[size];
        }
    };

    /**
     * Builder for {@link QCActionItem}.
     */
    public static class Builder {
        private final String mType;
        private boolean mIsChecked;
        private boolean mIsEnabled = true;
        private boolean mIsAvailable = true;
        private Icon mIcon;
        private PendingIntent mAction;

        public Builder(@NonNull @QCItemType String type) {
            if (!isValidType(type)) {
                throw new IllegalArgumentException("Invalid QCActionItem type provided" + type);
            }
            mType = type;
        }

        /**
         * Sets whether or not the action item should be checked.
         */
        public Builder setChecked(boolean checked) {
            mIsChecked = checked;
            return this;
        }

        /**
         * Sets whether or not the action item should be enabled.
         */
        public Builder setEnabled(boolean enabled) {
            mIsEnabled = enabled;
            return this;
        }

        /**
         * Sets whether or not the action item is available.
         */
        public Builder setAvailable(boolean available) {
            mIsAvailable = available;
            return this;
        }

        /**
         * Sets the icon for {@link QC_TYPE_ACTION_TOGGLE} actions
         */
        public Builder setIcon(@Nullable Icon icon) {
            mIcon = icon;
            return this;
        }

        /**
         * Sets the PendingIntent to be sent when the action item is clicked.
         */
        public Builder setAction(@Nullable PendingIntent action) {
            mAction = action;
            return this;
        }

        /**
         * Builds the final {@link QCActionItem}.
         */
        public QCActionItem build() {
            return new QCActionItem(mType, mIsChecked, mIsEnabled, mIsAvailable, mIcon, mAction);
        }

        private boolean isValidType(String type) {
            return type.equals(QC_TYPE_ACTION_SWITCH) || type.equals(QC_TYPE_ACTION_TOGGLE);
        }
    }
}
