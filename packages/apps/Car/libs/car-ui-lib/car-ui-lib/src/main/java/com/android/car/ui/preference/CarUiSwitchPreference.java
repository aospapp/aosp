/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.car.ui.preference;

import static com.android.car.ui.core.CarUi.MIN_TARGET_API;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SwitchPreference;

import com.android.car.ui.R;
import com.android.car.ui.utils.CarUiUtils;

import java.util.function.Consumer;

/**
 * This class is the same as the base {@link SwitchPreference} class, except it implements
 * {@link UxRestrictablePreference} and {@link ClickableWhileDisabledPreference}
 */
@SuppressWarnings("AndroidJdkLibsChecker")
@TargetApi(MIN_TARGET_API)
public class CarUiSwitchPreference extends SwitchPreference implements DisabledPreferenceCallback,
        ClickableWhileDisabledPreference {

    private Consumer<Preference> mRestrictedClickListener;
    private Consumer<Preference> mDisabledClickListener;
    private boolean mUxRestricted = false;
    private boolean mIsClickableWhileDisabled = false;

    public CarUiSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(attrs);
    }

    public CarUiSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    public CarUiSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    public CarUiSwitchPreference(Context context) {
        super(context);
        init(null);
    }

    private void init(AttributeSet attrs) {
        TypedArray a = getContext().obtainStyledAttributes(attrs, R.styleable.CarUiPreference);
        mUxRestricted = a.getBoolean(R.styleable.CarUiPreference_car_ui_ux_restricted, false);
        mIsClickableWhileDisabled = a.getBoolean(
                R.styleable.CarUiPreference_carUiClickableWhileDisabled, false);
        if (mIsClickableWhileDisabled) {
            super.setShouldDisableView(false);
        }
        a.recycle();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        CarUiUtils.makeAllViewsEnabledAndUxRestricted(holder.itemView, isEnabled(),
                isUxRestricted());
    }

    @Override
    @SuppressWarnings("RestrictTo")
    public void performClick() {
        if (!isEnabled() && mIsClickableWhileDisabled) {
            if (mDisabledClickListener != null) {
                mDisabledClickListener.accept(this);
            }
        } else if ((isEnabled() || isSelectable()) && isUxRestricted()) {
            if (mRestrictedClickListener != null) {
                mRestrictedClickListener.accept(this);
            }
        } else {
            super.performClick();
        }
    }

    @Override
    public void setShouldDisableView(boolean shouldDisableView) {
        throw new UnsupportedOperationException("Dynamically setting shouldDisableView is"
                + "unsupported in CarUiPreferences");
    }

    @Override
    public void setUxRestricted(boolean restricted) {
        if (mUxRestricted != restricted) {
            mUxRestricted = restricted;
            notifyChanged();
        }
    }

    @Override
    public boolean isUxRestricted() {
        return mUxRestricted;
    }

    @Override
    public void setOnClickWhileRestrictedListener(@Nullable Consumer<Preference> listener) {
        mRestrictedClickListener = listener;
    }

    @Nullable
    @Override
    public Consumer<Preference> getOnClickWhileRestrictedListener() {
        return mRestrictedClickListener;
    }

    @Override
    public void setClickableWhileDisabled(boolean clickableWhileDisabled) {
        if (mIsClickableWhileDisabled != clickableWhileDisabled) {
            super.setShouldDisableView(!clickableWhileDisabled);
            mIsClickableWhileDisabled = clickableWhileDisabled;
            notifyChanged();
        }
    }

    @Override
    public boolean isClickableWhileDisabled() {
        return mIsClickableWhileDisabled;
    }

    @Override
    public void setDisabledClickListener(Consumer<Preference> listener) {
        mDisabledClickListener = listener;
    }

    @Override
    public Consumer<Preference> getDisabledClickListener() {
        return mDisabledClickListener;
    }
}
