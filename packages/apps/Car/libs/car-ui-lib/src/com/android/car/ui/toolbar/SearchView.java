/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.car.ui.toolbar;

import static com.android.car.ui.utils.CarUiUtils.requireViewByRefId;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.car.ui.R;

import java.util.Collections;
import java.util.Set;

/**
 * A search view used by {@link Toolbar}.
 */
public class SearchView extends ConstraintLayout {
    private final InputMethodManager mInputMethodManager;
    private final ImageView mIcon;
    private final EditText mSearchText;
    private final View mCloseIcon;
    private final int mStartPaddingWithoutIcon;
    private final int mStartPadding;
    private final int mEndPadding;
    private Set<Toolbar.OnSearchListener> mSearchListeners = Collections.emptySet();
    private Set<Toolbar.OnSearchCompletedListener> mSearchCompletedListeners =
            Collections.emptySet();
    private final TextWatcher mTextWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
        }

        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
        }

        @Override
        public void afterTextChanged(Editable editable) {
            onSearch(editable.toString());
        }
    };

    private boolean mIsPlainText = false;

    public SearchView(Context context) {
        this(context, null);
    }

    public SearchView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SearchView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        mInputMethodManager = (InputMethodManager)
            getContext().getSystemService(Context.INPUT_METHOD_SERVICE);

        LayoutInflater inflater = LayoutInflater.from(context);
        inflater.inflate(R.layout.car_ui_toolbar_search_view, this, true);

        mSearchText = requireViewByRefId(this, R.id.car_ui_toolbar_search_bar);
        mIcon = requireViewByRefId(this, R.id.car_ui_toolbar_search_icon);
        mCloseIcon = requireViewByRefId(this, R.id.car_ui_toolbar_search_close);

        mCloseIcon.setOnClickListener(view -> mSearchText.getText().clear());
        mCloseIcon.setVisibility(View.GONE);

        mStartPaddingWithoutIcon = mSearchText.getPaddingStart();
        mStartPadding = context.getResources().getDimensionPixelSize(
                R.dimen.car_ui_toolbar_search_search_icon_container_width);
        mEndPadding = context.getResources().getDimensionPixelSize(
                R.dimen.car_ui_toolbar_search_close_icon_container_width);

        mSearchText.setSaveEnabled(false);
        mSearchText.setPaddingRelative(mStartPadding, 0, mEndPadding, 0);

        mSearchText.setOnFocusChangeListener(
                (view, hasFocus) -> {
                    if (hasFocus) {
                        mInputMethodManager.showSoftInput(view, 0);
                    } else {
                        mInputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
                    }
                });

        mSearchText.addTextChangedListener(mTextWatcher);

        mSearchText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_SEARCH) {
                mSearchText.clearFocus();
                for (Toolbar.OnSearchCompletedListener listener : mSearchCompletedListeners) {
                    listener.onSearchCompleted();
                }
            }
            return false;
        });
    }

    private boolean mWasShown = false;

    @Override
    public void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);

        boolean isShown = isShown();
        if (isShown && !mWasShown) {
            boolean hasQuery = mSearchText.getText().length() > 0;
            mCloseIcon.setVisibility(hasQuery ? View.VISIBLE : View.GONE);
            mSearchText.requestFocus();
        }
        mWasShown = isShown;
    }

    /**
     * Adds a listener for the search text changing.
     * See also {@link #unregisterOnSearchListener(Toolbar.OnSearchListener)}
     */
    public void setSearchListeners(Set<Toolbar.OnSearchListener> listeners) {
        mSearchListeners = listeners;
    }

    /**
     * Removes a search listener.
     * See also {@link #registerOnSearchListener(Toolbar.OnSearchListener)}
     */
    public void setSearchCompletedListeners(Set<Toolbar.OnSearchCompletedListener> listeners) {
        mSearchCompletedListeners = listeners;
    }

    /**
     * Sets the search hint.
     *
     * @param resId A string resource id of the search hint.
     */
    public void setHint(int resId) {
        mSearchText.setHint(resId);
    }

    /**
     * Sets the search hint
     *
     * @param hint A CharSequence of the search hint.
     */
    public void setHint(CharSequence hint) {
        mSearchText.setHint(hint);
    }

    /** Gets the search hint */
    public CharSequence getHint() {
        return mSearchText.getHint();
    }

    /**
     * Sets a custom icon to display in the search box.
     */
    public void setIcon(Drawable d) {
        if (d == null) {
            mIcon.setImageResource(R.drawable.car_ui_icon_search);
        } else {
            mIcon.setImageDrawable(d);
        }
    }

    /**
     * Sets a custom icon to display in the search box.
     */
    public void setIcon(int resId) {
        if (resId == 0) {
            mIcon.setImageResource(R.drawable.car_ui_icon_search);
        } else {
            mIcon.setImageResource(resId);
        }
    }

    /**
     * Sets whether or not the search bar should look like a regular text box
     * instead of a search box.
     */
    public void setPlainText(boolean plainText) {
        if (plainText != mIsPlainText) {
            if (plainText) {
                mSearchText.setPaddingRelative(mStartPaddingWithoutIcon, 0, mEndPadding, 0);
                mSearchText.setImeOptions(EditorInfo.IME_ACTION_DONE);
                mIcon.setVisibility(View.GONE);
            } else {
                mSearchText.setPaddingRelative(mStartPadding, 0, mEndPadding, 0);
                mSearchText.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
                mIcon.setVisibility(View.VISIBLE);
            }
            mIsPlainText = plainText;

            // Needed to detect changes to imeOptions
            mInputMethodManager.restartInput(mSearchText);
        }
    }

    private void onSearch(String query) {
        mCloseIcon.setVisibility(TextUtils.isEmpty(query) ? View.GONE : View.VISIBLE);

        for (Toolbar.OnSearchListener listener : mSearchListeners) {
            listener.onSearch(query);
        }
    }

    /**
     * Sets the text being searched.
     */
    public void setSearchQuery(String query) {
        mSearchText.setText(query);
        mSearchText.setSelection(mSearchText.getText().length());
    }
}
