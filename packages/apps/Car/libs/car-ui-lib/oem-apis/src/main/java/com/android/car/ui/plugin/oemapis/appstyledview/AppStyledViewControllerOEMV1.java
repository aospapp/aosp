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

package com.android.car.ui.plugin.oemapis.appstyledview;

import android.view.View;
import android.view.WindowManager;

/** The OEM interface for a AppStyledView. */
public interface AppStyledViewControllerOEMV1 {

    /**
     * Gets the view to display. This view will contain the content view set in {@link #setContent}.
     *
     * @return the view used for app styled view.
     */
    View getView();

    /**
     * Sets the content view to be contained within this AppStyledView.
     */
    void setContent(View content);

    /**
     * Sets a {@link Runnable} to be called whenever the close icon is clicked.
     */
    void setOnBackClickListener(Runnable listener);

    int NAV_ICON_DISABLED = 0;
    int NAV_ICON_BACK = 1;
    int NAV_ICON_CLOSE = 2;

    /**
     * Sets the nav icon to be used. Can be set to one of {@link #NAV_ICON_DISABLED},
     * {@link #NAV_ICON_BACK} or {@link #NAV_ICON_CLOSE}.
     */
    void setNavIcon(int navIcon);

    /**
     * Returns the layout params for the AppStyledView dialog
     */
    WindowManager.LayoutParams getDialogWindowLayoutParam(WindowManager.LayoutParams params);
}
