/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.ui.paintbooth.widescreenime;

import static com.android.car.ui.imewidescreen.CarUiImeWideScreenController.WIDE_SCREEN_CLEAR_DATA_ACTION;

import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;
import android.widget.EditText;

/**
 * Edit text supporting the callbacks from the IMS.
 */
public class EdiTextWithPrivateImeCommand extends EditText {

    public EdiTextWithPrivateImeCommand(Context context) {
        super(context);
    }

    public EdiTextWithPrivateImeCommand(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public EdiTextWithPrivateImeCommand(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public EdiTextWithPrivateImeCommand(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public boolean onPrivateIMECommand(String action, Bundle data) {
        if (WIDE_SCREEN_CLEAR_DATA_ACTION.equals(action)) {
            setText("");
        }
        return false;
    }
}
