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

package com.android.car.rotaryplayground;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListPopupWindow;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.android.car.ui.FocusParkingView;

/** Fragment to demo a popup window. */
public class PopupWindowFragment extends Fragment {

    private static final String[] ACTIONS = {"Action 1", "Action 2", "Action 3"};
    private static final int WINDOW_WIDTH = 300;
    private static final int WINDOW_HEIGHT = 400;

    @NonNull
    private ListPopupWindow mPopupWindow;
    @Nullable
    private ListView mListView;

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.popup_window_fragment, container, false);
        Button overflow = view.findViewById(R.id.overflow);

        mPopupWindow = new ListPopupWindow(getActivity());
        mPopupWindow.setAdapter(new ArrayAdapter<>(getActivity(), R.layout.list_item, ACTIONS));
        mPopupWindow.setAnchorView(overflow);
        mPopupWindow.setWidth(WINDOW_WIDTH);
        mPopupWindow.setHeight(WINDOW_HEIGHT);
        mPopupWindow.setModal(true);
        mPopupWindow.setOnItemClickListener((parent, v, position, id) -> {
            Toast.makeText(getActivity(), ACTIONS[position], Toast.LENGTH_SHORT).show();
            mPopupWindow.dismiss();
        });

        overflow.setOnClickListener((v) -> {
            mPopupWindow.show();
            mListView = mPopupWindow.getListView();
            if (mListView != null && mListView.isFocused()) {
                initRotaryMode();
            }
        });

        return view;
    }

    /**
     * Initializes the popup window to support rotary controller rotations and nudges.
     * <p>
     * In rotary mode the first focusable element in the ListView should be focused automatically
     * when the popup window shows up. However, in {@link ListPopupWindow#show}, Android framework
     * creates the ListView, sets it focusable, then focuses on it automatically. What's worse,
     * ListView doesn't handle focusSearch() properly, causing rotation fails once it's focused.
     * To work around it, move focus to its first focusable element, then set the ListView
     * to not be focusable.
     */
    private void initRotaryMode() {
        if (mListView == null) {
            return;
        }

        // Add a FocusParkingView to prevent rotary controller rotation wrap-around.
        View root = mListView.getRootView();
        if (root instanceof ViewGroup) {
            ViewGroup rootView = (ViewGroup) root;
            FocusParkingView fpv = new FocusParkingView(getActivity());

            // This popup window can be dismissed by controller nudges.
            fpv.setOnPopupWindowDismiss(() -> mPopupWindow.dismiss());

            rootView.addView(fpv);
        }

        // At this time the elements in the list are not loaded yet. Because there is no callbacks
        // to notify whether the elements are loaded, use an OnPreDrawListener as workaround.
        ViewTreeObserver.OnPreDrawListener listeners[] =
                new ViewTreeObserver.OnPreDrawListener[1];
        listeners[0] = (() -> {
            boolean focusInitialized = false;
            for (int i = 0; i < mListView.getChildCount(); i++) {
                View child = mListView.getChildAt(i);
                if (child != null) {
                    // Because the elements are set focusable, they'll steal click events from the
                    // list, so they need to handle the events on their own.
                    int position = i;
                    child.setOnClickListener(v -> mPopupWindow.performItemClick(position));

                    // Move focus to the first focusable element and set the list non-focussable.
                    if (!focusInitialized) {
                        focusInitialized = child.requestFocus();
                        if (focusInitialized) {
                            mListView.setFocusable(false);
                            mListView.getViewTreeObserver().removeOnPreDrawListener(listeners[0]);
                        }
                    }
                }
            }
            return true;
        });
        mListView.getViewTreeObserver().addOnPreDrawListener(listeners[0]);
    }
}
