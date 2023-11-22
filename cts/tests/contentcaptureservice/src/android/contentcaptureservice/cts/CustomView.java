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

package android.contentcaptureservice.cts;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewStructure;
import android.view.contentcapture.ContentCaptureSession;

import androidx.annotation.NonNull;

import com.android.compatibility.common.util.Visitor;

/**
 * A view that can be used to emulate custom behavior (like virtual children)
 */
public class CustomView extends View {

    private static final String TAG = CustomView.class.getSimpleName();

    private Visitor<ViewStructure> mDelegate;

    public CustomView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void onProvideContentCaptureStructure(@NonNull ViewStructure structure) {
        Log.v(TAG, "onProvideContentCaptureStructure(): delegate=" + mDelegate);
        if (mDelegate != null) {
            Log.d(TAG, "onProvideContentCaptureStructure(): delegating");
            structure.setClassName(getAccessibilityClassName().toString());
            mDelegate.visit(structure);
            Log.d(TAG, "onProvideContentCaptureStructure(): delegated");
        }
        else {
            Log.d(TAG, "onProvideContentCaptureStructure(): explicitly setting class name");
            structure.setClassName(getAccessibilityClassName().toString());
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (changed) {
            final ContentCaptureSession session = getContentCaptureSession();
            final ViewStructure structure = session.newViewStructure(this);
            onProvideContentCaptureStructure(structure);
            session.notifyViewAppeared(structure);
        }
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        final String name = CustomView.class.getName();
        Log.d(TAG, "getAccessibilityClassName(): " + name);
        return name;
    }

    void setContentCaptureDelegate(@NonNull Visitor<ViewStructure> delegate) {
        mDelegate = delegate;
    }
}
