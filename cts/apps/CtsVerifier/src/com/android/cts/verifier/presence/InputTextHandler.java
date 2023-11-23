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


package com.android.cts.verifier.presence;

import android.text.Editable;
import android.text.TextWatcher;

/**
 * Handles editable text inputted into test activities.
 */
public class InputTextHandler {

    /** Callback that is executed when text is changed. Takes modified text as input. */
    public interface OnTextChanged {
        void run(Editable s);
    }

    /**
     * Generic text changed handler that will execute the provided callback when text is modified.
     *
     * @param callback called when text is changed, and passed the modified text
     */
    public static TextWatcher getOnTextChangedHandler(OnTextChanged callback) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                callback.run(s);
            }
        };
    }
}
