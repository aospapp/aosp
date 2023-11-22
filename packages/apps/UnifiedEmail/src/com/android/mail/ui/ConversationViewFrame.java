/**
 * Copyright (C) 2014 Google Inc.
 * Licensed to The Android Open Source Project.
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
package com.android.mail.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.FrameLayout;

/**
 * Empty frame to steal events for two-pane view when the drawer is open.
 */
public class ConversationViewFrame extends FrameLayout {

    public interface DownEventListener {
        boolean onInterceptCVDownEvent();
    }

    private DownEventListener mDownEventListener;

    public ConversationViewFrame(Context c) {
        super(c, null);
    }

    public ConversationViewFrame(Context c, AttributeSet attrs) {
        super(c, attrs);
    }

    public void setDownEventListener(DownEventListener l) {
        mDownEventListener = l;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        boolean steal = false;
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && mDownEventListener != null) {
            steal = mDownEventListener.onInterceptCVDownEvent();
            // just drop the event stream that follows when we steal; we closed the drawer and
            // that's enough.
        }
        return steal;
    }

}
