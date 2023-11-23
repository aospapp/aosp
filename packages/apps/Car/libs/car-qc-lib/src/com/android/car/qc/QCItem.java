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
import android.content.Context;
import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Base class for all quick controls elements.
 */
public abstract class QCItem implements Parcelable {
    public static final String QC_TYPE_LIST = "QC_TYPE_LIST";
    public static final String QC_TYPE_ROW = "QC_TYPE_ROW";
    public static final String QC_TYPE_TILE = "QC_TYPE_TILE";
    public static final String QC_TYPE_SLIDER = "QC_TYPE_SLIDER";
    public static final String QC_TYPE_ACTION_SWITCH = "QC_TYPE_ACTION_SWITCH";
    public static final String QC_TYPE_ACTION_TOGGLE = "QC_TYPE_ACTION_TOGGLE";

    public static final String QC_ACTION_TOGGLE_STATE = "QC_ACTION_TOGGLE_STATE";
    public static final String QC_ACTION_SLIDER_VALUE = "QC_ACTION_SLIDER_VALUE";

    @StringDef(value = {
            QC_TYPE_LIST,
            QC_TYPE_ROW,
            QC_TYPE_TILE,
            QC_TYPE_SLIDER,
            QC_TYPE_ACTION_SWITCH,
            QC_TYPE_ACTION_TOGGLE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface QCItemType {
    }

    private final String mType;
    private ActionHandler mActionHandler;

    public QCItem(@NonNull @QCItemType String type) {
        mType = type;
    }

    public QCItem(@NonNull Parcel in) {
        mType = in.readString();
    }

    @NonNull
    @QCItemType
    public String getType() {
        return mType;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mType);
    }

    public void setActionHandler(@Nullable ActionHandler handler) {
        mActionHandler = handler;
    }

    @Nullable
    public ActionHandler getActionHandler() {
        return mActionHandler;
    }

    /**
     * Returns the PendingIntent that is sent when the item is clicked.
     */
    @Nullable
    public abstract PendingIntent getPrimaryAction();

    /**
     * Action handler that can listen for an action to occur and notify listeners.
     */
    public interface ActionHandler {
        /**
         * Callback when an action occurs.
         * @param item the QCItem that sent the action
         * @param context the context for the action
         * @param intent the intent that was sent with the action
         */
        void onAction(@NonNull QCItem item, @NonNull Context context, @NonNull Intent intent);

        default boolean isActivity() {
            return false;
        }
    }
}
