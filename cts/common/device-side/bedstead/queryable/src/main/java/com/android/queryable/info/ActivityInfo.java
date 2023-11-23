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

package com.android.queryable.info;

import android.app.Activity;
import android.content.IntentFilter;

import java.util.HashSet;
import java.util.Set;


/**
 * Wrapper for information about an {@link Activity}.
 *
 * <p>This is used instead of {@link Activity} so that it can be easily serialized.
 */
public final class ActivityInfo extends ClassInfo {

    private final boolean mExported;
    private final Set<IntentFilter> mIntentFilters;

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(android.content.pm.ActivityInfo activityInfo) {
        return builder()
                .activityClass(activityInfo.name)
                .exported(activityInfo.exported);
    }

    private ActivityInfo(String activityClass, boolean exported,
            Set<IntentFilter> intentFilters) {
        super(activityClass);
        mExported = exported;
        if (intentFilters == null) {
            mIntentFilters = new HashSet<>();
        } else {
            mIntentFilters = intentFilters;
        }
    }

    public boolean exported() {
        return mExported;
    }

    /** Return the intent filters of this activity.*/
    public Set<IntentFilter> intentFilters() {
        return mIntentFilters;
    }

    @Override
    public String toString() {
        return "Activity{"
                + "class=" + super.toString()
                + ", exported=" + mExported
                + ", intentFilters=" + mIntentFilters
                + "}";
    }

    public static final class Builder {
        String mActivityClass;
        boolean mExported;
        Set<IntentFilter> mIntentFilters;

        public Builder activityClass(String activityClassName) {
            mActivityClass = activityClassName;
            return this;
        }

        public Builder activityClass(Activity activity) {
            return activityClass(activity.getClass());
        }

        public Builder activityClass(Class<? extends Activity> activityClass) {
            return activityClass(activityClass.getName());
        }

        public Builder exported(boolean exported) {
            mExported = exported;
            return this;
        }

        /** Set the intent filters with the set of intent filters provided */
        public Builder intentFilters(Set<IntentFilter> intentFilters) {
            mIntentFilters = intentFilters;
            return this;
        }

        public ActivityInfo build() {
            return new ActivityInfo(
                    mActivityClass,
                    mExported,
                    mIntentFilters
            );
        }
    }
}
