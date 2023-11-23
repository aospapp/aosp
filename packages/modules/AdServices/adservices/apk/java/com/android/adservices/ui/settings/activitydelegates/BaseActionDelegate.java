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
package com.android.adservices.ui.settings.activitydelegates;

import android.os.Build;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.RequiresApi;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import com.android.adservices.ui.ModeSelector;
import com.android.adservices.ui.settings.activities.AdServicesBaseActivity;

import java.util.function.Function;

/**
 * Base Delegate class that helps activities that extend {@link AdServicesBaseActivity} to respond
 * to all view model/user events. Currently supports:
 *
 * <ul>
 *   <li>common logging events
 * </ul>
 */
// TODO(b/269798827): Enable for R.
@RequiresApi(Build.VERSION_CODES.S)
public abstract class BaseActionDelegate implements ModeSelector {
    final AdServicesBaseActivity mActivity;

    public BaseActionDelegate(AdServicesBaseActivity activity) {
        mActivity = activity;
    }

    <T> void configureElement(
            int resId,
            View.OnClickListener onClickListener,
            LiveData<T> liveData,
            Function<View, Observer<T>> observerProvider) {
        View view = mActivity.findViewById(resId);
        view.setVisibility(View.VISIBLE);
        view.setOnClickListener(onClickListener);
        liveData.observe(mActivity, observerProvider.apply(view));
    }

    <T> void configureElement(
            int resId, LiveData<T> liveData, Function<View, Observer<T>> observerProvider) {
        View view = mActivity.findViewById(resId);
        view.setVisibility(View.VISIBLE);
        liveData.observe(mActivity, observerProvider.apply(view));
    }

    void configureElement(int resId, View.OnClickListener onClickListener) {
        View view = mActivity.findViewById(resId);
        view.setVisibility(View.VISIBLE);
        view.setOnClickListener(onClickListener);
    }

    void configureElement(int resId, String text) {
        TextView textView = mActivity.findViewById(resId);
        textView.setVisibility(View.VISIBLE);
        textView.setText(text);
    }

    void configureElement(int resId, int textResId) {
        TextView textView = mActivity.findViewById(resId);
        textView.setVisibility(View.VISIBLE);
        textView.setText(textResId);
    }

    void configureLink(int resId) {
        ((TextView) mActivity.findViewById(resId))
                .setMovementMethod(LinkMovementMethod.getInstance());
    }

    void showElements(int[] elements) {
        for (int element : elements) {
            showElement(element);
        }
    }

    void showElement(int resId) {
        mActivity.findViewById(resId).setVisibility(View.VISIBLE);
    }

    void hideElements(int[] elements) {
        for (int element : elements) {
            hideElement(element);
        }
    }

    void hideElement(int resId) {
        mActivity.findViewById(resId).setVisibility(View.GONE);
    }
}
