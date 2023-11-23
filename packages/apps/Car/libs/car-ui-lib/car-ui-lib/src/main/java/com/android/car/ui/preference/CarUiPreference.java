/*
 * Copyright 2019 The Android Open Source Project
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
import android.view.View;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.car.ui.R;
import com.android.car.ui.utils.CarUiUtils;

import java.util.function.Consumer;

/**
 * This class extends the base {@link Preference} class. Adds the support to add a drawable icon to
 * the preference if there is one of fragment, intent or onPreferenceClickListener set.
 */
@SuppressWarnings("AndroidJdkLibsChecker")
@TargetApi(MIN_TARGET_API)
public class CarUiPreference extends Preference implements DisabledPreferenceCallback,
        ClickableWhileDisabledPreference {
    private boolean mShowChevron;

    private Consumer<Preference> mRestrictedClickListener;
    private Consumer<Preference> mDisabledClickListener;
    private boolean mUxRestricted = false;
    private boolean mIsClickableWhileDisabled = false;

    public CarUiPreference(Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(attrs, defStyleAttr, defStyleRes);
    }

    public CarUiPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, R.style.Preference_CarUi_Preference);
    }

    public CarUiPreference(Context context, AttributeSet attrs) {
        this(context, attrs, R.attr.carUiPreferenceStyle);
    }

    public CarUiPreference(Context context) {
        this(context, null);
    }

    private void init(AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        TypedArray a = getContext().obtainStyledAttributes(
                attrs,
                R.styleable.CarUiPreference,
                defStyleAttr,
                defStyleRes);

        mShowChevron = a.getBoolean(R.styleable.CarUiPreference_carUiShowChevron, true);
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
    public void onAttached() {
        super.onAttached();

        boolean allowChevron = getContext().getResources().getBoolean(
                R.bool.car_ui_preference_show_chevron);

        if (!allowChevron || !mShowChevron) {
            return;
        }

        if (getOnPreferenceClickListener() != null || getIntent() != null
                || getFragment() != null) {
            setWidgetLayoutResource(R.layout.car_ui_preference_chevron);
        }
    }

    /**
     * An exact copy of {@link androidx.preference.Preference#performClick(View)}
     * This method was added here because super.performClick(View) is not open
     * for app usage.
     */
    @SuppressWarnings("RestrictTo")
    void performClickUnrestricted(View v) {
        performClick();
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
        throw new UnsupportedOperationException("android:shouldDisableView is"
                + "unsupported on CarUiPreferences");
    }

    public void setShowChevron(boolean showChevron) {
        mShowChevron = showChevron;
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
    public void setUxRestricted(boolean restricted) {
        mUxRestricted = restricted;
        notifyChanged();
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
