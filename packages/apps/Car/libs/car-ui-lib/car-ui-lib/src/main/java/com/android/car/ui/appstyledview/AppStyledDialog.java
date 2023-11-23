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

package com.android.car.ui.appstyledview;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;

/**
 * App styled dialog used to display a view that cannot be customized via OEM. Dialog will inflate a
 * layout and add the view provided by the application into the layout. Everything other than the
 * view within the layout can be customized by OEM.
 *
 * Apps should not use this directly. App's should use {@link AppStyledDialogController}.
 */
/* package */ class AppStyledDialog extends Dialog implements DialogInterface.OnDismissListener {

    private final AppStyledViewController mController;
    private Runnable mOnDismissListener;
    private View mContent;
    private final Context mContext;

    public AppStyledDialog(@NonNull Context context, @NonNull AppStyledViewController controller) {
        super(context);
        // super.getContext() returns a ContextThemeWrapper which is not an Activity which we need 
        // in order to get call getWindow()
        mContext = context;
        mController = controller;
        setOnDismissListener(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(mController.getAppStyledView(mContent));

        WindowManager.LayoutParams params = getWindow().getAttributes();
        getWindow().setAttributes(mController.getDialogWindowLayoutParam(params));
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        if (mOnDismissListener != null) {
            mOnDismissListener.run();
        }
    }

    /**
     * An hack used to show the dialogs in Immersive Mode (that is with the NavBar hidden). To
     * obtain this, the method makes the dialog not focusable before showing it, change the UI
     * visibility of the window like the owner activity of the dialog and then (after showing it)
     * makes the dialog focusable again.
     */
    @Override
    public void show() {
        // Set the dialog to not focusable.
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);

        copySystemUiVisibility();

        // Show the dialog with NavBar hidden.
        super.show();

        // Set the dialog to focusable again.
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }

    /**
     * Copy the visibility of the Activity that has started the dialog {@link mContext}. If the
     * activity is in Immersive mode the dialog will be in Immersive mode too and vice versa.
     */
    private void copySystemUiVisibility() {
        getWindow().getDecorView().setSystemUiVisibility(
                ((Activity) mContext).getWindow().getDecorView().getSystemUiVisibility()
        );
    }

    void setContent(View contentView) {
        mContent = contentView;
    }

    void setOnDismissListener(Runnable listener) {
        mOnDismissListener = listener;
    }

    WindowManager.LayoutParams getWindowLayoutParams() {
        return getWindow().getAttributes();
    }
}
