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
package com.google.android.car.kitchensink.users;

/**
 * Represents a user restriction in a list.  Contains the key for the user restriction and the
 * "checked" status of the checkbox in the list.
 */
public final class UserRestrictionListItem {
    private final String mKey;
    private boolean mChecked;

    public UserRestrictionListItem(String key, boolean isChecked) {
        mKey = key;
        mChecked = isChecked;
    }

    public String getKey() {
        return mKey;
    }

    public void setChecked(boolean value) {
        mChecked = value;
    }

    public boolean isChecked() {
        return mChecked;
    }

    @Override
    public String toString() {
        return mKey + "(" + (mChecked ? "on" : "off") + ")";
    }
}
